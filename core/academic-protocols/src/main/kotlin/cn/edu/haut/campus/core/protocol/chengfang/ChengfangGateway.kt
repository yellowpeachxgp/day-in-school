package cn.edu.haut.campus.core.protocol.chengfang

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthMode
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.GatewayCapability
import cn.edu.haut.campus.core.protocol.GatewayDescriptor
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewayResponse
import cn.edu.haut.campus.core.protocol.GatewaySession
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
import cn.edu.haut.campus.core.protocol.GradeSnapshot
import cn.edu.haut.campus.core.protocol.ExamSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 乘方教务的只读入口配置：URL 全部由学校配置提供，路径与字段名都可覆盖，
 * 适配器本身不假设任何学校的固定地址。
 */
data class ChengfangConfig(
    val loginPath: String = "/academic/login.jsp",
    val termPath: String = "/academic/term/list.jsp",
    val timetablePath: String = "/academic/course/table.jsp",
    val gradePath: String = "/academic/score/list.jsp",
    val examPath: String = "/academic/exam/list.jsp",
    val termTableId: String = "termList",
    val timetableTableId: String = "courseTable",
    val gradeTableId: String = "gradeTable",
    val examTableId: String = "examTable",
    val userField: String = "userName",
    val passwordField: String = "userPassword",
    val roleField: String = "userRole",
    val roleStudent: String = "student",
    val captchaField: String = "checkCode"
)

/**
 * 乘方教务适配器（只读）。
 *
 * UNVERIFIED：本实现按乘方教务常见的"表单登录 + 表格页面"公开形态推导，
 * 只用合成夹具验证，未在任何真实学校核对过字段名、路径或分页文案。
 * 写能力（选课、退课、成绩复核）一律不实现，descriptor 的 writeCapabilities 恒为空。
 */
