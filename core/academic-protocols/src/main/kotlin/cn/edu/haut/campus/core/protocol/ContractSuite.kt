package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import kotlinx.coroutines.runBlocking

/** 每个协议必须提供的夹具集合；harness 不关心 URL 形态，只验证结构化结果。 */
data class ProtocolFixtures(
    val basePath: String,
    val origin: String,
    val hosts: Set<String>,
    val auth: String = "auth.json",
    val terms: String = "terms.json",
    val timetable: String = "timetable.json",
    val grades: String = "grades.json",
    val exams: String = "exams.json",
    val sessionExpired: String = "session-expired.json",
    val untrustedRedirect: String = "redirect.json",
    val partialGrades: String = "partial-grades.json",
    val emptyTimetable: String = "empty-timetable.json"
)

/** 适配器作者实现此接口后即可复用统一的九项合同检查。 */
interface GatewayContractSpec {
    val protocol: AcademicProtocolFamily
    val fixtures: ProtocolFixtures

    /** 每次调用返回全新的 Gateway 实例，避免状态跨场景泄漏。 */
    fun createGateway(): AcademicGateway

    /** 与 timetable/grades 夹具对应的学期；必须是夹具里真实出现的来源学期。 */
    val contractTerm: RemoteTerm

    fun credentials(): ProtocolCredentials?

    fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(fixtures.hosts)
}

class ContractScenario(
    val protocol: AcademicProtocolFamily,
    val transport: ReplayTransport,
    val session: GatewaySession,
    val gateway: AcademicGateway
)

/**
 * 九项通用合同检查：认证、学期、课表、成绩、考试、会话过期、越权跳转、部分响应、权威空快照。
 * 全部使用录制夹具离线执行，不触发任何真实学校网络请求。
 */
class GatewayContractSuite(private val spec: GatewayContractSpec) {

