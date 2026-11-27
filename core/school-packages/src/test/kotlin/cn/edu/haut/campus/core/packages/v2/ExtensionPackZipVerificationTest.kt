package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-01b / AC-42 校验联动：云端 `dis-plugin pack` 产出的确定性 ZIP 必须可被
 * Android 安装链消费——与审阅返修后唯一的区别在于：解包校验不再是测试内
 * 临时代码，而是走 [ExtensionPackZip] 这个 src/main 生产入口（严格 ZIP 读取 +
 * 封闭条目集 + 逐产物摘要核验）。签名验签继续使用生产 CanonicalJson 与
 * Ed25519 原语。
 *
 * 诚实边界：签名信任钉扎、原子安装、授权登记与按身份隔离的数据库属于
 * W6-01a，本测试覆盖 AC-42 在 Android 侧当前可验证的部分：pack ZIP 经
 * 生产解包校验入口消费成功，且签名经生产验签原语核验通过。
 */
class ExtensionPackZipVerificationTest {

    // -----------------------------------------------------------------------
    // 正向：云端 pack 产物经生产入口消费
    // -----------------------------------------------------------------------

    @Test
    fun 云端pack产出的zip经生产解包校验入口完整消费() {
        val unpacked = ExtensionPackZip.unpack(fixtureZipBytes())

        assertEquals("org.example.pack-fixture", unpacked.manifest.pluginId)
        assertEquals("dis-extension/v2", unpacked.manifest.schemaVersion)
        assertEquals(2, unpacked.artifactPaths().size)
        assertEquals(1, unpacked.manifest.uiSlots.size)
        assertEquals("home.cards", unpacked.manifest.uiSlots[0].slotId)
        assertEquals("showCardData", unpacked.manifest.uiSlots[0].actionCommand)
        assertTrue(unpacked.manifest.commands.any { it.name == "showCardData" })

        val embeddedKey = unpacked.embeddedPublicKeyBase64
        assertNotNull("pack zip must embed the publisher public key", embeddedKey)
        assertTrue(
            "embedded public key must be the SPKI base64 used for verification",
            embeddedKey!!.startsWith("MCowBQYDK2Vw")
        )

        // 签名验签：生产 CanonicalJson 构造载荷 + 生产 Ed25519 原语。
        val signature = unpacked.manifest.signature ?: error("pack manifest must be signed")
        val publicKey = extractRawPublicKey(embeddedKey)
        val canonicalPayload = canonicalUnsignedManifest(String(unpacked.copyOfManifestBytes(), Charsets.UTF_8))
        assertTrue(
            "pack manifest signature must verify with production Ed25519 primitive",
            Ed25519.verify(publicKey, canonicalPayload, Base64.getDecoder().decode(signature.valueBase64))
        )
    }

    @Test
    fun pack内嵌产物字节与声明摘要在生产解包入口下逐一核验() {
        val unpacked = ExtensionPackZip.unpack(fixtureZipBytes())
        unpacked.manifest.entryArtifacts.forEach { artifact ->
            val content = unpacked.copyOfArtifact(artifact.path)
            assertNotNull("declared artifact missing after production unpack: " + artifact.path, content)
            assertEquals(artifact.sizeBytes, content!!.size.toLong())
        }
    }

