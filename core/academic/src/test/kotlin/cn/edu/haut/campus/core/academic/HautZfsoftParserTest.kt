package cn.edu.haut.campus.core.academic

import cn.edu.haut.campus.core.model.AttemptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek

class HautZfsoftParserTest {

    @Test
    fun `parseTimetableJson extracts weeks, periods, weekday, and room accurately`() {
        val candidates = HautZfsoftParser.createSimulatedJwTimetableCandidates()
        assertEquals(3, candidates.size)
        val first = candidates.first()
        assertEquals("数据结构与算法", first.courseName)
        assertEquals(DayOfWeek.MONDAY, first.weekday)
        assertEquals(1, first.startPeriod)
        assertEquals(2, first.endPeriod)
        assertEquals((1..16).toSet(), first.weeks)
    }

    @Test
    fun `parseGradesJson preserves rawGrade and null gradePoint when absent without guessing`() {
        val sampleGradesJson = """
            {
              "items": [
                {
                  "kch_id": "CS201",
                  "kcmc": "数据结构",
                  "xnm": "2025",
                  "xqm": "3",
                  "xf": "4.0",
                  "cj": "92",
                  "jd": "4.2",
                  "ksxz": "正常考试",
                  "kcxzmc": "专业必修"
                },
                {
                  "kch_id": "GEN102",
                  "kcmc": "形势与政策",
                  "xnm": "2025",
                  "xqm": "12",
                  "xf": "1.0",
                  "cj": "优秀",
                  "jd": "",
                  "ksxz": "正常考试",
                  "kcxzmc": "通识必修"
                }
              ]
            }
        """.trimIndent()

        val attempts = HautZfsoftParser.parseGradesJson(sampleGradesJson)
        assertEquals(2, attempts.size)
        assertEquals(BigDecimal("4.2"), attempts[0].gradePoint)
        assertEquals("2025-2026-1", attempts[0].termId)

        // 第二门课教务未返回 jd，必须保持为 null，不自行套用公式
        assertNull(attempts[1].gradePoint)
        assertEquals(AttemptType.FIVE_LEVEL, attempts[1].attemptType)
        assertEquals("2025-2026-2", attempts[1].termId)
    }

    @Test
    fun `parseCsvSchedule parses custom local CSV courses`() {
        val csv = """
            # 课程名,教师,星期(1-7),开始节,结束节,周次,教室,学分
            编译原理,王志远,3,1,2,1-15周(单),1号教学楼1306,3.5
        """.trimIndent()
        val parsed = HautZfsoftParser.parseCsvSchedule(csv)
        assertEquals(1, parsed.size)
        assertEquals("编译原理", parsed.first().courseName)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), parsed.first().weeks)
    }
}
