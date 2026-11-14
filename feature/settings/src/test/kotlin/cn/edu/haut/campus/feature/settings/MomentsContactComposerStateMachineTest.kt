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
        m.onReplyFailed("req_1")
        assertEquals("回复一", m.replyDraft("req_1"))
        m.onReplySucceeded("req_1", r1.clientMessageId)
        assertEquals("", m.replyDraft("req_1"))
        assertEquals("回复二", m.replyDraft("req_2"))
        // req_1 下次发送换新键
        assertNotEquals(r1.clientMessageId, m.captureReplyPayload("req_1").clientMessageId)
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
