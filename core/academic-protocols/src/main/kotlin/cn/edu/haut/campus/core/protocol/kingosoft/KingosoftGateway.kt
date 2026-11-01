package cn.edu.haut.campus.core.protocol.kingosoft

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
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

/**
 * 青果教务的只读入口配置：ASP.NET 部署形态的路径、菜单号与字段名都可覆盖。
 */
data class KingosoftConfig(
    val loginPath: String = "/Default.aspx",
    val timetablePath: String = "/xskbcx.aspx",
    val gradePath: String = "/xscjcx.aspx",
    val examPath: String = "/xskscx.aspx",
    val timetableMenu: String = "N121603",
    val gradeMenu: String = "N121605",
    val examMenu: String = "N121604",
    val yearField: String = "ddlXN",
    val termField: String = "ddlXQ",
    val combinedTermField: String = "ddlXNXQ",
    val userField: String = "TextBox1",
    val passwordField: String = "TextBox2",
    val roleField: String = "RadioButtonList1",
    val roleStudent: String = "学生",
    val submitField: String = "Button1",
    val submitValue: String = "登录",
    val timetableTableId: String = "Table1",
    val gradeTableId: String = "Datagrid1",
    val examTableId: String = "Datagrid1"
)

/**
 * 青果教务适配器（只读）。
 *
 * UNVERIFIED：按青果教务常见的 ASP.NET 表单登录、学年/学期下拉与"节次×星期"网格课表推导，
 * 只用合成夹具验证；未在任何真实学校核对过菜单号、字段名或分页文案。
 * 选课等写操作一律不实现，writeCapabilities 恒为空。
 */
