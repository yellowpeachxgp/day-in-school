package cn.edu.haut.campus.core.packages.permission

import cn.edu.haut.campus.core.model.CapabilityKind

/**
 * 每个已安装学校包的授权登记表。
 *
 * 一次撤销立即生效：撤销后 [permissions] 与 [capabilities] 都返回空集合，
 * [allows] 恒为 false，不需要等待某个缓存失效或重新安装。撤销状态保留，
 * 直到用户重新授权（[grant]）为止；这样重装同一份包也不会静默恢复权限。
 */
class PackageGrantRegistry {

    private data class Grant(
        val permissions: PermissionSet,
        val capabilities: Set<CapabilityKind>
    )

    private val grants = LinkedHashMap<String, Grant>()
    private val revoked = LinkedHashSet<String>()

    fun grant(packageId: String, permissions: PermissionSet, capabilities: Set<CapabilityKind>) {
        require(packageId.isNotBlank()) { "包 ID 不能为空" }
        grants[packageId] = Grant(permissions, capabilities.toSet())
        revoked.remove(packageId)
    }

    fun revoke(packageId: String) {
        require(packageId.isNotBlank()) { "包 ID 不能为空" }
        revoked += packageId
        grants.remove(packageId)
    }

    fun isRevoked(packageId: String): Boolean = packageId in revoked

    fun permissions(packageId: String): PermissionSet =
        if (isRevoked(packageId)) PermissionSet.EMPTY else grants[packageId]?.permissions ?: PermissionSet.EMPTY

    fun capabilities(packageId: String): Set<CapabilityKind> =
        if (isRevoked(packageId)) emptySet() else grants[packageId]?.capabilities ?: emptySet()

    fun allows(packageId: String, method: PermissionMethod, url: String, purpose: PermissionPurpose): Boolean =
        permissions(packageId).allows(method, url, purpose)

    /** 是否存在"新包要求了当前授权之外的权限"。 */
    fun reauthorizationRequired(packageId: String, required: PermissionSet): Boolean =
        PermissionRules.needsReauthorization(permissions(packageId), required)

    fun revokedPackages(): Set<String> = revoked.toSet()
}
