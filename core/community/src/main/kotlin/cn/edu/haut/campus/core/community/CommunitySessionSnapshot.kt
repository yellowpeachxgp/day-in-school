package cn.edu.haut.campus.core.community

/**
 * 请求发起时的认证+身份原子快照。注入方必须在同一把身份锁/同一事务边界内同时读取
 * access token 与 cloud identity，避免切号发生在两次 suspend 读取之间造成 token/identity 错配。
 *
 * 该对象只存在于请求生命周期内；不得写入日志、缓存文件、备份或诊断。toString 显式脱敏。
 */
class CommunitySessionSnapshot(
    val accessToken: String?,
    val cacheIdentity: CommunityCacheIdentity?,
) {
    override fun toString(): String =
        "CommunitySessionSnapshot(authenticated=" + (!accessToken.isNullOrBlank()) +
            ", cacheIdentityPresent=" + (cacheIdentity != null) + ")"
}