class KingosoftGateway(
    private val config: KingosoftConfig = KingosoftConfig()
) : AcademicGateway {

    /** 青果多数页面要求把学号作为 xh 查询参数；登录成功后才会带上。 */
    private var studentNo: String? = null

    override val descriptor = GatewayDescriptor(
        id = "kingosoft-readonly",
        protocol = AcademicProtocolFamily.KINGOSOFT,
        displayName = "青果教务（只读，UNVERIFIED）",
        authMode = AuthMode.FORM_CREDENTIALS,
        readCapabilities = GatewayCapability.readOnly(),
        writeCapabilities = emptySet(),
        requiresJavaScript = false,
        notes = "UNVERIFIED：ASP.NET 表单登录 + 网格课表的只读适配器；未提供任何写能力"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        val provided = credentials
            ?: return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "青果教务需要学号与密码")
        return provided.use { secret ->
            val loginUrl = url(session, config.loginPath)
            val page = session.send(GatewayRequest(HttpMethod.GET, loginUrl, purpose = RequestPurpose.AUTH))
            if (!page.isSuccess) return@use AuthOutcome.Rejected("青果登录页不可用")
            // ASP.NET 的回发目标由页面里的 form action 决定，不能写死成登录页地址。
            val action = KingosoftHtml.formAction(page.body)
            val target = if (action == null) {
                loginUrl
            } else {
                session.urlPolicy.resolve(loginUrl, action, RequestPurpose.AUTH)
            }
            val form = LinkedHashMap<String, String>(KingosoftHtml.hiddenFields(page.body))
            form[config.userField] = secret.studentId
            form[config.passwordField] = secret.password
            form[config.roleField] = config.roleStudent
            form[config.submitField] = config.submitValue
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
            val accepted = landing.isSuccess && !KingosoftHtml.looksLikeLoginPage(landing.body)
            if (!accepted) {
                return@use AuthOutcome.Rejected("青果教务拒绝了本次认证")
            }
            studentNo = secret.studentId
            AuthOutcome.Authenticated
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        val response = fetchPage(session, menuUrl(session, config.timetablePath, config.timetableMenu))
        val terms = parseTerms(response.body)
        if (terms.isEmpty()) {
            return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "青果学期下拉框没有可识别的选项"
            )
        }
        AcademicResult.Content(terms.distinctBy { it.sourceId }, provenance(session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = guarded {
        val (year, termNo) = KingosoftParsers.splitTermId(term.sourceId)
            ?: return@guarded AcademicResult.Failure(
                AcademicError.SOURCE_DATA_INVALID,
                retryable = false,
                detail = "青果学期来源 ID 不是 学年-学期 形式"
            )
        val url = menuUrl(
            session,
            config.timetablePath,
            config.timetableMenu,
            "xn" to year,
            "xq" to termNo
        )
        val table = findTable(fetchPage(session, url).body, config.timetableTableId)
        val courses = parseGrid(table)
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty("青果教务在该学期没有返回课程", authoritative = true)
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
            val url = menuUrl(session, config.gradePath, config.gradeMenu, "page" to page.toString())
            val body = fetchPage(session, url).body
            val table = findTable(body, config.gradeTableId)
            val pager = parsePager(body)
            PagedJsonReader.Page(
                items = table.rows.map { row -> KingosoftHtml.row(table.header, row) },
                total = null,
                // 青果分页条只给"共 N 页"，没有总条数；缺失时按单页处理，避免无限翻页。
                totalPages = pager.totalPages ?: 1,
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
                credits = KingosoftParsers.decimal(item.str("学分")),
                scoreText = item.str("成绩"),
                gradePoint = KingosoftParsers.decimal(item.str("绩点")),
                termSourceId = term?.sourceId,
                category = item.str("课程性质")
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
        val table = findTable(
            fetchPage(session, menuUrl(session, config.examPath, config.examMenu)).body,
            config.examTableId
        )
        val exams = table.rows.mapNotNull { row ->
            val name = table.cell(row, "课程名称") ?: return@mapNotNull null
            val text = table.cell(row, "考试时间")
            val date = KingosoftParsers.date(text)
            RemoteExam(
                sourceId = listOfNotNull(table.cell(row, "课程代码"), table.cell(row, "序号"), date?.toString())
                    .joinToString("#")
                    .ifBlank { name },
                courseName = name,
                date = date,
                startTime = KingosoftParsers.clock(text, 0),
                endTime = KingosoftParsers.clock(text, 1),
                location = table.cell(row, "考试地点"),
                seat = table.cell(row, "座位号"),
                kind = table.cell(row, "考试形式")
            )
        }
        if (exams.isEmpty()) {
            return@guarded AcademicResult.Empty("青果教务没有公布该学期考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = provenance(session)
        )
    }

    private fun parseTerms(html: String): List<RemoteTerm> {
        val combined = KingosoftHtml.options(html, config.combinedTermField)
        if (combined.isNotEmpty()) {
            return combined.map { option ->
                val label = option.label.ifBlank { option.value }
                RemoteTerm(
                    sourceId = option.value,
                    label = label,
                    academicYear = KingosoftParsers.academicYear(label),
                    ordinal = KingosoftParsers.termOrdinal(label)
                )
            }
        }
        val years = KingosoftHtml.options(html, config.yearField)
        val semesters = KingosoftHtml.options(html, config.termField)
        if (years.isEmpty() || semesters.isEmpty()) return emptyList()
        val terms = ArrayList<RemoteTerm>()
        for (year in years) {
            for (semester in semesters) {
                val label = year.label.ifBlank { year.value } + "学年" + semester.label.ifBlank { semester.value }
                terms += RemoteTerm(
                    sourceId = year.value + "-" + semester.value,
                    label = label,
                    academicYear = KingosoftParsers.academicYear(year.label),
                    ordinal = KingosoftParsers.termOrdinal(semester.label)
                        ?: semester.value.toIntOrNull()?.takeIf { it in 1..3 }
                )
            }
        }
        return terms
    }

    private fun parseGrid(table: KingosoftHtml.Table): List<RemoteCourse> {
        if (table.header.isEmpty()) return emptyList()
        val weekdayByColumn = LinkedHashMap<Int, Int>()
        table.header.forEachIndexed { index, cell ->
            if (index == 0) return@forEachIndexed
            KingosoftParsers.weekday(cell)?.let { weekdayByColumn[index] = it }
        }
        if (weekdayByColumn.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<RemoteMeeting>>()
        val teachers = HashMap<String, String>()
        for (row in table.rows) {
            val sections = KingosoftParsers.sections(row.getOrNull(0)) ?: continue
            for (entry in weekdayByColumn.entries) {
                val cell = row.getOrNull(entry.key)?.takeIf { it.isNotBlank() } ?: continue
                val lines = cell.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val name = lines.firstOrNull() ?: continue
                grouped.getOrPut(name) { ArrayList() } += RemoteMeeting(
                    weekday = entry.value,
                    startSection = sections.first,
                    endSection = sections.last,
                    weeks = KingosoftParsers.weeks(lines.getOrNull(3)),
                    location = lines.getOrNull(2),
                    teacher = lines.getOrNull(1)
                )
                lines.getOrNull(1)?.let { teachers[name] = it }
            }
        }
        return grouped.map { (name, meetings) ->
            RemoteCourse(
                sourceId = name,
                name = name,
                teacher = teachers[name],
                meetings = meetings.distinctBy { meeting ->
                    listOf(meeting.weekday, meeting.startSection, meeting.endSection, meeting.weeks, meeting.location)
                }
            )
        }
    }

    private fun parsePager(html: String): Pager {
        val totalPages = Regex("共\\s*(\\d+)\\s*页").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val current = Regex("当前第\\s*(\\d+)\\s*页").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return Pager(current, totalPages)
    }

    private data class Pager(val current: Int?, val totalPages: Int?)

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
        if (response.isUnauthorized || KingosoftHtml.looksLikeLoginPage(response.body)) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "青果教务会话已过期，请重新登录")
        }
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "青果教务返回了失败状态")
        }
        return response
    }

    private fun findTable(html: String, id: String): KingosoftHtml.Table {
        val tables = KingosoftHtml.tables(html)
        return tables.firstOrNull { it.id == id }
            ?: tables.firstOrNull { it.header.isNotEmpty() }
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "青果页面结构变化：未找到表格 " + id)
    }

    private fun KingosoftHtml.Table.cell(row: List<String>, name: String): String? {
        val index = header.indexOfFirst { it == name }
        if (index < 0) return null
        return row.getOrNull(index)?.takeIf { it.isNotBlank() }
    }

    private fun menuUrl(
        session: GatewaySession,
        path: String,
        menu: String,
        vararg extra: Pair<String, String>
    ): String {
        val params = ArrayList<Pair<String, String>>()
        studentNo?.let { params += "xh" to it }
        params += "gnmkdm" to menu
        extra.forEach { params += it }
        return url(session, path, *params.toTypedArray())
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
        sourceSystem = "kingosoft-aspnet"
    )

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        if (failure.error == AcademicError.UNTRUSTED_REDIRECT) throw failure
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }
}
