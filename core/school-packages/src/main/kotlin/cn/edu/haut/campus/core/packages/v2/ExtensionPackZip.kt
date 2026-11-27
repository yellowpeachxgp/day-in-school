package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageLimits
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 云端 dis-plugin pack 传输 ZIP 解包校验通过的产物。
 *
 * 构造时复制全部字节：解包结果是签名校验与安装（W6-01a）的输入，
 * 调用方后续的任何改写都不允许影响这里固定下来的内容。
 */
class UnpackedExtensionPack internal constructor(
    val manifest: ExtensionManifestV2,
    manifestBytes: ByteArray,
    artifacts: Map<String, ByteArray>,
    val embeddedPublicKeyBase64: String?
) {
    private val manifestBytesCopy = manifestBytes.copyOf()
    private val artifactCopies = artifacts.mapValues { it.value.copyOf() }

    fun copyOfManifestBytes(): ByteArray = manifestBytesCopy.copyOf()

    fun artifactPaths(): Set<String> = artifactCopies.keys

    fun copyOfArtifact(path: String): ByteArray? = artifactCopies[path]?.copyOf()
}

/**
 * W6-01b / AC-42 生产入口：云端 dis-plugin pack 产出 ZIP 的严格解包校验。
 *
 * 这是 Android 安装链消费 pack 传输格式的唯一入口。格式事实源见云端
 * docs/PLUGIN_SDK_GUIDE.md §7：封闭条目集（manifest.json + 可选公钥 +
 * 清单声明产物）、确定性打包、中央目录驱动的严格读取。
 *
 * 安全包线与云端 readPluginPackageZip 逐项对齐：
 * zip-slip / 绝对路径 / 反斜杠 / NUL / 非规范化路径拒绝、重复条目（含大小写
 * 不敏感）、加密条目、ZIP64、多盘、非 store/deflate 压缩方式全部拒绝；
 * 本地头文件名必须与中央目录逐字节一致；逐条校验 CRC-32 与解压后长度。
 *
 * zip-bomb 防护是限量解压而非事后比对：deflate 流按 8 KiB 分块边解压边计数，
 * 输出超过声明长度立即中止拒绝，单个条目的瞬时内存以声明大小（上限
 * 4 MiB）加单个分块为界，攻击者控制的流永远不会先被完整解压进内存。
 *
 * 诚实边界：本对象只负责「读取 + 结构校验 + 摘要核验」。签名信任钉扎、
 * 原子安装、授权登记与按身份隔离的持久化属于 W6-01a，不在此处实现。
 */
object ExtensionPackZip {

    const val MANIFEST_ENTRY = "manifest.json"
    const val PUBLIC_KEY_ENTRY = "plugin-signing-public.key"

    private const val LOCAL_HEADER_SIG = 0x04034b50L
    private const val CENTRAL_HEADER_SIG = 0x02014b50L
    private const val EOCD_SIG = 0x06054b50L
    private const val EOCD_FIXED_LEN = 22
    private const val CENTRAL_FIXED_LEN = 46
    private const val LOCAL_FIXED_LEN = 30
    private const val MAX_COMMENT_LEN = 65535
    private const val METHOD_STORE = 0
    private const val METHOD_DEFLATE = 8
    private const val FLAG_ENCRYPTED = 0x0001
    private const val ZIP64_U16 = 0xFFFF
    private const val ZIP64_U32 = 0xFFFFFFFFL
    private const val INFLATE_CHUNK = 8192

