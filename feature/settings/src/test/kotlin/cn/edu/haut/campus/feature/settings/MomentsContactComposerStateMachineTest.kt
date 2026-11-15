package cn.edu.haut.campus.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 私密草稿受众防泄漏回归（纯 JVM，不依赖 Compose runtime 运行环境）：
 * 受众只绑定 postId+viewerId，异步请求列表/账号刷新绝不翻转受众或共享草稿。
 */
class MomentsContactComposerStateMachineTest {

    private fun newMachine(
        postId: String = "post_1",
        viewerId: String = "viewer_A",
        canManage: Boolean = false
    ) = MomentsContactComposerStateMachine(
        postId = postId,
        viewerId = viewerId,
        initialCanManagePost = canManage
    )

    @Test
    fun `non-owner defaults to private and delayed request list arrival never flips audience`() {
        val m = newMachine()
        // 初始列表为空 -> 私密受众，用户开始输入私密草稿
        assertEquals(ContactComposerAudience.PRIVATE_REQUEST, m.effectiveAudience)
        m.updatePrivateMessage("我捡到了你的校园卡，请核对")
        m.updatePrivateContact("wx:secret_contact_123")

        // 模拟延迟返回：empty -> 1 条会话 -> 增长到 3 条 -> 刷新
        m.onContactRequestsRefreshed()
        m.onContactRequestsRefreshed()
        m.onAccountDataRefreshed()

        assertEquals(ContactComposerAudience.PRIVATE_REQUEST, m.effectiveAudience)
        assertEquals("我捡到了你的校园卡，请核对", m.privateMessage)
        assertEquals("wx:secret_contact_123", m.privateContact)
    }

    @Test
    fun `switching to public never carries private contact text into public payload`() {
        val m = newMachine()
        m.updatePrivateMessage("认领说明")
        m.updatePrivateContact("我的手机号 13800001111")

        // 用户主动切公开：私密联系方式绝不自动带入公开 composer
        m.selectAudience(ContactComposerAudience.PUBLIC_COMMENT)
        assertEquals("", m.publicComment)
        assertEquals("", m.publicHint)

        // 公开载荷结构上不可能包含私密字段
        val publicPayload = m.captureSubmitPayload()
        assertEquals(ContactComposerAudience.PUBLIC_COMMENT, publicPayload.audience)
        assertEquals("", publicPayload.contact)
        assertFalse(publicPayload.text.contains("13800001111"))
        assertFalse(publicPayload.contact.contains("13800001111"))

        // 私密草稿仍然保留（切换不清空），但分属不同提交通道
        assertEquals("我的手机号 13800001111", m.privateContact)
        m.selectAudience(ContactComposerAudience.PRIVATE_REQUEST)
        val privatePayload = m.captureSubmitPayload()
        assertEquals(ContactComposerAudience.PRIVATE_REQUEST, privatePayload.audience)
        assertEquals("我的手机号 13800001111", privatePayload.contact)
    }

    @Test
    fun `submit payload is immutable snapshot and only matching success clears draft`() {
        val m = newMachine()
        m.updatePrivateMessage("说明A")
        m.updatePrivateContact("联系A")
        val p1 = m.captureSubmitPayload()
        assertEquals("post_1", p1.postId)
        assertEquals("viewer_A", p1.viewerId)
        assertEquals(ContactComposerAudience.PRIVATE_REQUEST, p1.audience)
        assertEquals("说明A", p1.text)
        assertEquals("联系A", p1.contact)
        assertTrue(p1.clientMessageId.isNotBlank())

        // 网络失败：草稿与幂等键保留，重试沿用同一 clientMessageId
        m.onSubmitFailed(p1)
        assertEquals("说明A", m.privateMessage)
        val p1Retry = m.captureSubmitPayload()
        assertEquals(p1.clientMessageId, p1Retry.clientMessageId)

        // 迟到的不匹配成功回执不得清空当前草稿
        m.onSubmitSucceeded(p1.copy(clientMessageId = "stale_other_id"))
        assertEquals("说明A", m.privateMessage)

        // 匹配该次发送的成功回执：清空草稿并轮换新幂等键
        m.onSubmitSucceeded(p1Retry)
        assertEquals("", m.privateMessage)
        assertEquals("", m.privateContact)
        val p2 = m.captureSubmitPayload()
        assertNotEquals(p1.clientMessageId, p2.clientMessageId)
    }

