package cn.edu.haut.campus.core.protocol.eams

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthMode
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.ExamSnapshot
import cn.edu.haut.campus.core.protocol.GatewayCapability
import cn.edu.haut.campus.core.protocol.GatewayDescriptor
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewayResponse
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GradeSnapshot
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.PagedJsonReader
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.RemoteCourse
import cn.edu.haut.campus.core.protocol.RemoteExam
import cn.edu.haut.campus.core.protocol.RemoteGrade
import cn.edu.haut.campus.core.protocol.RemoteMeeting
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TimetableSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 树维 EAMS 的只读入口配置；登录目标地址由页面动态给出，因此这里只配置入口页。 */
data class EamsConfig(
    val loginPath: String = "/eams/login",
    val termJsonPath: String = "/eams/term/list.json",
    val termHtmlPath: String = "/eams/term/list.html",
    val timetablePath: String = "/eams/timetable",
    val gradeJsonPath: String = "/eams/grade/list.json",
    val examJsonPath: String = "/eams/exam/list.json",
    val trainingPlanPath: String = "/eams/training/plan",
    val userField: String = "username",
    val passwordField: String = "password",
    val timetableTableId: String = "timetable"
)

/**
 * 树维 EAMS 适配器（只读）。
 *
 * UNVERIFIED：按 EAMS 常见的"动态登录 action + JSON 接口 + 表格页面"形态推导，
 * 只用合成夹具验证；未在任何真实学校核对过接口路径与字段名。
 * 培养方案只做读取解析，不接入任何 UI 流程，也不做学分核算。
 */
