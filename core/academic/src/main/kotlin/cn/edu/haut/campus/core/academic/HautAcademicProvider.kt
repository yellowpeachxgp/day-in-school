package cn.edu.haut.campus.core.academic

import android.webkit.CookieManager
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

/**
 * 河南工业大学正方教务系统与统一身份认证适配层（实测验证通过）：
 * - 统一身份认证（CAS）SSO 入口：https://jwglxt.haut.edu.cn/sso/jhlogin -> https://mapp.haut.edu.cn/cas/login
 * - 教务系统直连入口：https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html
 * - 个人课表 JSON 接口：POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151 (或 xskbcx_cxXsKb.html)
 * - 个人成绩 JSON 接口：POST /jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005
 * - 个人考试安排接口：POST /jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105
 * - 严守隐私规范：密码仅在本地受控同步请求中使用，绝不上传任何第三方。
 */
object HautAcademicEndpoints {
    const val BASE_URL = "https://jwglxt.haut.edu.cn"
    const val CAS_SSO_ENTRY_URL = "https://jwglxt.haut.edu.cn/sso/jhlogin"
    const val STUDENT_PORTAL_URL = "https://mapp.haut.edu.cn/haut/"
    const val OFFICIAL_LOGIN_URL = "https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html"
    const val TIMETABLE_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
    const val TIMETABLE_WEEK_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N215304&layout=default"
    const val TIMETABLE_API_PATH = "/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
    const val TIMETABLE_ALT_API_PATH = "/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
    const val TERM_WEEKS_API_PATH = "/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N215304"
    const val GRADES_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"
    const val GRADES_API_PATH = "/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"
    const val EXAMS_PAGE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105"
    const val EXAMS_API_PATH = "/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

    fun toTermId(xnm: String, xqm: String): String {
        val cleanXnm = xnm.trim().take(4).toIntOrNull() ?: 2025
        val termNo = if (xqm.trim() == "12" || xqm.trim() == "2") 2 else 1
        return "$cleanXnm-${cleanXnm + 1}-$termNo"
    }

    fun fromTermId(termId: String): Pair<String, String> {
        val parts = termId.trim().split("-")
        val xnm = parts.firstOrNull()?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: "2025"
        val termNo = parts.lastOrNull()?.toIntOrNull() ?: 1
        val xqm = if (termNo == 2) "12" else "3"
        return xnm to xqm
    }
}

data class HautLiveSyncResult(
    val termId: String,
    val studentProfile: StudentProfile?,
    val timetableCandidates: List<ImportCourseCandidate>,
    val gradeAttempts: List<GradeAttempt>,
    val examItems: List<ExamScheduleItem> = emptyList(),
    val semesterStart: LocalDate? = null,
    val totalWeeks: Int? = null,
    val regularCourseCount: Int = timetableCandidates.size,
    val practiceCourseCount: Int = 0,
    val additionalTermTimetables: Map<String, List<ImportCourseCandidate>> = emptyMap(),
    val additionalTermCalendars: Map<String, Pair<LocalDate, Int>> = emptyMap(),
    val gradesComplete: Boolean = false,
    val examsComplete: Boolean = false,
    val syncWarnings: List<String> = emptyList(),
    val unscheduledCourses: List<UnscheduledAcademicCourse> = emptyList()
)

