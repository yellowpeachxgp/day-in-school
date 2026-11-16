package cn.edu.haut.campus.feature.settings

import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CloudReservationQuote
import cn.edu.haut.campus.core.database.CloudPlanPriceDriftException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5-D3 预约提交草稿状态机回归（纯 JVM，可测状态机 + UI 接线层）：
 * - 任何身份/base/generation/学号/参数/引擎变化 → revision++ 并清空 pending；
 * - 异步结果/渲染弹窗/确认按钮三处复核同一不可变快照；
 * - 取消绝不重发；双击/旋屏至多一个有效业务尝试。
 */
class LibraryBookingSubmitStateMachineTest {

    private val identityA = BookingIdentityContext(
        profileId = "prof_1",
        cloudUserId = "cloud_user_A",
        identityGeneration = 1L,
        normalizedBase = "https://base-a.example.com",
        studentNo = "20240001"
    )
    private val identityB = BookingIdentityContext(
        profileId = "prof_1",
        cloudUserId = "cloud_user_B",
        identityGeneration = 2L,
        normalizedBase = "https://base-a.example.com",
        studentNo = "20240001"
    )
    private val paramsA = BookingDraftParams(
        engine = BookingExecutionMode.CLOUD_HOSTED,
        targetDate = "2026-11-26",
        targetTime = "07:00",
        areaName = "五层中区",
        seatLabel = "05桌 A座",
        repeatDays = 1,
        displayedPriceMicropeach = 10_000L
    )

    private var attemptSeq = 0
    private fun newMachine() = LibraryBookingSubmitStateMachine { "att_test_${++attemptSeq}" }

    private fun beginOnA(machine: LibraryBookingSubmitStateMachine): BookingDraftSnapshot {
        machine.updateContext(identityA, paramsA)
        return machine.beginSubmission()!!
    }

    private fun driftErr(old: Long, current: Long) = CloudPlanPriceDriftException(
        expectedPriceMicropeach = old,
        currentPriceMicropeach = current,
        serverReason = "价格调整"
    )

    @Test
    fun `pending reconfirmation binds immutable draft and confirm issues a new attempt on same snapshot`() {
        val m = newMachine()
        val attempt = beginOnA(m)
        assertEquals(BookingSubmitPhase.SUBMITTING, m.phase)

        val quote = CloudReservationQuote(
            quoteId = "quote_srv_1",
            amountMicropeach = 25_000L,
            priceVersion = "price-library-cloud-2026-11-v1",
            unit = "PER_CONFIRMED_RESERVATION",
            cloudUserId = "cloud_user_A",
            expiresAtEpochMillis = 1_900_000_000_000L
        )
        assertTrue(m.onAttemptPriceDrift(attempt, 10_000L, 25_000L, quote))
        assertEquals(BookingSubmitPhase.PRICE_CHANGED, m.phase)
        val pending = m.visiblePendingReconfirmation()!!
        assertEquals(pending, m.pendingReconfirmation)
        assertEquals(attempt, pending.snapshot)
        assertEquals(25_000L, pending.effectiveProposedPriceMicropeach)

        // 确认：返回绑定同一不可变 draft 的新尝试（新 attemptId + 确认价 + 原报价引用）
        val confirmed = m.confirmPendingReconfirmation()!!
        assertEquals(BookingSubmitPhase.USER_CONFIRMED, m.phase)
        assertNotEquals(attempt.attemptId, confirmed.attemptId)
        assertEquals(attempt.draftRevision, confirmed.draftRevision)
        assertEquals(attempt.targetDate, confirmed.targetDate)
        assertEquals(attempt.areaName, confirmed.areaName)
        assertEquals(attempt.seatLabel, confirmed.seatLabel)
        assertEquals(attempt.cloudUserId, confirmed.cloudUserId)
        assertEquals(attempt.identityGeneration, confirmed.identityGeneration)
        assertEquals(25_000L, confirmed.acceptedPriceMicropeach)
        assertEquals("quote_srv_1", confirmed.quoteId)
        assertEquals(1_900_000_000_000L, confirmed.quoteExpiresAtEpochMillis)

        assertTrue(m.beginConfirmedAttempt(confirmed))
        assertEquals(BookingSubmitPhase.SUBMITTING, m.phase)
        assertTrue(m.onAttemptSettled(confirmed, succeeded = true))
        assertEquals(BookingSubmitPhase.ACCEPTED, m.phase)
    }