    /**
     * 严格读取 ZIP 条目。返回按中央目录顺序排列的 路径 -> 内容 映射；
     * 任何结构、路径、加密、ZIP64、重复、上限、CRC 或长度异常都抛出
     * [PackageException]，绝不返回部分结果。
     */
    fun readEntriesStrict(
        bytes: ByteArray,
        limits: PackageLimits = PackageLimits.DEFAULT
    ): LinkedHashMap<String, ByteArray> {
        if (bytes.size < EOCD_FIXED_LEN) {
            corrupt("不是插件包 ZIP：档案小于 EOCD 记录")
        }
        val eocd = findEndOfCentralDirectory(bytes)
        val diskNumber = u16(bytes, eocd + 4)
        val centralDisk = u16(bytes, eocd + 6)
        if (diskNumber != 0 || centralDisk != 0) {
            unsupported("不支持多盘 ZIP 档案")
        }
        val entryCount = u16(bytes, eocd + 10)
        val centralSize = u32(bytes, eocd + 12)
        val centralOffset = u32(bytes, eocd + 16)
        if (entryCount == ZIP64_U16 || centralSize == ZIP64_U32 || centralOffset == ZIP64_U32) {
            unsupported("插件包不支持 ZIP64 档案")
        }
        if (entryCount > limits.maxEntries) {
            throw PackageException(
                PackageError.TOO_MANY_ENTRIES,
                "ZIP 条目数超出插件包上限：" + entryCount + " > " + limits.maxEntries
            )
        }
        if (centralOffset + centralSize > bytes.size) {
            corrupt("中央目录指向档案之外")
        }

        val entries = LinkedHashMap<String, ByteArray>()
        val seenLower = HashSet<String>()
        var totalUncompressed = 0L
        var cursor = centralOffset.toInt()

        repeat(entryCount) { index ->
            if (cursor + CENTRAL_FIXED_LEN > bytes.size || u32(bytes, cursor) != CENTRAL_HEADER_SIG) {
                corrupt("中央目录条目畸形（序号 " + index + "）")
            }
            val flags = u16(bytes, cursor + 8)
            if (flags and FLAG_ENCRYPTED != 0) {
                unsupported("不支持加密 ZIP 条目（序号 " + index + "）")
            }
            val method = u16(bytes, cursor + 10)
            if (method != METHOD_STORE && method != METHOD_DEFLATE) {
                unsupported("只支持 store/deflate 压缩方式（序号 " + index + "，method=" + method + "）")
            }
            val expectedCrc = u32(bytes, cursor + 16)
            val compressedSize = u32(bytes, cursor + 20)
            val uncompressedSize = u32(bytes, cursor + 24)
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val localHeaderOffset = u32(bytes, cursor + 42)
            if (compressedSize == ZIP64_U32 || uncompressedSize == ZIP64_U32 || localHeaderOffset == ZIP64_U32) {
                unsupported("插件包不支持 ZIP64 条目元数据（序号 " + index + "）")
            }
            if (cursor + CENTRAL_FIXED_LEN + nameLength > bytes.size) {
                corrupt("中央目录条目名越界（序号 " + index + "）")
            }
            val nameBytes = bytes.copyOfRange(cursor + CENTRAL_FIXED_LEN, cursor + CENTRAL_FIXED_LEN + nameLength)
            val entryPath = String(nameBytes, Charsets.UTF_8)

            assertCanonicalEntryName(entryPath, index)
            if (entryPath.length > limits.maxPathLength || entryPath.split("/").size > limits.maxPathDepth) {
                throw PackageException(
                    PackageError.UNSAFE_PATH,
                    "ZIP 条目路径超出插件包长度/深度上限: " + entryPath,
                    ExtensionManifestV2Codec.RUNTIME_PATH_UNSAFE
                )
            }
            val lower = entryPath.lowercase(Locale.ROOT)
            if (entries.containsKey(entryPath) || !seenLower.add(lower)) {
                throw PackageException(PackageError.DUPLICATE_ENTRY, "ZIP 内存在重复条目：" + entryPath)
            }
            if (uncompressedSize > limits.maxFileBytes) {
                throw PackageException(
                    PackageError.SIZE_LIMIT_EXCEEDED,
                    "ZIP 条目超出单文件上限（解压前预检）: " + entryPath
                )
            }
            totalUncompressed += uncompressedSize
            if (totalUncompressed > limits.maxTotalBytes) {
                throw PackageException(
                    PackageError.SIZE_LIMIT_EXCEEDED,
                    "ZIP 解压总量超出插件包上限（zip-bomb 防护）"
                )
            }

            // 数据位置经本地头解析，但两个长度字段只信中央目录——说谎的本地头无法改写读取范围。
            // 先用 Long 做越界判定再转 Int：直接 toInt 的溢出回绕会让负偏移骗过边界检查。
            if (localHeaderOffset + LOCAL_FIXED_LEN > bytes.size) {
                corrupt("中央目录条目缺少对应本地头: " + entryPath)
            }
            val local = localHeaderOffset.toInt()
            if (u32(bytes, local) != LOCAL_HEADER_SIG) {
                corrupt("中央目录条目缺少对应本地头: " + entryPath)
            }
            val localNameLength = u16(bytes, local + 26)
            val localExtraLength = u16(bytes, local + 28)
            if (local + LOCAL_FIXED_LEN + localNameLength > bytes.size) {
                corrupt("本地头文件名越界: " + entryPath)
            }
            val localNameBytes = bytes.copyOfRange(local + LOCAL_FIXED_LEN, local + LOCAL_FIXED_LEN + localNameLength)
            // 严格一致：本地头文件名必须与中央目录逐字节相同——两份名字不一致是经典篡改信号。
            if (!localNameBytes.contentEquals(nameBytes)) {
                corrupt("ZIP 本地头文件名与中央目录不一致: " + entryPath)
            }
            // 与本地头偏移同理：先以 Long 计算数据区间，越过档案末尾即拒绝，转换 Int 之前不再有回绕风险。
            val dataStartLong = local.toLong() + LOCAL_FIXED_LEN + localNameLength + localExtraLength
            val dataEnd = dataStartLong + compressedSize
            if (dataEnd > bytes.size) {
                corrupt("ZIP 条目数据超出档案末尾: " + entryPath)
            }
            val dataStart = dataStartLong.toInt()

            val content = if (method == METHOD_STORE) {
                // store 条目未压缩，两个长度字段必须相等；先在复制前核对，
                // 说谎的长度字段无法把复制本身变成超量分配。
                if (compressedSize != uncompressedSize) {
                    corrupt("ZIP store 条目两个长度字段不一致（篡改信号）: " + entryPath)
                }
                bytes.copyOfRange(dataStart, dataEnd.toInt())
            } else {
                inflateBounded(bytes, dataStart, compressedSize.toInt(), uncompressedSize, entryPath)
            }
            if (content.size.toLong() != uncompressedSize) {
                corrupt("ZIP 条目解压后长度与中央目录不一致: " + entryPath)
            }
            val crc = CRC32()
            crc.update(content)
            if (crc.value != expectedCrc) {
                corrupt("ZIP 条目 CRC-32 校验失败: " + entryPath)
            }

            entries[entryPath] = content
            cursor += CENTRAL_FIXED_LEN + nameLength + extraLength + commentLength
        }

        return entries
    }

