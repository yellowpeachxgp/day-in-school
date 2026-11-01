package cn.edu.haut.campus.core.protocol.zf

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
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
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TimetableSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 旧正方（ASP.NET WebForms 形态）适配器。
 *
 * UNVERIFIED：页面路径、表单字段名（TextBox1/TextBox2/txtSecretCode/ddlXN/ddlXQ）、
 * 表格列名全部按公开协议形态推导，**未在任何真实学校验证**。
 *
 * 设计要点：
 * 1. 每次提交都回填页面里的 __VIEWSTATE / __EVENTVALIDATION 等隐藏字段，
 *    否则 ASP.NET 会直接报“视图状态无效”；
 * 2. 页面编码按响应头 charset 处理，GBK 页面会被重新解码后再解析；
 * 3. 课表按 rowspan/colspan 展开成网格再按“星期列 + 节次行”取值；
 * 4. 菜单 ID（gnmkdm）来自首页链接，不写死在代码里。
 */
class ZfLegacyGateway : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "zf-legacy",
        protocol = AcademicProtocolFamily.ZF_LEGACY,
        displayName = "旧正方教务（UNVERIFIED 合成形态）",
        authMode = AuthMode.FORM_CREDENTIALS,
        notes = "WebForms 隐藏字段与表格形态按公开协议推导，未在真实学校验证"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        if (credentials == null) {
            return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "需要学号与密码登录教务系统")
        }
        return credentials.use { secret -> login(session, secret) }
    }

    private suspend fun login(session: GatewaySession, secret: ProtocolCredentials): AuthOutcome = try {
        val loginUrl = ZfUrls.absolute(session.origin, LOGIN_PATH)
        val landing = session.fetch(GatewayRequest(HttpMethod.GET, loginUrl))
        val currentUrl = landing.finalUrl.ifEmpty { loginUrl }
        val page = decode(landing)
        val form = ZfHtml.forms(page).firstOrNull { it.hasPassword }
        if (form == null) {
            ZfAuth.classify(landing.copy(body = page))
        } else if (form.usernameField == null || form.passwordField == null) {
            AuthOutcome.Rejected("登录页字段已变化，无法自动填写账号")
        } else {
            val action = form.action
                ?.let { session.urlPolicy.resolve(currentUrl, it, RequestPurpose.AUTH) }
                ?: currentUrl
            val body = form.credentialBody(secret.studentId, secret.password)
            val response = ZfRead.post(
                session,
                GatewayRequest(
                    method = HttpMethod.POST,
                    url = action,
                    headers = mapOf("Referer" to currentUrl),
                    body = body,
                    purpose = RequestPurpose.AUTH,
                    contentType = FORM_CONTENT_TYPE
                )
            )
            // 登录失败时服务端会回 200 并重新渲染登录页，需要先按声明的字符集解码再判定
            ZfAuth.classify(response.copy(body = decode(response)))
        }
    } catch (failure: TransportFailure) {
        AuthOutcome.Rejected(failure.message ?: "认证请求失败")
    }

    /**
     * 旧正方没有独立的学期接口，学年/学期下拉框出现在课表页上，
     * 这里把它展开成“学年 × 学期”的组合，学期 ID 统一为“学年-学期代码”。
     */
    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = zfGuarded {
        val menu = loadMenu(session)
        val page = fetchPage(session, menu.require(ZfPage.TIMETABLE))
        val options = ZfLegacyTables.termOptions(page)
        if (options.years.isEmpty() || options.semesters.isEmpty()) {
            throw TransportFailure(AcademicError.PAGE_CHANGED, "课表页面缺少学年/学期下拉框")
        }
        val seen = HashSet<String>()
        val terms = ArrayList<RemoteTerm>()
        for (year in options.years) {
            val yearCode = YEAR_CODE.find(year.value)?.value ?: continue
            for (semester in options.semesters) {
                val termCode = TERM_CODE.find(semester.value)?.value ?: continue
                val sourceId = yearCode + "-" + termCode
                if (!seen.add(sourceId)) continue
                terms += RemoteTerm(
                    sourceId = sourceId,
                    label = year.text + " " + semester.text,
                    academicYear = yearCode.toIntOrNull(),
                    ordinal = termCode.toIntOrNull()
                )
            }
        }
        if (terms.isEmpty()) {
            AcademicResult.Empty("教务没有提供可查询的学年学期", authoritative = true)
        } else {
            AcademicResult.Content(terms, zfProvenance(session, descriptor.id, SnapshotCompleteness.COMPLETE))
        }
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = zfGuarded {
        val menu = loadMenu(session)
        val url = menu.require(ZfPage.TIMETABLE)
        val page = fetchPage(session, url)
        val form = ZfHtml.forms(page).firstOrNull()
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "课表页面缺少查询表单")
        val html = submitPage(session, url, form, legacyTermFields(term))
        val parsed = ZfLegacyTables.timetable(html)
        if (parsed.courses.isEmpty()) {
            AcademicResult.Empty("当前学期没有排课记录", authoritative = !parsed.incomplete)
        } else {
            val completeness =
                if (parsed.incomplete) SnapshotCompleteness.PARTIAL else SnapshotCompleteness.COMPLETE
            AcademicResult.Content(
                TimetableSnapshot(term.sourceId, parsed.courses, completeness),
                zfProvenance(session, descriptor.id, completeness)
            )
        }
    }

    override suspend fun grades(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<GradeSnapshot> = zfGuarded {
        val menu = loadMenu(session)
        val url = menu.require(ZfPage.GRADES)
        val page = fetchPage(session, url)
        val form = ZfHtml.forms(page).firstOrNull()
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "成绩页面缺少查询表单")
        // 未指定学期时不覆盖下拉框，交给服务端按页面默认值返回
        val html = submitPage(session, url, form, term?.let { legacyTermFields(it) }.orEmpty())
        val parsed = ZfLegacyTables.grades(html)
        val partial = parsed.truncated || parsed.incomplete
        if (parsed.grades.isEmpty()) {
            if (partial) {
                AcademicResult.Failure(
                    code = AcademicError.PARTIAL_RESPONSE,
                    retryable = false,
                    detail = "成绩页面没有可解析的记录，且服务端提示结果不完整"
                )
            } else {
                AcademicResult.Empty("当前学期没有成绩记录", authoritative = true)
            }
        } else {
            val completeness = if (partial) SnapshotCompleteness.PARTIAL else SnapshotCompleteness.COMPLETE
            AcademicResult.Content(
                GradeSnapshot(term?.sourceId, parsed.grades, completeness),
                zfProvenance(session, descriptor.id, completeness)
            )
        }
    }

    override suspend fun exams(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<ExamSnapshot> = zfGuarded {
        val menu = loadMenu(session)
        val url = menu.require(ZfPage.EXAMS)
        val page = fetchPage(session, url)
        val form = ZfHtml.forms(page).firstOrNull()
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "考试页面缺少查询表单")
        val html = submitPage(session, url, form, term?.let { legacyTermFields(it) }.orEmpty())
        val parsed = ZfLegacyTables.exams(html)
        val partial = parsed.truncated || parsed.incomplete
        if (parsed.exams.isEmpty()) {
            if (partial) {
                AcademicResult.Failure(
                    code = AcademicError.PARTIAL_RESPONSE,
                    retryable = false,
                    detail = "考试页面没有可解析的记录，且服务端提示结果不完整"
                )
            } else {
                AcademicResult.Empty("当前学期没有考试安排", authoritative = true)
            }
        } else {
            val completeness = if (partial) SnapshotCompleteness.PARTIAL else SnapshotCompleteness.COMPLETE
            AcademicResult.Content(
                ExamSnapshot(term?.sourceId, parsed.exams, completeness),
                zfProvenance(session, descriptor.id, completeness)
            )
        }
    }

    private suspend fun loadMenu(session: GatewaySession): ZfMenuIndex {
        val html = fetchPage(session, ZfUrls.absolute(session.origin, INDEX_PATH))
        return ZfMenu.discover(html, session.origin)
    }

    private suspend fun fetchPage(
        session: GatewaySession,
        url: String,
        params: Map<String, String> = emptyMap()
    ): String = decode(ZfRead.get(session, url, params))

    /** 回填隐藏字段后提交查询表单；提交后按浏览器语义跟随跳转。 */
    private suspend fun submitPage(
        session: GatewaySession,
        pageUrl: String,
        form: ZfForm,
        extra: Map<String, String>
    ): String {
        val action = form.action?.let { session.urlPolicy.resolve(pageUrl, it, RequestPurpose.READ) } ?: pageUrl
        val response = ZfRead.post(
            session,
            GatewayRequest(
                method = HttpMethod.POST,
                url = action,
                headers = mapOf("Referer" to pageUrl),
                body = form.formBody(extra),
                purpose = RequestPurpose.READ,
                contentType = FORM_CONTENT_TYPE
            )
        )
        return decode(ZfRead.requireReadable(response))
    }

    private fun decode(response: GatewayResponse): String =
        ZfLegacyCharset.decode(response.body, response.header("Content-Type"))

    private fun legacyTermFields(term: RemoteTerm): Map<String, String> {
        val (year, semester) = ZfParsing.splitTermCode(term.sourceId)
            ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "学期来源 ID 无法映射为学年学期：" + term.sourceId)
        return linkedMapOf("ddlXN" to year, "ddlXQ" to semester)
    }

    private companion object {
        /** 登录页。UNVERIFIED 合成路径。 */
        const val LOGIN_PATH = "/default2.aspx"

        /** 学生主菜单页：菜单链接里带 gnmkdm 与 xh，学号不需要我们拼。 */
        const val INDEX_PATH = "/xs_main.aspx"

        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

        val YEAR_CODE = Regex("""\d{4}""")
        val TERM_CODE = Regex("""\d{1,2}""")
    }
}

/**
 * 旧正方页面编码处理（UNVERIFIED）。
 *
 * 传输层已经把字节解成 String；如果响应头声明的是非 UTF-8 字符集，而正文仍然全部落在
 * U+00FF 以内，说明拿到的是“字节逐位映射”的原始内容，需要按声明字符集重新解码。
 * 若正文里已经出现中文，说明上游已经解码正确，原样返回。
 */
internal object ZfLegacyCharset {

    fun decode(body: String, contentType: String?): String {
        val name = charsetName(contentType) ?: return body
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return body
        if (charset == StandardCharsets.UTF_8) return body
        if (body.isEmpty() || body.any { it.code > 0xFF }) return body
        return body.toByteArray(StandardCharsets.ISO_8859_1).toString(charset)
    }

    private fun charsetName(contentType: String?): String? {
        if (contentType == null) return null
        val marker = contentType.indexOf("charset=", ignoreCase = true)
        if (marker < 0) return null
        return contentType.substring(marker + CHARSET_PREFIX_LENGTH)
            .substringBefore(';')
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotEmpty() }
    }

    private const val CHARSET_PREFIX_LENGTH = 8
}