    @Test
    fun 篡改产物字节时生产解包入口拒绝() {
        val entries = unpackToMutableEntries()
        val target = "assets/card-data.json"
        val mutated = entries.getValue(target).copyOf()
        mutated[0] = (mutated[0].toInt() xor 0x01).toByte()
        entries[target] = mutated

        val outcome = runCatching { ExtensionPackZip.unpack(rebuildZip(entries)) }
        assertTrue("tampered artifact must fail production unpack", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(error is PackageException)
        assertEquals(PackageError.FILE_HASH_MISMATCH, (error as PackageException).error)
    }

    @Test
    fun 篡改签名字节时Ed25519验签失败() {
        val unpacked = ExtensionPackZip.unpack(fixtureZipBytes())
        val signature = unpacked.manifest.signature ?: error("pack manifest must be signed")
        val signatureBytes = Base64.getDecoder().decode(signature.valueBase64)
        signatureBytes[0] = (signatureBytes[0].toInt() xor 0x01).toByte()

        val publicKey = extractRawPublicKey(unpacked.embeddedPublicKeyBase64!!)
        val canonicalPayload = canonicalUnsignedManifest(String(unpacked.copyOfManifestBytes(), Charsets.UTF_8))
        assertFalse(
            "tampered signature must not verify",
            Ed25519.verify(publicKey, canonicalPayload, signatureBytes)
        )
    }

    @Test
    fun 封闭条目集之外的额外条目被生产解包入口拒绝() {
        val entries = unpackToMutableEntries()
        entries["assets/smuggled.bin"] = byteArrayOf(0x01)

        val outcome = runCatching { ExtensionPackZip.unpack(rebuildZip(entries)) }
        assertTrue("undeclared extra entry must be rejected", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(error is PackageException)
        assertEquals(PackageError.FILE_UNLISTED, (error as PackageException).error)
    }

    @Test
    fun 缺失清单声明产物时生产解包入口拒绝() {
        val entries = unpackToMutableEntries()
        entries.remove("assets/card-data.json")

        val outcome = runCatching { ExtensionPackZip.unpack(rebuildZip(entries)) }
        assertTrue("missing declared artifact must be rejected", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(error is PackageException)
        assertEquals(PackageError.FILE_MISSING, (error as PackageException).error)
    }

    // -----------------------------------------------------------------------
    // 负向：生产严格 ZIP 读取入口的结构攻击面回归
    // （与云端 readPluginPackageZip 负例同类别：duplicate/encrypted/ZIP64/
    // zip-bomb/zip-slip/CRC/local-central-name-mismatch）
    // -----------------------------------------------------------------------

    @Test
    fun zipSlip条目在生产严格读取入口被拒绝() {
        val evil = assembleRawZip(listOf(
            deflateEntry("../evil.json", "{}".toByteArray(Charsets.UTF_8))
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue("zip-slip entry must be rejected", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(error is PackageException)
        assertEquals(PackageError.UNSAFE_PATH, (error as PackageException).error)
    }

    @Test
    fun 反斜杠与非规范化路径条目在生产严格读取入口被拒绝() {
        val backslash = assembleRawZip(listOf(
            deflateEntry("dist\\evil.json", "{}".toByteArray(Charsets.UTF_8))
        ))
        assertTrue("backslash entry must be rejected", runCatching { ExtensionPackZip.readEntriesStrict(backslash) }.isFailure)

        val dotSegment = assembleRawZip(listOf(
            deflateEntry("dist/./index.json", "{}".toByteArray(Charsets.UTF_8))
        ))
        assertTrue("dot segment entry must be rejected", runCatching { ExtensionPackZip.readEntriesStrict(dotSegment) }.isFailure)
    }

    @Test
    fun 重复条目在生产严格读取入口被拒绝() {
        val exact = assembleRawZip(listOf(
            deflateEntry("dist/a.txt", "one".toByteArray(Charsets.UTF_8)),
            deflateEntry("dist/a.txt", "two".toByteArray(Charsets.UTF_8))
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(exact) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.DUPLICATE_ENTRY, (outcome.exceptionOrNull() as PackageException).error)

        val caseFolded = assembleRawZip(listOf(
            deflateEntry("dist/A.txt", "one".toByteArray(Charsets.UTF_8)),
            deflateEntry("dist/a.txt", "two".toByteArray(Charsets.UTF_8))
        ))
        val outcomeFolded = runCatching { ExtensionPackZip.readEntriesStrict(caseFolded) }
        assertTrue(outcomeFolded.isFailure)
        assertEquals(PackageError.DUPLICATE_ENTRY, (outcomeFolded.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun 加密条目在生产严格读取入口被拒绝() {
        val evil = assembleRawZip(listOf(
            deflateEntry("dist/index.mjs", "x".toByteArray(Charsets.UTF_8), flags = 0x0800 or 0x0001)
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.MANIFEST_UNSUPPORTED_FORMAT, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun ZIP64标记在生产严格读取入口被拒绝() {
        val zip64Count = assembleRawZip(
            listOf(deflateEntry("dist/index.mjs", "x".toByteArray(Charsets.UTF_8))),
            entryCountOverride = 0xFFFF
        )
        val outcomeCount = runCatching { ExtensionPackZip.readEntriesStrict(zip64Count) }
        assertTrue(outcomeCount.isFailure)
        assertEquals(PackageError.MANIFEST_UNSUPPORTED_FORMAT, (outcomeCount.exceptionOrNull() as PackageException).error)

        val zip64Entry = assembleRawZip(listOf(
            deflateEntry("dist/index.mjs", "x".toByteArray(Charsets.UTF_8), uncompressedSizeOverride = 0xFFFFFFFFL)
        ))
        val outcomeEntry = runCatching { ExtensionPackZip.readEntriesStrict(zip64Entry) }
        assertTrue(outcomeEntry.isFailure)
        assertEquals(PackageError.MANIFEST_UNSUPPORTED_FORMAT, (outcomeEntry.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun 本地头与中央目录文件名不一致在生产严格读取入口被拒绝() {
        val evil = assembleRawZip(listOf(
            deflateEntry("dist/index.mjs", "x".toByteArray(Charsets.UTF_8), localName = "dist/evil!.mjs")
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.ARCHIVE_CORRUPT, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun CRC篡改在生产严格读取入口被拒绝() {
        val evil = assembleRawZip(listOf(
            deflateEntry(
                "dist/index.mjs",
                "x".toByteArray(Charsets.UTF_8),
                crcOverride = crc32Of("definitely-other".toByteArray(Charsets.UTF_8))
            )
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.ARCHIVE_CORRUPT, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun store条目两个长度字段不一致在复制前被拒绝() {
        val payload = "store-me".toByteArray(Charsets.UTF_8)
        val evil = assembleRawZip(listOf(
            CraftedEntry(
                centralName = "dist/raw.bin",
                payload = payload,
                method = 0,
                crc = crc32Of(payload),
                compressedSize = payload.size.toLong() + 7,
                uncompressedSize = payload.size.toLong()
            )
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.ARCHIVE_CORRUPT, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun 谎报小尺寸的zipbomb在限量解压下被拒绝() {
        // 8 MiB 高度可压缩内容，中央目录谎报解压后只有 32 字节。
        // 限量解压边解压边计数，输出超过声明长度立即中止：拒绝且不会先把
        // 攻击者控制的流完整解压进内存。
        val bombContent = ByteArray(8 * 1024 * 1024) { 0x61 }
        val evil = assembleRawZip(listOf(
            deflateEntry("assets/bomb.bin", bombContent, uncompressedSizeOverride = 32)
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue("zip-bomb must be rejected", outcome.isFailure)
        assertEquals(PackageError.SIZE_LIMIT_EXCEEDED, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun 诚实申报的超尺寸条目在解压前预检拒绝() {
        val evil = assembleRawZip(listOf(
            deflateEntry("assets/big.bin", "x".toByteArray(Charsets.UTF_8), uncompressedSizeOverride = 5L * 1024 * 1024)
        ))
        val outcome = runCatching { ExtensionPackZip.readEntriesStrict(evil) }
        assertTrue(outcome.isFailure)
        assertEquals(PackageError.SIZE_LIMIT_EXCEEDED, (outcome.exceptionOrNull() as PackageException).error)
    }

    @Test
    fun 空产物条目可以正常往返() {
        // 空 deflate 流产出 0 字节：限量解压的零进度分支不能误杀合法空条目。
        val entries = assembleRawZip(listOf(
            deflateEntry("assets/empty.bin", ByteArray(0))
        ))
        val read = ExtensionPackZip.readEntriesStrict(entries)
        assertEquals(0, read.getValue("assets/empty.bin").size)
    }

    // -----------------------------------------------------------------------
    // 校验辅助与 ZIP 构造夹具
    // -----------------------------------------------------------------------

    /** 经生产严格读取入口取出夹具 zip 的全部条目，供测试侧篡改后重建。 */
    private fun unpackToMutableEntries(): MutableMap<String, ByteArray> =
        LinkedHashMap(ExtensionPackZip.readEntriesStrict(fixtureZipBytes()))

    /** 用 java.util.zip 把条目表重新写成 zip（只用于构造测试输入）。 */
    private fun rebuildZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 与云端 canonicalizeJson 等价的验签载荷：清单去除 signature 字段后按
     * CanonicalJson（键按 UTF-16 排序、无空白、数字归一化）序列化。
     */
    private fun canonicalUnsignedManifest(manifestText: String): ByteArray {
        val doc = Json.parse(manifestText).asObjOrNull ?: error("manifest must be object")
        val unsigned = JsonValue.Obj(doc.fields - "signature")
        return Json.canonical(unsigned).toByteArray(Charsets.UTF_8)
    }

    /** Ed25519 SPKI DER（44 字节）的最后 32 字节即 RFC 8032 原始公钥。 */
    private fun extractRawPublicKey(spkiBase64: String): java.security.PublicKey {
        val spki = Base64.getDecoder().decode(spkiBase64)
        val expectedPrefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        if (spki.size != 44 || !spki.copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix)) {
            error("embedded key is not an Ed25519 SPKI DER public key")
        }
        return Ed25519.toPublicKey(spki.copyOfRange(spki.size - 32, spki.size))
    }

    private fun fixtureZipBytes(): ByteArray {
        val resource = javaClass.classLoader.getResource(
            "extension-pack-fixtures/org.example.pack-fixture-1.0.0.zip.base64"
        ) ?: error("pack fixture resource is missing")
        val base64 = File(resource.toURI()).readText(Charsets.UTF_8).trim()
        return Base64.getDecoder().decode(base64)
    }

    // -----------------------------------------------------------------------
    // 底层 zip 构造器：生产 writer 正确地拒绝产出恶意档案，
    // 攻击样本只能在测试里逐字节拼装（完全控制 flags/长度/CRC/双份文件名/EOCD）。
    // -----------------------------------------------------------------------

    private data class CraftedEntry(
        val centralName: String,
        val payload: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val flags: Int = 0x0800,
        val localName: String = centralName
    )

    private fun deflateEntry(
        name: String,
        content: ByteArray,
        flags: Int = 0x0800,
        crcOverride: Long? = null,
        compressedSizeOverride: Long? = null,
        uncompressedSizeOverride: Long? = null,
        localName: String? = null
    ): CraftedEntry {
        val compressed = deflateRaw(content)
        return CraftedEntry(
            centralName = name,
            payload = compressed,
            method = 8,
            crc = crcOverride ?: crc32Of(content),
            compressedSize = compressedSizeOverride ?: compressed.size.toLong(),
            uncompressedSize = uncompressedSizeOverride ?: content.size.toLong(),
            flags = flags,
            localName = localName ?: name
        )
    }

    private fun deflateRaw(content: ByteArray): ByteArray {
        val deflater = Deflater(9, true)
        try {
            deflater.setInput(content)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val produced = deflater.deflate(chunk)
                if (produced > 0) {
                    out.write(chunk, 0, produced)
                }
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun crc32Of(content: ByteArray): Long {
        val crc = CRC32()
        crc.update(content)
        return crc.value
    }

    private fun assembleRawZip(entries: List<CraftedEntry>, entryCountOverride: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { entry ->
            val localOffset = out.size()
            val localNameBytes = entry.localName.toByteArray(Charsets.UTF_8)
            val centralNameBytes = entry.centralName.toByteArray(Charsets.UTF_8)

            out.writeU32(0x04034b50L)
            out.writeU16(20)
            out.writeU16(entry.flags)
            out.writeU16(entry.method)
            out.writeU16(0)
            out.writeU16(0)
            out.writeU32(entry.crc)
            out.writeU32(entry.compressedSize)
            out.writeU32(entry.uncompressedSize)
            out.writeU16(localNameBytes.size)
            out.writeU16(0)
            out.write(localNameBytes)
            out.write(entry.payload)

            central.writeU32(0x02014b50L)
            central.writeU16(20)
            central.writeU16(20)
            central.writeU16(entry.flags)
            central.writeU16(entry.method)
            central.writeU16(0)
            central.writeU16(0)
            central.writeU32(entry.crc)
            central.writeU32(entry.compressedSize)
            central.writeU32(entry.uncompressedSize)
            central.writeU16(centralNameBytes.size)
            central.writeU16(0)
            central.writeU16(0)
            central.writeU16(0)
            central.writeU16(0)
            central.writeU32(0)
            central.writeU32(localOffset.toLong())
            central.write(centralNameBytes)
        }
        val centralBytes = central.toByteArray()
        val centralOffset = out.size()
        out.write(centralBytes)
        out.writeU32(0x06054b50L)
        out.writeU16(0)
        out.writeU16(0)
        out.writeU16(entryCountOverride ?: entries.size)
        out.writeU16(entryCountOverride ?: entries.size)
        out.writeU32(centralBytes.size.toLong())
        out.writeU32(centralOffset.toLong())
        out.writeU16(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 24) and 0xFF).toInt())
    }
}
