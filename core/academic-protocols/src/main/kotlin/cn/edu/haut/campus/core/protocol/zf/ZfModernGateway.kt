package cn.edu.haut.campus.core.protocol.zf

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
import java.math.BigDecimal

/** 学期必须能映射成 jwglxt 的 xnm/xqm 查询参数；映射不出来时宁可失败，也不去请求“默认学期”。 */
private fun modernTermParams(term: RemoteTerm): Map<String, String> {
    val (year, semester) = ZfParsing.splitTermCode(term.sourceId)
        ?: throw TransportFailure(
            AcademicError.SOURCE_DATA_INVALID,
            "学期来源 ID 无法映射为 xnm/xqm：" + term.sourceId
        )
    return linkedMapOf("xnm" to year, "xqm" to semester)
}

/**
 * 新正方（jwglxt 形态）适配器。
 *
 * UNVERIFIED：登录入口、首页、菜单 ID、接口路径、JSON 字段名全部来自公开协议形态推导出的
 * 合成夹具，**没有在任何一个真实学校验证过**。所有“学校升级导致字段变化”的分支都返回
 * 结构化失败（PAGE_CHANGED / UNSUPPORTED / SOURCE_DATA_INVALID），不做猜测性兼容。
 *
 * 设计要点：
 * 1. 菜单 ID 不写死：每次操作先读首页，从菜单链接里发现本部署真实的功能入口地址；
 * 2. 成绩必须走 PagedJsonReader 读完全部分页，中途失败一律 PARTIAL_RESPONSE；
 * 3. 凭据只在内存里拼进表单，异常与日志中不出现密码、Cookie 或响应正文。
 */
