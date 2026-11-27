package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Bytes of a signed extension package archive. */
data class ExtensionPackageArchive(
    val manifestBytes: ByteArray,
    val artifactMap: Map<String, ByteArray>
)

/**
 * Small fail-closed ZIP reader for dis-extension/v2 packages.
 * Entries are never extracted to disk here: traversal, absolute paths, links-as-bytes,
 * duplicate names, over-limit payloads, and undeclared extras all fail before activation.
 */
object ExtensionZipPackageReader {
    private const val MAX_ENTRIES = 512
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    fun read(zipBytes: ByteArray): ExtensionPackageArchive {
        var manifestBytes: ByteArray? = null
        val artifacts = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                count += 1
                if (count > MAX_ENTRIES) {
                    throw PackageException(PackageError.TOO_MANY_ENTRIES, "插件 ZIP 条目数超出上限")
                }
                try {
                    ExtensionManifestV2Codec.assertSafeRelativePath(name)
                } catch (error: PackageException) {
                    throw PackageException(
                        PackageError.UNSAFE_PATH,
                        "插件 ZIP 条目路径被拒绝：" + name,
                        error.runtimeValidationCode
                    )
                }
                val normalized = name.replace('\\', '/')
                if (!seen.add(normalized.lowercase())) {
                    throw PackageException(PackageError.DUPLICATE_ENTRY, "插件 ZIP 存在重复条目：" + name)
                }
                val bytes = zip.readBytes()
                if (bytes.size.toLong() > MAX_FILE_BYTES) {
                    throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "插件 ZIP 单条目超出上限：" + name)
                }
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) {
                    throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "插件 ZIP 总大小超出上限")
                }
                if (name == "manifest.json") {
                    manifestBytes = bytes
                } else {
                    artifacts[name] = bytes
                }
            }
        }
        val manifest = manifestBytes
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件 ZIP 缺少 manifest.json")
        return ExtensionPackageArchive(
            manifestBytes = manifest,
            artifactMap = artifacts.mapValues { it.value.copyOf() }
        )
    }
}