    @Test
    fun `owner uses separate public composer and cannot reach private payload`() {
        val m = newMachine(canManage = true)
        assertEquals(ContactComposerAudience.PUBLIC_COMMENT, m.effectiveAudience)
        // 管理者无法切换受众（固定公开说明 composer）
        m.selectAudience(ContactComposerAudience.PRIVATE_REQUEST)
        assertEquals(ContactComposerAudience.PUBLIC_COMMENT, m.effectiveAudience)

        m.updatePublicComment("补充公开说明")
        val payload = m.captureSubmitPayload()
        assertEquals(ContactComposerAudience.PUBLIC_COMMENT, payload.audience)
        assertEquals("补充公开说明", payload.text)
    }

    @Test
    fun `reply drafts are per-request and keep own idempotency keys`() {
        val m = newMachine()
        m.updateReplyDraft("req_1", "回复一")
        m.updateReplyDraft("req_2", "回复二")
        val r1 = m.captureReplyPayload("req_1")
        val r2 = m.captureReplyPayload("req_2")
        assertNotEquals(r1.clientMessageId, r2.clientMessageId)

        // req_1 失败重试保留草稿与键；req_1 成功仅清 req_1
        m.onReplyFailed("req_1", r1.clientMessageId)
        assertEquals("回复一", m.replyDraft("req_1"))
        m.onReplySucceeded("req_1", r1.clientMessageId)
        assertEquals("", m.replyDraft("req_1"))
        assertEquals("回复二", m.replyDraft("req_2"))
        // req_1 下次发送换新键
        assertNotEquals(r1.clientMessageId, m.captureReplyPayload("req_1").clientMessageId)
    }

    // ---------- A3：草稿修订 × 在途发送幂等键分离 ----------

