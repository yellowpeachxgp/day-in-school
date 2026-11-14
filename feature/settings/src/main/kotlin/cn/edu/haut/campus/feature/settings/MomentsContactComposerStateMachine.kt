package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * 失物招领私信/留言 composer 的受众选择。替代原先 0/1 下标，
 * 避免把"异步加载的会话列表长度"误当成受众选择状态。
 */
enum class ContactComposerAudience {
    /** 私密联系申请 / 私密回复（仅发起人与申请人可见） */
    PRIVATE_REQUEST,

    /** 公开线索留言（所有人可见，绝不携带私密联系方式） */
    PUBLIC_COMMENT
}

/**
 * 失物招领详情页留言 composer 状态机（A1 私密草稿防泄漏修复）：
 *
 * - 生命周期严格绑定 (postId, viewerId)，绝不绑定异步请求列表长度或任何刷新计数；
 * - 非帖子管理者默认 [ContactComposerAudience.PRIVATE_REQUEST]；
 *   帖子管理者补充公开说明固定走 [ContactComposerAudience.PUBLIC_COMMENT] composer；
 * - 私密新会话、每条私密会话回复、公开留言分别维护**独立**草稿与独立 clientMessageId；
 * - 任何异步刷新（请求列表、账号绑定、帖子数据）都不得切换受众或共享/搬运草稿；
 *   用户主动切公开时绝不自动带入私密联系方式（两侧字段天然隔离）；
 * - 提交时捕获不可变 {postId, viewerId, audience, text, contact, clientMessageId}，
 *   仅当服务端成功且与该次发送的 clientMessageId 匹配才清空草稿；失败保留草稿与幂等键供重试。
 */
