package cn.edu.haut.campus.core.protocol.jinzhi

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonParseException
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
import cn.edu.haut.campus.core.protocol.RemoteCourse
import cn.edu.haut.campus.core.protocol.RemoteExam
import cn.edu.haut.campus.core.protocol.RemoteGrade
import cn.edu.haut.campus.core.protocol.RemoteMeeting
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TimetableSnapshot
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.math.BigDecimal

/**
 * 金智（jinzhi）适配器：路径全部走 [JinzhiEndpoints]（SchoolProfile 给出 basePath），
 * 认证既支持"跳转到统一身份认证 CAS 页面再提交表单"，也支持教务系统自带登录表单；
 * 课表/成绩/考试都是列表型 HTML 表格，列名用别名容忍（课程名/课程名称/科目…）。
 *
 * UNVERIFIED（未验证）：页面与字段形态按公开金智部署惯例推导，用**合成夹具**覆盖，
 * 未在任何真实学校站点登录、抓取或校验；不得据此宣称"某校已验证可用"。
 */
class JinzhiGateway(
    private val endpoints: JinzhiEndpoints = JinzhiEndpoints("")
) : AcademicGateway {

    override val descriptor = GatewayDescriptor(
        id = "jinzhi",
        protocol = AcademicProtocolFamily.JINZHI,
        displayName = "金智教育（可配置 basePath）",
        authMode = AuthMode.SSO_REDIRECT,
        notes = "UNVERIFIED：支持 CAS 跳转与本地表单两种认证路径，页面形态未在任何真实学校验证。"
    )

    override suspend fun authenticate(
        session: GatewaySession,
        credentials: ProtocolCredentials?
    ): AuthOutcome {
        val secret = credentials ?: return AuthOutcome.Rejected("缺少登录凭据")
        return try {
            secret.use { value ->
                val entry = fetchTracked(
                    session,
                    GatewayRequest(
                        HttpMethod.GET,
                        endpoints.url(session.origin, endpoints.loginEntry),
                        purpose = RequestPurpose.READ
                    )
                )
                if (entry.response.isUnauthorized) return@use AuthOutcome.Rejected("登录入口拒绝访问")
                if (!entry.response.isSuccess) return@use AuthOutcome.Rejected("登录入口返回异常状态")
                val html = entry.response.body
                if (JinzhiLoginForm.requiresCaptcha(html)) {
                    return@use AuthOutcome.RequiresUserAction(
                        UserAction.COMPLETE_CAPTCHA,
                        "该部署的登录页要求图形验证码，需要在官方页面完成"
                    )
                }
                val form = JinzhiLoginForm.parse(html) ?: return@use AuthOutcome.RequiresUserAction(
                    UserAction.LOGIN,
                    "未识别出登录表单，需要在官方页面手动登录"
                )
                val action = form.action?.trim().orEmpty()
                // 表单 action 可能是相对路径，也可能是 CAS 的绝对地址；两种情况都必须过来源策略。
                val actionUrl = if (action.isEmpty()) {
                    entry.url
                } else {
                    session.urlPolicy.resolve(entry.url, action, RequestPurpose.AUTH)
                }
                val response = session.send(
                    GatewayRequest(
                        method = HttpMethod.POST,
                        url = actionUrl,
                        body = form.buildBody(value.studentId, value.password),
                        contentType = "application/x-www-form-urlencoded",
                        purpose = RequestPurpose.AUTH
                    )
                )
                when {
                    response.isUnauthorized -> AuthOutcome.Rejected("认证被拒绝")
                    response.isRedirect -> AuthOutcome.Authenticated
                    response.isSuccess && JinzhiLoginForm.looksRejected(response.body) ->
                        AuthOutcome.Rejected("认证被拒绝：用户名或密码不正确")
                    response.isSuccess && JinzhiLoginForm.parse(response.body) != null ->
                        AuthOutcome.Rejected("认证失败：仍停留在登录表单")
                    response.isSuccess -> AuthOutcome.Authenticated
                    else -> AuthOutcome.Rejected("认证返回异常状态")
                }
            }
        } catch (failure: TransportFailure) {
            AuthOutcome.Rejected(failure.message ?: "认证请求被安全策略拒绝")
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        val response = session.fetch(
            GatewayRequest(
                HttpMethod.GET,
                endpoints.url(session.origin, endpoints.termsPath),
                purpose = RequestPurpose.READ
            )
        )
        ensureReadable(response)
        val root = try {
            Json.parse(response.body)
        } catch (_: JsonParseException) {
            return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "学期列表不是合法 JSON"
            )
        }
        val terms = root.arr("terms").orEmpty().mapNotNull { item ->
            val id = (item.str("id") ?: item.str("termId") ?: item.str("code"))?.trim()
            val label = (item.str("name") ?: item.str("label") ?: item.str("title"))?.trim()
            if (id.isNullOrEmpty() || label.isNullOrEmpty()) return@mapNotNull null
            RemoteTerm(sourceId = id, label = label)
        }.distinctBy { it.sourceId }
        if (terms.isEmpty()) {
            return@guarded AcademicResult.Empty(reason = "教务系统没有返回学期", authoritative = true)
        }
        AcademicResult.Content(terms, provenance(session))
    }

    override suspend fun timetable(
        session: GatewaySession,
        term: RemoteTerm
    ): AcademicResult<TimetableSnapshot> = guarded {
        val response = read(session, endpoints.timetablePath, term)
        val table = JinzhiHtml.tables(response).firstOrNull { it.hasAny(*COURSE_ALIASES) }
            ?: return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "课表页面没有可识别的课程表头"
            )
        val nameColumn = table.column(*COURSE_ALIASES)
        val timeColumn = table.column("上课时间", "时间", "节次", "上课节次")
        val placeColumn = table.column("上课地点", "地点", "教室", "场地")
        val teacherColumn = table.column("任课教师", "教师", "老师", "主讲教师")
        val codeColumn = table.column("课程代码", "课程号", "课程编号")
        val creditColumn = table.column("学分")
        if (timeColumn == null) throw TransportFailure(AcademicError.PAGE_CHANGED, "课表缺少时间列")
        val drafts = LinkedHashMap<String, CourseDraft>()
        for (row in table.rows) {
            val name = table.value(row, nameColumn) ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "数据行缺少有效课程或时段")
            val teacher = table.value(row, teacherColumn)
            val code = table.value(row, codeColumn)
            val key = if (code.isNullOrEmpty()) "jinzhi:" + name + "|" + teacher.orEmpty() else "jinzhi:" + code
            val draft = drafts.getOrPut(key) {
                CourseDraft(key, name, teacher, code, credit(creditColumn?.let { table.value(row, it) }))
            }
            val meeting = JinzhiSchedule.parse(table.value(row, timeColumn)) ?: throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "数据行缺少有效课程或时段")
            draft.meetings += RemoteMeeting(
                weekday = meeting.weekday,
                startSection = meeting.startSection,
                endSection = meeting.endSection,
                weeks = meeting.weeks,
                location = table.value(row, placeColumn),
                teacher = teacher
            )
        }
        val courses = drafts.values.map { draft ->
            RemoteCourse(
                sourceId = draft.sourceId,
                name = draft.name,
                code = draft.code,
                credits = draft.credits,
                teacher = draft.teacher,
                meetings = draft.meetings.toList()
            )
        }
        if (courses.any { it.meetings.isEmpty() || it.meetings.any { meeting -> meeting.weeks.isEmpty() } })
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "课表含无法确认的时段或周次")
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty(reason = "当前学期没有课程", authoritative = true)
        }
        AcademicResult.Content(
            TimetableSnapshot(term.sourceId, courses, SnapshotCompleteness.COMPLETE),
            provenance(session)
        )
    }

    override suspend fun grades(
        session: GatewaySession,
        term: RemoteTerm?
    ): AcademicResult<GradeSnapshot> = guarded {
        val response = read(session, endpoints.gradesPath, term)
        val table = JinzhiHtml.tables(response).firstOrNull { it.hasAny(*COURSE_ALIASES) }
            ?: return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "成绩页面没有可识别的课程表头"
            )
        val nameColumn = table.column(*COURSE_ALIASES)
        val creditColumn = table.column("学分", "学分值")
        val scoreColumn = table.column("成绩", "分数", "总评", "总评成绩")
        val pointColumn = table.column("绩点", "平均绩点")
        val attemptColumn = table.column("修读性质", "考试性质", "初修重考")
        val categoryColumn = table.column("课程性质", "课程类别", "类别")
        val seen = LinkedHashMap<String, Int>()
        val grades = table.rows.mapNotNull { row ->
            val name = table.value(row, nameColumn) ?: return@mapNotNull null
            val attempt = table.value(row, attemptColumn)
            val base = "jinzhi-grade:" + name + (if (attempt.isNullOrEmpty()) "" else "@" + attempt)
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            RemoteGrade(
                sourceId = if (count == 1) base else base + "#" + count,
                courseName = name,
                credits = credit(table.value(row, creditColumn)),
                scoreText = table.value(row, scoreColumn),
                gradePoint = credit(table.value(row, pointColumn)),
                attempt = attempt,
                termSourceId = term?.sourceId,
                category = table.value(row, categoryColumn)
            )
        }
        if (grades.isEmpty()) {
            return@guarded AcademicResult.Empty(reason = "没有任何成绩记录", authoritative = true)
        }
        val completeness = if (JinzhiHtml.looksPartial(response)) {
            SnapshotCompleteness.PARTIAL
        } else {
            SnapshotCompleteness.COMPLETE
        }
        AcademicResult.Content(
            GradeSnapshot(term?.sourceId, grades, completeness),
            provenance(session)
        )
    }

    override suspend fun exams(session: GatewaySession, term: RemoteTerm?): AcademicResult<ExamSnapshot> = guarded {
        val response = read(session, endpoints.examsPath, term)
        val table = JinzhiHtml.tables(response).firstOrNull { it.hasAny(*COURSE_ALIASES) }
            ?: return@guarded AcademicResult.Failure(
                AcademicError.PAGE_CHANGED,
                retryable = false,
                detail = "考试页面没有可识别的课程表头"
            )
        val nameColumn = table.column(*COURSE_ALIASES)
        val dateColumn = table.column("考试日期", "日期")
        val timeColumn = table.column("考试时间", "时间")
        val placeColumn = table.column("考试地点", "地点", "考场", "教室")
        val seatColumn = table.column("座位号", "座位")
        val kindColumn = table.column("考试方式", "考核方式", "形式")
        val seen = LinkedHashMap<String, Int>()
        val exams = table.rows.mapNotNull { row ->
            val name = table.value(row, nameColumn) ?: return@mapNotNull null
            val date = JinzhiSchedule.parseDate(table.value(row, dateColumn))
                ?: JinzhiSchedule.parseDate(table.value(row, timeColumn))
            val range = JinzhiSchedule.parseTimeRange(table.value(row, timeColumn))
            val base = "jinzhi-exam:" + name + "@" + (date?.toString() ?: "unknown")
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            RemoteExam(
                sourceId = if (count == 1) base else base + "#" + count,
                courseName = name,
                date = date,
                startTime = range?.first,
                endTime = range?.second,
                location = table.value(row, placeColumn),
                seat = table.value(row, seatColumn),
                kind = table.value(row, kindColumn)
            )
        }
        if (exams.isEmpty()) {
            return@guarded AcademicResult.Empty(reason = "没有考试安排", authoritative = true)
        }
        AcademicResult.Content(
            ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            provenance(session)
        )
    }

    private class CourseDraft(
        val sourceId: String,
        val name: String,
        var teacher: String?,
        var code: String?,
        var credits: BigDecimal?
    ) {
        val meetings = ArrayList<RemoteMeeting>()
    }

    private suspend fun read(session: GatewaySession, path: String, term: RemoteTerm?): String {
        val base = endpoints.url(session.origin, path)
        val query = term?.let { "?term=" + java.net.URLEncoder.encode(it.sourceId, Charsets.UTF_8) }.orEmpty()
        val response = session.fetch(GatewayRequest(HttpMethod.GET, base + query, purpose = RequestPurpose.READ))
        ensureReadable(response)
        return response.body
    }

    private fun ensureReadable(response: GatewayResponse) {
        if (response.isUnauthorized) throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务会话已过期")
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回了 " + response.status + " 状态")
        }
        if (JinzhiHtml.looksDenied(response.body)) {
            throw TransportFailure(AcademicError.ACCESS_DENIED, "当前账号没有该功能的访问权限")
        }
        if (JinzhiLoginForm.parse(response.body) != null) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务会话已过期，返回的是登录页")
        }
    }

    /** 手动逐跳跟随并记录最终地址：CAS 表单的 action 需要相对"真正落在的那个页面"解析。 */
    private suspend fun fetchTracked(
        session: GatewaySession,
        request: GatewayRequest,
        maxHops: Int = 6
    ): TrackedPage {
        var current = request
        repeat(maxHops) {
            val response = session.send(current)
            if (!response.isRedirect) return TrackedPage(response, current.url)
            val location = response.header("Location") ?: return TrackedPage(response, current.url)
            val next = session.urlPolicy.resolve(current.url, location, current.purpose)
            current = current.copy(
                url = next,
                method = if (response.status == 303) HttpMethod.GET else current.method
            )
        }
        throw TransportFailure(AcademicError.PAGINATION_LOOP, "认证跳转次数过多，已停止请求")
    }

    private class TrackedPage(val response: GatewayResponse, val url: String)

    private fun credit(raw: String?): BigDecimal? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { BigDecimal(text) }.getOrNull()
    }

    private fun provenance(session: GatewaySession): DataProvenance = DataProvenance(
        providerId = descriptor.id,
        schoolId = SchoolId(session.schoolId),
        accountId = AccountId(session.accountId),
        sourceSystem = "jinzhi-unverified"
    )

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }

    private companion object {
        val COURSE_ALIASES = arrayOf("课程名称", "课程名", "课程", "科目", "考试科目")
    }
}
