package cn.edu.haut.campus.core.reminder

import android.app.AlarmManager
import android.content.Context
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LiveCourseTickerRobolectricTest {
    private val now: Instant = Instant.parse("2026-11-27T02:20:00Z")
    private val event = ReminderEvent(
        eventKey = "CLASS:term:occ-robolectric",
        courseId = "course-robolectric",
        type = ReminderEventType.CLASS,
        displayTitle = "软件工程",
        startAt = now.minus(Duration.ofMinutes(10)),
        endAt = now.plus(Duration.ofMinutes(70)),
        locationText = "1号教学楼1104",
        buildingId = "b_t1",
        revision = "revision-robolectric",
        isCancelled = false
    )

    @Test
    fun `start schedules independent inexact window tick with profile generation and revision binding`() {
        val context = RuntimeEnvironment.getApplication()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        shadowAlarmManager.cancelAll()
        val accountScope = AccountBackgroundScope(profileId = "profile-live", epoch = 7L)
        val ticker = LiveCourseTicker(
            AndroidAlarmScheduler(context, accountScope) { true }
        )

        val outcome = ticker.scheduleNext(event, now, requestedFrequencyMinutes = 5)

        assertTrue(outcome is ScheduleOutcome.Scheduled)
        val scheduled = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)
        assertEquals(now.plus(Duration.ofMinutes(5)).toEpochMilli(), scheduled.triggerAtTime)
        assertEquals(LiveCourseTicker.windowLengthMsFor(5), scheduled.windowLengthMs)
        val savedIntent = shadowOf(scheduled.operation).savedIntent
        assertNotNull(savedIntent)
        assertEquals(LiveCourseTicker.TASK_ID, savedIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_TASK_ID))
        assertEquals(LiveCourseTicker.GENERATION, savedIntent.getIntExtra(AndroidAlarmScheduler.EXTRA_GENERATION, -1))
        assertEquals(event.eventKey, savedIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_KEY))
        assertEquals(event.revision, savedIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION))
        assertEquals("live-progress-frequency-5", savedIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_POLICY_REVISION))
        assertEquals("profile-live", savedIntent.getStringExtra(AccountBackgroundScope.EXTRA_PROFILE_ID))
        assertEquals(7L, savedIntent.getLongExtra(AccountBackgroundScope.EXTRA_EPOCH, -1L))

        ticker.cancel()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }
}
