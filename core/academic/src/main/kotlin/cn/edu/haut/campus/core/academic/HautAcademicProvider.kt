package cn.edu.haut.campus.core.academic

import android.webkit.CookieManager
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant

/**
 * 河南工业大学正方教务系统适配层（遵循隐私优先与本人会话授权原则）：
 * - 官方登录入口：https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html
 * - 绝不存储或上报用户明文密码，绝不绕过验证码
 * - 仅在用户本人在官方 WebView 完成登录后，读取当前授权会话内的课表/成绩 JSON
 */
object HautAcademicEndpoints {
    const val OFFICIAL_LOGIN_URL = "https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html"
    const val TIMETABLE_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
    const val GRADES_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"
}

object HautSessionManager {
    fun clearAuthorizedSessionCookies() {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }
}

/**
 * 独立编写的正方教务 JSON 与 CSV 解析器（无第三方未授权脚本复制）。
 */
object HautZfsoftParser {

    /**
     * 解析正方教务 `kbList` 格式的课表 JSON。
     */
    fun parseTimetableJson(jsonText: String): List<ImportCourseCandidate> {
        val root = JSONObject(jsonText)
        val kbList = root.optJSONArray("kbList") ?: JSONArray()
        val result = mutableListOf<ImportCourseCandidate>()

        for (i in 0 until kbList.length()) {
            val item = kbList.optJSONObject(i) ?: continue
            val courseName = item.optString("kcmc").trim()
            if (courseName.isEmpty()) continue
            val courseId = item.optString("kch_id").takeIf { it.isNotBlank() }
            val teacher = item.optString("xm").trim().takeIf { it.isNotBlank() }
            val rawRoom = item.optString("cdmc").trim().ifEmpty { "待定教室" }
            val credit = item.optString("xf").trim().toBigDecimalOrNull()
            val weekdayInt = item.optString("xqj").toIntOrNull() ?: item.optInt("xqj", 1)
            val weekday = DayOfWeek.of(weekdayInt.coerceIn(1, 7))

            // 节次如 "1-2节" 或 "3-4"
            val jcs = item.optString("jcs", "1-2").replace("节", "").trim()
            val jcParts = jcs.split("-")
            val startPeriod = jcParts.firstOrNull()?.toIntOrNull() ?: 1
            val endPeriod = jcParts.lastOrNull()?.toIntOrNull() ?: startPeriod

            // 周次如 "1-16周"、"1-15周(单)"
            val zcd = item.optString("zcd", "1-16周")
            val weeks = WeeksSetParser.parse(zcd).ifEmpty { (1..16).toSet() }

            result.add(
                ImportCourseCandidate(
                    externalId = courseId,
                    courseName = courseName,
                    teacher = teacher,
                    credit = credit,
                    weekday = weekday,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    weeks = weeks,
                    rawRoom = rawRoom
                )
            )
        }
        return result
    }