class ZfModernGateway : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "zf-modern",
        protocol = AcademicProtocolFamily.ZF_MODERN,
        displayName = "新正方教务（UNVERIFIED 合成形态）",
        authMode = AuthMode.CAS_REDIRECT,
        notes = "端点与字段按公开协议形态推导，未在真实学校验证；需同时放行学校域与统一认证域"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        if (credentials == null) {
            return AuthOutcome.RequiresUserAction(UserAction.LOGIN, "需要学号与密码完成统一身份认证")
        }
        return credentials.use { secret -> login(session, secret) }
    }

    private suspend fun login(session: GatewaySession, secret: ProtocolCredentials): AuthOutcome = try {
        val entryUrl = ZfUrls.absolute(session.origin, LOGIN_PATH)
        // 未登录时登录入口会 302 到统一认证（CAS），session.fetch 会逐跳校验来源后跟随
        val landing = session.fetch(GatewayRequest(HttpMethod.GET, entryUrl))
        val currentUrl = landing.finalUrl.ifEmpty { entryUrl }
        val form = ZfHtml.forms(landing.body).firstOrNull { it.hasPassword }
        if (form == null) {
            // 已有会话，或学校直接返回了首页：不再重复提交凭据
            ZfAuth.classify(landing)
        } else if (form.usernameField == null || form.passwordField == null) {
            AuthOutcome.Rejected("登录页字段已变化，无法自动填写账号")
        } else {
            val action = form.action
                ?.let { session.urlPolicy.resolve(currentUrl, it, RequestPurpose.AUTH) }
                ?: currentUrl
            val extra = if (form.inputNames.any { it.equals(EVENT_ID_FIELD, ignoreCase = true) }) {
                mapOf(EVENT_ID_FIELD to "submit")
            } else {
                emptyMap()
            }
            val body = form.credentialBody(secret.studentId, secret.password, extra)
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
            ZfAuth.classify(response)
        }
    } catch (failure: TransportFailure) {
        // failure.message 由本模块生成，不含密码、Cookie 或响应正文
        AuthOutcome.Rejected(failure.message ?: "认证请求失败")
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = zfGuarded {
        val menu = loadMenu(session)
        val response = ZfRead.get(session, menu.require(ZfPage.TERMS))
        val root = ZfRead.json(response.body)
        val items = root.arr("items") ?: root.arr("terms")
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "学期列表响应缺少 items/terms 字段")
        val seen = HashSet<String>()
        val terms = items.mapNotNull { parseTerm(it) }.filter { seen.add(it.sourceId) }
        if (terms.isEmpty()) {
            AcademicResult.Empty("教务没有返回任何学期", authoritative = true)
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
        val response = ZfRead.get(session, url, modernTermParams(term))
        val root = ZfRead.json(response.body)
        val rows = root.arr("kbList") ?: root.arr("items")
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "课表响应缺少 kbList/items 字段")
        val parsed = parseTimetableRows(rows)
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

    /**
     * 成绩必须完整：PagedJsonReader 只有在拿到终止证据（总数匹配 / 短页）后才返回，
     * 中途任何一页失败都会升级成 PARTIAL_RESPONSE，调用方据此保留旧快照。
     */
    override suspend fun grades(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<GradeSnapshot> = zfGuarded {
        val menu = loadMenu(session)
        val url = menu.require(ZfPage.GRADES)
        val baseParams = LinkedHashMap<String, String>()
        baseParams["doType"] = "query"
        // 未指定学期时不附加 xnm/xqm，由服务端返回当前学期；学期绑定在返回结果里体现
        if (term != null) baseParams.putAll(modernTermParams(term))
        val collected = PagedJsonReader.readAll { page ->
            val response = ZfRead.get(session, url, baseParams + ("page" to page.toString()))
            val root = ZfRead.json(response.body)
            PagedJsonReader.Page(
                items = root.arr("items")
                    ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "成绩响应缺少 items 字段"),
                total = ZfParsing.int(root.obj("totalResult") ?: root.obj("total")),
                currentPage = ZfParsing.int(root.obj("currentPage") ?: root.obj("page")),
                pageSize = ZfParsing.int(root.obj("showCount") ?: root.obj("pageSize"))
            )
        }
        val parsed = parseGradeRows(collected.items, term)
        if (parsed.grades.isEmpty()) {
            AcademicResult.Empty("当前学期没有成绩记录", authoritative = !parsed.incomplete)
        } else {
            val completeness =
                if (parsed.incomplete) SnapshotCompleteness.PARTIAL else SnapshotCompleteness.COMPLETE
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
        val params = LinkedHashMap<String, String>()
        params["doType"] = "query"
        if (term != null) params.putAll(modernTermParams(term))
        val response = ZfRead.get(session, url, params)
        val root = ZfRead.json(response.body)
        val items = root.arr("items") ?: root.arr("ksList")
            ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "考试响应缺少 items/ksList 字段")
        val parsed = parseExamRows(items)
        if (parsed.exams.isEmpty()) {
            AcademicResult.Empty("当前学期没有考试安排", authoritative = !parsed.incomplete)
        } else {
            val completeness =
                if (parsed.incomplete) SnapshotCompleteness.PARTIAL else SnapshotCompleteness.COMPLETE
            AcademicResult.Content(
                ExamSnapshot(term?.sourceId, parsed.exams, completeness),
                zfProvenance(session, descriptor.id, completeness)
            )
        }
    }

    /** 每次操作都重新读首页：菜单在会话期间被管理员调整时能立刻生效，避免长期缓存过期入口。 */
    private suspend fun loadMenu(session: GatewaySession): ZfMenuIndex {
        val response = ZfRead.get(session, ZfUrls.absolute(session.origin, INDEX_PATH))
        return ZfMenu.discover(response.body, session.origin)
    }

    // ---- 学期 ----

    private fun parseTerm(item: JsonValue): RemoteTerm? {
        val explicitId = ZfParsing.text(item.obj("term")) ?: ZfParsing.text(item.obj("id"))
        val xnm = ZfParsing.int(item.obj("xnm"))
        val xqm = ZfParsing.text(item.obj("xqm"))
        val sourceId = when {
            !explicitId.isNullOrBlank() -> explicitId.trim()
            xnm != null && xqm != null -> xnm.toString() + "-" + xqm
            else -> return null
        }
        if (sourceId.isBlank()) return null
        val label = ZfParsing.text(item.obj("label"))
            ?: ZfParsing.text(item.obj("xqmmc"))
            ?: ZfParsing.text(item.obj("name"))
            ?: sourceId
        val academicYear = xnm ?: sourceId.substringBefore('-').toIntOrNull()
        val ordinal = ZfParsing.int(item.obj("ordinal"))
            ?: xqm?.let { ORDINAL_BY_TERM_CODE[it] }
            ?: sourceId.substringAfter('-', "").toIntOrNull()
        return RemoteTerm(
            sourceId = sourceId,
            label = label,
            academicYear = academicYear,
            ordinal = ordinal
        )
    }

    // ---- 课表 ----

    private class ParsedCourses(val courses: List<RemoteCourse>, val incomplete: Boolean)

    private class CourseBuilder(
        val key: String,
        val name: String,
        val code: String?,
        val teacher: String?,
        val credits: BigDecimal?
    ) {
        val meetings = mutableListOf<RemoteMeeting>()

        fun build(): RemoteCourse = RemoteCourse(
            sourceId = "kb:" + key,
            name = name,
            code = code,
            credits = credits,
            teacher = teacher,
            meetings = meetings.sortedWith(compareBy({ it.weekday }, { it.startSection }))
        )
    }

    private fun parseTimetableRows(rows: List<JsonValue>): ParsedCourses {
        var incomplete = false
        val builders = LinkedHashMap<String, CourseBuilder>()
        rows.forEach { row ->
            val name = ZfParsing.text(row.obj("kcmc"))
                ?: ZfParsing.text(row.obj("courseName"))
                ?: ZfParsing.text(row.obj("name"))
            val weekday = ZfParsing.int(row.obj("xqj") ?: row.obj("weekday"))
            val sections = parseSections(row)
            if (name.isNullOrBlank() || weekday == null || weekday !in 1..7 || sections == null) {
                incomplete = true
                return@forEach
            }
            val rawWeeks = ZfParsing.text(row.obj("zcd")) ?: ZfParsing.text(row.obj("weeks"))
            val weeks = when {
                rawWeeks.isNullOrBlank() -> emptySet()
                else -> ZfWeeks.parseStrict(rawWeeks) ?: run {
                    incomplete = true
                    emptySet()
                }
            }
            val code = ZfParsing.text(row.obj("kch")) ?: ZfParsing.text(row.obj("code"))
            val teacher = zfEmptyToNull(ZfParsing.text(row.obj("xm")) ?: ZfParsing.text(row.obj("teacher")))
            val location = zfEmptyToNull(
                ZfParsing.text(row.obj("cdmc")) ?: ZfParsing.text(row.obj("location")) ?: ZfParsing.text(row.obj("room"))
            )
            val credits = (ZfParsing.text(row.obj("xf")) ?: ZfParsing.text(row.obj("credits")))?.toBigDecimalOrNull()
            val key = ZfParsing.text(row.obj("jxb_id"))
                ?: ZfParsing.text(row.obj("jxbId"))
                ?: listOfNotNull(code, name, teacher).joinToString("|")
            val builder = builders.getOrPut(key) { CourseBuilder(key, name, code, teacher, credits) }
            builder.meetings += RemoteMeeting(
                weekday = weekday,
                startSection = sections.first,
                endSection = sections.second,
                weeks = weeks,
                location = location,
                teacher = teacher
            )
        }
        return ParsedCourses(builders.values.map { it.build() }, incomplete)
    }

    private fun parseSections(row: JsonValue): Pair<Int, Int>? {
        val start = ZfParsing.int(row.obj("startSection"))
        val end = ZfParsing.int(row.obj("endSection"))
        if (start != null && end != null) return ZfParsing.validSections(start, end)
        return ZfParsing.sections(ZfParsing.text(row.obj("jcs")) ?: ZfParsing.text(row.obj("sections")))
    }

    // ---- 成绩 ----

    private class ParsedGrades(val grades: List<RemoteGrade>, val incomplete: Boolean)

    private fun parseGradeRows(items: List<JsonValue>, term: RemoteTerm?): ParsedGrades {
        var incomplete = false
        val used = HashMap<String, Int>()
        val grades = items.mapNotNull { item ->
            val name = ZfParsing.text(item.obj("kcmc"))
                ?: ZfParsing.text(item.obj("courseName"))
                ?: ZfParsing.text(item.obj("name"))
            if (name.isNullOrBlank()) {
                incomplete = true
                return@mapNotNull null
            }
            val code = ZfParsing.text(item.obj("kch")) ?: ZfParsing.text(item.obj("code"))
            val attempt = ZfParsing.text(item.obj("ksxz")) ?: ZfParsing.text(item.obj("attempt"))
            // 成绩行没有稳定主键：用课程代码 + 考核性质做基串，重复出现时追加序号
            val base = listOfNotNull(code ?: name, attempt).joinToString("|")
            val ordinal = (used[base] ?: 0) + 1
            used[base] = ordinal
            RemoteGrade(
                sourceId = if (ordinal == 1) "cj:" + base else "cj:" + base + "#" + ordinal,
                courseName = name,
                credits = (ZfParsing.text(item.obj("xf")) ?: ZfParsing.text(item.obj("credits")))?.toBigDecimalOrNull(),
                scoreText = ZfParsing.text(item.obj("cj")) ?: ZfParsing.text(item.obj("score")),
                gradePoint = (ZfParsing.text(item.obj("jd")) ?: ZfParsing.text(item.obj("gradePoint")))
                    ?.toBigDecimalOrNull(),
                attempt = attempt,
                termSourceId = term?.sourceId ?: termCodeOf(item),
                category = ZfParsing.text(item.obj("kclb")) ?: ZfParsing.text(item.obj("category"))
            )
        }
        return ParsedGrades(grades, incomplete)
    }

    private fun termCodeOf(item: JsonValue): String? {
        val xnm = ZfParsing.int(item.obj("xnm")) ?: return null
        val xqm = ZfParsing.text(item.obj("xqm")) ?: return null
        return xnm.toString() + "-" + xqm
    }

    // ---- 考试 ----

    private class ParsedExams(val exams: List<RemoteExam>, val incomplete: Boolean)

    private fun parseExamRows(items: List<JsonValue>): ParsedExams {
        var incomplete = false
        val used = HashMap<String, Int>()
        val exams = items.mapNotNull { item ->
            val name = ZfParsing.text(item.obj("kcmc"))
                ?: ZfParsing.text(item.obj("courseName"))
                ?: ZfParsing.text(item.obj("name"))
            if (name.isNullOrBlank()) {
                incomplete = true
                return@mapNotNull null
            }
            val slot = ZfParsing.examSlot(
                ZfParsing.text(item.obj("kssj")) ?: ZfParsing.text(item.obj("date"))
            )
            val end = slot?.end ?: ZfParsing.time(ZfParsing.text(item.obj("jssj")) ?: ZfParsing.text(item.obj("end")))
            val kind = ZfParsing.text(item.obj("ksxz")) ?: ZfParsing.text(item.obj("kind"))
            val code = ZfParsing.text(item.obj("kch")) ?: ZfParsing.text(item.obj("code"))
            val base = listOfNotNull(code ?: name, slot?.date?.toString(), kind).joinToString("|")
            val ordinal = (used[base] ?: 0) + 1
            used[base] = ordinal
            RemoteExam(
                sourceId = if (ordinal == 1) "ks:" + base else "ks:" + base + "#" + ordinal,
                courseName = name,
                date = slot?.date,
                startTime = slot?.start,
                endTime = end,
                location = zfEmptyToNull(
                    ZfParsing.text(item.obj("cdmc"))
                        ?: ZfParsing.text(item.obj("location"))
                        ?: ZfParsing.text(item.obj("room"))
                ),
                seat = zfEmptyToNull(ZfParsing.text(item.obj("zwh")) ?: ZfParsing.text(item.obj("seat"))),
                kind = kind
            )
        }
        return ParsedExams(exams, incomplete)
    }

    private companion object {
        /** 登录入口：未登录时会跳转统一认证。UNVERIFIED 合成路径。 */
        const val LOGIN_PATH = "/jwglxt/xtgl/login_slogin.html"

        /** 学生首页：菜单链接（含 gnmkdm 菜单 ID）只在页面里出现，不在代码里。 */
        const val INDEX_PATH = "/jwglxt/xtgl/index_initMenu.html"

        const val EVENT_ID_FIELD = "_eventId"
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

        /** 新正方的学期代码约定；未知代码保持 ordinal=null，不猜。UNVERIFIED。 */
        val ORDINAL_BY_TERM_CODE = mapOf("3" to 1, "12" to 2, "16" to 3)
    }
}
