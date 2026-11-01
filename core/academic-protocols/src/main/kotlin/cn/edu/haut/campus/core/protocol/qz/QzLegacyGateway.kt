package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthMode
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.ExamSnapshot
import cn.edu.haut.campus.core.protocol.GatewayDescriptor
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewayResponse
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GradeSnapshot
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.PagedJsonReader
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.RemoteGrade
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TimetableSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.math.BigDecimal

/**
 * 旧强智（qz-legacy）适配器：老 jsxsd 站点只会"先提交查询条件、再去取结果页"，
 * 页面是 GBK 编码，课表/成绩/考试全部要从 HTML 表格里还原。
 *
 * 查询轮次是本适配器的核心语义：
 *   POST {查询入口} + 表单条件  →  302  →  GET {结果页}
 * 结果页地址由服务器给出，必须逐跳经过 URL 策略校验；POST 后的 302 按浏览器实际行为降级为 GET，
 * 而不是沿用 POST 再打一次查询（老系统会把重复查询当成新一次查询）。
 *
 * UNVERIFIED（未验证）：页面形态、编码声明与查询轮次按公开 jsxsd 行为推导，用合成夹具覆盖，
 * 未在任何真实学校站点验证；不得据此宣称"某校已验证可用"。
 */
class QzLegacyGateway(
    private val loginPagePath: String = "/jsxsd/",
    private val loginSubmitPath: String = "/jsxsd/xk/LoginToXk",
    private val mainPagePath: String = "/jsxsd/framework/xsMain.jsp",
    private val timetableQueryPath: String = "/jsxsd/xskb/xskb_query",
    private val timetableResultPath: String = "/jsxsd/xskb/xskb_list.do",
    private val gradesQueryPath: String = "/jsxsd/xscj/xscjcx_query",
    private val gradesResultPath: String = "/jsxsd/xscj/xscj_list.do",
    private val examsQueryPath: String = "/jsxsd/kwgl/kscx_query",
    private val examsResultPath: String = "/jsxsd/kwgl/kscx_list.do"
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "qz-legacy",
        protocol = AcademicProtocolFamily.QZ_LEGACY,
        displayName = "强智科技（老版 jsxsd）",
        authMode = AuthMode.FORM_CREDENTIALS,
        notes = "UNVERIFIED：GBK 页面与查询轮次按公开 jsxsd 行为推导，未在任何真实学校站点验证。"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        val secret = credentials ?: return AuthOutcome.Rejected("缺少登录凭据")
        return try {
            secret.use { value ->
                val page = session.fetch(
                    GatewayRequest(HttpMethod.GET, QzSession.url(session, loginPagePath), purpose = RequestPurpose.READ)
                )
                if (page.isUnauthorized) return@use AuthOutcome.Rejected("登录页拒绝访问")
                if (!page.isSuccess) return@use AuthOutcome.Rejected("登录页返回异常状态")
                val html = readBody(page)
                val fields = QzHtml.formFields(html)
                if (fields.keys.any { it.lowercase() in CAPTCHA_FIELDS }) {
                    return@use AuthOutcome.RequiresUserAction(
                        UserAction.COMPLETE_CAPTCHA,
                        "该部署的登录页要求图形验证码，需要在官方页面完成"
                    )
                }
                val usernameField = fields.keys.firstOrNull { it in USERNAME_FIELDS } ?: "j_username"
                val passwordField = fields.keys.firstOrNull { it in PASSWORD_FIELDS } ?: "j_password"
                val body = QzTextCodec.gbkPercentEncode(usernameField) + "=" +
                    QzTextCodec.gbkPercentEncode(value.studentId) + "&" +
                    QzTextCodec.gbkPercentEncode(passwordField) + "=" +
                    QzTextCodec.gbkPercentEncode(value.password)
                val response = session.send(
                    GatewayRequest(
                        method = HttpMethod.POST,
                        url = QzSession.url(session, loginSubmitPath),
                        body = body,
                        contentType = "application/x-www-form-urlencoded; charset=GBK",
                        purpose = RequestPurpose.AUTH
                    )
                )
                when {
                    response.isUnauthorized -> AuthOutcome.Rejected("认证被拒绝")
                    response.isRedirect -> AuthOutcome.Authenticated
                    response.isSuccess && QzLoginPage.parse(readBody(response)) != null ->
                        AuthOutcome.Rejected("认证失败：仍停留在登录页")
                    response.isSuccess -> AuthOutcome.Authenticated
                    else -> AuthOutcome.Rejected("认证返回异常状态")
                }
            }
        } catch (failure: TransportFailure) {
            AuthOutcome.Rejected(failure.message ?: "认证请求被安全策略拒绝")
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = qzGuard {
        val response = session.fetch(
            GatewayRequest(HttpMethod.GET, QzSession.url(session, mainPagePath), purpose = RequestPurpose.READ)
        )
        val html = readBody(response)
        QzSession.ensureReadable(response, html)
        val options = QzHtml.selectOptions(html, TERM_SELECT)
        val terms = options
            .mapNotNull { option ->
                val value = option.value.trim()
                if (value.isEmpty()) return@mapNotNull null
                val label = option.text.trim().ifEmpty { value }
                RemoteTerm(
                    sourceId = value,
                    label = label,
                    academicYear = Regex("""(\d{4})""").find(value)?.groupValues?.get(1)?.toIntOrNull(),
                    ordinal = null
                )
            }
            .distinctBy { it.sourceId }
        if (terms.isEmpty()) {
            return@qzGuard AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "主页面没有可识别的学年学期下拉框 " + TERM_SELECT
            )
        }
        AcademicResult.Content(terms, QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = qzGuard {
        val result = queryThenGet(
            session = session,
            queryPath = timetableQueryPath,
            resultPath = timetableResultPath,
            formBody = termForm(term)
        )
        val html = readBody(result)
        QzSession.ensureReadable(result, html)
        val courses = QzTimetableGrid.parse(html) ?: return@qzGuard AcademicResult.Failure(
            AcademicError.PAGE_CHANGED,
            retryable = false,
            detail = "查询轮次后的结果页没有可识别的课表网格"
        )
        if (courses.isEmpty()) {
            return@qzGuard AcademicResult.Empty(reason = "当前学期没有课程", authoritative = true)
        }
        AcademicResult.Content(
            value = TimetableSnapshot(term.sourceId, courses, SnapshotCompleteness.COMPLETE),
            source = QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session)
        )
    }

    override suspend fun grades(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<GradeSnapshot> = qzGuard {
        val first = queryThenGet(
            session = session,
            queryPath = gradesQueryPath,
            resultPath = gradesResultPath,
            formBody = termForm(term)
        )
        val firstHtml = readBody(first)
        QzSession.ensureReadable(first, firstHtml)
        val firstTable = pickGradeTable(firstHtml) ?: return@qzGuard AcademicResult.Failure(
            AcademicError.PAGE_CHANGED,
            retryable = false,
            detail = "成绩结果页没有可识别的课程/成绩表头"
        )
        val totalPages = QzPager.totalPages(firstHtml)
        val columns = GradeColumns.from(firstTable)
        val pages = PagedJsonReader.readAll(maxPages = 60, maxRecords = 5000) { page ->
            val table = if (page == 1) {
                firstTable
            } else {
                val response = session.fetch(
                    GatewayRequest(
                        HttpMethod.GET,
                        QzSession.withQuery(session, gradesResultPath, "page=" + page),
                        purpose = RequestPurpose.READ
                    )
                )
                val html = readBody(response)
                QzSession.ensureReadable(response, html)
                pickGradeTable(html)
                    ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "成绩结果页结构在翻页后发生变化")
            }
            PagedJsonReader.Page(
                items = table.rows.map { row -> columns.toJson(row) },
                totalPages = totalPages,
                currentPage = page
            )
        }
        val identity = QzIdentity()
        val grades = pages.items.mapNotNull { item ->
            val name = QzJson.text(item, "courseName")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val attempt = QzJson.text(item, "attempt")?.trim()?.ifEmpty { null }
            val base = "qz-grade:" + name
            RemoteGrade(
                sourceId = identity.of(if (attempt == null) base else base + "@" + attempt),
                courseName = name,
                credits = QzJson.decimal(item, "credits"),
                scoreText = QzJson.text(item, "scoreText")?.trim()?.ifEmpty { null },
                gradePoint = QzJson.decimal(item, "gradePoint"),
                attempt = attempt,
                category = QzJson.text(item, "category")?.trim()?.ifEmpty { null },
                termSourceId = term?.sourceId
            )
        }
        if (grades.isEmpty()) {
            return@qzGuard AcademicResult.Empty(reason = "没有任何成绩记录", authoritative = true)
        }
        AcademicResult.Content(
            value = GradeSnapshot(term?.sourceId, grades, SnapshotCompleteness.COMPLETE),
            source = QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session)
        )
    }

    override suspend fun exams(session: GatewaySession, term: RemoteTerm?): AcademicResult<ExamSnapshot> = qzGuard {
        val result = queryThenGet(
            session = session,
            queryPath = examsQueryPath,
            resultPath = examsResultPath,
            formBody = termForm(term)
        )
        val html = readBody(result)
        QzSession.ensureReadable(result, html)
        val exams = QzExamTable.parse(html) ?: return@qzGuard AcademicResult.Failure(
            AcademicError.PAGE_CHANGED,
            retryable = false,
            detail = "考试结果页没有可识别的课程/考试时间表头"
        )
        if (exams.isEmpty()) {
            return@qzGuard AcademicResult.Empty(reason = "没有考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session)
        )
    }

    /**
     * 查询轮次：POST 查询条件 → 跟随 302 → GET 结果页。
     * 服务器给出的结果页地址必须再过一次 URL 策略，越权地址会被拒绝为 UNTRUSTED_REDIRECT。
     */
    private suspend fun queryThenGet(
        session: GatewaySession,
        queryPath: String,
        resultPath: String,
        formBody: String
    ): GatewayResponse {
        val post = GatewayRequest(
            method = HttpMethod.POST,
            url = QzSession.url(session, queryPath),
            body = formBody,
            contentType = "application/x-www-form-urlencoded; charset=GBK",
            purpose = RequestPurpose.READ
        )
        val first = session.send(post)
        if (!first.isRedirect) return first
        val location = first.header("Location")
            ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "查询后服务器没有给出结果页地址")
        val target = session.urlPolicy.resolve(post.url, location, RequestPurpose.READ)
        return session.fetch(GatewayRequest(HttpMethod.GET, target, purpose = RequestPurpose.READ))
    }

    private fun readBody(response: GatewayResponse): String =
        QzTextCodec.normalizeBody(response.body, response.header("Content-Type"))

    private fun termForm(term: RemoteTerm?): String =
        TERM_FIELD + "=" + QzTextCodec.gbkPercentEncode(term?.sourceId.orEmpty())

    private fun pickGradeTable(html: String): ColumnTable? =
        QzTables.parse(html).firstOrNull { it.hasAny(*COURSE_ALIASES) }

    /** 成绩页的列别名：老系统不同院系导出列名不一致，只按表头定位，不按列号。 */
    private class GradeColumns(
        val course: Int?,
        val credits: Int?,
        val score: Int?,
        val gradePoint: Int?,
        val attempt: Int?,
        val category: Int?
    ) {
        fun toJson(row: List<String>): JsonValue.Obj {
            val fields = LinkedHashMap<String, JsonValue>()
            fields["raw"] = JsonValue.Str(row.joinToString("\u0001"))
            put(fields, "courseName", course, row)
            put(fields, "credits", credits, row)
            put(fields, "scoreText", score, row)
            put(fields, "gradePoint", gradePoint, row)
            put(fields, "attempt", attempt, row)
            put(fields, "category", category, row)
            return JsonValue.Obj(fields)
        }

        private fun put(fields: MutableMap<String, JsonValue>, key: String, index: Int?, row: List<String>) {
            if (index == null) return
            fields[key] = JsonValue.Str(row.getOrNull(index).orEmpty().trim())
        }

        companion object {
            fun from(table: ColumnTable): GradeColumns = GradeColumns(
                course = table.indexOf(*COURSE_ALIASES),
                credits = table.indexOf("学分", "学分值", "credit"),
                score = table.indexOf("成绩", "分数", "总评", "总评成绩", "score"),
                gradePoint = table.indexOf("绩点", "平均绩点", "gradePoint"),
                attempt = table.indexOf("考试性质", "修读性质", "初修重考", "attempt"),
                category = table.indexOf("课程性质", "课程类别", "category")
            )
        }
    }

    private companion object {
        const val SOURCE_SYSTEM = "qz-legacy-unverified"
        const val TERM_SELECT = "xnxq"
        const val TERM_FIELD = "xnxq"
        val COURSE_ALIASES = arrayOf("课程名称", "课程", "科目", "考试科目")
        val USERNAME_FIELDS = setOf("j_username", "username", "userid", "xh")
        val PASSWORD_FIELDS = setOf("j_password", "password", "pwd", "mm")
        val CAPTCHA_FIELDS = setOf("verifycode", "captcha", "checkcode", "randcode")
    }
}