    /**
     * 解析正方教务 `items` 格式的成绩 JSON。
     * 严守规范：保留原始成绩 `cj` 与教务返回的课程绩点 `jd`；若教务未返回 `jd` 则置为 null，不自行套用公式。
     */
    fun parseGradesJson(jsonText: String): List<GradeAttempt> {
        val root = JSONObject(jsonText)
        val items = root.optJSONArray("items") ?: JSONArray()
        val now = Instant.now()
        val result = mutableListOf<GradeAttempt>()

        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val kcmc = obj.optString("kcmc").trim()
            if (kcmc.isEmpty()) continue
            val kchId = obj.optString("kch_id", kcmc).trim()
            val xnm = obj.optString("xnm", "2025")
            val academicYear = if (xnm.contains("-")) xnm else "${xnm}-${(xnm.toIntOrNull() ?: 2025) + 1}"
            val xqm = obj.optString("xqm", "3")
            val termNo = when (xqm) {
                "12", "2" -> 2
                else -> 1
            }
            val termId = "$academicYear-$termNo"

            val xf = obj.optString("xf", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val cjRaw = obj.optString("cj", "").trim()
            val numericScore = cjRaw.toBigDecimalOrNull()
            val jdRaw = obj.optString("jd", "").trim()
            val gradePoint = jdRaw.toBigDecimalOrNull()

            val ksxz = obj.optString("ksxz", "正常考试")
            val attemptType = when {
                ksxz.contains("重修") -> AttemptType.RETAKE
                ksxz.contains("补考") -> AttemptType.MAKEUP
                ksxz.contains("免修") || ksxz.contains("免考") -> AttemptType.EXEMPT
                ksxz.contains("缓考") -> AttemptType.DEFERRED
                cjRaw in listOf("通过", "不通过", "合格", "不合格") -> AttemptType.PASS_FAIL
                cjRaw in listOf("优秀", "良好", "中等", "及格", "不及格") -> AttemptType.FIVE_LEVEL
                else -> AttemptType.NORMAL
            }

            val kcxzmc = obj.optString("kcxzmc", "必修")
            val category = when {
                kcxzmc.contains("实践") || kcxzmc.contains("实训") -> CourseCategory.PRACTICE
                kcxzmc.contains("通识") || kcxzmc.contains("公选") -> CourseCategory.PUBLIC_ELECTIVE
                kcxzmc.contains("选修") -> CourseCategory.ELECTIVE
                else -> CourseCategory.COMPULSORY
            }

            val passed = when {
                numericScore != null -> numericScore >= BigDecimal("60")
                cjRaw.contains("不") || cjRaw.contains("未通过") || cjRaw.contains("缺考") -> false
                else -> true
            }

            result.add(
                GradeAttempt(
                    id = "jw_${termId}_${kchId}_${attemptType.name}_$i",
                    termId = termId,
                    academicYear = academicYear,
                    termNumber = termNo,
                    courseKey = kchId,
                    name = kcmc,
                    category = category,
                    credits = xf,
                    rawGrade = cjRaw,
                    numericScore = numericScore,
                    gradePoint = gradePoint,
                    attemptType = attemptType,
                    passed = passed,
                    source = CourseSource.OFFICIAL_JW,
                    syncedAt = now
                )
            )
        }
        return result
    }

    /**
     * 解析本地 CSV 课表文本（格式：课程名,教师,星期(1-7),开始节次,结束节次,周次表达式,教室,学分）。
     */
    fun parseCsvSchedule(csvContent: String): List<ImportCourseCandidate> {
        return csvContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("课程名") }
            .mapNotNull { line ->
                val cols = line.split(",").map { it.trim() }
                if (cols.size < 7) return@mapNotNull null
                val name = cols[0]
                val teacher = cols[1].ifBlank { null }
                val weekday = DayOfWeek.of((cols[2].toIntOrNull() ?: 1).coerceIn(1, 7))
                val startPeriod = cols[3].toIntOrNull() ?: 1
                val endPeriod = cols[4].toIntOrNull() ?: startPeriod
                val weeks = WeeksSetParser.parse(cols[5]).ifEmpty { (1..16).toSet() }
                val room = cols[6].ifBlank { "待定教室" }
                val credit = cols.getOrNull(7)?.toBigDecimalOrNull()
                ImportCourseCandidate(
                    externalId = null,
                    courseName = name,
                    teacher = teacher,
                    credit = credit,
                    weekday = weekday,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    weeks = weeks,
                    rawRoom = room
                )
            }
            .toList()
    }

    /**
     * 生成教务同步模拟报文（用于离线或未登录状态下一键验证“新增/修改/保留用户修改”导入差异预览流程）。
     */
    fun createSimulatedJwTimetableCandidates(): List<ImportCourseCandidate> {
        val sampleJson = """
            {
              "kbList": [
                {
                  "kch_id": "JW_CS201",
                  "kcmc": "数据结构与算法",
                  "xm": "张伟 教授",
                  "xf": "4.0",
                  "xqj": "1",
                  "jcs": "1-2节",
                  "zcd": "1-16周",
                  "cdmc": "1号教学楼1206(教务微调)"
                },
                {
                  "kch_id": "JW_CS205",
                  "kcmc": "操作系统原理",
                  "xm": "李娜 副教授",
                  "xf": "3.5",
                  "xqj": "2",
                  "jcs": "1-2节",
                  "zcd": "1-16周",
                  "cdmc": "2号教学楼2306"
                },
                {
                  "kch_id": "JW_AI301",
                  "kcmc": "人工智能导论（新同步）",
                  "xm": "刘洋 教授",
                  "xf": "3.0",
                  "xqj": "5",
                  "jcs": "3-4节",
                  "zcd": "1-16周",
                  "cdmc": "3号教学楼3402"
                }
              ]
            }
        """.trimIndent()
        return parseTimetableJson(sampleJson)
    }
}
