package cn.edu.haut.campus.widget.common

import android.content.ComponentName
import android.content.Intent
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DayTimelineSnapshot
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 小组件刷新协同与边界闹钟回归：Intent 印章携带身份代际（防串号）、
 * 启动 Intent 直达 MainActivity 本体（#18）、边界刷新只按已解析时间排闹钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WidgetRefreshCoordinatorTest {

    private val monday = LocalDate.of(2026, 9, 7)

    private fun emptyDaySnapshot(items: List<TimelineOccurrenceState> = emptyList()): DayTimelineSnapshot {
        val now = ZonedDateTime.of(monday, LocalTime.of(12, 0), HAUT_ZONE_ID)
        return DayTimelineSnapshot(
            date = monday,
            now = now,
            term = null,
            teachingWeek = 1,
            termStatus = TermDateStatus.IN_TERM,
            timelineStart = LocalTime.of(7, 45),
            timelineEnd = LocalTime.of(21, 45),
            pxPerMinute = 2.0f,
            totalHeightPx = 1000f,
            currentTimeLinePx = null,
            activeOrNextClass = null,
            items = items
        )
    }

    private fun snapshot(): WidgetScheduleSnapshot = WidgetScheduleSnapshot(
        profileId = "prof_stamp",
        generation = 42L,
        daySnapshot = emptyDaySnapshot(),
        weekOccurrences = emptyMap(),
        config = WidgetInstanceConfig(appWidgetId = 7, widgetType = WidgetType.TODAY_4X2),
        hasImportedCourses = true,
        chronometerTargetElapsedRealtime = null
    )

    @Test
    fun stampIntent_embedsProfileGenerationWidgetAndAction() {
        val intent = Intent()
        WidgetRefreshCoordinator.stampIntent(intent, snapshot(), 7, "open")

        assertNotNull(intent.data)
        assertEquals("dayinschool", intent.data!!.scheme)
        assertEquals("widget", intent.data!!.host)
        assertEquals(listOf("prof_stamp", "42", "7", "open"), intent.data!!.pathSegments)
        assertEquals("prof_stamp", intent.getStringExtra(WidgetRefreshCoordinator.EXTRA_PROFILE_ID))
        assertEquals(42L, intent.getLongExtra(WidgetRefreshCoordinator.EXTRA_GENERATION, -1L))
    }

    @Test
    fun stampIntent_differentGenerationsProduceDistinctUris() {
        val first = Intent()
        WidgetRefreshCoordinator.stampIntent(first, snapshot(), 7, "open")
        val second = Intent()
        WidgetRefreshCoordinator.stampIntent(second, snapshot().copy(generation = 43L), 7, "open")
        assertNotEquals(first.data, second.data)
    }

    @Test
    fun mainActivityLaunchIntent_targetsRealActivityWithNewTaskFlags() {
        val context = RuntimeEnvironment.getApplication()
        val intent = WidgetRefreshCoordinator.createMainActivityLaunchIntent(context)

        assertNotNull(intent.component)
        assertEquals(
            ComponentName(context.packageName, "cn.edu.haut.campus.MainActivity"),
            intent.component
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun registeredProviderClassNames_areDistinctAndStable() {
        val names = listOf(
            WidgetRefreshCoordinator.PROVIDER_NEXT_CLASS_2X2,
            WidgetRefreshCoordinator.PROVIDER_TODAY_4X2,
            WidgetRefreshCoordinator.PROVIDER_PARCEL_4X2,
            WidgetRefreshCoordinator.PROVIDER_TIMELINE_4X4,
            WidgetRefreshCoordinator.PROVIDER_WEEK_4X4
        )
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("cn.edu.haut.campus.widget.") })
    }

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = monday,
        weekCount = 20,
        timeTableId = "tt_boundary",
        current = true
    )

    private val periods = listOf(
        PeriodTime("p1", "tt_boundary", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime("p2", "tt_boundary", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        PeriodTime("p3", "tt_boundary", 3, LocalTime.of(10, 10), LocalTime.of(10, 55))
    )

    private val course = Course("c_b", "JW-B", "边界课", "测老师", 0xFF2563EB, BigDecimal("2.0"))

    private fun mondayRule() = CourseScheduleRule(
        id = "r_boundary",
        courseId = course.id,
        termId = term.id,
        weekday = DayOfWeek.MONDAY,
        startPeriod = 1,
        endPeriod = 2,
        weeks = (1..16).toSet(),
        rawRoom = "1号教学楼101"
    )

    @Test
    fun nextBoundary_beforeClassStarts_pointsAtStartTime() {
        // 07:30 → 下一边界是 08:00 上课点
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val day = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday, now = now, term = term,
            courses = listOf(course), rules = listOf(mondayRule()), periodTimes = periods
        )
        val boundary = ScheduleSnapshotUseCase.findNextBoundaryEpochMillis(day, now)

        assertNotNull(boundary)
        assertEquals(
            ZonedDateTime.of(monday, LocalTime.of(8, 0), HAUT_ZONE_ID).toInstant().toEpochMilli(),
            boundary
        )
    }

    @Test
    fun nextBoundary_insideClass_pointsAtEndTime() {
        // 08:30 课中 → 候选边界为整块课的起止端点，下一边界是 09:40 下课点
        val now = ZonedDateTime.of(monday, LocalTime.of(8, 30), HAUT_ZONE_ID)
        val day = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday, now = now, term = term,
            courses = listOf(course), rules = listOf(mondayRule()), periodTimes = periods
        )
        val boundary = ScheduleSnapshotUseCase.findNextBoundaryEpochMillis(day, now)

        assertNotNull(boundary)
        // 课程按 1-2 节整块：startTime=08:00,endTime=09:40，候选边界只有这两个端点
        assertEquals(
            ZonedDateTime.of(monday, LocalTime.of(9, 40), HAUT_ZONE_ID).toInstant().toEpochMilli(),
            boundary
        )
    }

    @Test
    fun nextBoundary_afterAllClasses_returnsNull() {
        val now = ZonedDateTime.of(monday, LocalTime.of(20, 0), HAUT_ZONE_ID)
        val day = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday, now = now, term = term,
            courses = listOf(course), rules = listOf(mondayRule()), periodTimes = periods
        )
        assertNull(ScheduleSnapshotUseCase.findNextBoundaryEpochMillis(day, now))
    }

    @Test
    fun nextBoundary_unresolvedTimeNeverSchedulesAlarm() {
        // 无作息表：时间为 UNRESOLVED 排序锚点，绝不允许据此排闹钟（防止伪造唤醒）
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val day = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday, now = now, term = term,
            courses = listOf(course), rules = listOf(mondayRule()), periodTimes = emptyList()
        )
        assertTrue(day.items.isNotEmpty())
        assertTrue(day.items.none { it.occurrence.hasResolvedTime })
        assertNull(ScheduleSnapshotUseCase.findNextBoundaryEpochMillis(day, now))
    }

    @Test
    fun showSwitching_emptyIdsIsNoOp() {
        // 空数组不得触发任何更新或崩溃
        WidgetRefreshCoordinator.showSwitching(RuntimeEnvironment.getApplication(), intArrayOf())
    }
}
