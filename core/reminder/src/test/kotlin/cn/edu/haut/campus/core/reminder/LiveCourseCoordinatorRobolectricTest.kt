package cn.edu.haut.campus.core.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LiveCourseCoordinatorRobolectricTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `in class reconcile schedules progress tick and end boundary`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )

        fixture.coordinator.reconcileNow(
            reason = "TEST_LIVE_START",
            forceRescheduleAlarms = true,
            now = fixture.now
        )

        val tick = requireLiveAlarm(ShadowLiveTask.TICK)
        val tickIntent = savedIntent(tick)
        assertEquals(fixture.now.plus(Duration.ofMinutes(5)).toEpochMilli(), tick.triggerAtTime)
        assertEquals(fixture.eventKey, tickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_KEY))
        assertEquals(fixture.eventRevision, tickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION))
        assertEquals("live-progress-frequency-5", tickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_POLICY_REVISION))
        assertEquals(fixture.session.profileId, tickIntent.getStringExtra(AccountBackgroundScope.EXTRA_PROFILE_ID))
        assertEquals(fixture.session.generation, tickIntent.getLongExtra(AccountBackgroundScope.EXTRA_EPOCH, -1L))

        val boundary = requireLiveAlarm(ShadowLiveTask.BOUNDARY)
        assertEquals(fixture.endAt.toEpochMilli(), boundary.triggerAtTime)
        assertEquals(fixture.eventRevision, savedIntent(boundary).getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = requireNotNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
        assertEquals(LiveProgressModel.PROGRESS_SCALE, posted.extras.getInt("android.progressMax"))
        assertTrue(posted.extras.getInt("android.progress") > 0)
        assertTrue((posted.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
    }

    @Test
    fun `tick fire republishes notification with advanced in class progress`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_PROGRESS_INITIAL", forceRescheduleAlarms = true, now = fixture.now)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val initial = requireNotNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
        val initialProgress = initial.extras.getInt("android.progress")
        val initialTick = requireLiveAlarm(ShadowLiveTask.TICK)
        val initialTickIntent = savedIntent(initialTick)

        val tickNow = fixture.now.plus(Duration.ofMinutes(5))
        fixture.coordinator.onAlarmFired(
            taskId = LiveCourseTicker.TASK_ID,
            expectedGeneration = LiveCourseTicker.GENERATION,
            now = tickNow,
            expectedEventKey = initialTickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_KEY),
            expectedEventRevision = initialTickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION),
            expectedPolicyRevision = initialTickIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_POLICY_REVISION)
        )

        val updated = requireNotNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
        assertTrue(updated.extras.getInt("android.progress") > initialProgress)
        val nextTick = requireLiveAlarm(ShadowLiveTask.TICK)
        assertEquals(tickNow.plus(Duration.ofMinutes(5)).toEpochMilli(), nextTick.triggerAtTime)
    }

    @Test
    fun `pre class live window start schedules tick and start boundary`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(9, 15)
        )

        fixture.coordinator.reconcileNow(
            reason = "TEST_LIVE_WINDOW_START",
            forceRescheduleAlarms = true,
            now = fixture.now
        )

        val tick = requireLiveAlarm(ShadowLiveTask.TICK)
        assertEquals(fixture.now.plus(Duration.ofMinutes(5)).toEpochMilli(), tick.triggerAtTime)
        val boundary = requireLiveAlarm(ShadowLiveTask.BOUNDARY)
        assertEquals(fixture.startAt.toEpochMilli(), boundary.triggerAtTime)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = requireNotNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
        assertEquals(0, posted.extras.getInt("android.progress"))
    }

    @Test
    fun `tick scheduling failure keeps chronometer notification without progress`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        val scope = AccountBackgroundScope.from(fixture.session)
        val delegate = AndroidAlarmScheduler(context, scope) { fixture.repository.isSessionActive }
        val coordinator = createCoordinator(
            session = fixture.session,
            repository = fixture.repository,
            alarmScheduler = FailingTickAlarmScheduler(delegate)
        )

        coordinator.reconcileNow(
            reason = "TEST_TICK_SCHEDULE_FAILURE",
            forceRescheduleAlarms = true,
            now = fixture.now
        )

        assertNull(findLiveAlarm(ShadowLiveTask.TICK))
        assertNotNull(findLiveAlarm(ShadowLiveTask.BOUNDARY))
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = requireNotNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
        assertTrue(posted.extras.getBoolean("android.showChronometer"))
        // Android framework extras 可能始终携带默认 0 值；关键是未写入有效进度上限/进度。
        assertEquals(0, posted.extras.getInt("android.progressMax"))
        assertEquals(0, posted.extras.getInt("android.progress"))
    }

    @Test
    fun `crossing class end cancels progress tick boundary and notification`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_LIVE_START", forceRescheduleAlarms = true, now = fixture.now)
        assertNotNull(findLiveAlarm(ShadowLiveTask.TICK))

        fixture.coordinator.reconcileNow(
            reason = "TEST_AFTER_CLASS_END",
            forceRescheduleAlarms = true,
            now = fixture.endAt.plusMillis(1)
        )

        assertNull(findLiveAlarm(ShadowLiveTask.TICK))
        assertNull(findLiveAlarm(ShadowLiveTask.BOUNDARY))
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
    }

    @Test
    fun `coordinator deactivation for identity switch cancels live alarms`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_LIVE_START", forceRescheduleAlarms = true, now = fixture.now)
        assertNotNull(findLiveAlarm(ShadowLiveTask.TICK))
        assertNotNull(findLiveAlarm(ShadowLiveTask.BOUNDARY))

        fixture.coordinator.deactivate()

        assertNull(findLiveAlarm(ShadowLiveTask.TICK))
        assertNull(findLiveAlarm(ShadowLiveTask.BOUNDARY))
    }

    @Test
    fun `user dismiss live notification cancels current tick boundary and card`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_BEFORE_DISMISS", forceRescheduleAlarms = true, now = fixture.now)
        assertNotNull(findLiveAlarm(ShadowLiveTask.TICK))

        fixture.coordinator.dismissLiveCourseNotification()

        assertNull(findLiveAlarm(ShadowLiveTask.TICK))
        assertNull(findLiveAlarm(ShadowLiveTask.BOUNDARY))
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(shadowOf(notificationManager).getNotification(LIVE_NOTIFICATION_ID))
    }

    @Test
    fun `event revision change replaces stale progress chain`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_REVISION_BEFORE", forceRescheduleAlarms = true, now = fixture.now)
        val oldRevision = savedIntent(requireLiveAlarm(ShadowLiveTask.TICK))
            .getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION)
        assertEquals(fixture.eventRevision, oldRevision)

        fixture.repository.saveUserCourseEdit(
            fixture.course,
            fixture.rule.copy(rawRoom = "测试楼202")
        )
        fixture.coordinator.reconcileNow("TEST_REVISION_AFTER", forceRescheduleAlarms = true, now = fixture.now)

        val ticks = liveAlarms(ShadowLiveTask.TICK)
        assertEquals(1, ticks.size)
        val newRevision = savedIntent(ticks.single()).getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION)
        assertNotNull(newRevision)
        assertNotEquals(oldRevision, newRevision)
    }

    @Test
    fun `process restart boundary fire rereads state and restores progress chain`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(9, 50)
        )
        fixture.coordinator.reconcileNow("TEST_PRE_CLASS", forceRescheduleAlarms = true, now = fixture.now)
        val boundaryBefore = requireLiveAlarm(ShadowLiveTask.BOUNDARY)
        val boundaryIntent = savedIntent(boundaryBefore)
        assertEquals(fixture.startAt.toEpochMilli(), boundaryBefore.triggerAtTime)

        // 新建协调器模拟进程被杀后的接收路径；系统侧 boundary PendingIntent 不依赖进程内存。
        val restarted = createCoordinator(fixture.session, fixture.repository)
        restarted.onAlarmFired(
            taskId = ReminderCoordinator.LIVE_COURSE_BOUNDARY_TASK_ID,
            expectedGeneration = 1,
            now = fixture.startAt,
            expectedEventKey = boundaryIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_KEY),
            expectedEventRevision = boundaryIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_EVENT_REVISION),
            expectedPolicyRevision = boundaryIntent.getStringExtra(AndroidAlarmScheduler.EXTRA_POLICY_REVISION)
        )

        val restoredTicks = liveAlarms(ShadowLiveTask.TICK)
        assertEquals(1, restoredTicks.size)
        assertEquals(fixture.startAt.plus(Duration.ofMinutes(5)).toEpochMilli(), restoredTicks.single().triggerAtTime)
        val endBoundary = requireLiveAlarm(ShadowLiveTask.BOUNDARY)
        assertEquals(fixture.endAt.toEpochMilli(), endBoundary.triggerAtTime)
    }

    @Test
    fun `frequency change replaces previous tick chain`() = runBlocking {
        val fixture = createFixture(
            startLocal = LocalTime.of(10, 0),
            endLocal = LocalTime.of(11, 30),
            nowLocal = LocalTime.of(10, 20)
        )
        fixture.coordinator.reconcileNow("TEST_FREQUENCY_5", forceRescheduleAlarms = true, now = fixture.now)
        assertEquals(
            fixture.now.plus(Duration.ofMinutes(5)).toEpochMilli(),
            requireLiveAlarm(ShadowLiveTask.TICK).triggerAtTime
        )

        fixture.repository.updateReminderGlobalConfig {
            it.copy(liveProgressRefreshMinutes = 10)
        }
        fixture.coordinator.reconcileNow("TEST_FREQUENCY_10", forceRescheduleAlarms = true, now = fixture.now)

        val ticks = liveAlarms(ShadowLiveTask.TICK)
        assertEquals(1, ticks.size)
        assertEquals(fixture.now.plus(Duration.ofMinutes(10)).toEpochMilli(), ticks.single().triggerAtTime)
        assertEquals(
            "live-progress-frequency-10",
            savedIntent(ticks.single()).getStringExtra(AndroidAlarmScheduler.EXTRA_POLICY_REVISION)
        )
    }

    private suspend fun createFixture(
        startLocal: LocalTime,
        endLocal: LocalTime,
        nowLocal: LocalTime
    ): LiveFixture {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)
        val session = AccountSessionCoordinator.getInstance(context).initialize()
        val repository = session.repository
        repository.updateReminderGlobalConfig {
            it.copy(
                enabled = true,
                classLeadMinutes = 20,
                preciseReminderPreferred = false,
                liveProgressRefreshMinutes = 5
            )
        }

        val startAt = zonedInstant(startLocal)
        val endAt = zonedInstant(endLocal)
        val now = zonedInstant(nowLocal)
        val termId = nextTermId()
        val termStart = TEST_DATE.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        repository.ensureTermExistsAndUpdateCalendar(
            termId = termId,
            semesterStart = termStart,
            totalWeeks = 2,
            setCurrent = true
        )
        val course = Course(
            id = "course_live_" + termId,
            name = "实况进度回归课",
            teacher = "测试教师",
            colorArgb = 0xFF053968L,
            source = CourseSource.MANUAL,
            userModified = true
        )
        val rule = CourseScheduleRule(
            id = "rule_live_" + termId,
            courseId = course.id,
            termId = termId,
            weekday = TEST_DATE.dayOfWeek,
            startPeriod = 1,
            endPeriod = 1,
            weeks = setOf(1),
            rawRoom = "测试楼101",
            customStartTime = startLocal,
            customEndTime = endLocal,
            userModified = true
        )
        repository.saveUserCourseEdit(course, rule)

        val snapshot = repository.getDirectScheduleDataSnapshot()
        val projected = cn.edu.haut.campus.core.domain.ReminderPlanner.buildWindowEvents(
            now = now,
            term = snapshot.term,
            courses = snapshot.courses,
            rules = snapshot.rules,
            periodTimes = snapshot.periodTimes,
            calendarOverrides = snapshot.calendarOverrides,
            occurrenceOverrides = snapshot.occurrenceOverrides,
            buildings = snapshot.buildings,
            exams = emptyList()
        ).single { it.displayTitle == course.name }
        return LiveFixture(
            session = session,
            repository = repository,
            coordinator = createCoordinator(session, repository),
            now = now,
            startAt = startAt,
            endAt = endAt,
            eventKey = projected.eventKey,
            eventRevision = projected.revision,
            course = course,
            rule = rule
        )
    }

    private fun createCoordinator(
        session: ActiveSession,
        repository: CampusRepository,
        alarmScheduler: AlarmScheduler? = null
    ): ReminderCoordinator {
        val scope = AccountBackgroundScope.from(session)
        val scheduler = alarmScheduler ?: AndroidAlarmScheduler(context, scope) { repository.isSessionActive }
        return ReminderCoordinator(context, repository, scheduler, scope)
    }

    private class FailingTickAlarmScheduler(
        private val delegate: AlarmScheduler
    ) : AlarmScheduler {
        override fun schedule(task: cn.edu.haut.campus.core.model.ReminderTaskDomain, preferExact: Boolean): ScheduleOutcome =
            delegate.schedule(task, preferExact)

        override fun scheduleInexactWindow(
            task: cn.edu.haut.campus.core.model.ReminderTaskDomain,
            windowLengthMs: Long
        ): ScheduleOutcome = ScheduleOutcome.Failed("TEST_TICK_SCHEDULE_UNAVAILABLE")

        override fun cancel(taskId: String, generation: Int) {
            delegate.cancel(taskId, generation)
        }
    }

    private fun zonedInstant(time: LocalTime): Instant =
        ZonedDateTime.of(TEST_DATE, time, HAUT_ZONE_ID).toInstant()

    private enum class ShadowLiveTask(val taskId: String) {
        TICK(LiveCourseTicker.TASK_ID),
        BOUNDARY(ReminderCoordinator.LIVE_COURSE_BOUNDARY_TASK_ID)
    }

    private data class LiveFixture(
        val session: ActiveSession,
        val repository: CampusRepository,
        val coordinator: ReminderCoordinator,
        val now: Instant,
        val startAt: Instant,
        val endAt: Instant,
        val eventKey: String,
        val eventRevision: String,
        val course: Course,
        val rule: CourseScheduleRule
    )

    private fun liveAlarms(kind: ShadowLiveTask): List<ShadowAlarmManager.ScheduledAlarm> {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarmManager).scheduledAlarms.filter { alarm ->
            savedIntent(alarm).getStringExtra(AndroidAlarmScheduler.EXTRA_TASK_ID) == kind.taskId
        }
    }

    private fun findLiveAlarm(kind: ShadowLiveTask): ShadowAlarmManager.ScheduledAlarm? =
        liveAlarms(kind).singleOrNull()

    private fun requireLiveAlarm(kind: ShadowLiveTask): ShadowAlarmManager.ScheduledAlarm =
        requireNotNull(findLiveAlarm(kind)) { "missing scheduled alarm for " + kind.taskId }

    private fun savedIntent(alarm: ShadowAlarmManager.ScheduledAlarm) =
        shadowOf(alarm.operation).savedIntent

    companion object {
        private val TEST_DATE: LocalDate = LocalDate.of(2026, 11, 27)
        private const val LIVE_NOTIFICATION_ID = 990102
        private var nextTermYear = 2300

        private fun nextTermId(): String {
            val year = nextTermYear++
            return year.toString() + "-" + (year + 1).toString() + "-1"
        }
    }
}
