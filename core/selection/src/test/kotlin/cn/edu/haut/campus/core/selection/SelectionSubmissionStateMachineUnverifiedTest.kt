package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.AcademicError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNVERIFIED：轮次、回执与官方回读全部是合成数据；本节验证受控提交流程的守卫，
 * 不代表任何真实学校已经支持选课写入。
 */
class SelectionSubmissionStateMachineUnverifiedTest {

    private val scope = SelectionFixtures.scope()

    private fun begin(round: SelectionRound = SelectionFixtures.OPEN_ROUND): SelectionSubmission =
        SelectionSubmission.begin(scope, round, SelectionFixtures.request())

    private fun confirmed(): SelectionSubmission {
        val transition = begin().confirm(SelectionFixtures.NOW)
        assertTrue("合成夹具(UNVERIFIED)：开放轮次必须能生成确认快照", transition is SelectionTransition.Applied)
        return (transition as SelectionTransition.Applied).submission
    }

    private fun approved(): SelectionSubmission {
        val submission = confirmed()
        val digest = submission.snapshot!!.digest
        val transition = submission.approve(digest, SelectionFixtures.NOW)
        assertTrue("合成夹具(UNVERIFIED)：摘要正确时必须批准", transition is SelectionTransition.Applied)
        return (transition as SelectionTransition.Applied).submission
    }

    private fun submitting(): SubmissionState.Submitting = approved().state as SubmissionState.Submitting

    @Test
    fun `初始状态是草稿且没有确认快照`() {
        val submission = begin()
        assertEquals(SubmissionState.Draft, submission.state)
        assertEquals("草稿", submission.stateLabel)
        assertNull(submission.snapshot)
    }