class MomentsContactComposerStateMachine(
    val postId: String,
    val viewerId: String,
    initialCanManagePost: Boolean
) {
    /** 不可变提交载荷：与当前会话身份逐项复核后再发送 */
    data class SubmitPayload(
        val postId: String,
        val viewerId: String,
        val audience: ContactComposerAudience,
        val text: String,
        val contact: String,
        val clientMessageId: String
    )

    /** 帖子管理权是异步属性：到后才更新，绝不参与 remember key 或清空草稿 */
    var canManagePost by mutableStateOf(initialCanManagePost)

    /** 非管理者选择的受众；管理者忽略此字段（见 [effectiveAudience]） */
    var audience by mutableStateOf(
        if (initialCanManagePost) {
            ContactComposerAudience.PUBLIC_COMMENT
        } else {
            ContactComposerAudience.PRIVATE_REQUEST
        }
    )
        private set

    /** 管理者永远使用公开说明 composer；受众对管理者无意义 */
    val effectiveAudience: ContactComposerAudience
        get() = if (canManagePost) ContactComposerAudience.PUBLIC_COMMENT else audience

    // ---- 私密新会话草稿（申请说明 + 仅发起人可见的联系方式）----
    var privateMessage by mutableStateOf("")
        private set
    var privateContact by mutableStateOf("")
        private set

    // ---- 公开留言草稿（所有人可见）----
    var publicComment by mutableStateOf("")
        private set
    var publicHint by mutableStateOf("")
        private set

    /** 每次"用户主动发送"一个全新幂等键；失败重试沿用、成功后轮换 */
    var privateMessageId by mutableStateOf(newClientMessageId())
        private set
    var publicMessageId by mutableStateOf(newClientMessageId())
        private set

    // ---- 每条私密会话回复的独立草稿与幂等键 ----
    // Observable map so Compose subscribes to per-request drafts; a plain
    // MutableMap + manual revision counter left the TextField unobserved
    // (typed text silently reverted to the stale value).
    private val replyDrafts = mutableStateMapOf<String, String>()
    private val replyMessageIds = mutableMapOf<String, String>()
    /** Kept as a cheap generation counter for non-Compose tests/tools. */
    var replyDraftsRevision by mutableIntStateOf(0)
        private set

    /**
     * 用户手动切换受众。绝不搬运草稿：私密文本不会流入公开 composer，
     * 公开文本也不会回填私密框。
     */
    fun selectAudience(next: ContactComposerAudience) {
        if (canManagePost) return
        audience = next
    }

    fun updatePrivateMessage(text: String) {
        privateMessage = text
    }

    fun updatePrivateContact(text: String) {
        privateContact = text
    }

    fun updatePublicComment(text: String) {
        publicComment = text
    }

    fun updatePublicHint(text: String) {
        publicHint = text
    }

    fun replyDraft(requestId: String): String = replyDrafts[requestId].orEmpty()

    fun updateReplyDraft(requestId: String, text: String) {
        if (text.isBlank() && requestId !in replyDrafts) return
        replyDrafts[requestId] = text
        replyDraftsRevision++
    }

    fun replyClientMessageId(requestId: String): String =
        replyMessageIds.getOrPut(requestId) { newClientMessageId() }

    /**
     * 异步联系申请列表刷新通知：刻意 NO-OP。
     * 列表延迟到达、数量增长、刷新重排都绝不改变受众、草稿或幂等键。
     */
    fun onContactRequestsRefreshed() = Unit

    /** 异步账号/绑定刷新通知：同样绝不改变受众与草稿（postId/viewerId 变更由外层重建状态机） */
    fun onAccountDataRefreshed() = Unit

    /**
     * 提交时捕获当前受众的不可变载荷。
     * 公开载荷只可能包含公开字段——结构上杜绝私密联系方式进入公开 API 请求体。
     */
    fun captureSubmitPayload(): SubmitPayload = when (effectiveAudience) {
        ContactComposerAudience.PRIVATE_REQUEST -> SubmitPayload(
            postId = postId,
            viewerId = viewerId,
            audience = ContactComposerAudience.PRIVATE_REQUEST,
            text = privateMessage,
            contact = privateContact,
            clientMessageId = privateMessageId
        )
        ContactComposerAudience.PUBLIC_COMMENT -> SubmitPayload(
            postId = postId,
            viewerId = viewerId,
            audience = ContactComposerAudience.PUBLIC_COMMENT,
            text = publicComment,
            contact = publicHint,
            clientMessageId = publicMessageId
        )
    }

    /** 捕获某条私密会话回复的不可变载荷 */
    fun captureReplyPayload(requestId: String): SubmitPayload = SubmitPayload(
        postId = postId,
        viewerId = viewerId,
        audience = ContactComposerAudience.PRIVATE_REQUEST,
        text = replyDraft(requestId),
        contact = "",
        clientMessageId = replyClientMessageId(requestId)
    )

    /**
     * 发送成功回执：仅当回执幂等键与当前待发键完全匹配，才清空对应草稿并轮换新键；
     * 旧发送的迟到回执、另一受众的回执都不影响当前草稿。
     */
    fun onSubmitSucceeded(payload: SubmitPayload) {
        when (payload.audience) {
            ContactComposerAudience.PUBLIC_COMMENT -> {
                if (payload.clientMessageId == publicMessageId) {
                    publicComment = ""
                    publicHint = ""
                    publicMessageId = newClientMessageId()
                }
            }
            ContactComposerAudience.PRIVATE_REQUEST -> {
                if (payload.clientMessageId == privateMessageId) {
                    privateMessage = ""
                    privateContact = ""
                    privateMessageId = newClientMessageId()
                } else {
                    val reqId = replyMessageIds.entries
                        .firstOrNull { it.value == payload.clientMessageId }?.key
                    if (reqId != null) {
                        replyDrafts.remove(reqId)
                        replyMessageIds[reqId] = newClientMessageId()
                        replyDraftsRevision++
                    }
                }
            }
        }
    }

    /** 发送失败：保留草稿与幂等键，用户重试沿用同一 clientMessageId */
    fun onSubmitFailed(@Suppress("UNUSED_PARAMETER") payload: SubmitPayload) = Unit

    /** 回复发送成功（APPROVE 附言等同理）：仅匹配本次发送键才清空该回复草稿 */
    fun onReplySucceeded(requestId: String, clientMessageId: String) {
        if (replyMessageIds[requestId] == clientMessageId) {
            replyDrafts.remove(requestId)
            replyMessageIds[requestId] = newClientMessageId()
            replyDraftsRevision++
        }
    }

    fun onReplyFailed(@Suppress("UNUSED_PARAMETER") requestId: String) = Unit

    companion object {
        fun newClientMessageId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
