package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c6 booking 执行引擎搬迁特征冻结。
 * 只使用内存 Room/DataStore 与合成账号，不访问真实学校账号或网络；
 * 冻结创建后 T−30/T−5/T 毫秒关系、手动提醒三阶段终态、排队取消语义、
 * 崩溃恢复 RESULT_UNKNOWN，以及 facade 预约 API 形状。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c6BookingEngineCharacterizationTest {
    private lateinit var fixture: RepairTestFixture
    private val repo get() = fixture.repository
    private val bookingDao get() = fixture.database.cloudAndParcelDao()

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_w005c6_booking_" + System.nanoTime()
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun planEntity(
        id: String,
        status: String,
        fireStatus: String,
        stateVersion: Int = 7,
        remainingDays: Int = 1
    ) = LibraryBookingPlanEntity(
        id = id,
        schoolId = "haut",
        executionMode = "LOCAL_DEVICE",
        targetLocalDate = "2099-01-04",
        targetLocalTime = "08:30:00",
        timezoneId = "Asia/Shanghai",
        precheckAtEpochMillis = 1_000L,
        finalCheckAtEpochMillis = 2_000L,
        fireAtEpochMillis = 3_000L,
        precheckStatus = "PRECHECK_OK",
        finalCheckStatus = "FINAL_CHECK_OK",
        fireStatus = fireStatus,
        preferredAreaName = "五层中区",
        preferredSeatLabel = "05桌 A座",
        allowAlternativeSeat = true,
        cloudJobId = null,
        externalBookingId = null,
        evidenceSha256 = null,
        priceMicropeach = 0L,
        paymentState = "NOT_REQUIRED",
        status = status,
        stateVersion = stateVersion,
        generation = 1,
        lastDiagnosticMessage = null,
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        repeatDays = 1,
        remainingDays = remainingDays
    )

    @Test
    fun w005c6_booking_facade_api_shape_snapshot() {
        val declaredNames = CampusRepository::class.java.declaredMethods.map { it.name }.toSet()
        fun hasSourceApiName(sourceName: String): Boolean =
            declaredNames.any { it == sourceName || it.startsWith(sourceName + "-") }

        val missingMethods = listOf(
            "getAllLibraryBookingPlansForAuditSnapshot",
            "snapshotBookingSubmissionIdentity",
            "createDualEngineBookingPlan",
            "loadLibraryPlanningRegions",
            "loadLibraryPlanningSeats",
            "fetchLibraryStudyRegions",
            "fetchLibraryRegionSeats",
            "advanceBookingPlanStage",
            "executeCloudBookingPlanNow",
            "stopInFlightOrQueuedDelegation",
            "cancelDualEngineBookingPlan",
            "cancelActiveLibrarySeatBooking",
            "rescheduleActiveLocalBookingPlans"
        ).filterNot(::hasSourceApiName)
        assertEquals("booking 公开实例方法不得减少", emptyList<String>(), missingMethods)

        val missingAccessors = listOf(
            "getAllLibraryBookingPlansForAuditFlow",
            "getUnattributedLegacyBookingPlansFlow",
            "getLibraryBookingPlansFlow"
        ).filterNot { it in declaredNames }
        assertEquals("booking 公开 Flow 访问器不得减少", emptyList<String>(), missingAccessors)

        val identityCtor = CampusRepository.BookingSubmissionIdentity::class.java.constructors.single()
        assertEquals(5, identityCtor.parameterCount)
    }

    @Test
    fun w005c6_manual_reminder_create_triple_schedule_and_fire_snapshot() = runBlocking {
        val targetDate = LocalDate.of(2099, 1, 2)
        val created = repo.createDualEngineBookingPlan(
            executionMode = BookingExecutionMode.MANUAL_REMINDER,
            targetDate = targetDate,
            targetTimeText = "08:30",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "05桌 A座",
            nowEpochMillis = 1_000L
        ).getOrThrow()

        val expectedFire = ZonedDateTime.of(targetDate, LocalTime.of(8, 30), ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        assertEquals("08:30:00", created.targetLocalTime)
        assertEquals(expectedFire, created.fireAtEpochMillis)
        assertEquals("T−30 初检逐毫秒保持", expectedFire - 1_800_000L, created.precheckAtEpochMillis)
        assertEquals("T−5 复检逐毫秒保持", expectedFire - 300_000L, created.finalCheckAtEpochMillis)
        assertEquals("ARMED", created.status)
        assertEquals("ARMED", created.precheckStatus)
        assertEquals("ARMED", created.finalCheckStatus)
        assertEquals("ARMED", created.fireStatus)
        assertEquals(0L, created.priceMicropeach)
        assertEquals("NOT_REQUIRED", created.paymentState)

        val precheck = repo.advanceBookingPlanStage(
            planId = created.id,
            stage = TripleScheduleStage.PRECHECK,
            nowEpochMillis = 2_000L
        ).getOrThrow()
        assertEquals("PRECHECK_OK", precheck.status)
        assertEquals("已检查抢座时间配置（建议先保存学校账号密码以便自动登录）", precheck.lastDiagnosticMessage)

        val finalCheck = repo.advanceBookingPlanStage(
            planId = created.id,
            stage = TripleScheduleStage.FINAL_CHECK,
            nowEpochMillis = 3_000L
        ).getOrThrow()
        assertEquals("FINAL_CHECK_OK", finalCheck.status)
        assertEquals("倒计时已就绪，等待 08:30:00 准点开抢", finalCheck.lastDiagnosticMessage)

        val fired = repo.advanceBookingPlanStage(
            planId = created.id,
            stage = TripleScheduleStage.FIRE,
            nowEpochMillis = 4_000L
        ).getOrThrow()
        assertEquals("REMINDER_SENT", fired.status)
        assertEquals("REMINDER_SENT", fired.fireStatus)
        assertEquals(
            "已到点发送选座提醒：请点击上方选座面板一键预约 五层中区 · 05桌 A座",
            fired.lastDiagnosticMessage
        )
        assertNull(fired.externalBookingId)
        assertNull(fired.evidenceSha256)
    }

    @Test
    fun w005c6_stop_queued_plan_freezes_cancel_without_school_cancel_claim() = runBlocking {
        val created = repo.createDualEngineBookingPlan(
            executionMode = BookingExecutionMode.MANUAL_REMINDER,
            targetDate = LocalDate.of(2099, 1, 3),
            targetTimeText = "08:30:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "05桌 A座",
            nowEpochMillis = 1_000L
        ).getOrThrow()

        val stopped = repo.stopInFlightOrQueuedDelegation(created.id)
        assertNotNull(stopped)
        val stoppedPlan = stopped!!
        assertEquals("CANCELLED", stoppedPlan.status)
        assertEquals("CANCELLED", stoppedPlan.fireStatus)
        assertEquals(0, stoppedPlan.remainingDays)
        assertEquals(created.generation + 1, stoppedPlan.generation)
        assertEquals(created.stateVersion + 1, stoppedPlan.stateVersion)
        assertEquals("已停止委托并取消排队中的预约计划", stoppedPlan.lastDiagnosticMessage)

        val persisted = bookingDao.getLibraryBookingPlanById(created.id)
        assertEquals(stoppedPlan.status, persisted?.status)
        assertEquals(stoppedPlan.stateVersion, persisted?.stateVersion)

        repo.cancelDualEngineBookingPlan(created.id)
        val afterIdempotentCancel = bookingDao.getLibraryBookingPlanById(created.id)!!
        assertEquals("CANCELLED", afterIdempotentCancel.status)
        assertEquals(stoppedPlan.stateVersion, afterIdempotentCancel.stateVersion)
    }

    @Test
    fun w005c6_crash_recovery_marks_interrupted_dispatch_result_unknown() = runBlocking {
        val interrupted = planEntity(
            id = "plan_w005c6_interrupted",
            status = "EXECUTING",
            fireStatus = "DISPATCH_INTENT:dead_attempt",
            stateVersion = 7,
            remainingDays = 1
        )
        bookingDao.upsertLibraryBookingPlan(interrupted)

        repo.rescheduleActiveLocalBookingPlans()

        val recovered = bookingDao.getLibraryBookingPlanById(interrupted.id)!!
        assertEquals("RESULT_UNKNOWN", recovered.status)
        assertEquals("RESULT_UNKNOWN", recovered.fireStatus)
        assertEquals(0, recovered.remainingDays)
        assertEquals(8, recovered.stateVersion)
        assertEquals(interrupted.generation, recovered.generation)
        assertEquals(
            "进程中断恢复：此前抢座请求可能已发出，已转入结果待核对状态（禁止重复提交）",
            recovered.lastDiagnosticMessage
        )
        assertTrue(recovered.updatedAtEpochMillis >= interrupted.updatedAtEpochMillis)
    }
}
