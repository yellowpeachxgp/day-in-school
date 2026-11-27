package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-01b / AC-42 校验联动：云端 `dis-plugin pack` 产出的确定性 ZIP 必须可被
 * Android 的校验链消费——java.util.zip 读取、生产代码 ExtensionManifestV2Codec 解析、
 * 生产代码 Digests 摘要核验、生产代码 CanonicalJson + Ed25519 验签原语。
 *
 * 诚实边界：本测试只覆盖「读取 + 校验」。Android 的完整安装链路（原子写入、
 * 授权登记、按身份隔离的数据库）属于 W6-01a，不在本测试断言范围。管道编排是
 * 测试内代码，但每一步都调用生产验签/摘要/解析原语，不使用任何测试替身。
 */
class ExtensionPackZipVerificationTest {

    private data class VerifiedPack(
        val manifest: ExtensionManifestV2,
        val verifiedArtifactCount: Int,
        val publicKeyBase64: String
    )

    @Test
    fun 云端pack产出的zip可被Android校验链完整消费() {
        val entries = readZipEntries(fixtureZipBytes())
        val verified = verifyPackPipeline(entries)

        assertEquals("org.example.pack-fixture", verified.manifest.pluginId)
        assertEquals("dis-extension/v2", verified.manifest.schemaVersion)
        assertEquals(2, verified.verifiedArtifactCount)
        assertEquals(1, verified.manifest.uiSlots.size)
        assertEquals("home.cards", verified.manifest.uiSlots[0].slotId)
        assertEquals("showCardData", verified.manifest.uiSlots[0].actionCommand)
        assertTrue(verified.manifest.commands.any { it.name == "showCardData" })
        assertTrue("embedded public key must be the SPKI base64 used for verification", verified.publicKeyBase64.startsWith("MCowBQYDK2Vw"))
    }

    @Test
    fun pack内嵌产物字节与声明摘要在生产摘要原语下逐一相等() {
        val entries = readZipEntries(fixtureZipBytes())
        val manifestText = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val parsed = ExtensionManifestV2Codec.parseOrAdapt(manifestText)
        parsed.entryArtifacts.forEach { artifact ->
            val content = entries[artifact.path]
            assertNotNull("declared artifact missing in zip: " + artifact.path, content)
            assertEquals(
                "digest mismatch for " + artifact.path,
                artifact.sha256,
                Digests.sha256Hex(content!!)
            )
            assertEquals(artifact.sizeBytes, content.size.toLong())
        }
    }

    @Test
    fun 篡改产物字节时摘要核验失败() {
        val entries = readZipEntries(fixtureZipBytes()).toMutableMap()
        val target = "assets/card-data.json"
        val mutated = entries.getValue(target).copyOf()
        mutated[0] = (mutated[0].toInt() xor 0x01).toByte()
        entries[target] = mutated

        val outcome = runCatching { verifyPackPipeline(entries) }
        assertTrue("tampered artifact must fail verification", outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message?.contains("digest") == true)
    }

    @Test
    fun 篡改签名字节时Ed25519验签失败() {
        val entries = readZipEntries(fixtureZipBytes())
        val manifestText = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val doc = Json.parse(manifestText).asObjOrNull ?: error("manifest must be object")
        val signatureObject = doc.fields["signature"]?.asObjOrNull ?: error("manifest must be signed")
        val signature = Base64.getDecoder().decode(
            signatureObject.fields["value_base64"]?.stringOrNull() ?: error("missing signature value")
        )
        signature[0] = (signature[0].toInt() xor 0x01).toByte()

        val publicKey = extractRawPublicKey(entries)
        val canonicalPayload = canonicalUnsignedManifest(manifestText)
        assertFalse(
            "tampered signature must not verify",
            Ed25519.verify(publicKey, canonicalPayload, signature)
        )
    }