    /**
     * 解包并校验 pack 传输 ZIP：严格读取后经生产清单解析器解析，
     * 强制封闭条目集合并逐产物核验 SHA-256 与登记大小。
     */
    fun unpack(bytes: ByteArray, limits: PackageLimits = PackageLimits.DEFAULT): UnpackedExtensionPack {
        val entries = readEntriesStrict(bytes, limits)
        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw PackageException(PackageError.FILE_MISSING, "插件包 ZIP 缺少 manifest.json")
        val manifest = ExtensionManifestV2Codec.parseOrAdapt(String(manifestBytes, Charsets.UTF_8))

        val declaredPaths = LinkedHashSet<String>()
        manifest.entryArtifacts.forEach { artifact ->
            ExtensionManifestV2Codec.assertSafeRelativePath(artifact.path)
            declaredPaths.add(artifact.path)
        }
        entries.keys.forEach { entryPath ->
            if (entryPath != MANIFEST_ENTRY && entryPath != PUBLIC_KEY_ENTRY && entryPath !in declaredPaths) {
                throw PackageException(PackageError.FILE_UNLISTED, "插件包 ZIP 含有未声明条目: " + entryPath)
            }
        }

        val artifacts = LinkedHashMap<String, ByteArray>()
        manifest.entryArtifacts.forEach { artifact ->
            val content = entries[artifact.path]
                ?: throw PackageException(PackageError.FILE_MISSING, "清单声明的产物在 ZIP 中缺失: " + artifact.path)
            if (Digests.sha256Hex(content) != artifact.sha256) {
                throw PackageException(PackageError.FILE_HASH_MISMATCH, "ZIP 内产物摘要与清单登记不一致: " + artifact.path)
            }
            if (content.size.toLong() != artifact.sizeBytes) {
                throw PackageException(PackageError.FILE_HASH_MISMATCH, "ZIP 内产物大小与清单登记不一致: " + artifact.path)
            }
            artifacts[artifact.path] = content
        }

        val publicKey = entries[PUBLIC_KEY_ENTRY]?.let { String(it, Charsets.UTF_8).trim() }
        return UnpackedExtensionPack(manifest, manifestBytes, artifacts, publicKey)
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val window = minOf(bytes.size, EOCD_FIXED_LEN + MAX_COMMENT_LEN)
        var offset = bytes.size - EOCD_FIXED_LEN
        while (offset >= bytes.size - window) {
            if (u32(bytes, offset) == EOCD_SIG) {
                return offset
            }
            offset -= 1
        }
        corrupt("不是插件包 ZIP：找不到中央目录结尾记录")
    }

