package cn.edu.haut.campus.core.packages.manifest

/**
 * 学校包版本号，采用 MAJOR.MINOR.PATCH[-预发布标识] 形式。
 *
 * 只比较数字段与"正式版优先"的预发布规则，不实现完整 semver 的范围语法；
 * 安装层只需要稳定的升/降序判断，用于回滚与"不要降级到更旧版本"的提示。
 * UNVERIFIED：这是本仓库自定义的版本比较策略，未与任何学校包的发布实践核对。
 */
@JvmInline
value class PackageVersion(val value: String) : Comparable<PackageVersion> {

    init {
        require(PATTERN.matches(value)) { "版本号必须形如 1.2.3 或 1.2.3-beta.1" }
    }

    private fun core(): List<Int> = value.substringBefore('-').split('.').map { it.toInt() }

    private fun preRelease(): String? = value.substringAfter('-', "").ifEmpty { null }

    override fun compareTo(other: PackageVersion): Int {
        val left = core()
        val right = other.core()
        for (index in 0..2) {
            val diff = left[index].compareTo(right[index])
            if (diff != 0) return diff
        }
        val leftPre = preRelease()
        val rightPre = other.preRelease()
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> leftPre.compareTo(rightPre)
        }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(-[0-9A-Za-z][0-9A-Za-z.-]{0,31})?$")

        fun parseOrNull(raw: String): PackageVersion? =
            if (PATTERN.matches(raw)) PackageVersion(raw) else null
    }
}

/**
 * 清单可声明的许可证白名单。
 *
 * 学校包最终会随本仓库（Apache-2.0）分发，因此只接受可兼容再分发的声明式内容许可证；
 * 不在白名单内的取值一律拒绝（包括未填写许可证）。这不是法律意见，
 * UNVERIFIED：白名单由本项目自定，尚未经法务或发行渠道确认。
 */
@JvmInline
value class PackageLicense(val spdxId: String) {

    init {
        require(spdxId in ALLOWED) { "许可证必须是允许的声明式内容许可证之一：" + ALLOWED.joinToString() }
    }

    override fun toString(): String = spdxId

    companion object {
        val ALLOWED: Set<String> = setOf("Apache-2.0", "MIT", "BSD-3-Clause", "CC-BY-4.0", "CC0-1.0")
    }
}

/**
 * 包类型。v1 只支持声明式内容；任何可执行类型（脚本、原生库、DEX）都不在枚举内，
 * 动态代码执行在宿主侧保持关闭。
 */
enum class PackageType(val wireName: String) {
    SCHOOL_PROFILE("school-profile"),
    CAMPUS_GUIDE("campus-guide");

    companion object {
        fun fromWire(raw: String): PackageType? = entries.firstOrNull { it.wireName == raw }
    }
}