class ChengfangGateway(
    private val config: ChengfangConfig = ChengfangConfig()
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "chengfang-readonly",
        protocol = AcademicProtocolFamily.CHENGFANG,
        displayName = "乘方教务（只读，UNVERIFIED）",
        authMode = AuthMode.FORM_CREDENTIALS,
        readCapabilities = GatewayCapability.readOnly(),
        writeCapabilities = emptySet(),
        requiresJavaScript = false,
        notes = "UNVERIFIED：按公开表格形态推导的只读适配器；未提供任何写能力"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        val provided = credentials
            ?: return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "乘方教务需要学号与密码")
        return provided.use { secret ->
            val loginUrl = url(session, config.loginPath)
            val page = session.send(GatewayRequest(HttpMethod.GET, loginUrl, purpose = RequestPurpose.AUTH))
            if (!page.isSuccess) return@use AuthOutcome.Rejected("乘方登录页不可用")
            val hidden = ChengfangHtml.hiddenFields(page.body)
            if (ChengfangHtml.hasInput(page.body, config.captchaField)) {
                return@use AuthOutcome.RequiresUserAction(
                    UserAction.COMPLETE_CAPTCHA,
                    "乘方登录页要求图形验证码，请在官方页面完成"
                )
            }
            val form = LinkedHashMap<String, String>(hidden)
            form[config.userField] = secret.studentId
            form[config.passwordField] = secret.password
            form[config.roleField] = config.roleStudent
            val posted = session.send(
                GatewayRequest(
                    method = HttpMethod.POST,
                    url = loginUrl,
                    body = encodeForm(form),
                    purpose = RequestPurpose.AUTH,
                    contentType = "application/x-www-form-urlencoded"
                )
            )
            val landing = followLanding(session, loginUrl, posted)
            when {
                landing.isUnauthorized -> AuthOutcome.Rejected("乘方教务拒绝了本次认证")
                !landing.isSuccess -> AuthOutcome.Rejected("乘方认证未正常结束")
                ChengfangHtml.looksLikeLoginPage(landing.body) -> AuthOutcome.Rejected("学号或密码不正确")
                else -> AuthOutcome.Authenticated
            }
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        val response = fetchPage(session, url(session, config.termPath))
        val table = findTable(response.body, config.termTableId)
        val terms = table.rows.mapNotNull { row ->
            val id = table.cell(row, "学期代码") ?: return@mapNotNull null
            val label = table.cell(row, "学期名称") ?: id
            RemoteTerm(
                sourceId = id,
                label = label,
                academicYear = ChengfangParsers.academicYear(label),
                ordinal = ChengfangParsers.termOrdinal(label),
                startDate = ChengfangParsers.date(table.cell(row, "开始日期")),
                endDate = ChengfangParsers.date(table.cell(row, "结束日期"))
            )
        }.distinctBy { it.sourceId }
        if (terms.isEmpty()) {
            return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "乘方学期页没有可识别的学期行"
            )
        }
        AcademicResult.Content(terms, provenance(session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = guarded {
        val url = url(session, config.timetablePath, "termId" to term.sourceId)
        val response = fetchPage(session, url)
        val table = findTable(response.body, config.timetableTableId)
        val courses = groupCourses(table)
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty("乘方教务在该学期没有返回课程", authoritative = true)
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
                url(session, config.gradePath, "page" to page.toString())
            } else {
                url(session, config.gradePath, "termId" to term.sourceId, "page" to page.toString())
            }
            val body = fetchPage(session, url).body
            val table = findTable(body, config.gradeTableId)
            val pager = ChengfangHtml.pager(body)
            val singlePage = pager.total == null && pager.totalPages == null
            PagedJsonReader.Page(
                items = table.rows.map { row -> ChengfangHtml.row(table.header, row) },
                total = pager.total,
                // 页面没有分页条时按单页处理，避免拿着不存在的第 2 页无限翻页。
                totalPages = if (singlePage) 1 else pager.totalPages,
                currentPage = pager.current ?: page,
                pageSize = null
            )
        }
        val grades = paged.items.mapNotNull { item ->
            val name = item.str("课程名称")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val code = item.str("课程代码")
            val serial = item.str("序号")
            RemoteGrade(
                sourceId = listOfNotNull(code, serial).joinToString("#").ifBlank { name },
                courseName = name,
                credits = ChengfangParsers.decimal(item.str("学分")),
                scoreText = item.str("成绩"),
                gradePoint = ChengfangParsers.decimal(item.str("绩点")),
                termSourceId = term?.sourceId,
                category = item.str("考核方式")
            )
        }
        AcademicResult.Content(
            value = GradeSnapshot(
                termSourceId = term?.sourceId,
                grades = grades,
                completeness = SnapshotCompleteness.COMPLETE
            ),
            source = provenance(session)
        )
    }

    override suspend fun exams(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<ExamSnapshot> = guarded {
        val url = if (term == null) {
            url(session, config.examPath)
        } else {
            url(session, config.examPath, "termId" to term.sourceId)
        }
        val response = fetchPage(session, url)
        val table = findTable(response.body, config.examTableId)
        val exams = table.rows.mapNotNull { row ->
            val name = table.cell(row, "课程名称") ?: return@mapNotNull null
            val time = ChengfangParsers.timeRange(table.cell(row, "考试时间"))
            val code = table.cell(row, "课程代码")
            val serial = table.cell(row, "序号")
            val date = ChengfangParsers.date(table.cell(row, "考试日期"))
            RemoteExam(
                sourceId = listOfNotNull(code, serial, date?.toString()).joinToString("#").ifBlank { name },
                courseName = name,
                date = date,
                startTime = time?.first,
                endTime = time?.second,
                location = table.cell(row, "考试地点"),
                seat = table.cell(row, "座位号"),
                kind = table.cell(row, "考试形式")
            )
        }
        if (exams.isEmpty()) {
            return@guarded AcademicResult.Empty("乘方教务没有公布该学期考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    private fun groupCourses(table: ChengfangHtml.Table): List<RemoteCourse> {
        val grouped = LinkedHashMap<String, MutableList<RemoteMeeting>>()
        val names = HashMap<String, String>()
        val credits = HashMap<String, java.math.BigDecimal>()
        val teachers = HashMap<String, String>()
        table.rows.forEach { row ->
            val name = table.cell(row, "课程名称") ?: return@forEach
            val code = table.cell(row, "课程代码") ?: name
            val weekday = ChengfangParsers.weekday(table.cell(row, "星期")) ?: return@forEach
            val sections = ChengfangParsers.sections(table.cell(row, "节次")) ?: return@forEach
            names[code] = name
            ChengfangParsers.decimal(table.cell(row, "学分"))?.let { credits[code] = it }
            table.cell(row, "任课教师")?.let { teachers[code] = it }
            grouped.getOrPut(code) { ArrayList() } += RemoteMeeting(
                weekday = weekday,
                startSection = sections.first,
                endSection = sections.last,
                weeks = ChengfangParsers.weeks(table.cell(row, "周次")),
                location = table.cell(row, "上课地点"),
                teacher = table.cell(row, "任课教师")
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

    private suspend fun followLanding(
        session: GatewaySession,
        loginUrl: String,
        posted: GatewayResponse
    ): GatewayResponse {
        if (!posted.isRedirect) return posted
        val location = posted.header("Location") ?: return posted
        val next = session.urlPolicy.resolve(loginUrl, location, RequestPurpose.READ)
        return session.fetch(GatewayRequest(HttpMethod.GET, next))
    }

    private suspend fun fetchPage(session: GatewaySession, url: String): GatewayResponse {
        val response = session.fetch(GatewayRequest(HttpMethod.GET, url))
        // 302 回登录页与 401 都是会话失效；登录页判定用"仍然出现密码输入框"作为证据。
        if (response.isUnauthorized || ChengfangHtml.looksLikeLoginPage(response.body)) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "乘方教务会话已过期，请重新登录")
        }
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "乘方教务返回了失败状态")
        }
        return response
    }

    private fun findTable(html: String, id: String): ChengfangHtml.Table {
        val tables = ChengfangHtml.tables(html)
        return tables.firstOrNull { it.id == id }
            ?: tables.firstOrNull { it.header.isNotEmpty() }
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "乘方页面结构变化：未找到表格 " + id)
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
        sourceSystem = "chengfang-html"
    )

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        // 越权跳转是安全边界，必须原样上抛给调用方，不能降级成普通数据错误。
        if (failure.error == AcademicError.UNTRUSTED_REDIRECT) throw failure
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }
}