    @Test
    fun `account switch clears pending and stale confirm is refused at all three rechecks`() = runBlocking {
        val m = newMachine()
        var identity = identityA
        var params = paramsA
        val submittedSnapshots = mutableListOf<BookingDraftSnapshot>()
        val submitter = LibraryBookingPlanSubmitter(
            machine = m,
            identityProvider = { identity },
            paramsProvider = { params },
            submitPlan = { snap ->
                submittedSnapshots += snap
                if (submittedSnapshots.size == 1) {
                    Result.failure<Unit>(driftErr(10_000L, 25_000L))
                } else {
                    Result.success(Unit)
                }
            }
        )

        // A 收到 409 → 弹窗 pending
        val ev1 = submitter.submitNewAttempt()
        assertTrue(ev1 is BookingSubmitEvent.PriceReconfirmationRequired)
        assertEquals(BookingSubmitPhase.PRICE_CHANGED, m.phase)
        assertNotNull(m.visiblePendingReconfirmation())

        // 切到 B：pending 立即失效（渲染复核不可见、确认按钮复核拒绝）
        identity = identityB
        submitter.refreshContext()
        assertNull(m.pendingReconfirmation)
        assertNull(m.visiblePendingReconfirmation())
        val refused = submitter.confirmPendingReconfirmation()
        assertTrue("切号后旧确认绝不可提交", refused is BookingSubmitEvent.StaleConfirmationRefused)
        assertEquals("旧确认绝不发起第二笔提交", 1, submittedSnapshots.size)

        Unit
    }

    @Test
    fun `late drift result for switched identity never installs pending on the new context`() = runBlocking {
        val m = newMachine()
        var identity = identityA
        var params = paramsA
        val release = CompletableDeferred<Unit>()
        val submitter = LibraryBookingPlanSubmitter(
            machine = m,
            identityProvider = { identity },
            paramsProvider = { params },
            submitPlan = {
                release.await()
                Result.failure<Unit>(driftErr(10_000L, 25_000L))
            }
        )

        // A 提交在途 → 切 B → A 的晚到漂移结果绝不污染 B
        val job = async(start = CoroutineStart.UNDISPATCHED) { submitter.submitNewAttempt() }
        identity = identityB
        submitter.refreshContext()
        release.complete(Unit)
        val ev = job.await()
        assertTrue(ev is BookingSubmitEvent.StaleResultIgnored)
        assertNull("晚到结果绝不在 B 上下文安装 pending", m.pendingReconfirmation)
        assertEquals(BookingSubmitPhase.EDITING, m.phase)

        Unit
    }

    @Test
    fun `base aba and same-account relogin generation and student number switch each invalidate pending`() {
        fun pendingSurvives(swapIdentity: BookingIdentityContext, swapParams: BookingDraftParams): Boolean {
            val m = newMachine()
            val attempt = beginOnA(m)
            m.onAttemptPriceDrift(attempt, 10_000L, 25_000L, null)
            assertNotNull(m.visiblePendingReconfirmation())
            m.updateContext(swapIdentity, swapParams)
            return m.visiblePendingReconfirmation() != null
        }

        // 1) base A→B：真实换址 → pending 作废
        val baseB = identityA.copy(normalizedBase = "https://base-b.example.com", identityGeneration = 2L)
        assertFalse("base 变化必须使 pending 失效", pendingSurvives(baseB, paramsA))
        // base A→B→A：回到原址但世代 +2 → pending 同样作废
        val abaBack = identityA.copy(identityGeneration = 3L)
        assertFalse("base ABA 复原后旧 pending 绝不复活", pendingSurvives(abaBack, paramsA))

        // 2) 同账号重新登录 generation+1 → pending 作废
        val relogin = identityA.copy(identityGeneration = 9L)
        assertFalse("重新登录世代推进必须使 pending 失效", pendingSurvives(relogin, paramsA))

        // 3) 学号切换 → pending 作废
        val otherStudent = identityA.copy(studentNo = "20240002")
        assertFalse("学号切换必须使 pending 失效", pendingSurvives(otherStudent, paramsA))
    }

    @Test
    fun `editing date seat repeat or engine invalidates pending reconfirmation`() {
        val edits = listOf(
            "改日期" to paramsA.copy(targetDate = "2026-11-27"),
            "改时间" to paramsA.copy(targetTime = "08:00"),
            "改座位" to paramsA.copy(seatLabel = "08桌 B座"),
            "改次数" to paramsA.copy(repeatDays = 5),
            "改模式" to paramsA.copy(engine = BookingExecutionMode.LOCAL_DEVICE)
        )
        for ((name, edited) in edits) {
            val m = newMachine()
            val attempt = beginOnA(m)
            m.onAttemptPriceDrift(attempt, 10_000L, 25_000L, null)
            assertNotNull(m.visiblePendingReconfirmation())
            m.updateContext(identityA, edited)
            assertNull("$name 必须使待确认快照失效", m.visiblePendingReconfirmation())
            assertNull(m.confirmPendingReconfirmation())
        }
    }

