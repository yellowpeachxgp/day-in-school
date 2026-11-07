package cn.edu.haut.campus.core.packages.install

import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import cn.edu.haut.campus.core.packages.permission.PackageGrantRegistry
import cn.edu.haut.campus.core.packages.permission.PermissionRules
import cn.edu.haut.campus.core.packages.permission.PermissionScope
import cn.edu.haut.campus.core.packages.permission.PermissionSet
import cn.edu.haut.campus.core.packages.store.ActivePackage
import cn.edu.haut.campus.core.packages.store.PackageStore
import cn.edu.haut.campus.core.packages.store.StagedVerification
import cn.edu.haut.campus.core.packages.verify.PackageVerifier
import cn.edu.haut.campus.core.packages.verify.VerificationResult

/** 本次安装作用的学校与可选教务实例。 */
data class InstallScope(
    val schoolId: SchoolId,
    val providerId: String? = null
)

/**
 * 安装请求。
 *
 * [consent] 是用户在确认框中**实际同意**的权限范围，不是清单要求；
 * 安装器会用它做覆盖判断，因此调用方不能把清单里的权限原样回填当作同意。
 */
data class InstallRequest(
    val manifestBytes: ByteArray,
    val bundle: PackageBundle,
    val signature: ByteArray,
    val scope: InstallScope,
    val host: HostEnvironment,
    val consent: PermissionSet? = null
)

sealed interface InstallOutcome {

    data class Installed(
        val active: ActivePackage,
        val manifestDigest: String
    ) : InstallOutcome

    /** [requiredScopes] 只在权限相关拒绝时非空，用于展示"这次多要了什么"。 */
    data class Rejected(
        val error: PackageError,
        val detail: String,
        val requiredScopes: List<PermissionScope> = emptyList()
    ) : InstallOutcome
}

/**
 * 安装编排：签名/内容校验 → 宿主与目标兼容 → 权限授权判断 → 暂存 → 校验 → 激活。
 *
 * 任一环节失败都不会留下活动版本；已写入的暂存内容会被丢弃。
 * 权限扩大或权限曾被撤销时，没有显式同意就不安装——不"先装再提示"。
 *
 * UNVERIFIED：以上策略是本仓库自定的安全收紧，未在真实学校包与真实用户授权流程上验证。
 */
class PackageInstaller(
    private val store: PackageStore,
    private val grants: PackageGrantRegistry,
    private val keys: PublisherKeyRegistry
) {

    fun install(request: InstallRequest): InstallOutcome {
        val precheck = PackageVerifier.verify(request.manifestBytes, request.bundle, request.signature, keys)
        val manifest = when (precheck) {
            is VerificationResult.Rejected -> return InstallOutcome.Rejected(precheck.error, precheck.detail)
            is VerificationResult.Accepted -> precheck.manifest
        }

        try {
            HostCompatibility.check(manifest, request.host, request.scope.schoolId, request.scope.providerId)
        } catch (error: PackageException) {
            return InstallOutcome.Rejected(error.error, error.message ?: error.error.name)
        }

        val required = manifest.permissionSet
        authorizationRejection(manifest.id, required, request.consent)?.let { return it }

        val staged = try {
            store.stage(manifest.id, request.bundle)
        } catch (error: PackageException) {
            return InstallOutcome.Rejected(error.error, error.message ?: error.error.name)
        } catch (error: IllegalArgumentException) {
            return InstallOutcome.Rejected(PackageError.MANIFEST_INVALID, error.message ?: "包参数不合法")
        }

        val verification = store.verify(staged, request.manifestBytes, request.signature, keys)
        if (verification is StagedVerification.Rejected) {
            store.discard(staged)
            return InstallOutcome.Rejected(verification.error, verification.detail)
        }
        val verified = verification as StagedVerification.Verified

        val active = try {
            store.activate(verified)
        } catch (error: PackageException) {
            store.discard(staged)
            return InstallOutcome.Rejected(error.error, error.message ?: error.error.name)
        }
        grants.grant(manifest.id, required, manifest.capabilities)
        return InstallOutcome.Installed(active, verified.manifestDigest)
    }

    /**
     * 权限授权判断：
     * - 曾被撤销的包一律要求显式同意，不能靠重装"恢复"权限；
     * - 新版本要求了当前授权之外的条目时要求显式同意，且同意范围必须覆盖清单要求。
     */
    private fun authorizationRejection(
        packageId: String,
        required: PermissionSet,
        consent: PermissionSet?
    ): InstallOutcome.Rejected? {
        if (grants.isRevoked(packageId)) {
            if (consent == null || !consent.covers(required)) {
                return InstallOutcome.Rejected(
                    PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                    "该包权限已被撤销，必须重新授权后才能安装",
                    required.scopes.sorted()
                )
            }
            return null
        }
        val current = grants.permissions(packageId)
        if (!PermissionRules.needsReauthorization(current, required)) return null
        val added = PermissionRules.addedScopes(current, required)
        if (consent == null) {
            return InstallOutcome.Rejected(
                PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                "新版本扩大了权限范围，需要重新授权：" + added.joinToString { it.signature() },
                added
            )
        }
        if (!consent.covers(required)) {
            val missing = required.addedRelativeTo(consent)
            return InstallOutcome.Rejected(
                PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                "授权范围不足以覆盖清单要求：" + missing.joinToString { it.signature() },
                missing
            )
        }
        return null
    }

    /** 回滚到上一个已验证版本；不存在时抛 [PackageError.ROLLBACK_UNAVAILABLE]。 */
    fun rollback(packageId: String): ActivePackage = store.rollback(packageId)

    /** 撤销后该包所有能力立即失效，重装也需要重新授权。 */
    fun revoke(packageId: String) = grants.revoke(packageId)

    fun activeVersion(packageId: String): ActivePackage? = store.active(packageId)
}