    @Test
    fun `send then edit then success receipt keeps the newer edited draft`() {
        val m = newMachine()
        m.updatePrivateMessage("版本A")
        m.updatePrivateContact("联系A")
        val sendA = m.captureSubmitPayload()

        // 发送在途期间用户继续编辑成版本B
        m.updatePrivateMessage("版本B-更新")

        // A 的成功回执到达：A 自己的 pending 完结，但较新的 B 草稿绝不被清
        m.onSubmitSucceeded(sendA)
        assertEquals("版本B-更新", m.privateMessage)
        assertEquals("联系A", m.privateContact)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(sendA.clientMessageId)
        )
    }

    @Test
    fun `failed send then edited resend uses a brand new key`() {
        val m = newMachine()
        m.updatePrivateMessage("载荷A")
        m.updatePrivateContact("联系A")
        val sendA = m.captureSubmitPayload()
        m.onSubmitFailed(sendA)

        // 编辑后再次发送 = 全新发送：新 clientMessageId，绝不是同键不同载荷
        m.updatePrivateMessage("载荷B-已编辑")
        val sendB = m.captureSubmitPayload()
        assertNotEquals(sendA.clientMessageId, sendB.clientMessageId)
        assertEquals("载荷B-已编辑", sendB.text)

        // 失败的旧记录保留：UI 可提供"重发旧载荷"，其键仍是 sendA 的键
        val retryOld = m.failedPendingSubmitPayload()
        assertEquals(sendA.clientMessageId, retryOld?.clientMessageId)
        assertEquals("载荷A", retryOld?.text)
    }

    @Test
    fun `identical text sent twice proactively gets two different keys`() {
        val m = newMachine()
        m.updatePrivateMessage("同一段话")
        m.updatePrivateContact("同一联系")
        val first = m.captureSubmitPayload()
        m.onSubmitSucceeded(first)

        // 再次输入完全相同的内容主动发送：这是一条新消息，必须新键
        m.updatePrivateMessage("同一段话")
        m.updatePrivateContact("同一联系")
        val second = m.captureSubmitPayload()
        assertEquals(first.text, second.text)
        assertNotEquals(first.clientMessageId, second.clientMessageId)
    }

    @Test
    fun `identical payload retry reuses the same key`() {
        val m = newMachine()
        m.updatePrivateMessage("  相同内容  ")
        m.updatePrivateContact("  wx:abc  ")
        val first = m.captureSubmitPayload()
        // 规范化在捕获点完成：载荷即 trim+截断后的值
        assertEquals("相同内容", first.text)
        assertEquals("wx:abc", first.contact)

        m.onSubmitFailed(first)
        // 未做任何编辑的重试（含外围空白差异无关——草稿未变）：沿用同一幂等键
        val retry = m.captureSubmitPayload()
        assertEquals(first.clientMessageId, retry.clientMessageId)
        assertEquals(first.text, retry.text)
        // 再成功：按 key 完结记录并清空未再编辑的草稿
        m.onSubmitSucceeded(retry)
        assertEquals("", m.privateMessage)
        assertEquals("", m.privateContact)
    }

    @Test
    fun `lost-response retry after edit is a new send not a same-key different payload`() {
        val m = newMachine()
        m.updatePrivateMessage("旧载荷")
        m.updatePrivateContact("旧联系")
        val first = m.captureSubmitPayload()
        // 响应丢失：客户端按失败处理
        m.onSubmitFailed(first)

        // 用户编辑后"重试"：绝不复用旧键发新载荷（服务端按旧键去重会返回陈旧结果/409）
        m.updatePrivateMessage("编辑后的新载荷")
        val afterEdit = m.captureSubmitPayload()
        assertNotEquals(first.clientMessageId, afterEdit.clientMessageId)

        // 迟到的旧发送成功回执仍完结自己的记录，但不碰已编辑的新草稿
        m.onSubmitSucceeded(first)
        assertEquals("编辑后的新载荷", m.privateMessage)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(first.clientMessageId)
        )
    }

    @Test
    fun `late receipt after audience switch touches only its own channel`() {
        val m = newMachine()
        m.updatePrivateMessage("私密申请")
        m.updatePrivateContact("私密联系")
        val privateSend = m.captureSubmitPayload()

        // 切到公开通道并另起草稿
        m.selectAudience(ContactComposerAudience.PUBLIC_COMMENT)
        m.updatePublicComment("公开留言")

        // 私密发送的迟到回执：公开通道草稿与在途记录完全不受影响
        m.onSubmitSucceeded(privateSend)
        assertEquals("公开留言", m.publicComment)
        // 私密草稿自捕获后未再编辑 -> 该次发送确实送达，清空它自己的通道草稿
        assertEquals("", m.privateMessage)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(privateSend.clientMessageId)
        )
    }

    @Test
    fun `late receipt after account or post switch changes nothing new`() {
        val mA = newMachine(viewerId = "viewer_A")
        mA.updatePrivateMessage("A的草稿")
        mA.updatePrivateContact("A的联系")
        val sendFromA = mA.captureSubmitPayload()

        // 账号/帖子切换：外层重建状态机
        val mB = newMachine(viewerId = "viewer_B")
        val mOtherPost = newMachine(postId = "post_2", viewerId = "viewer_A")
        mB.updatePrivateMessage("B的新草稿")
        mOtherPost.updatePrivateMessage("另一帖的草稿")

        // A 旧实例上迟到的回执：只完结旧实例自己的记录，新实例分毫无损
        mA.onSubmitSucceeded(sendFromA)
        assertEquals("B的新草稿", mB.privateMessage)
        assertEquals("另一帖的草稿", mOtherPost.privateMessage)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            mA.pendingStatusOf(sendFromA.clientMessageId)
        )
        assertEquals(null, mB.pendingStatusOf(sendFromA.clientMessageId))
    }

    @Test
    fun `reply channels are keyed per requestId with independent pending sends`() {
        val m = newMachine()
        m.updateReplyDraft("req_1", "回复甲")
        m.updateReplyDraft("req_2", "回复乙")
        val r1 = m.captureReplyPayload("req_1")
        val r2 = m.captureReplyPayload("req_2")
        assertNotEquals(r1.clientMessageId, r2.clientMessageId)
        assertEquals("req_1", r1.requestId)
        assertEquals("req_2", r2.requestId)

        // req_1 在途时编辑 req_2 再发：req_2 同载荷重试同键、req_1 记录不受影响
        m.onReplyFailed("req_2", r2.clientMessageId)
        m.updateReplyDraft("req_2", "回复乙-编辑")
        val r2Edited = m.captureReplyPayload("req_2")
        assertNotEquals(r2.clientMessageId, r2Edited.clientMessageId)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.IN_FLIGHT,
            m.pendingStatusOf(r1.clientMessageId)
        )

        // req_2 编辑前的旧载荷失败记录可显式重发；req_1 成功只清 req_1 草稿
        assertEquals(r2.clientMessageId, m.failedPendingReplyPayload("req_2")?.clientMessageId)
        m.onReplySucceeded("req_1", r1.clientMessageId)
        assertEquals("", m.replyDraft("req_1"))
        assertEquals("回复乙-编辑", m.replyDraft("req_2"))
    }

    @Test
    fun `reply late success after further edits keeps the edited reply draft`() {
        val m = newMachine()
        m.updateReplyDraft("req_9", "回复v1")
        val send = m.captureReplyPayload("req_9")
        m.updateReplyDraft("req_9", "回复v2-改过")
        m.onReplySucceeded("req_9", send.clientMessageId)
        assertEquals("回复v2-改过", m.replyDraft("req_9"))
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(send.clientMessageId)
        )
    }

    // ---------- R3 §4：乱序/重复回执守卫 —— SUCCEEDED 终态 ----------

    @Test
    fun `same key late failure after success never regresses the settled record`() {
        val m = newMachine()
        m.updatePrivateMessage("申请说明")
        m.updatePrivateContact("wx:a")
        val send = m.captureSubmitPayload()
        val key = send.clientMessageId

        // 成功先到位：记录完结、草稿清空
        m.onSubmitSucceeded(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )
        assertEquals("", m.privateMessage)

        // 同键迟到失败回执：绝不允许把成功回退成失败
        m.onSubmitFailed(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )
        // 草稿保持已清空状态，绝不因迟到失败复活旧文本
        assertEquals("", m.privateMessage)
        assertEquals("", m.privateContact)
        // 且不再存在任何"可重发的失败载荷"
        assertEquals(null, m.failedPendingSubmitPayload()?.clientMessageId)
    }

    @Test
    fun `same key concurrent retry landing one success one failure keeps the success`() {
        val m = newMachine()
        m.updatePrivateMessage("同一载荷")
        m.updatePrivateContact("wx:k")

        // 同键并发：第一次发送在途时同载荷重试沿用同一 key
        val first = m.captureSubmitPayload()
        val retry = m.captureSubmitPayload()
        assertEquals(first.clientMessageId, retry.clientMessageId)
        val key = first.clientMessageId

        // 失败先到位、成功后到位：允许 FAILED -> SUCCEEDED
        m.onSubmitFailed(first)
        m.onSubmitSucceeded(retry)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )

        // 再来一次同键迟到失败：成功是终态，绝不翻转
        m.onSubmitFailed(first)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )
    }

    @Test
    fun `repeated success receipts are idempotent and stay succeeded`() {
        val m = newMachine()
        m.updatePrivateMessage("说明")
        m.updatePrivateContact("wx:r")
        val send = m.captureSubmitPayload()
        val key = send.clientMessageId

        m.onSubmitSucceeded(send)
        m.onSubmitSucceeded(send)
        m.onSubmitSucceeded(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )
        assertEquals("", m.privateMessage)

        // 成功终态连"显式重发标记"也吸收：只有 FAILED 记录才能显式回到 IN_FLIGHT
        m.markPendingResubmitted(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(key)
        )
    }

    @Test
    fun `failed record can still return to in-flight via explicit resubmit`() {
        val m = newMachine()
        m.updatePrivateMessage("说明")
        m.updatePrivateContact("wx:f")
        val send = m.captureSubmitPayload()
        m.onSubmitFailed(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.FAILED,
            m.pendingStatusOf(send.clientMessageId)
        )

        // 显式重发旧载荷：FAILED -> IN_FLIGHT（合法新发送语义）
        m.markPendingResubmitted(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.IN_FLIGHT,
            m.pendingStatusOf(send.clientMessageId)
        )
        // 在途重发再失败：仍可落到 FAILED
        m.onSubmitFailed(send)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.FAILED,
            m.pendingStatusOf(send.clientMessageId)
        )
    }

    @Test
    fun `reply channel late failure after success also stays succeeded`() {
        val m = newMachine()
        m.updateReplyDraft("req_9", "回复内容")
        val send = m.captureReplyPayload("req_9")

        m.onReplySucceeded("req_9", send.clientMessageId)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(send.clientMessageId)
        )
        assertEquals("", m.replyDraft("req_9"))

        // 同键迟到失败回执：成功终态吸收，草稿记录均不回退
        m.onReplyFailed("req_9", send.clientMessageId)
        assertEquals(
            MomentsContactComposerStateMachine.PendingStatus.SUCCEEDED,
            m.pendingStatusOf(send.clientMessageId)
        )
        assertEquals("", m.replyDraft("req_9"))
        assertEquals(null, m.failedPendingReplyPayload("req_9"))
    }

    @Test
    fun `post or viewer switch yields isolated composer state`() {
        val mA = newMachine(viewerId = "viewer_A")
        mA.updatePrivateMessage("A的私密草稿")
        mA.updatePrivateContact("A的联系")
        val mB = newMachine(viewerId = "viewer_B")
        // 账号切换后的新 composer 不携带上一账号任何草稿
        assertEquals("", mB.privateMessage)
        assertEquals("", mB.publicComment)
        val mOtherPost = newMachine(postId = "post_2", viewerId = "viewer_A")
        assertEquals("", mOtherPost.privateMessage)
        // 原账号原帖 composer 仍完整保留（A->B->A 回来草稿不丢、键不变）
        assertEquals("A的私密草稿", mA.privateMessage)
    }
}