    @Test
    fun `double click and rotation admit at most one in-flight business attempt`() = runBlocking {
        val m = newMachine()
        val submitCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val submitter = LibraryBookingPlanSubmitter(
            machine = m,
            identityProvider = { identityA },
            paramsProvider = { paramsA },
            submitPlan = {
                submitCalls.incrementAndGet()
                release.await()
                Result.success(Unit)
            }
        )

        // 双击：第一笔在途，第二笔直接 Busy
        val job1 = async(start = CoroutineStart.UNDISPATCHED) { submitter.submitNewAttempt() }
        val ev2 = submitter.submitNewAttempt()
        assertTrue(ev2 is BookingSubmitEvent.Busy)
        release.complete(Unit)
        val ev1 = job1.await()
        assertTrue(ev1 is BookingSubmitEvent.Accepted)
        assertEquals("至多一个有效业务尝试", 1, submitCalls.get())

        // 确认重发期间的新提交同样被拒（旋屏/连点只能有一笔在途）
        val m2 = newMachine()
        var submitCalls2 = 0
        val release2 = CompletableDeferred<Unit>()
        val submitter2 = LibraryBookingPlanSubmitter(
            machine = m2,
            identityProvider = { identityA },
            paramsProvider = { paramsA },
            submitPlan = {
                submitCalls2++
                if (submitCalls2 == 1) {
                    Result.failure<Unit>(driftErr(10_000L, 25_000L))
                } else {
                    release2.await()
                    Result.success(Unit)
                }
            }
        )
        assertTrue(submitter2.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        val confirmJob = async(start = CoroutineStart.UNDISPATCHED) {
            submitter2.confirmPendingReconfirmation()
        }
        val dupSubmit = submitter2.submitNewAttempt()
        assertTrue("确认在途期间新提交必须 Busy", dupSubmit is BookingSubmitEvent.Busy)
        release2.complete(Unit)
        assertTrue(confirmJob.await() is BookingSubmitEvent.Accepted)
        assertEquals("确认重发恰一笔新尝试", 2, submitCalls2)

        Unit
    }

    @Test
    fun `cancel never resubmits and confirmed attempt carries fresh business key`() = runBlocking {
        val m = newMachine()
        val submittedSnapshots = mutableListOf<BookingDraftSnapshot>()
        var failNextAttempt = true
        val submitter = LibraryBookingPlanSubmitter(
            machine = m,
            identityProvider = { identityA },
            paramsProvider = { paramsA },
            submitPlan = { snap ->
                submittedSnapshots += snap
                if (failNextAttempt) {
                    failNextAttempt = false
                    Result.failure<Unit>(driftErr(10_000L, 25_000L))
                } else {
                    Result.success(Unit)
                }
            }
        )

        assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        assertEquals(1, submittedSnapshots.size)

        // 取消：绝不重发
        submitter.cancelPendingReconfirmation()
        assertNull(m.pendingReconfirmation)
        assertEquals(BookingSubmitPhase.EDITING, m.phase)
        assertEquals("取消后绝不自动重发", 1, submittedSnapshots.size)

        // 再次漂移 → 确认 → 新尝试绑定同一 draft 但 attemptId 全新（全新业务键）
        failNextAttempt = true
        assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        val accepted = submitter.confirmPendingReconfirmation()
        assertTrue(accepted is BookingSubmitEvent.Accepted)
        assertEquals(3, submittedSnapshots.size)
        val confirmedAttempt = submittedSnapshots.last()
        assertNotEquals(submittedSnapshots[1].attemptId, confirmedAttempt.attemptId)
        assertEquals(25_000L, confirmedAttempt.acceptedPriceMicropeach)
        assertEquals(submittedSnapshots[1].targetDate, confirmedAttempt.targetDate)
        assertEquals(submittedSnapshots[1].cloudUserId, confirmedAttempt.cloudUserId)

        Unit
    }

    @Test
    fun `submit payload carries freshly read params after user edits date time and area`() = runBlocking {
        // R6-2 接线契约：paramsProvider 每次复核现场读取——模拟屏幕 lambda 直读底层 state；
        // 冻结首次组合值的 lambda 会令载荷永远携带旧日期/时间/区域。
        val m = newMachine()
        var params = paramsA
        val submittedSnapshots = mutableListOf<BookingDraftSnapshot>()
        val submitter = LibraryBookingPlanSubmitter(
            machine = m,
            identityProvider = { identityA },
            paramsProvider = { params },
            submitPlan = { snap ->
                submittedSnapshots += snap
                Result.success(Unit)
            }
        )

        // 首次提交：载荷携带初始参数
        val ev1 = submitter.submitNewAttempt()
        assertTrue(ev1 is BookingSubmitEvent.Accepted)
        submittedSnapshots.single().let { snap ->
            assertEquals("2026-11-26", snap.targetDate)
            assertEquals("07:00", snap.targetTime)
            assertEquals("五层中区", snap.areaName)
            assertEquals("05桌 A座", snap.seatLabel)
        }

        // 用户改日期/时间/区域/座位后再提交：新载荷必须携带新值而非首次捕获的旧值
        params = paramsA.copy(
            targetDate = "2026-11-27",
            targetTime = "08:30",
            areaName = "三层南区",
            seatLabel = "12桌 C座"
        )
        val ev2 = submitter.submitNewAttempt()
        assertTrue(ev2 is BookingSubmitEvent.Accepted)
        assertEquals(2, submittedSnapshots.size)
        submittedSnapshots.last().let { snap ->
            assertEquals("2026-11-27", snap.targetDate)
            assertEquals("08:30", snap.targetTime)
            assertEquals("三层南区", snap.areaName)
            assertEquals("12桌 C座", snap.seatLabel)
        }

        Unit
    }
}