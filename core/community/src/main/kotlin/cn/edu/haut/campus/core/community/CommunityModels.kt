package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.model.json.JsonValue

/** W4 群组云客户端的领域开关。论坛/私信不属于本模块；它们由 W4-06 单独预留。 */
enum class CommunityFeature {
    GROUPS,
    AVAILABILITY,
    POLLS,
    LEDGER,
    DUTY,
    REPAIRS,
    PLACE_STATUS,
}

data class CommunityFeatureDecision(
    val enabled: Boolean,
    val reason: String? = null,
) {
    init {
        require(enabled || !reason.isNullOrBlank()) { "关闭群组云能力时必须给出可展示原因" }
    }
}

interface CommunityFeatureGate {
    suspend fun decision(feature: CommunityFeature): CommunityFeatureDecision

    companion object {
        val ENABLED: CommunityFeatureGate = object : CommunityFeatureGate {
            override suspend fun decision(feature: CommunityFeature): CommunityFeatureDecision =
                CommunityFeatureDecision(enabled = true)
        }

        fun disabled(reason: String): CommunityFeatureGate = object : CommunityFeatureGate {
            override suspend fun decision(feature: CommunityFeature): CommunityFeatureDecision =
                CommunityFeatureDecision(enabled = false, reason = reason)
        }
    }
}

enum class CommunityErrorKind {
    NETWORK,
    HTTP,
    CONTRACT,
    CACHE,
}

data class CommunityError(
    val kind: CommunityErrorKind,
    val code: String,
    val message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
)

enum class CommunityCacheFallbackReason {
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
}

/** 所有协作 API 的统一结果。陈旧缓存永远是显式 STALE，绝不伪装成云端新数据。 */
sealed interface CommunityResult<out T> {
    data class Success<T>(
        val value: T,
        val provenance: DataProvenance,
        val staleness: Staleness,
        val cachedAtEpochMillis: Long? = null,
        val cacheFallbackReason: CommunityCacheFallbackReason? = null,
    ) : CommunityResult<T> {
        init {
            require((staleness == Staleness.STALE) == (cachedAtEpochMillis != null)) {
                "陈旧结果必须带缓存时间，鲜活结果不得带缓存时间"
            }
        }
    }

    data class Failure(
        val error: CommunityError,
    ) : CommunityResult<Nothing>

    data class Disabled(
        val feature: CommunityFeature,
        val reason: String,
    ) : CommunityResult<Nothing>

    data object AuthenticationRequired : CommunityResult<Nothing>
}

/** 成功 envelope 的轻量运行时视图；data 保持契约 JSON，避免在本卡伪造未生成 schema 类型。 */
data class CommunityEnvelope(
    val ok: Boolean,
    val code: String,
    val requestId: String,
    val data: JsonValue?,
    val meta: JsonValue.Obj,
    val raw: JsonValue.Obj,
) {
    init {
        require(ok) { "CommunityEnvelope 只表示成功响应" }
        require(code.isNotBlank()) { "成功 envelope 缺少 code" }
        require(requestId.isNotBlank()) { "成功 envelope 缺少 request_id" }
    }
}
