package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.BreakType
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class ScheduleTimelineAndConflictTest {

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 9, 7),
        weekCount = 20,
        timeTableId = "haut_default",
        current = true
    )

    private val periods = listOf(
        PeriodTime("p1", "haut_default", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime("p2", "haut_default", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        PeriodTime("p3", "haut_default", 3, LocalTime.of(10, 10), LocalTime.of(10, 55)),
        PeriodTime("p4", "haut_default", 4, LocalTime.of(11, 5), LocalTime.of(11, 50)),
        PeriodTime("p5", "haut_default", 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
        PeriodTime("p6", "haut_default", 6, LocalTime.of(15, 25), LocalTime.of(16, 10))
    )

    private val courses = listOf(
        Course("c_algo", "JW01", "数据结构与算法", "张伟", 0xFF2563EB, BigDecimal("4.0")),
        Course("c_os", "JW02", "操作系统原理", "李娜", 0xFFEA580C, BigDecimal("3.5")),
        Course("c_elective", "JW03", "粮食储藏工程导论", "王建国", 0xFF059669, BigDecimal("2.0"))
    )

    @Test
    fun `timeline maps time proportionally and transitions UPCOMING to IN_PROGRESS to FINISHED`() {
        // 2026-10-31 是第 8 周周六
        val oct31 = LocalDate.of(2026, 10, 31)
        val rules = listOf(
            CourseScheduleRule(
                id = "r1",
                courseId = "c_os",
                termId = term.id,
                weekday = DayOfWeek.SATURDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = (1..16).toSet(),
                rawRoom = "1号教学楼1204"
            ),
            CourseScheduleRule(
                id = "r2",
                courseId = "c_algo",
                termId = term.id,
                weekday = DayOfWeek.SATURDAY,
                startPeriod = 3,
                endPeriod = 4,
                weeks = setOf(2, 4, 6, 8, 10, 12, 14, 16), // 双周课，第8周有课
                rawRoom = "32号楼32204"
            )
        )

        val nowAt946 = ZonedDateTime.of(oct31, LocalTime.of(9, 46), HAUT_ZONE_ID)
        val snapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = oct31,
            now = nowAt946,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            pxPerMinute = 2.0f,
            defaultStart = LocalTime.of(8, 0),
            defaultEnd = LocalTime.of(18, 0)
        )

        assertEquals(2, snapshot.items.size)
        val first = snapshot.items[0]
        val second = snapshot.items[1]

        // 08:00 - 09:40 在 09:46 已结束
        assertEquals(ClassStatus.FINISHED, first.status)
        assertEquals(0f, first.topPx, 0.01f)
        assertEquals(200f, first.heightPx, 0.01f) // 100 mins * 2.0 px/min

        // 10:10 - 11:50 在 09:46 未开始，距开始 24 分钟
        assertEquals(ClassStatus.UPCOMING, second.status)
        assertEquals(260f, second.topPx, 0.01f) // (10:10 - 08:00) = 130 mins * 2.0 = 260px
        assertEquals(24L, second.minutesUntilStart)
        assertEquals("c_algo", snapshot.activeOrNextClass?.occurrence?.courseId)

        // 当前时间线位置：(09:46 - 08:00) = 106 mins * 2.0 = 212px
        assertNotNull(snapshot.currentTimeLinePx)
        assertEquals(212f, snapshot.currentTimeLinePx!!, 0.01f)

        // 当时间推进到 10:30（课中）
        val nowAt1030 = ZonedDateTime.of(oct31, LocalTime.of(10, 30), HAUT_ZONE_ID)
        val inProgressSnapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = oct31,
            now = nowAt1030,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            pxPerMinute = 2.0f,
            defaultStart = LocalTime.of(8, 0),
            defaultEnd = LocalTime.of(18, 0)
        )
        val secondInProgress = inProgressSnapshot.items[1]
        assertEquals(ClassStatus.IN_PROGRESS, secondInProgress.status)
        assertEquals(20L, secondInProgress.minutesElapsed)
        assertEquals(80L, secondInProgress.minutesRemaining)
    }

    @Test
    fun `detects overlapping course conflicts and splits columns`() {
        val date = LocalDate.of(2026, 10, 26) // 第 8 周周一
        val rules = listOf(
            CourseScheduleRule("r1", "c_algo", term.id, DayOfWeek.MONDAY, 1, 2, setOf(8), "1号教学楼1102"),
            CourseScheduleRule("r2", "c_elective", term.id, DayOfWeek.MONDAY, 2, 3, setOf(8), "3号教学楼3201")
        )

        val occurrences = ScheduleEngine.resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods
        )

        assertEquals(2, occurrences.size)
        assertEquals(0, occurrences[0].conflictColumnIndex)
        assertEquals(2, occurrences[0].conflictColumnCount)
        assertEquals(1, occurrences[1].conflictColumnIndex)
        assertEquals(2, occurrences[1].conflictColumnCount)
    }

    @Test
    fun `occurrence override cancels or moves single day class without polluting original rule`() {
        val date = LocalDate.of(2026, 10, 26) // 第 8 周周一
        val nextWeekDate = LocalDate.of(2026, 11, 2) // 第 9 周周一
        val rules = listOf(
            CourseScheduleRule("r1", "c_algo", term.id, DayOfWeek.MONDAY, 1, 2, setOf(8, 9), "1号教学楼1102")
        )
        val overrides = listOf(
            OccurrenceOverride(
                id = "ov1",
                termId = term.id,
                courseRuleId = "r1",
                date = date,
                room = "4号教学楼4308",
                reason = "临时更换机房"
            )
        )

        val week8List = ScheduleEngine.resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            occurrenceOverrides = overrides
        )
        assertEquals(1, week8List.size)
        assertEquals("4号教学楼4308", week8List.first().rawRoom)
        assertTrue(week8List.first().isMakeupOrOverridden)

        // 第 9 周不受第 8 周单次调课污染
        val week9List = ScheduleEngine.resolveOccurrencesForDate(
            date = nextWeekDate,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            occurrenceOverrides = overrides
        )
        assertEquals(1, week9List.size)
        assertEquals("1号教学楼1102", week9List.first().rawRoom)
    }

    @Test
    fun `import diff engine protects user modified courses from silent overwrite`() {
        val existingCourses = listOf(
            Course("c1", "JW01", "数据结构（自改备注）", "张伟", 0xFF2563EB, BigDecimal("4.0"), userModified = true),
            Course("c2", "JW02", "操作系统原理", "李娜", 0xFFEA580C, BigDecimal("3.5"), userModified = false)
        )
        val existingRules = listOf(
            CourseScheduleRule("r1", "c1", term.id, DayOfWeek.MONDAY, 1, 2, (1..16).toSet(), "A204(我的自习座)", userModified = true),
            CourseScheduleRule("r2", "c2", term.id, DayOfWeek.TUESDAY, 3, 4, (1..16).toSet(), "B302", userModified = false)
        )
        val candidates = listOf(
            ImportCourseCandidate("JW01", "数据结构", "张伟", BigDecimal("4.0"), DayOfWeek.MONDAY, 1, 2, (1..16).toSet(), "A204"),
            ImportCourseCandidate("JW02", "操作系统原理", "李娜", BigDecimal("3.5"), DayOfWeek.TUESDAY, 3, 4, (1..16).toSet(), "B306"),
            ImportCourseCandidate("JW03", "计算机网络", "赵敏", BigDecimal("3.0"), DayOfWeek.THURSDAY, 5, 6, (1..16).toSet(), "C108")
        )

        val diff = ImportDiffEngine.computeDiff(
            termId = term.id,
            source = CourseSource.OFFICIAL_JW,
            existingCourses = existingCourses,
            existingRules = existingRules,
            incomingCandidates = candidates
        )

        assertEquals(1, diff.addedCount)
        assertEquals(1, diff.modifiedCount)
        assertEquals(1, diff.conflictUserEditCount)
        val conflictEntry = diff.entries.first { it.changeType == ImportChangeType.CONFLICT_USER_MODIFIED }
        assertTrue(conflictEntry.keepUserEditByDefault)
    }

    @Test
    fun `automatically recognizes intra-class small breaks and inter-class big breaks on HAUT timetable`() {
        val hautPeriods = listOf(
            PeriodTime("p1", "haut_default", 1, LocalTime.of(8, 30), LocalTime.of(9, 15)),
            PeriodTime("p2", "haut_default", 2, LocalTime.of(9, 20), LocalTime.of(10, 5)),
            PeriodTime("p3", "haut_default", 3, LocalTime.of(10, 25), LocalTime.of(11, 10)),
            PeriodTime("p4", "haut_default", 4, LocalTime.of(11, 15), LocalTime.of(12, 0)),
            PeriodTime("p5", "haut_default", 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
            PeriodTime("p6", "haut_default", 6, LocalTime.of(15, 20), LocalTime.of(16, 5)),
            PeriodTime("p7", "haut_default", 7, LocalTime.of(16, 25), LocalTime.of(17, 10)),
            PeriodTime("p8", "haut_default", 8, LocalTime.of(17, 15), LocalTime.of(18, 0)),
            PeriodTime("p9", "haut_default", 9, LocalTime.of(19, 30), LocalTime.of(20, 15)),
            PeriodTime("p10", "haut_default", 10, LocalTime.of(20, 25), LocalTime.of(21, 10))
        )

        val wednesday = LocalDate.of(2026, 10, 28) // 第 8 周周三
        val rules = listOf(
            CourseScheduleRule(
                id = "r_stat",
                courseId = "c_algo",
                termId = term.id,
                weekday = DayOfWeek.WEDNESDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = (1..16).toSet(),
                rawRoom = "莲4号教学楼321",
                buildingId = "b_teach_4"
            ),
            CourseScheduleRule(
                id = "r_pol",
                courseId = "c_os",
                termId = term.id,
                weekday = DayOfWeek.WEDNESDAY,
                startPeriod = 3,
                endPeriod = 4,
                weeks = (1..16).toSet(),
                rawRoom = "文科组团楼131",
                buildingId = "b_wenke_group"
            ),
            CourseScheduleRule(
                id = "r_pm",
                courseId = "c_elective",
                termId = term.id,
                weekday = DayOfWeek.WEDNESDAY,
                startPeriod = 5,
                endPeriod = 6,
                weeks = (1..16).toSet(),
                rawRoom = "莲5号教学楼208",
                buildingId = "b_teach_5"
            )
        )

        // 1) 在 09:17（1-2节连堂课中间的 5 分钟小课间 09:15-09:20）验证课内小课间识别
        val nowAt917 = ZonedDateTime.of(wednesday, LocalTime.of(9, 17), HAUT_ZONE_ID)
        val snapshotAtSmallBreak = ScheduleEngine.buildDayTimelineSnapshot(
            date = wednesday,
            now = nowAt917,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = hautPeriods
        )

        val firstItem = snapshotAtSmallBreak.items[0]
        assertEquals(1, firstItem.occurrence.internalBreaks.size)
        val internalBreak = firstItem.occurrence.internalBreaks.first()
        assertEquals(LocalTime.of(9, 15), internalBreak.startTime)
        assertEquals(LocalTime.of(9, 20), internalBreak.endTime)
        assertEquals(5L, internalBreak.durationMinutes)
        assertNotNull(firstItem.activeInternalBreak)
        assertTrue(firstItem.statusText.contains("小课间"))

        // 2) 在 10:12（1-2节与3-4节之间的 20 分钟大课间 10:05-10:25）验证跨课程大课间与跨楼换教室识别
        val nowAt1012 = ZonedDateTime.of(wednesday, LocalTime.of(10, 12), HAUT_ZONE_ID)
        val snapshotAtBigBreak = ScheduleEngine.buildDayTimelineSnapshot(
            date = wednesday,
            now = nowAt1012,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = hautPeriods
        )

        assertEquals(2, snapshotAtBigBreak.breakIntervals.size)
        val morningBigBreak = snapshotAtBigBreak.breakIntervals[0]
        assertEquals(BreakType.BIG_BREAK, morningBigBreak.breakType)
        assertEquals(20L, morningBigBreak.durationMinutes)
        assertEquals(LocalTime.of(10, 5), morningBigBreak.startTime)
        assertEquals(LocalTime.of(10, 25), morningBigBreak.endTime)
        assertTrue(morningBigBreak.requiresRoomChange)
        assertTrue(morningBigBreak.requiresBuildingChange)
        assertEquals("跨楼换教室：莲4号教学楼321 → 文科组团楼131", morningBigBreak.roomTransferText)
        assertTrue(morningBigBreak.isActiveNow)
        assertEquals(13L, morningBigBreak.minutesRemainingInBreak)
        assertEquals(morningBigBreak, snapshotAtBigBreak.activeBreakInterval)

        val noonBreak = snapshotAtBigBreak.breakIntervals[1]
        assertEquals(BreakType.NOON_BREAK, noonBreak.breakType)
        assertEquals(150L, noonBreak.durationMinutes)
        assertEquals(LocalTime.of(12, 0), noonBreak.startTime)
        assertEquals(LocalTime.of(14, 30), noonBreak.endTime)
    }

    @Test
    fun `merged adjacent rules preserve all mergedRuleIds and practical sjk course does not collide with same-name regular course`() {
        val monday = LocalDate.of(2026, 9, 7)
        val multiSlotRules = listOf(
            CourseScheduleRule(
                id = "r_lab_1_2",
                courseId = "c_algo",
                termId = term.id,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = setOf(1, 2),
                rawRoom = "莲4号教学楼321"
            ),
            CourseScheduleRule(
                id = "r_lab_3_4",
                courseId = "c_algo",
                termId = term.id,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 3,
                endPeriod = 4,
                weeks = setOf(1, 2),
                rawRoom = "莲4号教学楼321"
            )
        )
        val mergedOccs = ScheduleEngine.resolveOccurrencesForDate(
            date = monday,
            term = term,
            courses = courses,
            rules = multiSlotRules,
            periodTimes = periods
        )
        assertEquals(1, mergedOccs.size)
        assertEquals(1, mergedOccs.first().startPeriod)
        assertEquals(4, mergedOccs.first().endPeriod)
        assertEquals(listOf("r_lab_1_2", "r_lab_3_4"), mergedOccs.first().mergedRuleIds)

        // Same-name practical course ("sjk:...") must NOT collide with regular JW lecture ("JW_ALGO_01")
        val practicalCourse = Course(
            id = "c_prac_algo",
            externalId = "sjk:2026-2027-1:deadbeef",
            name = "算法设计与分析",
            teacher = "王工",
            colorArgb = 0xFF0D9488,
            source = CourseSource.OFFICIAL_JW,
            userModified = true
        )
        val practicalRule = CourseScheduleRule(
            id = "r_prac_algo",
            courseId = practicalCourse.id,
            termId = term.id,
            weekday = DayOfWeek.FRIDAY,
            startPeriod = 5,
            endPeriod = 6,
            weeks = (1..16).toSet(),
            rawRoom = "实践机房A",
            userModified = true
        )
        val regularLectureCandidate = ImportCourseCandidate(
            externalId = "JW_ALGO_LECTURE",
            courseName = "算法设计与分析",
            teacher = "李教授",
            credit = BigDecimal("4.0"),
            weekday = DayOfWeek.FRIDAY,
            startPeriod = 5,
            endPeriod = 6,
            weeks = (1..16).toSet(),
            rawRoom = "莲4号教学楼201"
        )
        val diff = ImportDiffEngine.computeDiff(
            termId = term.id,
            source = CourseSource.OFFICIAL_JW,
            existingCourses = listOf(practicalCourse),
            existingRules = listOf(practicalRule),
            incomingCandidates = listOf(regularLectureCandidate)
        )
        assertEquals(1, diff.addedCount)
        val addedEntry = diff.entries.single { it.changeType == ImportChangeType.ADDED }
        assertEquals("JW_ALGO_LECTURE", addedEntry.incoming?.externalId)
        val preservedPractical = diff.entries.single { it.changeType == ImportChangeType.CONFLICT_USER_MODIFIED }
        assertEquals(null, preservedPractical.incoming)
        assertEquals("sjk:2026-2027-1:deadbeef", preservedPractical.existingCourse?.externalId)
        assertTrue(preservedPractical.keepUserEditByDefault)
    }

    @Test
    fun periods11To12_neverFabricateMorningTimeWhenPeriodDefinitionsAreMissing() {
        // periods fixture only defines 1..10; adding an 11-12节 evening course MUST NOT fall back to 08:00 AM!
        val monday = LocalDate.of(2026, 9, 7)
        val eveningRules = listOf(
            CourseScheduleRule(
                id = "r_morning_1_2",
                courseId = "c_algo",
                termId = term.id,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = setOf(1, 2),
                rawRoom = "31号楼31101"
            ),
            CourseScheduleRule(
                id = "r_evening_11_12",
                courseId = "c_elective",
                termId = term.id,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 11,
                endPeriod = 12,
                weeks = setOf(1, 2),
                rawRoom = "3号教学楼3201"
            )
        )
        val tenPeriods = periods + listOf(
            PeriodTime("p7", "haut_default", 7, LocalTime.of(16, 25), LocalTime.of(17, 10)),
            PeriodTime("p8", "haut_default", 8, LocalTime.of(17, 15), LocalTime.of(18, 0)),
            PeriodTime("p9", "haut_default", 9, LocalTime.of(19, 30), LocalTime.of(20, 15)),
            PeriodTime("p10", "haut_default", 10, LocalTime.of(20, 25), LocalTime.of(21, 10))
        )
        val occs = ScheduleEngine.resolveOccurrencesForDate(
            date = monday,
            term = term,
            courses = courses,
            rules = eveningRules,
            periodTimes = tenPeriods // only 1..10!
        )
        assertEquals(2, occs.size)
        val morningOcc = occs.first { it.ruleId == "r_morning_1_2" }
        val unresolved1112 = occs.first { it.ruleId == "r_evening_11_12" }
        assertTrue(morningOcc.hasResolvedTime)
        org.junit.Assert.assertFalse("Missing 11-12 period times must set hasResolvedTime = false", unresolved1112.hasResolvedTime)
        assertTrue("Unresolved 11-12 anchor must sort AFTER period 10, never at 08:00", unresolved1112.startTime.isAfter(LocalTime.of(21, 10)))
        assertTrue(unresolved1112.formatTimeOrPeriodRange(java.time.format.DateTimeFormatter.ofPattern("HH:mm")).contains("作息待设"))

        // At 08:45 AM, timeline MUST NOT mark unresolved 11-12节 as IN_PROGRESS or conflicting with 1-2节!
        val snapMorning = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday,
            now = ZonedDateTime.of(monday, LocalTime.of(8, 45), HAUT_ZONE_ID),
            term = term,
            courses = courses,
            rules = eveningRules,
            periodTimes = tenPeriods
        )
        val item1112 = snapMorning.items.single { it.occurrence.ruleId == "r_evening_11_12" }
        assertEquals(cn.edu.haut.campus.core.model.ClassStatus.UPCOMING, item1112.status)
        assertEquals(null, item1112.minutesUntilStart)
        assertTrue(item1112.statusText.contains("节次时间待设置"))

        // ReminderPlanner MUST NOT schedule a bogus morning alarm for unresolved 11-12节!
        val windowEvents = ReminderPlanner.buildWindowEvents(
            now = ReminderRevision.schoolInstant(monday, LocalTime.of(7, 30)),
            horizonDays = 0L,
            term = term,
            courses = courses,
            rules = eveningRules,
            periodTimes = tenPeriods,
            calendarOverrides = emptyList(),
            occurrenceOverrides = emptyList(),
            buildings = emptyList()
        )
        assertEquals("Only resolved 1-2节 should produce a ReminderEvent", 1, windowEvents.size)
        assertTrue(windowEvents.single().eventKey.endsWith("r_morning_1_2"))
    }
}
