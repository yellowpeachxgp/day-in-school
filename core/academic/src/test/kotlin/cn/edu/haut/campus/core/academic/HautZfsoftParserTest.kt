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
    fun `parseTimetableJson and parseGradesJson handle real HAUT field variants`() {
        val realShapedKbJson = """
            {
              "xsxx": {
                "XH": "231040600000",
                "XM": "测试同学",
                "BJMC": "空信2302",
                "ZYMC": "空间信息与数字技术"
              },
              "kbList": [
                {
                  "kch_id": "0A2FA6787AB7527AE0635FFE12AC5BBD",
                  "kcmc": "空间信息前沿与应用",
                  "xm": "赵颜创",
                  "xf": "1.5",
                  "xqj": "1",
                  "jc": "3-4节",
                  "zcd": "1-3周(单),4-6周",
                  "cdmc": "文科组团楼131",
                  "lh": "莲03号教学楼"
                }
              ]
            }
        """.trimIndent()

        val profile = HautZfsoftParser.parseStudentProfileFromTimetableJson(realShapedKbJson)
        assertEquals("测试同学", profile?.displayName)
        assertEquals("空间信息与数字技术 · 空信2302", profile?.major)

        val kbCandidates = HautZfsoftParser.parseTimetableJson(realShapedKbJson)
        assertEquals(1, kbCandidates.size)
        assertEquals(3, kbCandidates[0].startPeriod)
        assertEquals(4, kbCandidates[0].endPeriod)
        assertEquals(setOf(1, 3, 4, 5, 6), kbCandidates[0].weeks)
        assertEquals("文科组团楼131", kbCandidates[0].rawRoom)

        val realShapedGradesJson = """
            {
              "items": [
                {
                  "kch_id": "C1",
                  "kcmc": "工程训练",
                  "xnm": "2024",
                  "xqm": "3",
                  "xf": "2.0",
                  "cj": "优",
                  "jd": "4.50",
                  "ksxz": "正常考试",
                  "kcxzmc": "必修课"
                },
                {
                  "kch_id": "C2",
                  "kcmc": "软件工程",
                  "xnm": "2025",
                  "xqm": "12",
                  "xf": "3.0",
                  "cj": "60",
                  "jd": "1.00",
                  "ksxz": "补考一",
                  "kcxzmc": "必修课"
                }
              ]
            }
        """.trimIndent()
        val grades = HautZfsoftParser.parseGradesJson(realShapedGradesJson)
        assertEquals(AttemptType.FIVE_LEVEL, grades[0].attemptType)
        assertEquals(BigDecimal("4.50"), grades[0].gradePoint)
        assertEquals(AttemptType.MAKEUP, grades[1].attemptType)
        assertEquals(BigDecimal("1.00"), grades[1].gradePoint)
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

    @Test
    fun `parseExamsJson classifies early makeup, mid-term decentralized, and final centralized exams`() {
        val sampleExamsJson = """
            {
              "items": [
                {
                  "kch": "T01121402A",
                  "kcmc": "高等数学A（二）",
                  "xnm": "2025",
                  "xqm": "12",
                  "ksmc": "2025-2026-2学期集中考试",
                  "sjbh": "统一-2025-2026-2学期集中考试-X01261002B",
                  "ksfs": "笔试",
                  "kssj": "2026-07-07(09:00-11:00)",
                  "cdmc": "莲4号教学楼521",
                  "zwh": "27",
                  "xqmc": "莲花街校区",
                  "cxbj": "是"
                },
                {
                  "kch": "X01041307A",
                  "kcmc": "服务式GIS",
                  "xnm": "2025",
                  "xqm": "12",
                  "ksmc": "2025-2026-2学期分散考试",
                  "sjbh": "随堂-2025-2026-2学期分散考试-X01041307A",
                  "ksfs": "其他",
                  "kssj": "2026-05-08(10:20-12:20)",
                  "cdmc": "莲4号教学楼120",
                  "zwh": "45",
                  "xqmc": "莲花街校区",
                  "cxbj": "否"
                },
                {
                  "kch": "Z01041350A",
                  "kcmc": "软件工程",
                  "xnm": "2025",
                  "xqm": "12",
                  "ksmc": "2025-2026-2学期补（缓）考",
                  "ksfs": "其他",
                  "kssj": "2026-03-08(14:00-16:00)",
                  "cdmc": "莲4号教学楼121",
                  "zwh": "58",
                  "xqmc": "莲花街校区"
                }
              ]
            }
        """.trimIndent()

        val exams = HautZfsoftParser.parseExamsJson(sampleExamsJson)
        assertEquals(3, exams.size)
        // 按时间升序排列：03-08 期初补缓考 -> 05-08 期中随堂考 -> 07-07 期末集中考
        assertEquals("软件工程", exams[0].courseName)
        assertEquals("期初补缓考", exams[0].examStageLabel)
        assertEquals("58", exams[0].seatNumber)

        assertEquals("服务式GIS", exams[1].courseName)
        assertEquals("期中/随堂考", exams[1].examStageLabel)
        assertEquals("莲4号教学楼120", exams[1].rawRoom)

        assertEquals("高等数学A（二）", exams[2].courseName)
        assertEquals("期末集中考", exams[2].examStageLabel)
        assertEquals("27", exams[2].seatNumber)
    }
}
