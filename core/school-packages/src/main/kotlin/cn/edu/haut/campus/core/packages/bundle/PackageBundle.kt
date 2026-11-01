package cn.edu.haut.campus.core.packages.bundle

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * 包结构与体积上限。默认值刻意保守：学校配置包是小的声明式内容，
 * 单文件 4 MiB、总量 16 MiB、512 个条目已经远超正常需要。
 * 超过上限立即拒绝，避免把"解压炸弹"式内容读进内存。
 */
data class PackageLimits(
    val maxEntries: Int = 512,
    val maxFileBytes: Long = 4L * 1024 * 1024,
    val maxTotalBytes: Long = 16L * 1024 * 1024,
    val maxPathLength: Int = 200,
    val maxPathDepth: Int = 8
) {
    init {
        require(maxEntries > 0 && maxFileBytes > 0 && maxTotalBytes > 0) { "包体上限必须为正数" }
        require(maxPathLength in 16..1024 && maxPathDepth in 1..32) { "路径上限不合法" }
    }

    companion object {
        val DEFAULT: PackageLimits = PackageLimits()
    }
}

/**
 * 包内单个文件。构造时复制字节并立即算出摘要，之后对外返回副本，
 * 避免调用方在安装期间改写内容造成"校验时与写盘时不一致"。
 */
class PackageFile private constructor(val path: String, private val content: ByteArray) {

    val size: Int = content.size

    val sha256: String = Digests.sha256Hex(content)

    fun copyOfContent(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PackageFile && other.path == path && other.sha256 == sha256

    override fun hashCode(): Int = 31 * path.hashCode() + sha256.hashCode()

    override fun toString(): String = "PackageFile(" + path + ", " + size + "B)"

    companion object {
        fun of(path: String, content: ByteArray): PackageFile = PackageFile(path, content.copyOf())
    }
}

/**
 * 一次待安装的包内容。
 *
 * 构造即完成结构校验：路径白名单、重复条目（大小写不敏感）、单文件与总量上限。
 * 内容摘要 [digest] 与清单 files 映射算出的摘要必须相等，否则视为篡改。
 */
class PackageBundle private constructor(
    val files: List<PackageFile>,
    val limits: PackageLimits
) {

    val digest: String = PackageFileDigest.of(files.associate { it.path to it.sha256 })

    val totalBytes: Long = files.sumOf { it.size.toLong() }

    fun file(path: String): PackageFile? = files.firstOrNull { it.path == path }

    override fun toString(): String =
        "PackageBundle(" + files.size + " 个条目, " + totalBytes + "B, " + digest.take(12) + ")"

    companion object {

        val DEFAULT_ALLOWED_PREFIXES: List<String> = PackagePathPolicy.DEFAULT_ALLOWED_PREFIXES

        fun of(
            files: List<PackageFile>,
            limits: PackageLimits = PackageLimits.DEFAULT,
            allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES
        ): PackageBundle {
            PackagePathPolicy.requireValidPrefixes(allowedPrefixes)
            if (files.isEmpty()) {
                throw PackageException(PackageError.EMPTY_PACKAGE, "包内容不能为空")
            }
            if (files.size > limits.maxEntries) {
                throw PackageException(
                    PackageError.TOO_MANY_ENTRIES,
                    "包条目数超出上限：" + files.size + " > " + limits.maxEntries
                )
            }
            val seen = HashSet<String>()
            var total = 0L
            files.forEach { file ->
                PackagePathPolicy.validate(file.path, limits, allowedPrefixes)
                val normalized = file.path.lowercase(Locale.ROOT)
                if (!seen.add(normalized)) {
                    throw PackageException(PackageError.DUPLICATE_ENTRY, "包内存在重复条目：" + file.path)
                }
                if (file.size > limits.maxFileBytes) {
                    throw PackageException(
                        PackageError.SIZE_LIMIT_EXCEEDED,
                        "单个条目超出上限：" + file.path + " " + file.size + "B"
                    )
                }
                total += file.size
                if (total > limits.maxTotalBytes) {
                    throw PackageException(
                        PackageError.SIZE_LIMIT_EXCEEDED,
                        "包总大小超出上限：" + file.path + " 累计 " + total + "B"
                    )
                }
            }
            return PackageBundle(files.toList(), limits)
        }

        fun ofContents(
            contents: Map<String, ByteArray>,
            limits: PackageLimits = PackageLimits.DEFAULT,
            allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES
        ): PackageBundle =
            of(contents.entries.sortedBy { it.key }.map { PackageFile.of(it.key, it.value) }, limits, allowedPrefixes)

        /**
         * 从本地目录读取包内容。
         *
         * 先看文件大小再读内容：超过单文件上限的文件不会被读进内存。
         * 软链接、设备文件、目录遍历失败都直接拒绝——不跟随链接，也不跳过可疑条目。
         */
        fun fromDirectory(
            directory: Path,
            limits: PackageLimits = PackageLimits.DEFAULT,
            allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES
        ): PackageBundle {
            if (!Files.isDirectory(directory)) {
                throw PackageException(PackageError.UNSAFE_PATH, "包目录不存在或不是目录")
            }
            val collected = mutableListOf<PackageFile>()
            var total = 0L
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(dir)) {
                        throw PackageException(PackageError.UNSAFE_PATH, "包目录不能是软链接")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = directory.relativize(file).joinToString("/") { it.toString() }
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                        throw PackageException(PackageError.UNSAFE_PATH, "包条目必须是普通文件：" + relative)
                    }
                    if (collected.size >= limits.maxEntries) {
                        throw PackageException(PackageError.TOO_MANY_ENTRIES, "包条目数超出上限")
                    }
                    if (attrs.size() > limits.maxFileBytes) {
                        throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "单个条目超出上限：" + relative)
                    }
                    total += attrs.size()
                    if (total > limits.maxTotalBytes) {
                        throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "包总大小超出上限：" + relative)
                    }
                    collected += PackageFile.of(relative, Files.readAllBytes(file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    throw PackageException(PackageError.UNSAFE_PATH, "无法读取包条目：" + file.fileName)
                }
            })
            return of(collected, limits, allowedPrefixes)
        }
    }
}
