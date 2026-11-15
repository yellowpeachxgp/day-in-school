package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
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
 * 失物招领详情页留言 composer 状态机（A1 私密草稿防泄漏 + A3 发送/草稿幂等键分离）：
 *
 * - 生命周期严格绑定 (postId, viewerId)，绝不绑定异步请求列表长度或任何刷新计数；
 * - 非帖子管理者默认 [ContactComposerAudience.PRIVATE_REQUEST]；
 *   帖子管理者补充公开说明固定走 [ContactComposerAudience.PUBLIC_COMMENT] composer；
 * - 私密新会话、公开留言、每条私密会话回复分别是一条**独立提交通道**，
 *   各自持有 [Draft]{revision,text,contact} 与若干不可变 [PendingSend] 记录；
 * - 任何异步刷新（请求列表、账号绑定、帖子数据）都不得切换受众或共享/搬运草稿；
 *   用户主动切公开时绝不自动带入私密联系方式（两侧字段天然隔离）；
 * - 发送键（clientMessageId）绑定的是**载荷**而不是草稿框：
 *   相同规范化载荷的重试沿用同一 key，任何编辑后的发送都是新 key 的新发送；
 * - 成功回执只清空"捕获时修订号仍等于当前草稿修订号"的通道草稿；
 *   迟到回执照常完结自己的 pending 记录，但绝不动更新过的草稿；
 * - 失败保留 pending 记录与新草稿：UI 可显式提供"重发旧载荷"
 *   （[failedPendingSubmitPayload]/[failedPendingReplyPayload]）或直接"发送新内容"。
 */