    /**
     * 限量解压（zip-bomb 防护核心）：deflate 流按块解压并累计输出字节数，
     * 超过上限立即中止。上限取声明解压长度（解压前已强制 <= maxFileBytes），
     * 诚实的条目刚好等于声明值，产出更多的流正是长度核验本来就会拒绝的篡改情形。
     */
    private fun inflateBounded(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        cap: Long,
        artifactPath: String
    ): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArrayOutputStream(if (cap < INFLATE_CHUNK) cap.toInt() + 1 else INFLATE_CHUNK)
            val chunk = ByteArray(INFLATE_CHUNK)
            while (!inflater.finished()) {
                val produced = try {
                    inflater.inflate(chunk)
                } catch (e: DataFormatException) {
                    corrupt("ZIP 条目 deflate 流损坏: " + artifactPath)
                }
                if (produced == 0) {
                    // inflate 可能在产出 0 字节的同时刚好吃到流结尾（空内容条目）；
                    // 除此之外的零进度只可能是输入耗尽（截断）或需要字典，都按损坏拒绝。
                    if (inflater.finished()) {
                        break
                    }
                    corrupt("ZIP 条目 deflate 流不完整或损坏: " + artifactPath)
                }
                out.write(chunk, 0, produced)
                if (out.size() > cap) {
                    throw PackageException(
                        PackageError.SIZE_LIMIT_EXCEEDED,
                        "ZIP 条目解压输出超过声明长度，限量解压中止（zip-bomb 防护）: " + artifactPath
                    )
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * 条目名必须已经是规范化 posix 形式（与云端读取器对齐）：
     * zip-slip/遍历由 [ExtensionManifestV2Codec.assertSafeRelativePath] 拒绝，
     * 这里额外拒绝反斜杠、空段（含 "//" 与首尾 "/"）和 "." 段——
     * 否则同一个名字在两端规范化后会指向不同条目。
     */
    private fun assertCanonicalEntryName(entryPath: String, index: Int) {
        ExtensionManifestV2Codec.assertSafeRelativePath(entryPath)
        if (entryPath.contains('\\')) {
            corrupt("ZIP 条目路径含反斜杠，不是规范化 posix 形式: " + entryPath)
        }
        val segments = entryPath.split("/")
        if (segments.any { it.isEmpty() || it == "." }) {
            corrupt("ZIP 条目路径不是规范化 posix 形式（序号 " + index + "）: " + entryPath)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun unsupported(message: String): Nothing = throw PackageException(
        PackageError.MANIFEST_UNSUPPORTED_FORMAT,
        message
    )

    private fun corrupt(message: String): Nothing = throw PackageException(
        PackageError.ARCHIVE_CORRUPT,
        message
    )
}
