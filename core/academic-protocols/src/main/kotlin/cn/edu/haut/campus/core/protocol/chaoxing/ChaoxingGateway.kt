package cn.edu.haut.campus.core.protocol.chaoxing

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

/** 超星智慧教务的只读入口配置。 */
data class ChaoxingConfig(
    val loginPath: String = "/academic/login",
    val termsPath: String = "/academic/api/terms",
    val timetablePath: String = "/academic/course/table",
    val gradePath: String = "/academic/score/table",
    val examPath: String = "/academic/exam/table",
    val userField: String = "uname",
    val passwordField: String = "pwd",
    val timetableTableId: String = "courseTable",
    val gradeTableId: String = "gradeTable",
    val examTableId: String = "examTable"
)

/**
 * 超星智慧教务适配器（只读）。
 *
 * UNVERIFIED：按超星教务常见的"表单登录 + JSON 学期 + 表格课表/成绩/考试"形态推导，
 * 只用合成夹具验证；未在任何真实学校核对过路径与字段名。
 *
 * 与学习通（超星统一认证）严格隔离：会话来源不允许是 chaoxing.com 及其子域，
 * 调用方必须为教务账号建立独立 Cookie 容器与独立 session。
 */
class ChaoxingGateway(
    private val config: ChaoxingConfig = ChaoxingConfig()
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "chaoxing-academic-readonly",
        protocol = AcademicProtocolFamily.CHAOXING_ACADEMIC,
        displayName = "超星智慧教务（只读，UNVERIFIED）",
        authMode = AuthMode.FORM_CREDENTIALS,
        readCapabilities = GatewayCapability.readOnly(),
        writeCapabilities = emptySet(),
        requiresJavaScript = false,
        notes = "UNVERIFIED：与学习通账号隔离的只读适配器；未提供任何写能力"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        if (ChaoxingIsolation.isStudyAccountHost(session.origin)) {
            return AuthOutcome.Rejected(ChaoxingIsolation.requirementMessage())
        }
        val provided = credentials
            ?: return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "超星教务需要学号与密码")
        return provided.use { secret ->
            val loginUrl = url(session, config.loginPath)
            val page = session.send(GatewayRequest(HttpMethod.GET, loginUrl, purpose = RequestPurpose.AUTH))
            if (!page.isSuccess) return@use AuthOutcome.Rejected("超星登录页不可用")
            val form = LinkedHashMap<String, String>(ChaoxingHtml.hiddenFields(page.body))
            form[config.userField] = secret.studentId
            form[config.passwordField] = secret.password
            val action = ChaoxingHtml.formAction(page.body)
            val target = if (action == null) {
                loginUrl
            } else {
                session.urlPolicy.resolve(loginUrl, action, RequestPurpose.AUTH)
            }
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
                landing.isUnauthorized -> AuthOutcome.Rejected("超星教务拒绝了本次认证")
                !landing.isSuccess -> AuthOutcome.Rejected("超星认证未正常结束")
                ChaoxingHtml.looksLikeLoginPage(landing.body) -> AuthOutcome.Rejected("学号或密码不正确")
                else -> AuthOutcome.Authenticated
            }
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        requireIsolation(session)
        val body = fetchPage(session, url(session, config.termsPath)).body
        val items = parseJson(body).obj("result")?.arr("list")
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "超星学期 JSON 缺少 result.list")
        val terms = items.mapNotNull { item ->
            val id = item.str("code")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = item.str("title")?.takeIf { it.isNotBlank() } ?: id
            RemoteTerm(
                sourceId = id,
                label = label,
                academicYear = ChaoxingTimeParser.academicYear(label),
                ordinal = ChaoxingTimeParser.termOrdinal(label),
                startDate = ChaoxingTimeParser.date(item.str("startDate")),
                endDate = ChaoxingTimeParser.date(item.str("endDate"))
            )
        }
        if (terms.isEmpty()) {
            return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "超星学期接口没有返回任何学期"
            )
        }
        AcademicResult.Content(terms.distinctBy { it.sourceId }, provenance(session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = guarded {
        requireIsolation(session)
        val url = url(session, config.timetablePath, "termId" to term.sourceId)
        val tables = ChaoxingHtml.tables(fetchPage(session, url).body)
        val table = tables.firstOrNull { it.id == config.timetableTableId }
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "超星课表页没有可识别的表格")
        if (!table.header.containsAll(listOf("课程名称", "上课时间"))) throw TransportFailure(AcademicError.PAGE_CHANGED, "课表缺少必需表头")
        val courses = groupCourses(table)
        if (courses.any { it.meetings.isEmpty() || it.meetings.any { meeting -> meeting.weeks.isEmpty() } })
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "课表含无法确认的时段或周次")
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty("超星教务在该学期没有返回课程", authoritative = true)
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
        requireIsolation(session)
        val paged = PagedJsonReader.readAll { page ->
            val url = if (term == null) {
                url(session, config.gradePath, "page" to page.toString())
            } else {
                url(session, config.gradePath, "termId" to term.sourceId, "page" to page.toString())
            }
            val body = fetchPage(session, url).body
            val table = findTable(body, config.gradeTableId)
            val pager = ChaoxingHtml.pager(body)
            PagedJsonReader.Page(
                items = table.rows.map { row -> ChaoxingHtml.row(table.header, row) },
                total = null,
                // 既没有总条数也没有每页条数时按单页处理，避免无限翻页。
                totalPages = if (pager.pageSize == null) 1 else null,
                currentPage = pager.current ?: page,
                pageSize = pager.pageSize
            )
        }
        val grades = paged.items.mapNotNull { item ->
            val name = item.str("课程名称")?.takeIf { it.isNotBlank() } ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "数据行缺少有效课程或时段")
            val code = item.str("课程编号")
            val serial = item.str("序号")
            RemoteGrade(
                sourceId = listOfNotNull(code, serial).joinToString("#").ifBlank { name },
                courseName = name,
                credits = ChaoxingTimeParser.decimalOf(item.str("学分")),
                scoreText = item.str("成绩"),
                gradePoint = ChaoxingTimeParser.decimalOf(item.str("绩点")),
                termSourceId = term?.sourceId,
                category = item.str("修读性质")
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
        requireIsolation(session)
        val url = if (term == null) {
            url(session, config.examPath)
        } else {
            url(session, config.examPath, "termId" to term.sourceId)
        }
        val table = findTable(fetchPage(session, url).body, config.examTableId)
        val exams = table.rows.mapNotNull { row ->
            val name = table.cell(row, "课程名称") ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "数据行缺少有效课程或时段")
            val date = ChaoxingTimeParser.date(table.cell(row, "考试日期"))
            RemoteExam(
                sourceId = listOfNotNull(table.cell(row, "课程编号"), table.cell(row, "序号"), date?.toString())
                    .joinToString("#")
                    .ifBlank { name },
                courseName = name,
                date = date,
                startTime = ChaoxingTimeParser.clock(table.cell(row, "开始时间")),
                endTime = ChaoxingTimeParser.clock(table.cell(row, "结束时间")),
                location = table.cell(row, "考场"),
                seat = table.cell(row, "座位号"),
                kind = table.cell(row, "考核方式")
            )
        }
        if (exams.isEmpty()) {
            return@guarded AcademicResult.Empty("超星教务没有公布该学期考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    private fun groupCourses(table: ChaoxingHtml.Table): List<RemoteCourse> {
        val grouped = LinkedHashMap<String, MutableList<RemoteMeeting>>()
        val names = HashMap<String, String>()
        val teachers = HashMap<String, String>()
        for (row in table.rows) {
            val name = table.cell(row, "课程名称") ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "数据行缺少有效课程或时段")
            val code = table.cell(row, "课程编号") ?: name
            names[code] = name
            table.cell(row, "教师")?.let { teachers[code] = it }
            val meetings = grouped.getOrPut(code) { ArrayList() }
            val slots = ChaoxingTimeParser.slots(table.cell(row, "上课时间"))
            if (slots.isEmpty()) throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "课表时段无法解析")
            for (slot in slots) {
                meetings += RemoteMeeting(
                    weekday = slot.weekday,
                    startSection = slot.startSection,
                    endSection = slot.endSection,
                    weeks = slot.weeks,
                    location = table.cell(row, "上课地点"),
                    teacher = table.cell(row, "教师")
                )
            }
        }
        return grouped.map { (code, meetings) ->
            RemoteCourse(
                sourceId = code,
                name = names.getValue(code),
                teacher = teachers[code],
                meetings = meetings.distinctBy { meeting ->
                    listOf(meeting.weekday, meeting.startSection, meeting.endSection, meeting.weeks, meeting.location)
                }
            )
        }
    }

    private fun requireIsolation(session: GatewaySession) {
        if (ChaoxingIsolation.isStudyAccountHost(session.origin)) {
            throw TransportFailure(AcademicError.PERMISSION_REVOKED, ChaoxingIsolation.requirementMessage())
        }
    }

    private fun parseJson(text: String): JsonValue = try {
        Json.parse(text)
    } catch (_: Exception) {
        throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "超星教务返回的不是合法 JSON")
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
        if (response.isUnauthorized || ChaoxingHtml.looksLikeLoginPage(response.body)) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "超星教务会话已过期，请重新登录")
        }
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "超星教务返回了失败状态")
        }
        return response
    }

    private fun findTable(html: String, id: String): ChaoxingHtml.Table {
        val tables = ChaoxingHtml.tables(html)
        val table = tables.firstOrNull { it.id == id }
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "超星页面结构变化：未找到表格 " + id)
        val required = listOf("课程名称")
        if (!table.header.containsAll(required)) throw TransportFailure(AcademicError.PAGE_CHANGED, "缺少必需表头")
        return table
    }

    private fun url(session: GatewaySession, path: String, vararg query: Pair<String, String>): String {
        val base = session.origin.trimEnd('/') + path
        if (query.isEmpty()) return base
        return base + "?" + query.joinToString("&") { encode(it.first) + "=" + encode(it.second) }
    }

    private fun encodeForm(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun provenance(session: GatewaySession) = DataProvenance(
        providerId = descriptor.id,
        schoolId = SchoolId(session.schoolId),
        accountId = AccountId(session.accountId),
        sourceSystem = "chaoxing-academic"
    )

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        if (failure.error == AcademicError.UNTRUSTED_REDIRECT) throw failure
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }
}
