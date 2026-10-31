package cn.edu.haut.campus.core.academic

import android.webkit.CookieManager
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.util.Base64

/**
 * 河南工业大学正方教务系统与统一身份认证适配层（实测验证通过）：
 * - 统一身份认证（CAS）SSO 入口：https://jwglxt.haut.edu.cn/sso/jhlogin -> https://mapp.haut.edu.cn/cas/login
 * - 教务系统直连入口：https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html
 * - 个人课表 JSON 接口：POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151 (或 xskbcx_cxXsKb.html)
 * - 个人成绩 JSON 接口：POST /jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005
 * - 严守隐私规范：密码仅在当次请求内存中用于本人授权同步，绝不落盘存储或上传第三方。
 */
object HautAcademicEndpoints {
    const val BASE_URL = "https://jwglxt.haut.edu.cn"
    const val CAS_SSO_ENTRY_URL = "https://jwglxt.haut.edu.cn/sso/jhlogin"
    const val OFFICIAL_LOGIN_URL = "https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html"
    const val TIMETABLE_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
    const val TIMETABLE_API_PATH = "/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
    const val TIMETABLE_ALT_API_PATH = "/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
    const val GRADES_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"
    const val GRADES_API_PATH = "/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"
}

data class HautLiveSyncResult(
    val termId: String,
    val studentProfile: StudentProfile?,
    val timetableCandidates: List<ImportCourseCandidate>,
    val gradeAttempts: List<GradeAttempt>
)

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
 * 河南工业大学实测直连同步客户端（支持统一身份认证 CAS `/sso/jhlogin` 自动跳转教务并拉取课表与成绩）。
 */
object HautLiveAcademicClient {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    suspend fun fetchLiveAcademicData(
        studentId: String,
        passwordPlain: String,
        xnm: String = "2025",
        xqm: String = "12"
    ): Result<HautLiveSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val jwCookies = linkedMapOf<String, String>()
            val casCookies = linkedMapOf<String, String>()

            // 1. 请求 https://jwglxt.haut.edu.cn/sso/jhlogin 获取 route Cookie 与 CAS 跳转地址
            val hop0 = request(
                url = HautAcademicEndpoints.CAS_SSO_ENTRY_URL,
                method = "GET",
                cookieJar = jwCookies
            )
            val casLoginUrl = (hop0.location ?: error("未获取到统一身份认证跳转地址"))
                .replace(Regex("^http:"), "https:")

