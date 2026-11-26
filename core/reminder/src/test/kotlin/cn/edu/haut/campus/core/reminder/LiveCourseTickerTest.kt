package cn.edu.haut.campus.core.reminder

import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LiveCourseTickerTest {
    private val now: Instant = Instant.parse("2026-11-27T02:30:00Z")
    private val event = ReminderEvent(
        eventKey = "CLASS:term:occ-1",
        courseId = "course-1",
        type = ReminderEventType.CLASS,
        displayTitle = "编译原理",
        startAt = now.minus(Duration.ofMinutes(10)),
        endAt = now.plus(Duration.ofMinutes(80)),
        locationText = "1号教学楼1104",
        buildingId = "b_t1",
        revision = "revision-a",
        isCancelled = false
    )

    @Test
    fun `schedule next uses independent synthetic id event revision and default five minute cadence`() {
        val scheduler = RecordingAlarmScheduler()
        val ticker = LiveCourseTicker(scheduler)

        val outcome = ticker.scheduleNext(event, now, requestedFrequencyMinutes = 99)

        assertEquals(ScheduleOutcome.Scheduled(ReminderAccuracyMode.INEXACT), outcome)
        val scheduled = scheduler.windowSchedules.single()
        assertEquals(LiveCourseTicker.TASK_ID, scheduled.first.id)
        assertEquals("__live_course_progress_tick__", scheduled.first.id)
        assertEquals(LiveCourseTicker.GENERATION, scheduled.first.generation)
        assertEquals(event.eventKey, scheduled.first.eventKey)
        assertEquals(event.revision, scheduled.first.eventRevision)
        assertEquals("live-progress-frequency-5", scheduled.first.policyRevision)
        assertEquals(now.plus(Duration.ofMinutes(5)).toEpochMilli(), scheduled.first.scheduledAtEpochMs)
        assertEquals(Duration.ofMinutes(2).plusSeconds(30).toMillis(), scheduled.second)
    }

    @Test
    fun `supported one and ten minute cadences control trigger and window length`() {
        val scheduler = RecordingAlarmScheduler()
        val ticker = LiveCourseTicker(scheduler)

        ticker.scheduleNext(event, now, requestedFrequencyMinutes = 1)
        ticker.scheduleNext(event, now, requestedFrequencyMinutes = 10)

        assertEquals(now.plus(Duration.ofMinutes(1)).toEpochMilli(), scheduler.windowSchedules[0].first.scheduledAtEpochMs)
        assertEquals(Duration.ofMinutes(1).toMillis(), scheduler.windowSchedules[0].second)
        assertEquals("live-progress-frequency-1", scheduler.windowSchedules[0].first.policyRevision)
        assertEquals(now.plus(Duration.ofMinutes(10)).toEpochMilli(), scheduler.windowSchedules[1].first.scheduledAtEpochMs)
        assertEquals(Duration.ofMinutes(5).toMillis(), scheduler.windowSchedules[1].second)
        assertEquals("live-progress-frequency-10", scheduler.windowSchedules[1].first.policyRevision)
    }

    @Test
    fun `cancel uses same synthetic id and generation as scheduled tick`() {
        val scheduler = RecordingAlarmScheduler()

        LiveCourseTicker(scheduler).cancel()

        assertEquals(listOf(LiveCourseTicker.TASK_ID to LiveCourseTicker.GENERATION), scheduler.cancels)
    }

    @Test
    fun `scheduling failure is propagated so caller can hide progress`() {
        val failure = ScheduleOutcome.Failed("TEST_FAILURE")
        val scheduler = RecordingAlarmScheduler(windowOutcome = failure)

        val outcome = LiveCourseTicker(scheduler).scheduleNext(event, now, requestedFrequencyMinutes = 5)

        assertEquals(failure, outcome)
    }

    private class RecordingAlarmScheduler(
        private val windowOutcome: ScheduleOutcome = ScheduleOutcome.Scheduled(
            mode = ReminderAccuracyMode.INEXACT,
            schedulerState = ReminderSchedulerState.SCHEDULED
        )
    ) : AlarmScheduler {
        val windowSchedules = mutableListOf<Pair<ReminderTaskDomain, Long>>()
        val cancels = mutableListOf<Pair<String, Int>>()

        override fun schedule(task: ReminderTaskDomain, preferExact: Boolean): ScheduleOutcome {
            error("普通调度不应被 LiveCourseTicker 直接使用")
        }

        override fun scheduleInexactWindow(task: ReminderTaskDomain, windowLengthMs: Long): ScheduleOutcome {
            windowSchedules += task to windowLengthMs
            return windowOutcome
        }

        override fun cancel(taskId: String, generation: Int) {
            cancels += taskId to generation
        }
    }
}