class MomentsContactComposerStateMachine(
    val postId: String,
    val viewerId: String,
    initialCanManagePost: Boolean
) {
    /**
     * 不可变提交载荷：与当前会话身份逐项复核后再发送。
     * text/contact 在捕获点已做规范化（trim + 长度上限），即发即所用值。
     */
    data class SubmitPayload(
        val postId: String,
        val viewerId: String,
        val audience: ContactComposerAudience,
        val text: String,
        val contact: String,
        val clientMessageId: String,
        /** 私密回复通道的会话 id；私密申请/公开留言通道为 null */
        val requestId: String? = null
    )

    /** 某通道的草稿快照：revision 在每次内容变化（含成功清空）时单调递增 */
    data class Draft(
        val revision: Long,
        val text: String,
        val contact: String
    )

    /** 在途发送状态机：IN_FLIGHT → SUCCEEDED / FAILED（FAILED 可被同载荷重试回到 IN_FLIGHT） */
    enum class PendingStatus {
        IN_FLIGHT,
        FAILED,
        SUCCEEDED
    }

    /**
     * 不可变在途发送记录：key 即 clientMessageId，revision 为捕获点草稿修订号，
     * payloadHash 覆盖规范化载荷；状态迁移以整条记录 copy 替换保持不可变。
     */
    data class PendingSend(
        val key: String,
        val revision: Long,
        val payloadHash: String,
        val payload: SubmitPayload,
        val status: PendingStatus
    )

    /**
     * 一条提交通道：草稿修订号 + key 索引的全部发送记录。
     * 草稿文本本体仍由 Compose 可观察字段持有，通道只追踪修订号与发送记录。
     */
    private inner class SendChannel(
        val readText: () -> String,
        val readContact: () -> String,
        val writeDraft: (text: String, contact: String) -> Unit,
        val onDraftMutated: () -> Unit = {}
    ) {
        var draftRevision = 0L
        val sends = LinkedHashMap<String, PendingSend>()

        fun currentDraft() = Draft(draftRevision, readText(), readContact())

        /** 任何草稿内容变化（用户编辑、成功清空）都必须经由此处递增修订号 */
        fun noteDraftEdited() {
            draftRevision++
            onDraftMutated()
        }

        fun clearDraft() {
            noteDraftEdited()
            writeDraft("", "")
        }

        fun latestFailed(): PendingSend? =
            sends.values.lastOrNull { it.status == PendingStatus.FAILED }

        /**
         * 状态迁移守卫（R3 §4 乱序回执修复）：
         * - SUCCEEDED 为终态——其后的迟到 FAILED、重复回执、显式标记一律吸收，绝不回退；
         *   同键并发重试"一成功一失败"落地时，先到位的成功绝不被后到的失败翻转；
         * - FAILED 只允许经显式重发回到 IN_FLIGHT（[markPendingResubmitted] 或
         *   capture 以同键换新 PendingSend 记录——二者均为"显式新发送"语义）。
         */
        fun markStatus(key: String, status: PendingStatus) {
            val record = sends[key] ?: return
            if (record.status == PendingStatus.SUCCEEDED && status != PendingStatus.SUCCEEDED) {
                return
            }
            sends[key] = record.copy(status = status)
        }
    }

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

    // ---- 每条私密会话回复的独立草稿 ----
    // Observable map so Compose subscribes to per-request drafts; a plain
    // MutableMap + manual revision counter left the TextField unobserved
    // (typed text silently reverted to the stale value).
    private val replyDrafts = mutableStateMapOf<String, String>()
    /** Kept as a cheap generation counter for non-Compose tests/tools. */
    var replyDraftsRevision by mutableIntStateOf(0)
        private set

    // ---- 三大提交通道（回复通道按 requestId 懒创建）----
    private val privateChannel = SendChannel(
        readText = { privateMessage },
        readContact = { privateContact },
        writeDraft = { t, c -> privateMessage = t; privateContact = c }
    )
    private val publicChannel = SendChannel(
        readText = { publicComment },
        readContact = { publicHint },
        writeDraft = { t, c -> publicComment = t; publicHint = c }
    )
    private val replyChannels = mutableMapOf<String, SendChannel>()

    private fun replyChannel(requestId: String): SendChannel =
        replyChannels.getOrPut(requestId) {
            SendChannel(
                readText = { replyDrafts[requestId].orEmpty() },
                readContact = { "" },
                writeDraft = { _, _ -> replyDrafts.remove(requestId) },
                onDraftMutated = { replyDraftsRevision++ }
            )
        }

    private fun channelFor(audience: ContactComposerAudience, requestId: String?): SendChannel = when {
        requestId != null -> replyChannel(requestId)
        audience == ContactComposerAudience.PUBLIC_COMMENT -> publicChannel
        else -> privateChannel
    }

    /**
     * 用户手动切换受众。绝不搬运草稿：私密文本不会流入公开 composer，
     * 公开文本也不会回填私密框。通道间发送记录同样互不相通。
     */
    fun selectAudience(next: ContactComposerAudience) {
        if (canManagePost) return
        audience = next
    }

    fun updatePrivateMessage(text: String) {
        privateChannel.noteDraftEdited()
        privateMessage = text
    }

    fun updatePrivateContact(text: String) {
        privateChannel.noteDraftEdited()
        privateContact = text
    }

    fun updatePublicComment(text: String) {
        publicChannel.noteDraftEdited()
        publicComment = text
    }

    fun updatePublicHint(text: String) {
        publicChannel.noteDraftEdited()
        publicHint = text
    }

    fun replyDraft(requestId: String): String = replyDrafts[requestId].orEmpty()

    fun updateReplyDraft(requestId: String, text: String) {
        if (text.isBlank() && requestId !in replyDrafts) return
        replyChannel(requestId).noteDraftEdited()
        replyDrafts[requestId] = text
    }

    /**
     * 异步联系申请列表刷新通知：刻意 NO-OP。
     * 列表延迟到达、数量增长、刷新重排都绝不改变受众、草稿或幂等键。
     */
    fun onContactRequestsRefreshed() = Unit

    /** 异步账号/绑定刷新通知：同样绝不改变受众与草稿（postId/viewerId 变更由外层重建状态机） */
    fun onAccountDataRefreshed() = Unit

    /**
     * 在当前通道捕获一次发送：
     * - 规范化（trim + 长度上限）当前草稿为规范载荷并计算 payloadHash；
     * - 存在同 hash 且未成功（IN_FLIGHT/FAILED）的记录 → 同载荷重试，沿用其 key；
     * - 否则（编辑过、已成功过、首次发送）→ 全新 key 的全新 [PendingSend]。
     */
    private fun capture(
        channel: SendChannel,
        audience: ContactComposerAudience,
        requestId: String?
    ): SubmitPayload {
        val draft = channel.currentDraft()
        val canonicalText = draft.text.trim().take(MAX_TEXT_LENGTH)
        val canonicalContact = draft.contact.trim().take(MAX_CONTACT_LENGTH)
        val hash = payloadHash(postId, viewerId, audience, requestId, canonicalText, canonicalContact)
        val reusable = channel.sends.values.firstOrNull {
            it.payloadHash == hash && it.status != PendingStatus.SUCCEEDED
        }
        val key = reusable?.key ?: newClientMessageId()
        val payload = SubmitPayload(
            postId = postId,
            viewerId = viewerId,
            audience = audience,
            text = canonicalText,
            contact = canonicalContact,
            clientMessageId = key,
            requestId = requestId
        )
        // 同 hash 的旧成功记录已无重试价值，顺手回收，保证每条 hash 至多一条活跃记录
        channel.sends.values.removeAll { it.payloadHash == hash && it.status == PendingStatus.SUCCEEDED }
        channel.sends[key] = PendingSend(
            key = key,
            revision = draft.revision,
            payloadHash = hash,
            payload = payload,
            status = PendingStatus.IN_FLIGHT
        )
        return payload
    }

    /**
     * 提交时捕获当前受众的不可变载荷。
     * 公开载荷只可能包含公开字段——结构上杜绝私密联系方式进入公开 API 请求体。
     */
    fun captureSubmitPayload(): SubmitPayload = when (effectiveAudience) {
        ContactComposerAudience.PRIVATE_REQUEST ->
            capture(privateChannel, ContactComposerAudience.PRIVATE_REQUEST, requestId = null)
        ContactComposerAudience.PUBLIC_COMMENT ->
            capture(publicChannel, ContactComposerAudience.PUBLIC_COMMENT, requestId = null)
    }

    /** 捕获某条私密会话回复的不可变载荷（回复通道按 requestId 独立键控） */
    fun captureReplyPayload(requestId: String): SubmitPayload =
        capture(replyChannel(requestId), ContactComposerAudience.PRIVATE_REQUEST, requestId = requestId)

    private fun onSendSettled(payload: SubmitPayload, status: PendingStatus) {
        val channel = channelFor(payload.audience, payload.requestId)
        val record = channel.sends[payload.clientMessageId] ?: return
        // 经统一迁移守卫落地：SUCCEEDED 终态吸收迟到 FAILED/重复回执
        channel.markStatus(record.key, status)
        if (status == PendingStatus.SUCCEEDED &&
            channel.currentDraft().revision == record.revision
        ) {
            channel.clearDraft()
        }
    }

    /**
     * 发送成功回执：完结该 key 自己的 pending 记录；
     * 仅当草稿自捕获后未被再编辑（修订号仍相等）才清空对应通道草稿。
     * 旧发送的迟到回执、另一通道的回执都不影响当前草稿。
     */
    fun onSubmitSucceeded(payload: SubmitPayload) = onSendSettled(payload, PendingStatus.SUCCEEDED)

    /** 发送失败：pending 记录转 FAILED，草稿保留；同载荷重试沿用同一 clientMessageId */
    fun onSubmitFailed(payload: SubmitPayload) = onSendSettled(payload, PendingStatus.FAILED)

    /** 回复发送成功（APPROVE 附言等同理）：完结该回复通道内此 key 的记录并按修订号清草稿 */
    fun onReplySucceeded(requestId: String, clientMessageId: String) {
        val channel = replyChannels[requestId] ?: return
        val record = channel.sends[clientMessageId] ?: return
        channel.markStatus(record.key, PendingStatus.SUCCEEDED)
        if (channel.currentDraft().revision == record.revision) {
            channel.clearDraft()
        }
    }

    /** 回复发送失败：该回复通道内此 key 转 FAILED，草稿保留供同键重试 */
    fun onReplyFailed(requestId: String, clientMessageId: String) {
        replyChannels[requestId]?.markStatus(clientMessageId, PendingStatus.FAILED)
    }

    /** 当前受众通道里最近一条 FAILED 载荷：供 UI 显式"重发旧载荷"（编辑后的新草稿走正常 capture 即新 key） */
    fun failedPendingSubmitPayload(): SubmitPayload? =
        channelFor(effectiveAudience, requestId = null).latestFailed()?.payload

    /** 某回复通道里最近一条 FAILED 载荷 */
    fun failedPendingReplyPayload(requestId: String): SubmitPayload? =
        replyChannels[requestId]?.latestFailed()?.payload

    /** 显式重发一条 pending 载荷前调用：将其记录重新置为 IN_FLIGHT（键与载荷不变） */
    fun markPendingResubmitted(payload: SubmitPayload) {
        channelFor(payload.audience, payload.requestId)
            .markStatus(payload.clientMessageId, PendingStatus.IN_FLIGHT)
    }

    /** 测试/诊断：按 key 查询任意通道的发送记录状态 */
    fun pendingStatusOf(clientMessageId: String): PendingStatus? {
        for (channel in sequenceOf(privateChannel, publicChannel) + replyChannels.values.asSequence()) {
            channel.sends[clientMessageId]?.let { return it.status }
        }
        return null
    }

    companion object {
        /** 与服务端字段上限一致的规范化常量 */
        const val MAX_TEXT_LENGTH = 240
        const val MAX_CONTACT_LENGTH = 64

        fun newClientMessageId(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * 规范载荷指纹：覆盖 (postId, viewerId, audience, requestId, 规范化 text+contact)。
         * 每段带长度前缀，字段含分隔符也不会产生歧义拼接。
         */
        internal fun payloadHash(
            postId: String,
            viewerId: String,
            audience: ContactComposerAudience,
            requestId: String?,
            text: String,
            contact: String
        ): String {
            fun seg(value: String?) = "${value?.length ?: -1}:$value|"
            val canonical = buildString {
                append(seg(postId)).append(seg(viewerId)).append(seg(audience.name))
                append(seg(requestId)).append(seg(text)).append(seg(contact))
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