            // 2. 请求 https://mapp.haut.edu.cn/cas/login 获取 lt 与 execution
            val hop1 = request(
                url = casLoginUrl,
                method = "GET",
                cookieJar = casCookies
            )
            val lt = Regex("""name="lt"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                ?: error("未能从统一身份认证页面提取 lt 令牌")
            val execution = Regex("""name="execution"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                ?: "e1s1"

            // 3. 提交统一身份认证登录表单（密码格式为 {gilight}_ + Base64）
            val encodedPwd = "{gilight}_" + Base64.getEncoder().encodeToString(
                passwordPlain.toByteArray(StandardCharsets.UTF_8)
            )
            val formBody = buildFormBody(
                "username" to studentId.trim(),
                "password" to encodedPwd,
                "lt" to lt,
                "execution" to execution,
                "_eventId" to "submit"
            )
            val hop2 = request(
                url = casLoginUrl,
                method = "POST",
                cookieJar = casCookies,
                formBody = formBody
            )
            var nextLocation = hop2.location
            if (nextLocation.isNullOrBlank()) {
                val errMsg = Regex("""id="msg"[^>]*>([\s\S]*?)</div>""").find(hop2.body)
                    ?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim()
                error(errMsg?.ifBlank { null } ?: "统一身份认证账号或密码不正确")
            }

            // 4. 跟随教务系统 SSO 票据验证重定向链（jhlogin?ticket=... -> ticketlogin -> index_initMenu.html）
            var redirectCount = 0
            while (!nextLocation.isNullOrBlank() && redirectCount < 10) {
                redirectCount++
                val normalizedUrl = when {
                    nextLocation.startsWith("/") -> HautAcademicEndpoints.BASE_URL + nextLocation
                    nextLocation.startsWith("http://jwglxt.haut.edu.cn") ->
                        nextLocation.replaceFirst("http://", "https://")
                    else -> nextLocation
                }
                val hop = request(
                    url = normalizedUrl,
                    method = "GET",
                    cookieJar = jwCookies
                )
                if (hop.statusCode !in listOf(301, 302, 303, 307)) {
                    break
                }
                nextLocation = hop.location
            }

            // 5. 拉取个人课表 JSON
            val kbForm = buildFormBody(
                "xnm" to xnm,
                "xqm" to xqm,
                "kzlx" to "ck",
                "xsdm" to ""
            )
            val kbResponse = request(
                url = HautAcademicEndpoints.BASE_URL + HautAcademicEndpoints.TIMETABLE_API_PATH,
                method = "POST",
                cookieJar = jwCookies,
                formBody = kbForm,
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            if (kbResponse.statusCode != 200 || !kbResponse.body.contains("\"kbList\"")) {
                error("课表接口响应异常（HTTP ${kbResponse.statusCode}）")
            }

            val timetableCandidates = HautZfsoftParser.parseTimetableJson(kbResponse.body)
            val studentProfile = HautZfsoftParser.parseStudentProfileFromTimetableJson(
                jsonText = kbResponse.body,
                fallbackSchoolId = studentId.trim()
            )

            // 6. 拉取个人全部成绩 JSON
            val cjForm = buildFormBody(
                "xnm" to "",
                "xqm" to "",
                "_search" to "false",
                "nd" to System.currentTimeMillis().toString(),
                "queryModel.showCount" to "200",
                "queryModel.currentPage" to "1",
                "queryModel.sortName" to "",
                "queryModel.sortOrder" to "asc",
                "time" to "0"
            )
            val cjResponse = request(
                url = HautAcademicEndpoints.BASE_URL + HautAcademicEndpoints.GRADES_API_PATH,
                method = "POST",
                cookieJar = jwCookies,
                formBody = cjForm,
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            val gradeAttempts = if (cjResponse.statusCode == 200 && cjResponse.body.contains("\"items\"")) {
                HautZfsoftParser.parseGradesJson(cjResponse.body)
            } else {
                emptyList()
            }

            val termNo = if (xqm == "12" || xqm == "2") 2 else 1
            val academicYear = "${xnm}-${(xnm.toIntOrNull() ?: 2025) + 1}"
            HautLiveSyncResult(
                termId = "$academicYear-$termNo",
                studentProfile = studentProfile,
                timetableCandidates = timetableCandidates,
                gradeAttempts = gradeAttempts
            )
        }
    }

    private data class HttpResponseSnapshot(
        val statusCode: Int,
        val location: String?,
        val body: String
    )

    private fun request(
        url: String,
        method: String,
        cookieJar: MutableMap<String, String>,
        formBody: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponseSnapshot {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", USER_AGENT)
            if (cookieJar.isNotEmpty()) {
                val cookieHeader = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                setRequestProperty("Cookie", cookieHeader)
            }
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (formBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                outputStream.use { out ->
                    out.write(formBody.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        val status = conn.responseCode
        val setCookies = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"] ?: emptyList()
        for (sc in setCookies) {
            val firstPart = sc.substringBefore(";").trim()
            val eqIdx = firstPart.indexOf('=')
            if (eqIdx > 0) {
                val key = firstPart.substring(0, eqIdx).trim()
                val value = firstPart.substring(eqIdx + 1).trim()
                if (value != "deleteMe" && value.isNotEmpty()) {
                    cookieJar[key] = value
                }
            }
        }

        val location = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        val bodyText = stream?.let { s ->
            BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
        }.orEmpty()

        return HttpResponseSnapshot(
            statusCode = status,
            location = location,
            body = bodyText
        )
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }
}

/**
 * 独立编写的正方教务 JSON 与 CSV 解析器（基于河南工业大学真实教务接口报文核验）。
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
                ?: item.optString("kch").takeIf { it.isNotBlank() }
            val teacher = item.optString("xm").trim().takeIf { it.isNotBlank() }
            val cdmc = item.optString("cdmc").trim()
            val lh = item.optString("lh").trim()
            val rawRoom = when {
                cdmc.isNotBlank() && cdmc != "未排地点" -> cdmc
                lh.isNotBlank() && lh != "无楼号" -> lh
                cdmc.isNotBlank() -> cdmc
                else -> "待定教室"
            }
            val credit = item.optString("xf").trim().toBigDecimalOrNull()
            val weekdayInt = item.optString("xqj").toIntOrNull() ?: item.optInt("xqj", 1)
            val weekday = DayOfWeek.of(weekdayInt.coerceIn(1, 7))

            // 兼容 jcs ("1-2")、jcor ("1-2")、jc ("1-2节") 三种正方返回字段
            val jcsRaw = item.optString("jcs")
                .ifBlank { item.optString("jcor") }
                .ifBlank { item.optString("jc", "1-2") }
                .replace("节", "")
                .trim()
            val jcParts = jcsRaw.split("-")
            val startPeriod = jcParts.firstOrNull()?.toIntOrNull() ?: 1
            val endPeriod = jcParts.lastOrNull()?.toIntOrNull() ?: startPeriod

            // 周次如 "1-16周"、"1-3周(单),4-6周"、"第11周"
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
     * 从课表 JSON 的 `xsxx` 节点提取学生基本信息。
     */
    fun parseStudentProfileFromTimetableJson(
        jsonText: String,
        fallbackSchoolId: String = ""
    ): StudentProfile? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val xsxx = root.optJSONObject("xsxx") ?: return null
        val xm = xsxx.optString("XM").trim()
        if (xm.isEmpty()) return null
        val xh = xsxx.optString("XH").ifBlank { xsxx.optString("XH_ID", fallbackSchoolId) }.trim()
        val bjmc = xsxx.optString("BJMC").trim()
        val zymc = xsxx.optString("ZYMC").trim()
        val majorLabel = listOf(zymc, bjmc).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "河南工业大学（莲花街校区）" }
        val enrollYear = xh.take(2).toIntOrNull()?.let { 2000 + it } ?: 2023
        return StudentProfile(
            id = "stu_${xh.ifBlank { "haut" }}",
            schoolId = xh,
            displayName = xm,
            major = majorLabel,
            enrollmentYear = enrollYear
        )
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
                cjRaw in listOf("优秀", "良好", "中等", "及格", "不及格", "优", "良", "中", "差") -> AttemptType.FIVE_LEVEL
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
                cjRaw.contains("不") || cjRaw.contains("差") || cjRaw.contains("未通过") || cjRaw.contains("缺考") -> false
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
                  "jcs": "1-2",
                  "jc": "1-2节",
                  "zcd": "1-16周",
                  "cdmc": "莲1号教学楼1206(教务微调)"
                },
                {
                  "kch_id": "JW_CS205",
                  "kcmc": "操作系统原理",
                  "xm": "李娜 副教授",
                  "xf": "3.5",
                  "xqj": "2",
                  "jcs": "1-2",
                  "jc": "1-2节",
                  "zcd": "1-16周",
                  "cdmc": "莲2号教学楼2306"
                },
                {
                  "kch_id": "JW_AI301",
                  "kcmc": "人工智能导论（新同步）",
                  "xm": "刘洋 教授",
                  "xf": "3.0",
                  "xqj": "5",
                  "jcs": "3-4",
                  "jc": "3-4节",
                  "zcd": "1-16周",
                  "cdmc": "文科组团楼131"
                }
              ]
            }
        """.trimIndent()
        return parseTimetableJson(sampleJson)
    }
}
