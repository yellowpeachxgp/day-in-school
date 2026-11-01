package cn.edu.haut.campus.core.packages.bundle

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException

/**
 * 包内条目路径策略。
 *
 * 允许的字符集刻意很窄（ASCII 字母、数字、点、下划线、短横线、斜杠），
 * 因此百分号编码的 ../（%2e%2e）也会在字符集检查处被拒绝；此外显式拒绝：
 * 绝对路径、反斜杠、盘符冒号、空段、"." 与 ".." 段、尾随斜杠、控制字符、
 * 超长路径与过深层级。所有条目还必须落在宿主的目录白名单内
 * （默认只有 profile/、assets/、rules/ 三个前缀）。
 *
 * UNVERIFIED：白名单与上限是本模块自定的收紧策略，尚未与真实学校包协商。
 */
object PackagePathPolicy {

    /** 默认允许的目录前缀；不在其中的条目一律拒绝。 */
    val DEFAULT_ALLOWED_PREFIXES: List<String> = listOf("profile/", "assets/", "rules/")

    private const val MAX_SEGMENT_LENGTH = 64

    /**
     * 校验并返回原路径（路径本身已经是规范形式，不做隐式改写）。
     * 任何不合规都在写盘或比较摘要之前失败，避免"先落盘再发现越界"。
     */
    fun validate(
        path: String,
        limits: PackageLimits = PackageLimits.DEFAULT,
        allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES
    ): String {
        if (path.isEmpty() || path.length > limits.maxPathLength) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径长度非法")
        }
        if (path.any { it.isISOControl() }) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径包含控制字符")
        }
        if (path.startsWith("/")) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径不能是绝对路径：" + path)
        }
        if (path.contains('\\')) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径不能包含反斜杠：" + path)
        }
        if (path.endsWith("/")) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径必须以文件名结尾：" + path)
        }
        path.forEach { ch ->
            val allowed = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '.' || ch == '_' || ch == '-' || ch == '/'
            if (!allowed) {
                throw PackageException(PackageError.UNSAFE_PATH, "条目路径包含非法字符：" + path)
            }
        }
        val segments = path.split('/')
        if (segments.size > limits.maxPathDepth) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径层级过深：" + path)
        }
        segments.forEach { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw PackageException(PackageError.UNSAFE_PATH, "条目路径包含空段、. 或 ..：" + path)
            }
            if (segment.length > MAX_SEGMENT_LENGTH) {
                throw PackageException(PackageError.UNSAFE_PATH, "条目路径单段过长：" + path)
            }
        }
        if (allowedPrefixes.none { path.startsWith(it) }) {
            throw PackageException(PackageError.UNSAFE_PATH, "条目路径不在白名单目录内：" + path)
        }
        return path
    }

    /** 白名单前缀本身必须形如 "dir/"；配置错误属于调用方缺陷。 */
    fun requireValidPrefixes(prefixes: List<String>) {
        require(prefixes.isNotEmpty()) { "路径白名单不能为空" }
        prefixes.forEach { prefix ->
            require(prefix.endsWith("/") && prefix.length > 1) { "路径白名单前缀必须以 / 结尾：" + prefix }
            require(!prefix.startsWith("/")) { "路径白名单前缀必须是相对路径：" + prefix }
            prefix.dropLast(1).split('/').forEach { segment ->
                require(segment.isNotEmpty() && segment != "." && segment != "..") {
                    "路径白名单前缀包含非法段：" + prefix
                }
            }
        }
    }
}
