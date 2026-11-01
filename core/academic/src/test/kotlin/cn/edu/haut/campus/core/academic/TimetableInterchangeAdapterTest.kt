package cn.edu.haut.campus.core.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
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
}