    fun runAll() {
        val failures = mutableListOf<String>()
        listOf(
            "authenticate" to ::checkAuthenticate,
            "terms" to ::checkTerms,
            "timetable" to ::checkTimetable,
            "grades" to ::checkGrades,
            "exams" to ::checkExams,
            "session-expired" to ::checkSessionExpired,
            "untrusted-redirect" to ::checkUntrustedRedirect,
            "partial-response" to ::checkPartial,
            "authoritative-empty" to ::checkEmpty
        ).forEach { (name, check) ->
            try {
                check()
            } catch (error: Throwable) {
                failures += spec.protocol.name + " / " + name + "：" + (error.message ?: error::class.simpleName)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("协议合同检查失败：\n" + failures.joinToString("\n"))
        }
    }

    private fun scenario(fixture: String): ContractScenario {
        val transport = ReplayTransport.fromResource(spec.fixtures.basePath + "/" + fixture)
        val policy = spec.urlPolicy()
        val jar = ProtocolCookieJar(spec.fixtures.hosts)
        val session = GatewaySession(
            schoolId = "contract-school",
            accountId = "contract-account",
            origin = spec.fixtures.origin,
            transport = transport,
            urlPolicy = policy,
            cookieJar = jar
        )
        return ContractScenario(spec.protocol, transport, session, spec.createGateway())
    }

    fun checkAuthenticate() = runBlocking {
        val scenario = scenario(spec.fixtures.auth)
        val outcome = scenario.gateway.authenticate(scenario.session, spec.credentials())
        check(outcome is AuthOutcome.Authenticated) { "合法凭据必须认证成功，实际为 " + outcome }
        check(scenario.transport.consumed.isNotEmpty()) { "认证必须实际发出请求" }
    }

    fun checkTerms() = runBlocking {
        val scenario = scenario(spec.fixtures.terms)
        val first = scenario.gateway.terms(scenario.session)
        val initial = requireContent(first)
        check(initial.value.isNotEmpty()) { "学期列表不能为空" }
        check(initial.value.all { it.sourceId.isNotBlank() && it.label.isNotBlank() }) { "学期字段不完整" }
        check(initial.value.map { it.sourceId }.distinct().size == initial.value.size) { "学期来源 ID 重复" }

        val second = scenario(spec.fixtures.terms)
        val again = second.gateway.terms(second.session)
        check(again is AcademicResult.Content) { "同夹具重复读取必须稳定成功" }
    }

    fun checkTimetable() = runBlocking {
        val scenario = scenario(spec.fixtures.timetable)
        val result = scenario.gateway.timetable(scenario.session, spec.contractTerm)
        val content = requireContent(result)
        check(content.value.courses.isNotEmpty()) { "课表不能为空" }
        check(content.value.termSourceId.isNotBlank()) { "课表必须绑定来源学期" }
        check(content.value.completeness != SnapshotCompleteness.PARTIAL) { "完整夹具不应产生部分快照" }
        content.value.courses.forEach { course ->
            check(course.sourceId.isNotBlank() && course.name.isNotBlank()) { "课程身份不完整" }
            course.meetings.forEach { meeting ->
                check(meeting.weekday in 1..7) { "星期越界" }
                check(meeting.startSection <= meeting.endSection) { "节次区间非法" }
            }
        }
        check(content.value.courses.map { it.sourceId }.distinct().size == content.value.courses.size) {
            "课程来源 ID 重复，可能把同名课程合并"
        }
    }

    fun checkGrades() = runBlocking {
        val scenario = scenario(spec.fixtures.grades)
        val content = requireContent(scenario.gateway.grades(scenario.session, null))
        check(content.value.completeness == SnapshotCompleteness.COMPLETE) { "多页成绩必须完整" }
        val ids = content.value.grades.map { it.sourceId }
        check(ids.distinct().size == ids.size) { "成绩来源 ID 重复，可能出现 identity drift" }
        check(content.value.grades.all { it.courseName.isNotBlank() }) { "成绩缺少课程名称" }
    }

    fun checkExams() = runBlocking {
        val scenario = scenario(spec.fixtures.exams)
        val content = requireContent(scenario.gateway.exams(scenario.session, null))
        val ids = content.value.exams.map { it.sourceId }
        check(ids.distinct().size == ids.size) { "考试来源 ID 重复" }
        check(content.value.exams.all { it.courseName.isNotBlank() }) { "考试缺少课程名称" }
    }

    fun checkSessionExpired() = runBlocking {
        val scenario = scenario(spec.fixtures.sessionExpired)
        val result = scenario.gateway.grades(scenario.session, null)
        val mapped = when (result) {
            is AcademicResult.Failure -> result.code == AcademicError.SESSION_EXPIRED ||
                result.code == AcademicError.AUTH_REJECTED
            is AcademicResult.RequiresAction -> result.action == UserAction.REAUTHENTICATE || result.action == UserAction.LOGIN
            else -> false
        }
        check(mapped) { "登录过期必须映射为 SESSION_EXPIRED 或 REAUTHENTICATE，实际为 " + result }
    }

    fun checkUntrustedRedirect() = runBlocking {
        val scenario = scenario(spec.fixtures.untrustedRedirect)
        val attempt = runCatching { scenario.gateway.grades(scenario.session, null) }
        val failure = attempt.exceptionOrNull()
        val mapped = when {
            failure is TransportFailure -> failure.error == AcademicError.UNTRUSTED_REDIRECT
            failure == null -> {
                val result = attempt.getOrNull()
                result is AcademicResult.Failure && result.code == AcademicError.UNTRUSTED_REDIRECT
            }
            else -> false
        }
        check(mapped) { "越权跳转必须被拦截为 UNTRUSTED_REDIRECT，实际为 " + (failure?.message ?: "未拦截") }
    }

    fun checkPartial() = runBlocking {
        val scenario = scenario(spec.fixtures.partialGrades)
        val result = scenario.gateway.grades(scenario.session, null)
        val acceptable = when (result) {
            is AcademicResult.Failure -> result.code == AcademicError.PARTIAL_RESPONSE ||
                result.code == AcademicError.STALE_SNAPSHOT
            is AcademicResult.Content -> result.value.completeness != SnapshotCompleteness.COMPLETE
            else -> false
        }
        check(acceptable) { "部分响应不得以完整快照发布，实际为 " + result }
    }

    fun checkEmpty() = runBlocking {
        val scenario = scenario(spec.fixtures.emptyTimetable)
        val result = scenario.gateway.timetable(scenario.session, spec.contractTerm)
        val acceptable = when (result) {
            is AcademicResult.Empty -> result.authoritative
            is AcademicResult.Content -> result.value.courses.isEmpty() &&
                result.value.completeness == SnapshotCompleteness.EMPTY_CONFIRMED
            else -> false
        }
        check(acceptable) { "合法空课表必须标记为权威空快照，实际为 " + result }
    }

    private fun <T> requireContent(result: AcademicResult<T>): AcademicResult.Content<T> =
        result as? AcademicResult.Content<T> ?: throw AssertionError("预期 Content，实际为 " + result)
}