    @Test
    fun 封闭条目集之外的额外条目被拒绝() {
        val entries = readZipEntries(fixtureZipBytes()).toMutableMap()
        entries["assets/smuggled.bin"] = byteArrayOf(0x01)
        val outcome = runCatching { verifyPackPipeline(entries) }
        assertTrue("undeclared extra entry must be rejected", outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message?.contains("undeclared") == true)
    }

    @Test
    fun zipSlip条目在读取阶段即被拒绝() {
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.json"))
            zip.write("{}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val outcome = runCatching { readZipEntries(evil.toByteArray()) }
        assertTrue("zip-slip entry must be rejected at read time", outcome.isFailure)
    }

    // -----------------------------------------------------------------------
    // 校验管道（测试内编排；每一步都调用生产原语）
    // -----------------------------------------------------------------------

    private fun verifyPackPipeline(entries: Map<String, ByteArray>): VerifiedPack {
        val manifestBytes = entries["manifest.json"] ?: error("pack zip does not contain manifest.json")
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val parsed = ExtensionManifestV2Codec.parseOrAdapt(manifestText)

        val declaredPaths = parsed.entryArtifacts.map { it.path }.toSet()
        val allowed = declaredPaths + setOf("manifest.json", "plugin-signing-public.key")
        entries.keys.forEach { entryPath ->
            if (entryPath !in allowed) {
                error("pack zip contains an undeclared entry: " + entryPath)
            }
        }

        var verifiedCount = 0
        parsed.entryArtifacts.forEach { artifact ->
            val content = entries[artifact.path] ?: error("declared artifact missing: " + artifact.path)
            val actualDigest = Digests.sha256Hex(content)
            if (actualDigest != artifact.sha256) {
                error("artifact digest mismatch for " + artifact.path)
            }
            verifiedCount += 1
        }

        val publicKey = extractRawPublicKey(entries)
        val signatureObject = Json.parse(manifestText).asObjOrNull
            ?.fields?.get("signature")?.asObjOrNull ?: error("pack manifest is unsigned")
        val signature = Base64.getDecoder().decode(
            signatureObject.fields["value_base64"]?.stringOrNull() ?: error("missing signature value")
        )
        val canonicalPayload = canonicalUnsignedManifest(manifestText)
        if (!Ed25519.verify(publicKey, canonicalPayload, signature)) {
            error("Ed25519 signature verification failed for pack manifest")
        }

        val publicKeyBase64 = entries.getValue("plugin-signing-public.key").toString(Charsets.UTF_8).trim()
        return VerifiedPack(parsed, verifiedCount, publicKeyBase64)
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
    private fun extractRawPublicKey(entries: Map<String, ByteArray>): java.security.PublicKey {
        val publicKeyEntry = entries["plugin-signing-public.key"] ?: error("pack zip does not embed a public key")
        val spki = Base64.getDecoder().decode(publicKeyEntry.toString(Charsets.UTF_8).trim())
        val expectedPrefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        if (spki.size != 44 || !spki.copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix)) {
            error("embedded key is not an Ed25519 SPKI DER public key")
        }
        return Ed25519.toPublicKey(spki.copyOfRange(spki.size - 32, spki.size))
    }

    /** 以 java.util.zip 读取条目，路径安全与重复检测与生产管线同一方向。 */
    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                ExtensionManifestV2Codec.assertSafeRelativePath(path)
                if (entries.put(path, zip.readBytes()) != null) {
                    error("duplicate zip entry: " + path)
                }
            }
        }
        if (entries.isEmpty()) error("zip archive contains no entries")
        return entries
    }

    private fun fixtureZipBytes(): ByteArray {
        val resource = javaClass.classLoader.getResource(
            "extension-pack-fixtures/org.example.pack-fixture-1.0.0.zip.base64"
        ) ?: error("pack fixture resource is missing")
        val base64 = File(resource.toURI()).readText(Charsets.UTF_8).trim()
        return Base64.getDecoder().decode(base64)
    }
}