    @Test
    fun `确认快照完整记录学校账号课程教学班与动作`() {
        val snapshot = (confirmed().state as SubmissionState.ConfirmRequired).snapshot
        assertEquals(SelectionFixtures.SCHOOL, snapshot.scope.schoolId)
        assertEquals(SelectionFixtures.ACCOUNT, snapshot.scope.accountId)
        assertEquals(SelectionFixtures.TERM, snapshot.scope.termId)
        assertEquals("round-1", snapshot.roundId)
        assertEquals("off-1", snapshot.offeringId)
        assertEquals("高等数学（合成）", snapshot.courseName)
        assertEquals("class-1", snapshot.teachingClassId)
        assertEquals(SelectionAction.ENROLL, snapshot.action)
        assertTrue("快照摘要必须是 sha256 十六进制：" + snapshot.digest, snapshot.digest.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `轮次不可写时不能生成确认快照`() {
        val readOnly = begin(SelectionFixtures.READ_ONLY_ROUND).confirm(SelectionFixtures.NOW)
        assertTrue(readOnly is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.ROUND_NOT_WRITABLE, (readOnly as SelectionTransition.Blocked).reason)

        val closed = begin(SelectionFixtures.CLOSED_ROUND).confirm(SelectionFixtures.NOW)
        assertTrue(closed is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.ROUND_NOT_WRITABLE, (closed as SelectionTransition.Blocked).reason)
        assertTrue(closed.detail.contains("已结束"))
    }

    @Test
    fun `重复确认保持同一份快照`() {
        val submission = confirmed()
        val again = submission.confirm(SelectionFixtures.NOW)
        assertTrue(again is SelectionTransition.Applied)
        assertSame("重复确认必须是幂等的", submission, (again as SelectionTransition.Applied).submission)
    }

    @Test
    fun `未确认不能直接批准提交`() {
        val blocked = begin().approve("anything", SelectionFixtures.NOW)
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.INVALID_STATE, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `摘要不匹配时拒绝批准提交`() {
        val blocked = confirmed().approve("0".repeat(64), SelectionFixtures.NOW)
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.CONFIRMATION_MISMATCH, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `批准后进入提交中并生成确定性幂等键`() {
        val state = submitting()
        assertEquals("提交中", state.stateLabel)
        assertEquals(
            SelectionIdempotencyKey.derive(state.snapshot),
            state.key
        )
        assertEquals(SelectionFixtures.NOW, state.approvedAt)
    }

    @Test
    fun `确认后闭轮不能继续批准`() {
        val round = SelectionFixtures.OPEN_ROUND.copy(closesAt = SelectionFixtures.NOW.plusSeconds(1))
        val submission = SelectionSubmission.begin(scope, round, SelectionFixtures.request())
        val confirmedSubmission = (submission.confirm(SelectionFixtures.NOW) as SelectionTransition.Applied).submission
        val digest = confirmedSubmission.snapshot!!.digest
        val blocked = confirmedSubmission.approve(digest, SelectionFixtures.NOW.plusSeconds(2))
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.ROUND_NOT_WRITABLE, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `修改教学班后旧确认摘要立即失效`() {
        val confirmedSubmission = confirmed()
        val staleDigest = confirmedSubmission.snapshot!!.digest
        val revised = confirmedSubmission.revise(SelectionFixtures.request(classId = "class-2"))
        assertTrue(revised is SelectionTransition.Applied)
        assertEquals(SubmissionState.Draft, (revised as SelectionTransition.Applied).submission.state)

        val reconfirmed = (revised.submission.confirm(SelectionFixtures.NOW) as SelectionTransition.Applied).submission
        val blocked = reconfirmed.approve(staleDigest, SelectionFixtures.NOW)
        assertTrue("界面停留在旧快照时必须拒绝提交", blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.CONFIRMATION_MISMATCH, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `写入发出后不允许再修改请求`() {
        val blocked = approved().revise(SelectionFixtures.request(classId = "class-2"))
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.INVALID_STATE, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `校方受理后进入已提交待核对`() {
        val submission = approved()
        val outcome = SelectionFixtures.acceptedOutcome(submission.state as SubmissionState.Submitting)
        val applied = submission.recordOutcome(outcome, SelectionFixtures.NOW)
        val state = (applied as SelectionTransition.Applied).submission.state as SubmissionState.SubmittedUnverified
        assertEquals("已提交待核对", state.stateLabel)
        assertEquals("receipt-demo", state.receipt.serverReference)
        assertNull("受理不等于生效，回读前不得给出结论", state.readback)
    }

    @Test
    fun `校方拒绝时保留原因`() {
        val applied = approved().recordOutcome(WriteOutcome.Rejected("学分已满", retryable = true), SelectionFixtures.NOW)
        val state = (applied as SelectionTransition.Applied).submission.state as SubmissionState.Rejected
        assertEquals("学分已满", state.reason)
        assertTrue(state.retryable)
        assertEquals("校方拒绝", state.stateLabel)
    }

    @Test
    fun `超时与结果未知一律进入未知态`() {
        val applied = approved().recordOutcome(
            WriteOutcome.ResultUnknown(AcademicError.TIMEOUT, "等待教务响应超时"),
            SelectionFixtures.NOW
        )
        val state = (applied as SelectionTransition.Applied).submission.state as SubmissionState.Unknown
        assertEquals(AcademicError.TIMEOUT, state.cause)
        assertEquals("等待教务响应超时", state.detail)
        assertEquals("结果未知", state.stateLabel)
    }

    @Test
    fun `回执幂等键不匹配时拒绝采纳`() {
        val submission = approved()
        val submitting = submission.state as SubmissionState.Submitting
        val forged = WriteOutcome.Accepted(
            SubmissionReceipt(
                key = IdempotencyKey("sel-" + "f".repeat(64)),
                snapshotDigest = submitting.snapshot.digest,
                submittedAt = SelectionFixtures.NOW
            )
        )
        val blocked = submission.recordOutcome(forged, SelectionFixtures.NOW)
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.INVALID_STATE, (blocked as SelectionTransition.Blocked).reason)
        assertNull("拒绝伪造回执时不得产生新状态", blocked.submissionOrNull)
    }

    @Test
    fun `非提交态不接受写入结果`() {
        val blocked = confirmed().recordOutcome(
            WriteOutcome.ResultUnknown(AcademicError.TIMEOUT),
            SelectionFixtures.NOW
        )
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.INVALID_STATE, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `协调器只调用一次写端口并保存幂等键`() {
        val submission = approved()
        val port = RecordingWritePort(SelectionFixtures.acceptedOutcome(submission.state as SubmissionState.Submitting))
        val ledger = SelectionIdempotencyLedger()
        val coordinator = SelectionSubmissionCoordinator(port, ledger) { SelectionFixtures.NOW }

        val applied = coordinator.submit(submission)
        assertTrue(applied is SelectionTransition.Applied)
        assertEquals(1, port.commands.size)
        assertEquals(1, ledger.size())
        assertNotNull(ledger.record(port.commands.single().key))
        assertEquals("写入命令必须原样携带确认快照", submission.snapshot, port.commands.single().snapshot)
    }

    @Test
    fun `同一幂等键第二次提交在触网之前被拒绝`() {
        val first = approved()
        val second = approved()
        assertEquals("同一意图必须得到同一幂等键", (first.state as SubmissionState.Submitting).key, (second.state as SubmissionState.Submitting).key)

        val port = RecordingWritePort(SelectionFixtures.acceptedOutcome(first.state as SubmissionState.Submitting))
        val coordinator = SelectionSubmissionCoordinator(port) { SelectionFixtures.NOW }
        assertTrue(coordinator.submit(first) is SelectionTransition.Applied)

        val duplicate = coordinator.submit(second)
        assertTrue(duplicate is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.DUPLICATE_IDEMPOTENCY_KEY, (duplicate as SelectionTransition.Blocked).reason)
        assertEquals("重复提交不得再次触网", 1, port.commands.size)
    }

    @Test
    fun `结果未知后只能核对官方结果不能重发`() {
        val submission = approved()
        val port = RecordingWritePort(WriteOutcome.ResultUnknown(AcademicError.TIMEOUT, "网络超时"))
        val ledger = SelectionIdempotencyLedger()
        val coordinator = SelectionSubmissionCoordinator(port, ledger) { SelectionFixtures.NOW }

        val unknown = (coordinator.submit(submission) as SelectionTransition.Applied).submission
        assertTrue(unknown.state is SubmissionState.Unknown)

        val retry = coordinator.submit(unknown)
        assertTrue(retry is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.RESULT_UNKNOWN_REQUIRES_READBACK, (retry as SelectionTransition.Blocked).reason)
        assertEquals("结果未知时绝不允许重发", 1, port.commands.size)
        assertEquals("结果未知时幂等键不得释放", 1, ledger.size())
    }

    @Test
    fun `写端口抛异常按结果未知处理且不泄露异常正文`() {
        val submission = approved()
        val coordinator = SelectionSubmissionCoordinator(
            SelectionWritePort { throw IllegalStateException("session=secret-cookie") }
        ) { SelectionFixtures.NOW }
        val applied = coordinator.submit(submission)
        val state = (applied as SelectionTransition.Applied).submission.state as SubmissionState.Unknown
        assertEquals(AcademicError.WRITE_RESULT_UNKNOWN, state.cause)
        assertTrue(state.detail!!.contains("IllegalStateException"))
        assertFalse("异常正文（可能含 Cookie）不得进入状态", state.detail.contains("secret-cookie"))
    }

    @Test
    fun `官方明确拒绝后允许重新走确认流程`() {
        val port = RecordingWritePort(WriteOutcome.Rejected("课程已满", retryable = false))
        val ledger = SelectionIdempotencyLedger()
        val coordinator = SelectionSubmissionCoordinator(port, ledger) { SelectionFixtures.NOW }

        val rejected = (coordinator.submit(approved()) as SelectionTransition.Applied).submission
        assertTrue(rejected.state is SubmissionState.Rejected)
        assertEquals("校方明确拒绝时可以确定写入未生效，应释放幂等键", 0, ledger.size())

        val again = coordinator.submit(approved())
        assertTrue(again is SelectionTransition.Applied)
        assertEquals(2, port.commands.size)
    }

    @Test
    fun `完整走通草稿到官方回读一致`() {
        val submission = approved()
        val port = RecordingWritePort(SelectionFixtures.acceptedOutcome(submission.state as SubmissionState.Submitting))
        val coordinator = SelectionSubmissionCoordinator(port) { SelectionFixtures.NOW }
        val unverified = (coordinator.submit(submission) as SelectionTransition.Applied).submission

        val readback = readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        val verified = coordinator.verify(unverified, readback)
        val state = (verified as SelectionTransition.Applied).submission.state as SubmissionState.Verified
        assertTrue("官方回读一致才能进入已验证", state.comparison.matched)
        assertEquals("receipt-demo", state.receipt!!.serverReference)
        assertEquals(SelectionFixtures.NOW, state.verifiedAt)
        assertEquals("官方回读一致", state.stateLabel)
    }

    @Test
    fun `回读不一致时保持待核对并记录差异`() {
        val submission = approved()
        val port = RecordingWritePort(SelectionFixtures.acceptedOutcome(submission.state as SubmissionState.Submitting))
        val coordinator = SelectionSubmissionCoordinator(port) { SelectionFixtures.NOW }
        val unverified = (coordinator.submit(submission) as SelectionTransition.Applied).submission

        val emptyReadback = readbackOf(scope)
        val result = coordinator.verify(unverified, emptyReadback)
        val state = (result as SelectionTransition.Applied).submission.state as SubmissionState.SubmittedUnverified
        val comparison = state.readback!!
        assertFalse("回读缺失不得判定为已验证", comparison.matched)
        assertEquals(ReadbackDifferenceKind.MISSING_IN_OFFICIAL, comparison.differences.single().kind)
        assertEquals("核对期间不得产生新的写请求", 1, port.commands.size)
    }

    @Test
    fun `官方回读不属于当前账号时拒绝判定`() {
        val submission = approved()
        val port = RecordingWritePort(SelectionFixtures.acceptedOutcome(submission.state as SubmissionState.Submitting))
        val coordinator = SelectionSubmissionCoordinator(port) { SelectionFixtures.NOW }
        val unverified = (coordinator.submit(submission) as SelectionTransition.Applied).submission

        val otherAccountReadback = readbackOf(
            SelectionFixtures.scope(account = SelectionFixtures.OTHER_ACCOUNT),
            AcceptedClass("off-1", "class-1", "高等数学（合成）")
        )
        val blocked = coordinator.verify(unverified, otherAccountReadback)
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.SCOPE_MISMATCH, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `结果未知但官方回读一致时可以直接确认成功`() {
        val submission = approved()
        val coordinator = SelectionSubmissionCoordinator(
            RecordingWritePort(WriteOutcome.ResultUnknown(AcademicError.TIMEOUT, "网络超时"))
        ) { SelectionFixtures.NOW }
        val unknown = (coordinator.submit(submission) as SelectionTransition.Applied).submission
        assertTrue(unknown.state is SubmissionState.Unknown)

        val readback = readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        val verified = coordinator.verify(unknown, readback)
        val state = (verified as SelectionTransition.Applied).submission.state as SubmissionState.Verified
        assertTrue(state.comparison.matched)
        assertNull("结果未知时没有校方回执，不得伪造", state.receipt)
    }

    @Test
    fun `结果未知且回读不一致时仍保持未知且不能重发`() {
        val submission = approved()
        val port = RecordingWritePort(WriteOutcome.ResultUnknown(AcademicError.WRITE_RESULT_UNKNOWN))
        val coordinator = SelectionSubmissionCoordinator(port) { SelectionFixtures.NOW }
        val unknown = (coordinator.submit(submission) as SelectionTransition.Applied).submission

        val stillUnknown = (coordinator.verify(unknown, readbackOf(scope)) as SelectionTransition.Applied).submission
        val state = stillUnknown.state as SubmissionState.Unknown
        assertFalse(state.readback!!.matched)
        assertEquals(ReadbackDifferenceKind.MISSING_IN_OFFICIAL, state.readback.differences.single().kind)

        val retry = coordinator.submit(stillUnknown)
        assertTrue(retry is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.RESULT_UNKNOWN_REQUIRES_READBACK, (retry as SelectionTransition.Blocked).reason)
        assertEquals(1, port.commands.size)
    }

    @Test
    fun `草稿状态不能核对回读`() {
        val blocked = confirmed().verify(readbackOf(scope), SelectionFixtures.NOW)
        assertTrue(blocked is SelectionTransition.Blocked)
        assertEquals(SelectionBlockReason.INVALID_STATE, (blocked as SelectionTransition.Blocked).reason)
    }

    @Test
    fun `快照摘要对教学班与动作敏感`() {
        val snapshot = confirmed().snapshot!!
        assertEquals("同一内容必须得到同一摘要", snapshot.digest, snapshot.copy().digest)
        assertFalse("更换教学班必须改变摘要", snapshot.digest == snapshot.copy(teachingClassId = "class-2").digest)
        assertFalse("更换动作必须改变摘要", snapshot.digest == snapshot.copy(action = SelectionAction.DROP).digest)
        assertFalse("更换账号必须改变摘要", snapshot.digest == snapshot.copy(scope = SelectionFixtures.scope(account = SelectionFixtures.OTHER_ACCOUNT)).digest)
    }

    @Test
    fun `确认页文案不包含账号与学校标识`() {
        val text = confirmed().snapshot!!.summaryText()
        assertTrue("确认文案必须包含课程名：" + text, text.contains("高等数学（合成）"))
        assertTrue(text.contains("class-1"))
        assertFalse("确认文案不得包含账号 ID", text.contains("account-a"))
        assertFalse("确认文案不得包含学校 ID", text.contains("demo-school"))
        assertFalse("确认文案不得包含学期 ID", text.contains("2026-1"))
    }

    @Test
    fun `轮次与账号学期不一致时拒绝创建提交`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSubmission.begin(
                scope,
                SelectionFixtures.OPEN_ROUND.copy(termId = SelectionFixtures.OTHER_TERM),
                SelectionFixtures.request()
            )
        }
    }

    @Test
    fun `幂等键格式非法时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKey("short") }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKey("sel-" + "x".repeat(63) + "!") }
    }

    @Test
    fun `幂等键推导只依赖作用域与课程身份`() {
        val snapshot = confirmed().snapshot!!
        assertEquals(
            "同一快照必须得到同一幂等键",
            SelectionIdempotencyKey.derive(snapshot),
            SelectionIdempotencyKey.derive(snapshot.copy(courseName = "改名后的课程（合成）"))
        )
        assertFalse(
            "退课与选课必须是两笔不同的写入",
            SelectionIdempotencyKey.derive(snapshot) == SelectionIdempotencyKey.derive(snapshot.copy(action = SelectionAction.DROP))
        )
        assertFalse(
            "不同账号必须得到不同幂等键",
            SelectionIdempotencyKey.derive(snapshot) == SelectionIdempotencyKey.derive(
                snapshot.copy(scope = SelectionFixtures.scope(account = SelectionFixtures.OTHER_ACCOUNT))
            )
        )
    }
}