fun HautLiveSyncResult.syncSummaryText(): String = buildString {
    append("课表已更新（$termId）：${timetableCandidates.size} 条排课")
    if (unscheduledCourses.isNotEmpty()) append(" · ${unscheduledCourses.size} 门实践课程时间待定，不生成提醒")
    if (timetableCandidates.isEmpty()) {
        val fallback = additionalTermTimetables.entries.firstOrNull { (sibId, cands) ->
            sibId != termId && cands.isNotEmpty()
        }
        if (fallback != null) {
            append("\n检测到 $termId 暂未排常规课，已自动同步并切换至 ${fallback.key}（${fallback.value.size} 条排课）")
        }
    }
    append(if (gradesComplete) "\n成绩已完整读取：${gradeAttempts.size} 条" else "\n成绩同步未完成，已保留之前的数据")
    append(if (examsComplete) "\n考试已完整读取：${examItems.size} 场" else "\n考试同步未完成，已保留之前的数据")
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
            val jwCookies = OfficialAcademicCookieJar(HautAcademicUrlPolicy.ACADEMIC_HOST)
            val casCookies = OfficialAcademicCookieJar(HautAcademicUrlPolicy.CAS_HOST)

            // 1. 请求 https://jwglxt.haut.edu.cn/sso/jhlogin 获取 route Cookie 与 CAS 跳转地址
            val hop0 = request(
                url = HautAcademicEndpoints.CAS_SSO_ENTRY_URL,
                method = "GET",
                cookieJar = jwCookies
            )
            val casLoginUrl = normalizeRedirectUrl(
                rawLocation = hop0.location ?: error("未获取到统一身份认证跳转地址"),
                baseHostUrl = HautAcademicEndpoints.CAS_SSO_ENTRY_URL
            )

            // 2. 请求 https://mapp.haut.edu.cn/cas/login 获取 lt 与 execution
            val hop1 = request(
                url = casLoginUrl,
                method = "GET",
                cookieJar = casCookies
            )
            val lt = Regex("""name="lt"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                ?: Regex("""value="([^"]+)"\s+name="lt"""").find(hop1.body)?.groupValues?.get(1)
                ?: error("未能从统一身份认证页面提取 lt 令牌")
            val execution = Regex("""name="execution"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                ?: "e1s1"
            val formActionRaw = Regex("""<form[^>]*id="fm1"[^>]*action="([^"]+)"""").find(hop1.body)
                ?.groupValues?.get(1)
                ?.replace("&amp;", "&")
            val casPostUrl = if (!formActionRaw.isNullOrBlank()) {
                normalizeRedirectUrl(formActionRaw, casLoginUrl)
            } else {
                casLoginUrl
            }

            HautAcademicUrlPolicy.validate(casPostUrl, credentialPost = true)

            // 3. 提交统一身份认证登录表单（密码格式为 {gilight}_ + Base64）
            val passwordBytes = passwordPlain.toByteArray(StandardCharsets.UTF_8)
            val encodedPwd = try {
                "{gilight}_" + Base64.getEncoder().encodeToString(passwordBytes)
            } finally { passwordBytes.fill(0) }
            val formBody = buildFormBody(
                "username" to studentId.trim(),
                "password" to encodedPwd,
                "lt" to lt,
                "execution" to execution,
                "_eventId" to "submit"
            )
            val hop2 = request(
                url = casPostUrl,
                method = "POST",
                cookieJar = casCookies,
                formBody = formBody
            )
            var nextLocation = hop2.location
            if (nextLocation.isNullOrBlank()) {
                val needsChallenge = hop2.body.contains("验证码") || hop2.body.contains("captcha", ignoreCase = true)
                throw AcademicReadException(
                    if (needsChallenge) "CAPTCHA_REQUIRED" else "AUTH_REJECTED",
                    if (needsChallenge) "学校要求人工验证，请使用官方网页登录完成验证" else "学校认证未完成，请核对账号后重新登录"
                )
            }

            // 4. 跟随教务系统 SSO 票据验证重定向链（jhlogin?ticket=... -> ticketlogin -> index_initMenu.html）
            var redirectCount = 0
            var currentRedirectUrl = casPostUrl
            while (!nextLocation.isNullOrBlank() && redirectCount < 12) {
                redirectCount++
                val normalizedUrl = normalizeRedirectUrl(
                    rawLocation = nextLocation,
                    baseHostUrl = currentRedirectUrl
                )
                val targetJar = if (HautAcademicUrlPolicy.validate(normalizedUrl).host == HautAcademicUrlPolicy.CAS_HOST) casCookies else jwCookies
                val hop = request(
                    url = normalizedUrl,
                    method = "GET",
                    cookieJar = targetJar
                )
                currentRedirectUrl = normalizedUrl
                if (hop.statusCode !in listOf(301, 302, 303, 307, 308)) {
                    nextLocation = null
                    break
                }
                nextLocation = hop.location
            }

            if (!nextLocation.isNullOrBlank()) throw AcademicReadException("UNTRUSTED_REDIRECT", "学校认证跳转次数过多，请重新登录")

            // This import can outlive its screen/profile. Keep its CAS jar local:
            // only the repository's lifecycle-locked library login may export WebView cookies.

            // 5. 拉取当前选定学期个人课表 JSON 与官方周次校历
            val primaryBody = fetchTermTimetableJsonText(xnm = xnm, xqm = xqm, cookieJar = jwCookies)
                ?: error("课表接口响应异常，请确认教务系统服务可用")
            val primaryBreakdown = HautZfsoftParser.parseTimetableWithBreakdown(primaryBody)
            val primaryCalendar = fetchTermCalendarPair(xnm = xnm, xqm = xqm, cookieJar = jwCookies)
            val studentProfile = HautZfsoftParser.parseStudentProfileFromTimetableJson(
                jsonText = primaryBody,
                fallbackSchoolId = studentId.trim()
            )

            // 5.1 按本次选择推导相邻学期，不固定某一学年。
            val additionalTimetables = linkedMapOf<String, List<ImportCourseCandidate>>()
            val additionalCalendars = linkedMapOf<String, Pair<LocalDate, Int>>()
            val selectedYear = xnm.toIntOrNull() ?: error("学年格式无效")
            val siblingSemesters = if (xqm in setOf("12", "2")) {
                listOf(selectedYear.toString() to "3", (selectedYear + 1).toString() to "3")
            } else {
                listOf((selectedYear - 1).toString() to "12", selectedYear.toString() to "12")
            }
            for ((sibXnm, sibXqm) in siblingSemesters) {
                if (sibXnm == xnm && sibXqm == xqm) continue
                val sibTermId = HautAcademicEndpoints.toTermId(sibXnm, sibXqm)
                runCatching {
                    val sibJson = fetchTermTimetableJsonText(sibXnm, sibXqm, jwCookies)
                    if (sibJson != null) {
                        val sibCandidates = HautZfsoftParser.parseTimetableJson(sibJson)
                        if (sibCandidates.isNotEmpty()) {
                            additionalTimetables[sibTermId] = sibCandidates
                        }
                    }
                    val sibCal = fetchTermCalendarPair(sibXnm, sibXqm, jwCookies)
                    if (sibCal != null) {
                        additionalCalendars[sibTermId] = sibCal
                    }
                }
            }

            // 成绩与考试分别收集完整快照；失败项不发布空数据。
            val gradesRead = runCatching {
                HautZfsoftParser.parseGradesJson(fetchPagedItems(HautAcademicEndpoints.GRADES_API_PATH, jwCookies))
            }.also { if (it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! }
            val examsRead = runCatching {
                HautZfsoftParser.parseExamsJson(fetchPagedItems(HautAcademicEndpoints.EXAMS_API_PATH, jwCookies))
            }.also { if (it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! }
            val gradeAttempts = gradesRead.getOrDefault(emptyList())
            val examItems = examsRead.getOrDefault(emptyList())

            HautLiveSyncResult(
                termId = HautAcademicEndpoints.toTermId(xnm, xqm),
                studentProfile = studentProfile,
                timetableCandidates = primaryBreakdown.allCandidates,
                gradeAttempts = gradeAttempts,
                examItems = examItems,
                semesterStart = primaryCalendar?.first,
                totalWeeks = primaryCalendar?.second,
                regularCourseCount = primaryBreakdown.regularCount,
                practiceCourseCount = primaryBreakdown.practiceCount,
                additionalTermTimetables = additionalTimetables,
                additionalTermCalendars = additionalCalendars,
                gradesComplete = gradesRead.isSuccess,
                examsComplete = examsRead.isSuccess,
                syncWarnings = listOfNotNull(
                    gradesRead.exceptionOrNull()?.let { "成绩同步未完成，已保留原数据" },
                    examsRead.exceptionOrNull()?.let { "考试同步未完成，已保留原数据" }
                ),
                unscheduledCourses = primaryBreakdown.unscheduledCourses
            )
        }.recoverCatching { error ->
            when (error) {
                is CancellationException -> throw error
                is AcademicReadException -> throw error
                is javax.net.ssl.SSLException -> throw AcademicReadException("TLS_ERROR", "学校安全连接验证失败，请稍后重试")
                is java.net.SocketTimeoutException -> throw AcademicReadException("TIMEOUT", "学校响应超时，请稍后重试；已保留之前的数据")
                is java.io.IOException -> throw AcademicReadException("NETWORK_OFFLINE", "无法连接学校，请检查网络；已保留之前的数据")
                else -> throw AcademicReadException("PAGE_CHANGED", "学校数据读取未完成，请尝试官方网页登录；已保留之前的数据")
            }
        }
    }

    private fun fetchTermTimetableJsonText(
        xnm: String,
        xqm: String,
        cookieJar: OfficialAcademicCookieJar
    ): String? {
        val kbForm = buildFormBody(
            "xnm" to xnm,
            "xqm" to xqm,
            "kzlx" to "ck",
            "xsdm" to ""
        )
        val primaryResp = request(
            url = HautAcademicEndpoints.BASE_URL + HautAcademicEndpoints.TIMETABLE_API_PATH,
            method = "POST",
            cookieJar = cookieJar,
            formBody = kbForm,
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        if (primaryResp.statusCode == 200 && primaryResp.body.contains("\"kbList\"")) {
            return primaryResp.body
        }
        val altResp = request(
            url = HautAcademicEndpoints.BASE_URL + HautAcademicEndpoints.TIMETABLE_ALT_API_PATH,
            method = "POST",
            cookieJar = cookieJar,
            formBody = buildFormBody("xnm" to xnm, "xqm" to xqm),
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        return if (altResp.statusCode == 200 && altResp.body.contains("\"kbList\"")) {
            altResp.body
        } else {
            null
        }
    }

    private fun fetchTermCalendarPair(
        xnm: String,
        xqm: String,
        cookieJar: OfficialAcademicCookieJar
    ): Pair<LocalDate, Int>? = runCatching {
        val zcResp = request(
            url = HautAcademicEndpoints.BASE_URL + HautAcademicEndpoints.TERM_WEEKS_API_PATH,
            method = "POST",
            cookieJar = cookieJar,
            formBody = buildFormBody("xnm" to xnm, "xqm" to xqm),
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        if (zcResp.statusCode == 200) {
            HautZfsoftParser.parseTermCalendarJson(zcResp.body)
        } else {
            null
        }
    }.getOrNull()

    private fun fetchPagedItems(path: String, cookieJar: OfficialAcademicCookieJar): String =
        AcademicPageReader.readAll { page, size ->
            val response = request(
                url = HautAcademicEndpoints.BASE_URL + path,
                method = "POST", cookieJar = cookieJar,
                formBody = buildFormBody(
                    "xnm" to "", "xqm" to "", "_search" to "false",
                    "queryModel.showCount" to size.toString(),
                    "queryModel.currentPage" to page.toString(),
                    "queryModel.sortName" to "", "queryModel.sortOrder" to "asc", "time" to "0"
                ),
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            when (response.statusCode) {
                200 -> Unit
                401, 301, 302, 303, 307, 308 -> throw AcademicReadException("SESSION_EXPIRED", "学校会话已失效，请重新登录")
                403 -> throw AcademicReadException("ACCESS_DENIED", "学校未允许本次查询，已保留之前的数据")
                429 -> throw AcademicReadException("RATE_LIMITED", "学校要求降低请求频率，请稍后再试")
                else -> throw AcademicReadException("PARTIAL_RESPONSE", "学校数据查询未完成，已保留之前的数据")
            }
            response.body
        }

    private fun normalizeRedirectUrl(rawLocation: String, baseHostUrl: String): String =
        HautAcademicUrlPolicy.resolve(rawLocation, baseHostUrl)

    private data class HttpResponseSnapshot(val statusCode: Int, val location: String?, val body: String)

    private fun request(
        url: String,
        method: String,
        cookieJar: OfficialAcademicCookieJar,
        formBody: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponseSnapshot {
        val uri = HautAcademicUrlPolicy.validate(url)
        val cookieHeaders = cookieJar.headers(uri)
        val conn = (uri.toURL().openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            cookieHeaders.forEach { (key, values) -> conn.setRequestProperty(key, values.joinToString("; ")) }
            extraHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val bytes = formBody.toByteArray(StandardCharsets.UTF_8)
                try { conn.outputStream.use { it.write(bytes) } } finally { bytes.fill(0) }
            }
            val status = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }
            cookieJar.accept(uri, headers)
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) throw AcademicReadException("SOURCE_DATA_INVALID", "学校响应超过安全读取上限")
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }.orEmpty()
            return HttpResponseSnapshot(status, conn.getHeaderField("Location"), body)
        } finally { conn.disconnect() }
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }
}

data class TimetableParseBreakdown(
    val allCandidates: List<ImportCourseCandidate>,
    val regularCount: Int,
    val practiceCount: Int,
    val unscheduledCourses: List<UnscheduledAcademicCourse> = emptyList()
)

data class UnscheduledAcademicCourse(
    val sourceId: String?, val name: String, val teacher: String?,
    val credit: BigDecimal?, val rawWeeks: String, val category: String
)

/**
 * 独立编写的正方教务 JSON 与 CSV 解析器（基于河南工业大学真实教务接口报文核验）。
 */
object HautZfsoftParser {

    /**
     * 解析正方教务 `kbList` 与 `sjkList` 格式的课表 JSON。
     */
    fun parseTimetableJson(jsonText: String): List<ImportCourseCandidate> =
        parseTimetableWithBreakdown(jsonText).allCandidates

    fun parseTimetableWithBreakdown(jsonText: String): TimetableParseBreakdown {
        val root = JSONObject(jsonText)
        val kbList = root.optJSONArray("kbList") ?: throw AcademicReadException("PAGE_CHANGED", "课表结构发生变化，已保留之前的数据")
        val sjkList = if (root.has("sjkList") && !root.isNull("sjkList")) {
            root.optJSONArray("sjkList") ?: throw AcademicReadException("PAGE_CHANGED", "实践课结构发生变化，已保留之前的数据")
        } else JSONArray()
        val result = mutableListOf<ImportCourseCandidate>()
        var regularCount = 0
        var practiceCount: Int

        for (i in 0 until kbList.length()) {
            val item = kbList.optJSONObject(i) ?: throw AcademicReadException("SOURCE_DATA_INVALID", "课表包含无效记录")
            val courseName = item.optString("kcmc").trim()
            if (courseName.isEmpty()) throw AcademicReadException("SOURCE_DATA_INVALID", "课表课程名称缺失")
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
            val weekdayInt = item.optString("xqj").toIntOrNull()?.takeIf { it in 1..7 }
                ?: throw AcademicReadException("SOURCE_DATA_INVALID", "课表星期缺失或无效，无法安排提醒")
            val weekday = DayOfWeek.of(weekdayInt)

            // 兼容 jcs ("1-2")、jcor ("1-2")、jc ("1-2节") 三种正方返回字段
            val jcsRaw = item.optString("jcs")
                .ifBlank { item.optString("jcor") }
                .ifBlank { item.optString("jc") }
                .replace("节", "")
                .trim()
            val jcParts = jcsRaw.split("-")
            val startPeriod = jcParts.firstOrNull()?.toIntOrNull()
                ?: throw AcademicReadException("SOURCE_DATA_INVALID", "课表开始节次无效")
            val endPeriod = jcParts.lastOrNull()?.toIntOrNull()
                ?: throw AcademicReadException("SOURCE_DATA_INVALID", "课表结束节次无效")
            if (jcParts.size !in 1..2 || startPeriod < 1 || endPeriod !in startPeriod..30) {
                throw AcademicReadException("SOURCE_DATA_INVALID", "课表节次范围无效")
            }

            // 周次如 "1-16周"、"1-3周(单),4-6周"、"第11周"
            val zcd = item.optString("zcd")
            val weeks = WeeksSetParser.parse(zcd)
            if (weeks.isEmpty()) throw AcademicReadException("SOURCE_DATA_INVALID", "课表周次缺失或无效，无法安排提醒")

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
            regularCount++
        }

        // 没有时间证据的实践课单独保存，绝不分配虚构节次或触发提醒。
        val unscheduled = mutableListOf<UnscheduledAcademicCourse>()
        for (i in 0 until sjkList.length()) {
            val item = sjkList.optJSONObject(i) ?: throw AcademicReadException("SOURCE_DATA_INVALID", "实践课程记录无效")
            val name = item.optString("kcmc").trim()
            if (name.isEmpty()) throw AcademicReadException("SOURCE_DATA_INVALID", "实践课程缺少名称")
            unscheduled += UnscheduledAcademicCourse(
                sourceId = item.optString("kch_id").ifBlank { item.optString("kch") }.takeIf { it.isNotBlank() },
                name = name,
                teacher = item.optString("jsxm").ifBlank { item.optString("xm") }.takeIf { it.isNotBlank() },
                credit = item.optString("xf").toBigDecimalOrNull(),
                rawWeeks = item.optString("qsjsz"),
                category = item.optString("kclb").ifBlank { "实践/集中课" }
            )
        }
        practiceCount = unscheduled.size

        return TimetableParseBreakdown(
            allCandidates = result,
            regularCount = regularCount,
            practiceCount = practiceCount,
            unscheduledCourses = unscheduled
        )
    }

    /**
     * 解析 `/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html` 返回的学期周次列表 JSON，
     * 提取第 1 周周一日期与学期总周数。
     */
    fun parseTermCalendarJson(jsonText: String): Pair<LocalDate, Int>? {
        val arr = runCatching { JSONArray(jsonText.trim()) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val firstObj = arr.optJSONObject(0) ?: return null
        val rq = firstObj.optString("rq", "").trim()
        val startIso = Regex("""(\d{4}-\d{2}-\d{2})""").find(rq)?.groupValues?.getOrNull(1) ?: return null
        val startDate = runCatching { LocalDate.parse(startIso) }.getOrNull() ?: return null
        val maxZc = (0 until arr.length()).maxOfOrNull { idx ->
            arr.optJSONObject(idx)?.optInt("zc", 0) ?: 0
        } ?: 0
        val totalWeeks = maxOf(arr.length(), maxZc).coerceIn(10, 30)
        return startDate to totalWeeks
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
        val items = root.optJSONArray("items") ?: throw AcademicReadException("PAGE_CHANGED", "教务数据列表缺失")
        val now = Instant.now()
        val result = mutableListOf<GradeAttempt>()

        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: throw AcademicReadException("SOURCE_DATA_INVALID", "教务记录格式无效")
            val kcmc = obj.optString("kcmc").trim()
            if (kcmc.isEmpty()) throw AcademicReadException("SOURCE_DATA_INVALID", "教务记录缺少课程名称")
            val kchId = obj.optString("kch_id", kcmc).trim()
            val xnm = requireAcademicYear(obj.optString("xnm"))
            val academicYear = if (xnm.contains("-")) xnm else "${xnm}-${(xnm.toIntOrNull() ?: 2025) + 1}"
            val xqm = requireTermCode(obj.optString("xqm"))
            val termNo = when (xqm) {
                "12", "2" -> 2
                else -> 1
            }
            val termId = "$academicYear-$termNo"

            val xf = obj.optString("xf", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val cjRaw = obj.optString("cj", "").trim()
            if (cjRaw.isBlank()) throw AcademicReadException("SOURCE_DATA_INVALID", "学校成绩记录未提供结果，已保留之前的数据")
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
                else -> cjRaw in setOf("通过", "合格", "优秀", "良好", "中等", "及格", "优", "良", "中")
            }

            result.add(
                GradeAttempt(
                    id = "jw_" + stableAcademicId("zf-modern", "haut", termId,
                        obj.optString("cjbh").ifBlank { obj.optString("cj_id") }.ifBlank {
                            listOf(kchId, obj.optString("jxb_id"), ksxz, obj.optString("ksnd"), obj.optString("ksxq")).joinToString("|")
                        }),
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
        requireUniqueIds(result.map { it.id })
        return result
    }

    private fun requireAcademicYear(value: String): String {
        val normalized = value.trim()
        val year = normalized.take(4).toIntOrNull()
        if (year == null || year !in 1900..2200 ||
            normalized != year.toString() && normalized != "$year-${year + 1}") {
            throw AcademicReadException("SOURCE_DATA_INVALID", "学校记录缺少有效学年，已保留之前的数据")
        }
        return normalized
    }

    private fun requireTermCode(value: String): String = value.trim().also {
        if (it !in setOf("3", "1", "12", "2")) throw AcademicReadException(
            "SOURCE_DATA_INVALID", "学校学期编码无法识别，已保留之前的数据"
        )
    }

    private fun requireUniqueIds(ids: List<String>) {
        if (ids.toSet().size != ids.size) throw AcademicReadException(
            "AMBIGUOUS_IDENTITY", "学校记录身份存在歧义，已保留之前的数据，请核对官方记录"
        )
    }

    private val EXAM_TIME_REGEX = Regex("""(\d{4}-\d{2}-\d{2})\s*\((\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})\)""")

    /**
     * 解析正方教务 `/jwglxt/kwgl/kscx_cxXsksxxIndex.html` 返回的考试信息 JSON (`items`)。
     * 自动识别期初补缓考、期中随堂/分散考试、期末集中考试，并提取考场、座位号与精确日期时间。
     */
    fun parseExamsJson(jsonText: String): List<ExamScheduleItem> {
        val root = JSONObject(jsonText)
        val items = root.optJSONArray("items") ?: throw AcademicReadException("PAGE_CHANGED", "教务数据列表缺失")
        val now = Instant.now()
        val result = mutableListOf<ExamScheduleItem>()

        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: throw AcademicReadException("SOURCE_DATA_INVALID", "教务记录格式无效")
            val kcmc = obj.optString("kcmc").trim()
            if (kcmc.isEmpty()) throw AcademicReadException("SOURCE_DATA_INVALID", "教务记录缺少课程名称")
            val kch = obj.optString("kch", kcmc).trim()
            val xnm = requireAcademicYear(obj.optString("xnm")).trim()
            val xnmc = obj.optString("xnmc", "").trim()
            val academicYear = when {
                xnmc.contains("-") -> xnmc
                xnm.contains("-") -> xnm
                else -> "${xnm}-${(xnm.toIntOrNull() ?: 2025) + 1}"
            }
            val xqm = requireTermCode(obj.optString("xqm")).trim()
            val xqmmc = obj.optString("xqmmc", "").trim()
            val termNo = when {
                xqmmc == "2" || xqm == "12" || xqm == "2" -> 2
                else -> 1
            }
            val termId = "$academicYear-$termNo"

            val ksmc = obj.optString("ksmc", "课程考试").trim()
            val sjbh = obj.optString("sjbh", "").trim()
            val stageLabel = when {
                ksmc.contains("补") || ksmc.contains("缓") -> "期初补缓考"
                ksmc.contains("分散") || sjbh.contains("随堂") -> "期中/随堂考"
                ksmc.contains("集中") || sjbh.contains("统一") -> "期末集中考"
                else -> "课程考试"
            }

            val ksfs = obj.optString("ksfs", "考试").trim().ifBlank { "考试" }
            val kssj = obj.optString("kssj", "").trim()
            val match = EXAM_TIME_REGEX.find(kssj)
            val parsedDate = match?.groupValues?.getOrNull(1)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            val parsedStart = match?.groupValues?.getOrNull(2)?.let { raw ->
                val norm = if (raw.indexOf(':') == 1) "0$raw" else raw
                runCatching { LocalTime.parse(norm) }.getOrNull()
            }
            val parsedEnd = match?.groupValues?.getOrNull(3)?.let { raw ->
                val norm = if (raw.indexOf(':') == 1) "0$raw" else raw
                runCatching { LocalTime.parse(norm) }.getOrNull()
            }

            val cdmc = obj.optString("cdmc", "待定考场").trim().ifBlank { "待定考场" }
            val zwh = obj.optString("zwh", "").trim().takeIf { it.isNotBlank() && it != "无" }
            val xqmc = obj.optString("xqmc").trim()
            val isRetake = obj.optString("cxbj", "").trim() == "是"

            result.add(
                ExamScheduleItem(
                    id = "exam_" + stableAcademicId("zf-modern", "haut", termId,
                        obj.optString("ksxx_id").ifBlank { obj.optString("ks_id") }.ifBlank {
                            listOf(kch, obj.optString("jxb_id"), ksmc, sjbh, ksfs).joinToString("|")
                        }),
                    termId = termId,
                    courseCode = kch,
                    courseName = kcmc,
                    examBatchName = ksmc,
                    examStageLabel = stageLabel,
                    examFormat = ksfs,
                    rawTimeText = kssj.ifBlank { "时间待定" },
                    examDate = parsedDate,
                    startTime = parsedStart,
                    endTime = parsedEnd,
                    rawRoom = cdmc,
                    seatNumber = zwh,
                    campusName = xqmc,
                    isRetake = isRetake,
                    syncedAt = now
                )
            )
        }

        requireUniqueIds(result.map { it.id })
        return result.sortedWith(
            compareByDescending<ExamScheduleItem> { it.termId }
                .thenBy { it.examDate ?: LocalDate.of(2099, 12, 31) }
                .thenBy { it.startTime ?: LocalTime.MIN }
        )
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
                require(startPeriod in 1..24 && endPeriod in startPeriod..24) { "课程节次超出 1–24 范围" }
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
