package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonParseException
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthMode
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.ExamSnapshot
import cn.edu.haut.campus.core.protocol.GatewayDescriptor
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GradeSnapshot
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.PagedJsonReader
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.RemoteCourse
import cn.edu.haut.campus.core.protocol.RemoteGrade
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TimetableSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure

/**
 * 新版强智（qz-modern）适配器：同一套站点里 JSON 与 HTML 混用 ——
 * 登录页与学期入口是 HTML，课表可能返回 HTML 网格也可能返回 kbList JSON，成绩是分页 JSON，考试是 HTML 表格。
 *
 * UNVERIFIED（未验证）：全部页面/字段形态按公开 jsxsd 结构推导，并用**合成夹具**覆盖，
 * 未在任何真实学校站点登录、抓取或校验；不得据此宣称"某校已验证可用"。
 * 所有路径都可注入，便于 /jsxsd、/jwglxt 等不同部署复用同一实现。
 */
class QzModernGateway(
    private val loginPagePath: String = "/jsxsd/",
    private val loginSubmitPath: String = "/jsxsd/xk/LoginToXk",
    private val mainPagePath: String = "/jsxsd/framework/xsMain.jsp",
    private val timetablePath: String = "/jsxsd/xskb/xskb_list.do",
    private val gradesPath: String = "/jsxsd/xscj/xscjcx",
    private val examsPath: String = "/jsxsd/kwgl/kscx"
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "qz-modern",
        protocol = AcademicProtocolFamily.QZ_MODERN,
        displayName = "强智科技（新版 jsxsd）",
        authMode = AuthMode.FORM_CREDENTIALS,
        notes = "UNVERIFIED：页面与字段形态按公开 jsxsd 结构推导，未在任何真实学校站点验证。"
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
                val payload = QzLoginPage.parse(page.body)
                    ?: return@use AuthOutcome.Rejected("未识别出登录表单，页面可能已改版")
                val body = payload.buildBody(value.studentId, value.password)
                    ?: return@use AuthOutcome.Rejected("登录参数超出加密字段允许长度")
                val response = session.send(
                    GatewayRequest(
                        method = HttpMethod.POST,
                        url = resolveSubmitUrl(session, payload.submitAction),
                        body = body,
                        contentType = "application/x-www-form-urlencoded",
                        purpose = RequestPurpose.AUTH
                    )
                )
                when {
                    response.isUnauthorized -> AuthOutcome.Rejected("认证被拒绝")
                    response.isRedirect -> AuthOutcome.Authenticated
                    response.isSuccess && QzLoginPage.parse(response.body) != null ->
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
        QzSession.ensureReadable(response, response.body)
        val terms = parseTermLinks(response.body)
        if (terms.isEmpty()) {
            return@qzGuard AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "学期入口页面没有可识别的 xnm/xqm 链接"
            )
        }
        AcademicResult.Content(terms, QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = qzGuard {
        val query = termQuery(QzTermRef.parse(term.sourceId))
        val response = session.fetch(
            GatewayRequest(
                HttpMethod.GET,
                QzSession.withQuery(session, timetablePath, query),
                purpose = RequestPurpose.READ
            )
        )
        QzSession.ensureReadable(response, response.body)
        val body = response.body.trim()
        val courses: List<RemoteCourse> = if (body.startsWith("{") || body.startsWith("[")) {
            QzTimetableJson.parse(parseJson(body))
        } else {
            QzTimetableGrid.parse(body) ?: return@qzGuard AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "课表页面既不是课表 JSON，也没有可识别的星期表头"
            )
        }
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
        val prefix = termQuery(QzTermRef.parse(term?.sourceId)).let { if (it.isEmpty()) "" else it + "&" }
        val pages = PagedJsonReader.readAll(maxPages = 60, maxRecords = 5000) { page ->
            val response = session.fetch(
                GatewayRequest(
                    HttpMethod.GET,
                    QzSession.withQuery(session, gradesPath, prefix + "page=" + page),
                    purpose = RequestPurpose.READ
                )
            )
            QzSession.ensureReadable(response, response.body)
            val root = parseJson(response.body)
            PagedJsonReader.Page(
                items = root.asArrOrNull?.items ?: root.arr("items") ?: root.arr("rows").orEmpty(),
                total = QzJson.int(root, "total", "totalResult", "records"),
                totalPages = QzJson.int(root, "totalPage", "totalPages", "pageCount"),
                currentPage = QzJson.int(root, "currentPage", "pageNum", "page"),
                pageSize = QzJson.int(root, "pageSize", "size")
            )
        }
        val identity = QzIdentity()
        val grades = pages.items.mapNotNull { item -> toGrade(item, identity) }
        if (grades.isEmpty()) {
            return@qzGuard AcademicResult.Empty(reason = "没有任何成绩记录", authoritative = true)
        }
        AcademicResult.Content(
            value = GradeSnapshot(term?.sourceId, grades, SnapshotCompleteness.COMPLETE),
            source = QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session)
        )
    }

    override suspend fun exams(session: GatewaySession, term: RemoteTerm?): AcademicResult<ExamSnapshot> = qzGuard {
        val response = session.fetch(
            GatewayRequest(
                HttpMethod.GET,
                QzSession.withQuery(session, examsPath, termQuery(QzTermRef.parse(term?.sourceId))),
                purpose = RequestPurpose.READ
            )
        )
        QzSession.ensureReadable(response, response.body)
        val exams = QzExamTable.parse(response.body) ?: return@qzGuard AcademicResult.Failure(
            AcademicError.PAGE_CHANGED,
            retryable = false,
            detail = "考试页面没有可识别的课程/考试日期表头"
        )
        if (exams.isEmpty()) {
            return@qzGuard AcademicResult.Empty(reason = "没有考试安排", authoritative = true)
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = QzSession.provenance(descriptor.id, SOURCE_SYSTEM, session)
        )
    }

    private fun toGrade(item: JsonValue, identity: QzIdentity): RemoteGrade? {
        val name = QzJson.text(item, "kcmc", "courseName", "name")?.trim().orEmpty()
        if (name.isEmpty()) return null
        val code = QzJson.text(item, "kch", "courseCode")?.trim().orEmpty()
        val attempt = QzJson.text(item, "ksxz", "attempt", "kcxz")?.trim()?.ifEmpty { null }
        val base = if (code.isEmpty()) "qz-grade:" + name else "qz-grade:" + code
        return RemoteGrade(
            sourceId = identity.of(if (attempt == null) base else base + "@" + attempt),
            courseName = name,
            credits = QzJson.decimal(item, "xf", "credit", "credits"),
            scoreText = QzJson.text(item, "cj", "score", "zscj")?.trim()?.ifEmpty { null },
            gradePoint = QzJson.decimal(item, "jd", "gradePoint", "pjxfjd"),
            attempt = attempt,
            category = QzJson.text(item, "kclb", "category", "kcxz")?.trim()?.ifEmpty { null }
        )
    }

    private fun parseTermLinks(html: String): List<RemoteTerm> {
        val terms = LinkedHashMap<String, RemoteTerm>()
        for (link in QzHtml.links(html)) {
            val parameters = QzQuery.parameters(link.href)
            val ref = QzTermRef.of(parameters["xnm"], parameters["xqm"]) ?: continue
            val label = link.text.trim().ifBlank { parameters["xnm"].orEmpty() + " 学年" }
            terms.putIfAbsent(
                ref.sourceId,
                RemoteTerm(
                    sourceId = ref.sourceId,
                    label = label,
                    academicYear = academicYearOf(ref.academicYearCode),
                    ordinal = null
                )
            )
        }
        return terms.values.toList()
    }

    /** 学年编号里的四位年份只用于展示；学期序数一律不推导，避免把 3/12/16 这类编码当成"第几学期"。 */
    private fun academicYearOf(code: String): Int? =
        Regex("""(\d{4})""").find(code)?.groupValues?.get(1)?.toIntOrNull()

    private fun resolveSubmitUrl(session: GatewaySession, action: String?): String {
        val raw = action?.trim().orEmpty()
        return when {
            raw.startsWith("https://") -> raw
            raw.startsWith("/") -> session.origin.trimEnd('/') + raw
            else -> QzSession.url(session, loginSubmitPath)
        }
    }

    private fun termQuery(ref: QzTermRef?): String {
        if (ref == null) return ""
        return "xnm=" + QzTextCodec.percentEncode(ref.academicYearCode) +
            "&xqm=" + QzTextCodec.percentEncode(ref.termCode)
    }

    private fun parseJson(body: String): JsonValue = try {
        Json.parse(body)
    } catch (_: JsonParseException) {
        throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回的 JSON 无法解析")
    }

    private companion object {
        const val SOURCE_SYSTEM = "qz-modern-unverified"
    }
}
