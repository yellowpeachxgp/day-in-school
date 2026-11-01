package cn.edu.haut.campus.core.packages.manifest

import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.packages.bundle.PackageFileDigest
import cn.edu.haut.campus.core.packages.bundle.PackagePathPolicy
import cn.edu.haut.campus.core.packages.permission.NetworkPermission
import cn.edu.haut.campus.core.packages.permission.PermissionSet

/**
 * 包目标：稳定学校 ID + 可选教务实例 ID。
 * 学校 ID 复用 core:model 的 [SchoolId] 校验，不能使用教务域名或学号。
 */
data class PackageTarget(
    val schoolId: SchoolId,
    val provider: String? = null
) {
    init {
        require(provider == null || PROVIDER.matches(provider)) { "教务实例 ID 非法" }
    }

    private companion object {
        val PROVIDER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/**
 * 清单自带的证据声明。fixtureDigest 是"作者提交的夹具集合"摘要，
 * 用于把包与具体夹具版本绑定，避免换夹具后仍沿用旧的验证等级。
 * 它不是签名，也不代表学校已经确认。
 */
data class PackageVerification(
    val level: VerificationLevel,
    val fixtureDigest: String
) {
    init {
        require(PackageFileDigest.isSha256(fixtureDigest)) { "夹具摘要必须是 64 位小写十六进制" }
    }
}

/**
 * dis-campus-plugin/v1 清单的不可变模型。
 *
 * 所有字段都会在 [PackageManifestCodec] 里做逐项校验；本类只保留跨字段不变量，
 * 便于测试直接构造对象时也不会得到自相矛盾的清单。
 */
data class PackageManifest(
    val id: String,
    val version: PackageVersion,
    val type: PackageType,
    val publisher: String,
    val license: PackageLicense,
    val targets: List<PackageTarget>,
    val minHostApi: Int,
    val minAppVersionCode: Int,
    val capabilities: Set<CapabilityKind>,
    val permissions: List<NetworkPermission>,
    val files: Map<String, String>,
    val verification: PackageVerification
) {
    init {
        require(PACKAGE_ID.matches(id)) { "包 ID 必须由小写字母、数字、点、下划线或短横线组成" }
        require(PUBLISHER_ID.matches(publisher)) { "发布者 ID 必须是小写标识符" }
        require(targets.isNotEmpty()) { "清单必须声明至少一个目标学校" }
        require(targets.map { it.schoolId }.distinct().size == targets.size) { "清单 targets 包含重复学校" }
        require(minHostApi >= 1) { "minHostApi 必须为正整数" }
        require(minAppVersionCode >= 1) { "minAppVersionCode 必须为正整数" }
        require(files.isNotEmpty()) { "清单必须声明至少一个文件" }
        files.keys.forEach { path -> PackagePathPolicy.validate(path) }
    }

    /** 清单 files 映射的规范摘要；必须与真实包内容摘要相等。 */
    val filesDigest: String get() = PackageFileDigest.of(files)

    /** 展开后的网络权限集合；同一来源的多条路径互为独立授权。 */
    val permissionSet: PermissionSet get() = PermissionSet(permissions.flatMap { it.scopes() }.toSet())

    /** 目标是否覆盖"当前学校 + 可选教务实例"。provider 为空的 targets 视为覆盖该校全部实例。 */
    fun matchesTarget(schoolId: SchoolId, providerId: String?): Boolean = targets.any { target ->
        target.schoolId == schoolId && (target.provider == null || target.provider == providerId)
    }

    companion object {
        val PACKAGE_ID: Regex = Regex("^[a-z0-9][a-z0-9._-]{2,127}$")
        val PUBLISHER_ID: Regex = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    }
}