class EamsGateway(
    private val config: EamsConfig = EamsConfig()
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "eams-readonly",
        protocol = AcademicProtocolFamily.EAMS,
        displayName = "树维 EAMS（只读，UNVERIFIED）",
        authMode = AuthMode.FORM_CREDENTIALS,
        readCapabilities = GatewayCapability.readOnly(),
        writeCapabilities = emptySet(),
        requiresJavaScript = false,
        notes = "UNVERIFIED：动态登录 action + JSON 接口的只读适配器；未提供任何写能力"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        val provided = credentials
            ?: return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "EAMS 需要学号与密码")
        return provided.use { secret ->
            val loginUrl = url(session, config.loginPath)
            val page = session.send(GatewayRequest(HttpMethod.GET, loginUrl, purpose = RequestPurpose.AUTH))
            if (!page.isSuccess) return@use AuthOutcome.Rejected("EAMS 登录页不可用")
            // EAMS 的登录目标带一次性随机参数，必须从页面里取，不能退化成固定地址。
            val action = EamsDocument.formAction(page.body)
                ?: return@use AuthOutcome.RequiresUserAction(
                    UserAction.OPEN_OFFICIAL_WEB,
                    "EAMS 登录页没有可识别的表单目标，请在官方页面登录"
                )
            val target = session.urlPolicy.resolve(loginUrl, action, RequestPurpose.AUTH)
            val form = LinkedHashMap<String, String>(EamsDocument.hiddenFields(page.body))
            form[config.userField] = secret.studentId
            form[config.passwordField] = secret.password
            val posted = session.send(
                GatewayRequest(
                    method = HttpMethod.POST,
                    url = target,
                    body = encodeForm(form),
                    purpose = RequestPurpose.AUTH,
                    contentType = "application/x-www-form-urlencoded"
                )
            )
            val landing = followLanding(session, target, posted)
            when {
                landing.isUnauthorized -> AuthOutcome.Rejected("EAMS 拒绝了本次认证")
                !landing.isSuccess -> AuthOutcome.Rejected("EAMS 认证未正常结束")
                EamsDocument.looksLikeLoginPage(landing.body) -> AuthOutcome.Rejected("学号或密码不正确")
                else -> AuthOutcome.Authenticated
            }
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        val jsonBody = fetchPage(session, url(session, config.termJsonPath)).body
        val fromJson = runCatching { parseTermsJson(jsonBody) }.getOrNull()
        // 学期接口有的部署返回 JSON、有的返回 HTML 片段：JSON 不可用时才改读 HTML。
        val terms = fromJson ?: parseTermsHtml(fetchPage(session, url(session, config.termHtmlPath)).body)
        if (terms.isEmpty()) {
            return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "EAMS 学期数据为空，无法确认学期列表"
            )
        }
        AcademicResult.Content(terms.distinctBy { it.sourceId }, provenance(session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = guarded {
        val url = url(session, config.timetablePath, "termId" to term.sourceId)
        val tables = EamsDocument.tables(fetchPage(session, url).body)
        val table = tables.firstOrNull { it.id == config.timetableTableId }
            ?: tables.firstOrNull { it.header.isNotEmpty() }
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "EAMS 课表页没有可识别的表格")
        val courses = groupCourses(table)
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty("EAMS 在该学期没有返回课程", authoritative = true)
        }
        AcademicResult.Content(
            value = TimetableSnapshot(term.sourceId, courses, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    override suspend fun grades(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<GradeSnapshot> = guarded {
        val paged = PagedJsonReader.readAll { page ->
            val url = if (term == null) {
                url(session, config.gradeJsonPath, "page" to page.toString())
            } else {
                url(session, config.gradeJsonPath, "termId" to term.sourceId, "page" to page.toString())
            }
            val data = parseJson(fetchPage(session, url).body).obj("data")
                ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "EAMS 成绩 JSON 缺少 data 字段")
            val total = data.obj("total")?.intOrNull()
            PagedJsonReader.Page(
                items = data.arr("records").orEmpty(),
                total = total,
                // 没有总条数时按单页处理，避免拿着不存在的下一页无限翻页。
                totalPages = if (total == null) 1 else null,
                currentPage = data.obj("pageNo")?.intOrNull() ?: page,
                pageSize = data.obj("pageSize")?.intOrNull()
            )
        }
        val grades = paged.items.mapNotNull { item ->
            val name = item.str("courseName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemoteGrade(
                sourceId = item.str("id")?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(term?.sourceId, item.str("courseCode")).joinToString("#").ifBlank { name },
                courseName = name,
                credits = EamsParsers.decimal(item.str("credit")),
                scoreText = item.str("score"),
                gradePoint = EamsParsers.decimal(item.str("point")),
                termSourceId = term?.sourceId,
                category = item.str("category")
            )
        }
        AcademicResult.Content(
            value = GradeSnapshot(term?.sourceId, grades, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    override suspend fun exams(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<ExamSnapshot> = guarded {
        val url = if (term == null) {
            url(session, config.examJsonPath)
        } else {
            url(session, config.examJsonPath, "termId" to term.sourceId)
        }
        val items = parseJson(fetchPage(session, url).body).obj("data")?.arr("items").orEmpty()
        val exams = items.mapNotNull { item ->
            val name = item.str("courseName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemoteExam(
                sourceId = item.str("id")?.takeIf { it.isNotBlank() } ?: name,
                courseName = name,
                date = EamsParsers.date(item.str("date")),
                startTime = EamsParsers.clock(item.str("startTime")),
                endTime = EamsParsers.clock(item.str("endTime")),
                location = item.str("location"),
                seat = item.str("seat"),
                kind = item.str("kind")
            )
        }
        if (exams.isEmpty()) {
            return@guarded AcademicResult.Empty("EAMS 没有公布该学期考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    /**
     * 培养方案只读解析：返回页面能直接读到的条目，不进入 UI、不做学分核算。
     *
     * UNVERIFIED：条目列名按合成夹具推导，真实部署可能用"课程名称/学分"之外的表头。
     */
    suspend fun trainingPlan(
        session: GatewaySession,
        programId: String
    ): AcademicResult<EamsTrainingPlan> = guarded {
        val url = url(session, config.trainingPlanPath, "programId" to programId)
        val plan = EamsTrainingPlanParser.parse(programId, fetchPage(session, url).body)
        if (plan.entries.isEmpty()) {
            return@guarded AcademicResult.Empty("培养方案里没有可识别的课程要求", authoritative = true)
        }
        AcademicResult.Content(plan, provenance(session, sourceSystem = "eams-training-plan"))
    }

    private fun parseTermsJson(text: String): List<RemoteTerm> {
        val items = Json.parse(text).obj("data")?.arr("terms")
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "EAMS 学期 JSON 缺少 data.terms")
        return items.map { item ->
            val label = item.str("name").orEmpty().ifBlank { item.str("id").orEmpty() }
            RemoteTerm(
                sourceId = item.str("id").orEmpty(),
                label = label,
                academicYear = EamsParsers.academicYear(label),
                ordinal = EamsParsers.termOrdinal(label),
                startDate = EamsParsers.date(item.str("startDate")),
                endDate = EamsParsers.date(item.str("endDate"))
            )
        }.filter { it.sourceId.isNotBlank() }
    }

    private fun parseTermsHtml(html: String): List<RemoteTerm> {
        val tables = EamsDocument.tables(html)
        val table = tables.firstOrNull { it.id == "termTable" } ?: tables.firstOrNull { it.header.isNotEmpty() }
            ?: return emptyList()
        return table.rows.mapNotNull { row ->
            val id = table.cell(row, "学期代码") ?: return@mapNotNull null
            val label = table.cell(row, "学期名称") ?: id
            RemoteTerm(
                sourceId = id,
                label = label,
                academicYear = EamsParsers.academicYear(label),
                ordinal = EamsParsers.termOrdinal(label),
                startDate = EamsParsers.date(table.cell(row, "开始日期")),
                endDate = EamsParsers.date(table.cell(row, "结束日期"))
            )
        }
    }

    private fun groupCourses(table: EamsDocument.Table): List<RemoteCourse> {
        val grouped = LinkedHashMap<String, MutableList<RemoteMeeting>>()
        val names = HashMap<String, String>()
        val credits = HashMap<String, java.math.BigDecimal>()
        val teachers = HashMap<String, String>()
        for (row in table.rows) {
            val name = table.cell(row, "课程名") ?: continue
            val code = table.cell(row, "课程号") ?: name
            val weekday = EamsParsers.weekday(table.cell(row, "星期")) ?: continue
            val sections = EamsParsers.sections(table.cell(row, "节次")) ?: continue
            names[code] = name
            EamsParsers.decimal(table.cell(row, "学分"))?.let { credits[code] = it }
            table.cell(row, "教师")?.let { teachers[code] = it }
            grouped.getOrPut(code) { ArrayList() } += RemoteMeeting(
                weekday = weekday,
                startSection = sections.first,
                endSection = sections.last,
                weeks = EamsParsers.weeks(table.cell(row, "周次")),
                location = table.cell(row, "地点"),
                teacher = table.cell(row, "教师")
            )
        }
        return grouped.map { (code, meetings) ->
            RemoteCourse(
                sourceId = code,
                name = names.getValue(code),
                credits = credits[code],
                teacher = teachers[code],
                meetings = meetings.distinctBy { meeting ->
                    listOf(meeting.weekday, meeting.startSection, meeting.endSection, meeting.weeks, meeting.location)
                }
            )
        }
    }

    private fun parseJson(text: String): JsonValue = try {
        Json.parse(text)
    } catch (_: Exception) {
        throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "EAMS 返回的不是合法 JSON")
    }

    private suspend fun followLanding(
        session: GatewaySession,
        postUrl: String,
        posted: GatewayResponse
    ): GatewayResponse {
        if (!posted.isRedirect) return posted
        val location = posted.header("Location") ?: return posted
        val next = session.urlPolicy.resolve(postUrl, location, RequestPurpose.READ)
        return session.fetch(GatewayRequest(HttpMethod.GET, next))
    }

    private suspend fun fetchPage(session: GatewaySession, url: String): GatewayResponse {
        val response = session.fetch(GatewayRequest(HttpMethod.GET, url))
        if (response.isUnauthorized || EamsDocument.looksLikeLoginPage(response.body)) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "EAMS 会话已过期，请重新登录")
        }
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "EAMS 返回了失败状态")
        }
        return response
    }

    private fun url(session: GatewaySession, path: String, vararg query: Pair<String, String>): String {
        val base = session.origin.trimEnd('/') + path
        if (query.isEmpty()) return base
        return base + "?" + query.joinToString("&") { encode(it.first) + "=" + encode(it.second) }
    }

    private fun encodeForm(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun provenance(session: GatewaySession, sourceSystem: String = "eams-json-html") = DataProvenance(
        providerId = descriptor.id,
        schoolId = SchoolId(session.schoolId),
        accountId = AccountId(session.accountId),
        sourceSystem = sourceSystem
    )

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        if (failure.error == AcademicError.UNTRUSTED_REDIRECT) throw failure
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }
}
