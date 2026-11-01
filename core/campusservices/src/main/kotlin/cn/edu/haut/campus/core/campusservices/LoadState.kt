package cn.edu.haut.campus.core.campusservices

/**
 * 校园服务统一加载状态契约（支持离线缓存陈旧态与失败降级快照）。
 */
sealed interface LoadState<out T> {
    data object Idle : LoadState<Nothing>
    data object Loading : LoadState<Nothing>

    data class Content<T>(
        val data: T,
        val updatedAtEpochMs: Long,
        val isStale: Boolean = false
    ) : LoadState<T>

    data class Empty(
        val reason: String
    ) : LoadState<Nothing>

    data class Failed<T>(
        val error: ServiceError,
        val cachedSnapshot: T? = null
    ) : LoadState<T>
}

/**
 * 校园服务标准错误分类。
 */
enum class ServiceErrorCode {
    NETWORK_UNAVAILABLE,
    TLS_OR_ROUTE_BLOCKED,
    AUTH_REQUIRED,
    NO_BOUND_ROOM,
    UPSTREAM_CHANGED,
    RATE_LIMITED,
    STORAGE_KEY_INVALIDATED,
    TRANSPORT_UNVERIFIED
}

data class ServiceError(
    val code: ServiceErrorCode,
    val userMessage: String,
    val canFallbackToOfficialWeb: Boolean = false
)
