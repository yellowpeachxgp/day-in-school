package cn.edu.haut.campus.core.packages.install

import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.manifest.PackageManifest

/**
 * 当前宿主的兼容性声明。
 *
 * hostApi 是宿主安装/权限契约的版本（本模块 v1 为 1），appVersionCode 与
 * version.properties 的 VERSION_BUILD 对齐；两者都低于清单要求时拒绝安装，
 * 避免旧客户端装上"它解释不了"的包。
 */
data class HostEnvironment(
    val hostApi: Int,
    val appVersionCode: Int,
    val supportedCapabilities: Set<CapabilityKind> = CapabilityKind.entries.toSet()
) {
    init {
        require(hostApi >= 1) { "hostApi 必须为正整数" }
        require(appVersionCode >= 1) { "appVersionCode 必须为正整数" }
    }
}

/** 安装前兼容性检查；任何一项不满足都拒绝，并且不写盘。 */
object HostCompatibility {

    const val CURRENT_HOST_API: Int = 1

    fun check(manifest: PackageManifest, host: HostEnvironment, schoolId: SchoolId, providerId: String?) {
        if (manifest.minHostApi > host.hostApi) {
            throw PackageException(
                PackageError.HOST_API_TOO_OLD,
                "宿主 API 版本过低：" + host.hostApi + " < " + manifest.minHostApi
            )
        }
        if (manifest.minAppVersionCode > host.appVersionCode) {
            throw PackageException(
                PackageError.APP_VERSION_TOO_OLD,
                "App 版本码过低：" + host.appVersionCode + " < " + manifest.minAppVersionCode
            )
        }
        val unsupported = manifest.capabilities - host.supportedCapabilities
        if (unsupported.isNotEmpty()) {
            throw PackageException(
                PackageError.CAPABILITY_UNSUPPORTED,
                "宿主不支持清单声明的能力：" + unsupported.map { it.name }.sorted().joinToString()
            )
        }
        if (!manifest.matchesTarget(schoolId, providerId)) {
            throw PackageException(
                PackageError.TARGET_MISMATCH,
                "包的安装目标与当前学校/教务实例不一致：" + schoolId.value
            )
        }
    }
}
