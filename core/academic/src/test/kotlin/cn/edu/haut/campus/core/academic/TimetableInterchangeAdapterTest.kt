package cn.edu.haut.campus.core.academic

import cn.edu.haut.campus.core.domain.ImportDiffEngine
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportChangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class TimetableInterchangeAdapterTest {

    @Test
    fun parseUnivTimetableV1Json_splitsNonContiguousSectionsIntoContiguousBlocks() {
        val json = """
            {
              "contract": "univ-timetable/v1",
              "meta": {
                "school": "河南工业大学",
                "semester": "2026-2027-1",
                "semesterStart": "2026-09-07",
                "totalWeeks": 20
              },
              "periods": [
                {"index": 1, "start": "08:00", "end": "08:45"},
                {"index": 2, "start": "08:55", "end": "09:40"}
              ],
              "courses": [
                {
                  "name": "操作系统",
                  "teacher": "张老师",
                  "location": "莲花街6203",
                  "dayOfWeek": 2,
                  "sections": [1, 2, 5, 6],
                  "weeks": [1, 2, 3, 4, 5, 6, 7, 8]
                }
              ]
            }
        """.trimIndent()

        val result = TimetableInterchangeAdapter.parse(json)
        assertEquals(0, result.skippedCount)
        assertEquals(LocalDate.of(2026, 9, 7), result.semesterStart)
        assertEquals(20, result.totalWeeks)
        assertEquals(2, result.sectionTimes.size)

        // [1, 2, 5, 6] must split into two contiguous ImportCourseCandidate items: 1..2 and 5..6
        assertEquals(2, result.candidates.size)
        val first = result.candidates[0]
        val second = result.candidates[1]
        assertEquals("操作系统", first.courseName)
        assertEquals(DayOfWeek.TUESDAY, first.weekday)
        assertEquals(1, first.startPeriod)
        assertEquals(2, first.endPeriod)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), first.weeks)

        assertEquals(5, second.startPeriod)
        assertEquals(6, second.endPeriod)
    }

    @Test
    fun parseMarkdownTable_extractsCoursesAndSanitizesPiiOnExport() {
        val md = """
            # 大学课表数据文件

            > 契约版本：`univ-timetable/v1`

            ## 一、课表元信息

            | 项目 | 内容 |
            | --- | --- |
            | 学校 | 河南工业大学 |
            | 学期 | 2026-2027-1 |
            | 学期开始日期 | 2026-09-07 |
            | 学期总周数 | 20 |
            | 学生 | - |
            | 学号 | - |

            ## 三、课程明细

            | 课程名称 | 教师 | 地点 | 星期 | 节次 | 周次 | 备注 |
            | --- | --- | --- | --- | --- | --- | --- |
            | 编译原理 | 李老师 | 6301 | 周三 | 第3-4节 | 1-16周 | - |
            | 计算机网络 | 赵老师 | 3102 | 周五 | 第1-2,5-6节 | 1-12周(双) | - |
        """.trimIndent()

        val parsed = TimetableInterchangeAdapter.parse(md)
        assertEquals(3, parsed.candidates.size)
        assertEquals("编译原理", parsed.candidates[0].courseName)
        assertEquals(DayOfWeek.WEDNESDAY, parsed.candidates[0].weekday)
        assertEquals(3, parsed.candidates[0].startPeriod)
        assertEquals(4, parsed.candidates[0].endPeriod)

        assertEquals("计算机网络", parsed.candidates[1].courseName)
        assertEquals(setOf(2, 4, 6, 8, 10, 12), parsed.candidates[1].weeks)

        val exportedJson = TimetableInterchangeAdapter.exportJson(
            term = null,
            courses = emptyList(),
            rules = emptyList(),
            periodTimes = emptyList()
        )
        assertTrue(exportedJson.contains("\"contract\": \"univ-timetable/v1\""))
        assertFalse(exportedJson.contains("password"))
        assertFalse(exportedJson.contains("studentId"))
        assertFalse(exportedJson.contains("roomverify"))
    }

    @Test
    fun parseZfsoftTimetable_includesSjkListPracticeCoursesAndTermCalendar() {
        val zfJson2026 = """
            {
              "kbList": [],
              "sjkList": [
                {
                  "kcmc": "综合项目实践",
                  "jsxm": "李伟",
                  "qsjsz": "1-6周",
                  "xf": "6.0",
                  "sjkcgs": "综合项目实践(李伟)1-6周"
                },
                {
                  "kcmc": "形势与政策（七）",
                  "jsxm": "赵敏",
                  "qsjsz": "7-10周",
                  "xf": "0.25",
                  "sjkcgs": "形势与政策（七）(赵敏)7-10周"
                }
              ]
            }
        """.trimIndent()

        val breakdown = HautZfsoftParser.parseTimetableWithBreakdown(zfJson2026)
        assertEquals(0, breakdown.regularCount)
        assertEquals(2, breakdown.practiceCount)
        assertEquals(2, breakdown.allCandidates.size)
        assertEquals("综合项目实践", breakdown.allCandidates[0].courseName)
        assertEquals(setOf(1, 2, 3, 4, 5, 6), breakdown.allCandidates[0].weeks)
        assertEquals("形势与政策（七）", breakdown.allCandidates[1].courseName)
        assertEquals(setOf(7, 8, 9, 10), breakdown.allCandidates[1].weeks)

        val zcJson = """
            [
              {"zc": 1, "rq": "2026-08-31-2026-09-06"},
              {"zc": 20, "rq": "2027-01-11-2027-01-17"}
            ]
        """.trimIndent()
        val cal = HautZfsoftParser.parseTermCalendarJson(zcJson)
        assertNotNull(cal)
        assertEquals(LocalDate.of(2026, 8, 31), cal?.first)
        assertEquals(20, cal?.second)

        assertEquals("2026-2027-1", HautAcademicEndpoints.toTermId("2026", "3"))
        assertEquals("2025-2026-2", HautAcademicEndpoints.toTermId("2025", "12"))
        assertEquals("2026" to "3", HautAcademicEndpoints.fromTermId("2026-2027-1"))
        assertEquals("2025" to "12", HautAcademicEndpoints.fromTermId("2025-2026-2"))
    }

    @Test
    fun computeDiff_preservesMultipleCoursesOnSameWeekdayAndPeriod() {
        val zfJson2025 = """
            {
              "kbList": [
                {
                  "kcmc": "高等数学A（二）",
                  "kch_id": "MATH02",
                  "xm": "刘老师",
                  "cdmc": "6305",
                  "xqj": "2",
                  "jcs": "1-2",
                  "zcd": "1-16周",
                  "xf": "4.5"
                },
                {
                  "kcmc": "服务式GIS",
                  "kch_id": "GIS09",
                  "xm": "陈老师",
                  "cdmc": "8410",
                  "xqj": "2",
                  "jcs": "1-2",
                  "zcd": "1-7周",
                  "xf": "2.0"
                }
              ],
              "sjkList": []
            }
        """.trimIndent()

        val incoming = HautZfsoftParser.parseTimetableJson(zfJson2025)
        assertEquals(2, incoming.size)

        // Existing unrelated course on Tuesday 1-2 in the same term should be marked REMOVED_FROM_REMOTE,
        // while both incoming Tuesday 1-2 courses must be ADDED without colliding with each other.
        val existingCourse = Course(
            id = "c_old",
            externalId = "OLD01",
            name = "旧演示课程",
            teacher = "王老师",
            colorArgb = 0xFF0072BC,
            source = CourseSource.OFFICIAL_JW,
            userModified = false,
            lastSyncAt = Instant.now()
        )
        val existingRule = CourseScheduleRule(
            id = "r_old",
            courseId = "c_old",
            termId = "2025-2026-2",
            weekday = DayOfWeek.TUESDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "1106",
            userModified = false
        )

        val diff = ImportDiffEngine.computeDiff(
            termId = "2025-2026-2",
            source = CourseSource.OFFICIAL_JW,
            existingCourses = listOf(existingCourse),
            existingRules = listOf(existingRule),
            incomingCandidates = incoming
        )

        assertEquals(2, diff.addedCount)
        assertEquals(1, diff.entries.count { it.changeType == ImportChangeType.REMOVED_FROM_REMOTE })
        assertEquals(
            setOf("高等数学A（二）", "服务式GIS"),
            diff.entries.filter { it.changeType == ImportChangeType.ADDED }
                .mapNotNull { it.incoming?.courseName }
                .toSet()
        )
    }
}
