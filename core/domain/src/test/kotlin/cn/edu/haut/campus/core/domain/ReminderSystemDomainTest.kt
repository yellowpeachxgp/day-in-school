package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.SnoozeChoice
import cn.edu.haut.campus.core.model.SnoozeRejectReason
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

class ReminderSystemDomainTest {

    private val sampleDate = LocalDate.of(2026, 11, 4) // 周三
    private val classStart = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(10, 10))
    private val classEnd = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(11, 50))
    private val eventKey = "CLASS:2026-2027-1:${sampleDate}_r1"

    private fun buildSampleEvent(
        startAt: java.time.Instant = classStart,
        location: String = "32号楼32204",
        isCancelled: Boolean = false
    ): ReminderEvent {
        val rev = ReminderRevision.computeEventRevision(
            eventKey = eventKey,
            type = ReminderEventType.CLASS,
            startAt = startAt,
            endAt = classEnd,
            locationText = location,
            buildingId = "b_2",
            isCancelled = isCancelled
        )
        return ReminderEvent(
            eventKey = eventKey,
            courseId = "c_ds",
            type = ReminderEventType.CLASS,
            displayTitle = "数据结构",
            startAt = startAt,
            endAt = classEnd,
            locationText = location,
            buildingId = "b_2",
            revision = rev,
            isCancelled = isCancelled
        )
    }

    private fun buildFiredTask(
        event: ReminderEvent,
        snoozeCount: Int = 0,
        maxSnoozes: Int = 2,
        status: ReminderTaskStatus = ReminderTaskStatus.FIRED,
        generation: Int = 0
    ): ReminderTaskDomain {
        val global = ReminderGlobalConfig(enabled = true)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, global)
        val firstTrigger = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 50)).toEpochMilli()
        return ReminderTaskDomain(
            id = "task-ds-01",
            taskKey = "${event.eventKey}|CLASS_BEFORE",
            eventKey = event.eventKey,
            eventType = ReminderEventType.CLASS,
            reminderKind = ReminderKind.CLASS_BEFORE,
            eventRevision = event.revision,
            policyRevision = policy.revision,
            eventStartAtEpochMs = event.startAt.toEpochMilli(),
            originalScheduledAtEpochMs = firstTrigger,
            scheduledAtEpochMs = firstTrigger,
            status = status,
            schedulerState = ReminderSchedulerState.NONE,
            generation = generation,
            snoozeCount = snoozeCount,
            maxSnoozes = maxSnoozes,
            scheduleMode = ReminderAccuracyMode.EXACT,
            createdAtEpochMs = firstTrigger - 3600_000L,
            updatedAtEpochMs = firstTrigger
        )
    }

    @Test
    fun `UT-01 09_50 snooze 5 minutes for 10_10 class is accepted at 09_55`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 50))
        val event = buildSampleEvent()
        val task = buildFiredTask(event)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.Plus5Minutes, now, event.startAt)
        assertEquals(ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 55)), candidate)
        assertNull(ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now))
    }

    @Test
    fun `UT-02 09_50 snooze 10 minutes for 10_10 class is accepted at 10_00`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 50))
        val event = buildSampleEvent()
        val task = buildFiredTask(event)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.Plus10Minutes, now, event.startAt)
        assertEquals(ReminderRevision.schoolInstant(sampleDate, LocalTime.of(10, 0)), candidate)
        assertNull(ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now))
    }

    @Test
    fun `UT-03 09_50 snooze before class 5 minutes for 10_10 class is accepted at 10_05`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 50))
        val event = buildSampleEvent()
        val task = buildFiredTask(event)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.BeforeClass5Minutes, now, event.startAt)
        assertEquals(ReminderRevision.schoolInstant(sampleDate, LocalTime.of(10, 5)), candidate)
        assertNull(ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now))
    }

    @Test
    fun `UT-04 10_02 snooze 10 minutes for 10_10 class is rejected as TOO_LATE`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(10, 2))
        val event = buildSampleEvent()
        val task = buildFiredTask(event)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.Plus10Minutes, now, event.startAt)
        val reason = ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now)
        assertEquals(SnoozeRejectReason.TOO_LATE, reason)

        // 但上课前 5 分钟 (10:05) 在 10:02 时仍然可用
        val before5Candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.BeforeClass5Minutes, now, event.startAt)
        assertNull(ReminderPolicyEngine.validateSnooze(task, event, policy, before5Candidate, now))
    }

    @Test
    fun `UT-05 10_06 snooze before class 5 minutes is rejected as TOO_SOON`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(10, 6))
        val event = buildSampleEvent()
        val task = buildFiredTask(event)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.BeforeClass5Minutes, now, event.startAt)
        val reason = ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now)
        assertEquals(SnoozeRejectReason.TOO_SOON, reason)
    }

    @Test
    fun `UT-06 snooze already at maxSnoozes 2 is rejected as MAX_REACHED`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 55))
        val event = buildSampleEvent()
        val task = buildFiredTask(event, snoozeCount = 2, maxSnoozes = 2)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, ReminderGlobalConfig(enabled = true))

        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.Plus5Minutes, now, event.startAt)
        assertEquals(SnoozeRejectReason.MAX_REACHED, ReminderPolicyEngine.validateSnooze(task, event, policy, candidate, now))
    }

    @Test
    fun `UT-07 and UT-08 cancelled event or modified classroom rejects old snooze`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 50))
        val originalEvent = buildSampleEvent(location = "32号楼32204")
        val task = buildFiredTask(originalEvent)
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(originalEvent, ReminderGlobalConfig(enabled = true))
        val candidate = ReminderPolicyEngine.resolveRequestedTime(SnoozeChoice.Plus5Minutes, now, originalEvent.startAt)

        // UT-07: 课程被停课取消
        val cancelledEvent = buildSampleEvent(isCancelled = true)
        assertEquals(
            SnoozeRejectReason.EVENT_CANCELLED,
            ReminderPolicyEngine.validateSnooze(task, cancelledEvent, policy, candidate, now)
        )

        // UT-08: 教室已改为 4号教学楼4102，旧通知 revision 不匹配
        val changedRoomEvent = buildSampleEvent(location = "4号教学楼4102")
        assertEquals(
            SnoozeRejectReason.EVENT_CHANGED,
            ReminderPolicyEngine.validateSnooze(task, changedRoomEvent, policy, candidate, now)
        )
    }

    @Test
    fun `UT-09 and UT-10 reconcile is idempotent when unchanged and increments generation when rescheduled`() {
        val now = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(8, 0))
        val event = buildSampleEvent()
        val global = ReminderGlobalConfig(enabled = true)
        val desired = ReminderPlanner.planAllEvents(listOf(event), global, emptyMap(), now)
        assertEquals(1, desired.size)

        // 首次生成任务
        val diff1 = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(event),
            desiredPlans = desired,
            persistedTasks = emptyList(),
            remindersEnabled = true,
            now = now,
            idGenerator = { "fixed-id-1" }
        )
        assertEquals(1, diff1.tasksToUpsert.size)
        val persisted1 = diff1.tasksToUpsert.first().copy(schedulerState = ReminderSchedulerState.SCHEDULED)

        // UT-10: 完全相同的教务同步再跑一遍，不得产生更新或重置 generation
        val diff2 = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(event),
            desiredPlans = desired,
            persistedTasks = listOf(persisted1),
            remindersEnabled = true,
            now = now
        )
        assertTrue(diff2.tasksToUpsert.isEmpty())
        assertTrue(diff2.alarmsToCancel.isEmpty())
        assertTrue(diff2.tasksToSchedule.isEmpty())

        // 若用户已在 09:50 点击了 Snooze，状态为 SNOOZED(g1, 09:55)，无变化的同步同样不得覆盖用户的 SNOOZED 状态
        val snoozedTask = persisted1.copy(
            status = ReminderTaskStatus.SNOOZED,
            generation = 1,
            snoozeCount = 1,
            scheduledAtEpochMs = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 55)).toEpochMilli()
        )
        val nowAt951 = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(9, 51))
        val desiredAt951 = ReminderPlanner.planAllEvents(listOf(event), global, emptyMap(), nowAt951)
        val diffWhileSnoozed = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(event),
            desiredPlans = desiredAt951,
            persistedTasks = listOf(snoozedTask),
            remindersEnabled = true,
            now = nowAt951
        )
        assertTrue(diffWhileSnoozed.tasksToUpsert.isEmpty())
        assertTrue(diffWhileSnoozed.alarmsToCancel.isEmpty())

        // UT-09: 课程推迟两小时到 12:10，旧任务必须被撤销旧代 Alarm 并递增 generation 重排
        val delayedStart = ReminderRevision.schoolInstant(sampleDate, LocalTime.of(12, 10))
        val delayedEvent = buildSampleEvent(startAt = delayedStart)
        val desiredDelayed = ReminderPlanner.planAllEvents(listOf(delayedEvent), global, emptyMap(), nowAt951)
        val diffDelayed = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(delayedEvent),
            desiredPlans = desiredDelayed,
            persistedTasks = listOf(snoozedTask),
            remindersEnabled = true,
            now = nowAt951
        )
        assertEquals(listOf("fixed-id-1" to 1), diffDelayed.alarmsToCancel)
        assertEquals(1, diffDelayed.tasksToUpsert.size)
        val updatedTask = diffDelayed.tasksToUpsert.first()
        assertEquals(2, updatedTask.generation)
        assertEquals(0, updatedTask.snoozeCount)
        assertEquals(ReminderTaskStatus.PENDING, updatedTask.status)
    }

    @Test
    fun `UT-12 and UT-13 holiday and makeup calendar overrides are respected by ReminderPlanner`() {
        val term = Term(
            id = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = LocalDate.of(2026, 9, 7), // 周一
            current = true
        )
        val course = Course(id = "c1", name = "操作系统", colorArgb = 0xFF2563EB)
        val mondayRule = CourseScheduleRule(
            id = "r_mon",
            courseId = "c1",
            termId = term.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "4号楼4102"
        )
        val periods = listOf(
            PeriodTime("p1", term.timeTableId, 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
            PeriodTime("p2", term.timeTableId, 2, LocalTime.of(8, 55), LocalTime.of(9, 40))
        )

        val mondayDate = LocalDate.of(2026, 10, 5) // 周一放假
        val saturdayDate = LocalDate.of(2026, 10, 10) // 周六补周一的课
        val overrides = listOf(
            CalendarOverride(
                id = "cal_holiday",
                termId = term.id,
                date = mondayDate,
                overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
                title = "国庆放假"
            ),
            CalendarOverride(
                id = "cal_makeup",
                termId = term.id,
                date = saturdayDate,
                overrideType = CalendarOverrideType.MAKEUP_FOLLOW_DATE,
                followDate = mondayDate,
                title = "国庆调休补周一课"
            )
        )

        val now = ReminderRevision.schoolInstant(mondayDate, LocalTime.of(7, 0))
        val windowEvents = ReminderPlanner.buildWindowEvents(
            now = now,
            horizonDays = 6L,
            term = term,
            courses = listOf(course),
            rules = listOf(mondayRule),
            periodTimes = periods,
            calendarOverrides = overrides,
            occurrenceOverrides = emptyList(),
            buildings = emptyList()
        )

        // UT-13: 周一放假不产生课程事件；UT-12: 周六调休补课按周六实际日期产生提醒事件
        assertEquals(1, windowEvents.size)
        val makeupEvent = windowEvents.first()
        assertEquals(
            ReminderRevision.schoolInstant(saturdayDate, LocalTime.of(8, 0)),
            makeupEvent.startAt
        )
    }

    @Test
    fun `UT-15 schoolInstant is invariant to device system timezone changes`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val nyInstant = ReminderRevision.schoolInstant(
                LocalDate.of(2026, 11, 4),
                LocalTime.of(10, 10)
            )
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val shanghaiInstant = ReminderRevision.schoolInstant(
                LocalDate.of(2026, 11, 4),
                LocalTime.of(10, 10)
            )
            assertEquals(shanghaiInstant, nyInstant)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `UT-17 and UT-18 exam with TBD time produces no alarm while valid exam produces two distinct taskKeys`() {
        val tbdExam = ExamScheduleItem(
            id = "ex_tbd",
            termId = "2026-2027-1",
            courseCode = "CS101",
            courseName = "随堂测试课",
            examBatchName = "期中随堂考",
            examStageLabel = "期中/随堂考",
            examFormat = "笔试",
            rawTimeText = "时间待定",
            examDate = null,
            startTime = null,
            rawRoom = "待定"
        )
        assertNull(ReminderPlanner.projectExamToEvent(tbdExam))

        val validExam = ExamScheduleItem(
            id = "ex_math",
            termId = "2026-2027-1",
            courseCode = "MA101",
            courseName = "高等数学",
            examBatchName = "2026-2027-1期末考试",
            examStageLabel = "期末集中考",
            examFormat = "闭卷",
            rawTimeText = "2026-11-06(09:00-11:00)",
            examDate = LocalDate.of(2026, 11, 6),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            rawRoom = "7号教学楼301",
            seatNumber = "18"
        )
        val examEvent = ReminderPlanner.projectExamToEvent(validExam)
        assertNotNull(examEvent)

        val now = ReminderRevision.schoolInstant(LocalDate.of(2026, 11, 4), LocalTime.of(8, 0))
        val plans = ReminderPlanner.planAllEvents(
            events = listOf(examEvent!!),
            globalConfig = ReminderGlobalConfig(
                enabled = true,
                examDayBeforeEnabled = true,
                examHourBeforeEnabled = true
            ),
            overridesByScope = emptyMap(),
            now = now
        )
        assertEquals(2, plans.size)
        assertFalse(plans[0].taskKey == plans[1].taskKey)
        assertTrue(plans.any { it.reminderKind == ReminderKind.EXAM_DAY_BEFORE })
        assertTrue(plans.any { it.reminderKind == ReminderKind.EXAM_HOUR_BEFORE })
    }

    @Test
    fun `UT-19 restoring single-day canceled occurrence reactivates CANCELLED reminder task to PENDING`() {
        val classDate = LocalDate.of(2026, 9, 14)
        val occ = ClassOccurrence(
            occurrenceId = "${classDate}_r1",
            courseId = "c_math",
            ruleId = "r1",
            courseName = "高等数学",
            colorArgb = 0xFF053968,
            date = classDate,
            teachingWeek = 3,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = LocalTime.of(8, 30),
            endTime = LocalTime.of(10, 5),
            rawRoom = "莲4号教学楼101"
        )
        val event = ReminderPlanner.projectClassOccurrenceToEvent("2026-2027-1", occ)
        val config = ReminderGlobalConfig(enabled = true, classLeadMinutes = 15)
        val now = ReminderRevision.schoolInstant(classDate, LocalTime.of(7, 30))

        // Step 1: initial schedule creates PENDING task
        val initialPlans = ReminderPlanner.planAllEvents(listOf(event), config, emptyMap(), now)
        val initialDiff = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(event),
            desiredPlans = initialPlans,
            persistedTasks = emptyList(),
            remindersEnabled = true,
            now = now
        )
        assertEquals(1, initialDiff.tasksToSchedule.size)
        val scheduledTask = initialDiff.tasksToUpsert.single().copy(
            schedulerState = ReminderSchedulerState.SCHEDULED
        )

        // Step 2: occurrence canceled -> desiredPlans empty -> persisted task transitions to CANCELLED
        val cancelDiff = ReminderPlanner.computeReconcileDiff(
            currentEvents = emptyList(),
            desiredPlans = emptyList(),
            persistedTasks = listOf(scheduledTask),
            remindersEnabled = true,
            now = now.plusSeconds(60)
        )
        val cancelledTask = cancelDiff.tasksToUpsert.single()
        assertEquals(ReminderTaskStatus.CANCELLED, cancelledTask.status)

        // Step 3: occurrence restored -> same eventRevision & policyRevision -> MUST reactivate to PENDING + NEEDS_SCHEDULE!
        val restorePlans = ReminderPlanner.planAllEvents(listOf(event), config, emptyMap(), now.plusSeconds(120))
        val restoreDiff = ReminderPlanner.computeReconcileDiff(
            currentEvents = listOf(event),
            desiredPlans = restorePlans,
            persistedTasks = listOf(cancelledTask),
            remindersEnabled = true,
            now = now.plusSeconds(120)
        )
        assertEquals(1, restoreDiff.tasksToSchedule.size)
        val reactivated = restoreDiff.tasksToUpsert.single()
        assertEquals(ReminderTaskStatus.PENDING, reactivated.status)
        assertEquals(cancelledTask.generation + 1, reactivated.generation)
    }
}
