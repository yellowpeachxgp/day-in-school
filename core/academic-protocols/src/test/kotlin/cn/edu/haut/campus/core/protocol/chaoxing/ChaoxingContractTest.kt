package cn.edu.haut.campus.core.protocol.chaoxing

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.GatewayContractSpec
import cn.edu.haut.campus.core.protocol.GatewayContractSuite
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GatewayTransport
import cn.edu.haut.campus.core.protocol.GatewayUrlPolicy
import cn.edu.haut.campus.core.protocol.ProtocolCookieJar
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.ProtocolFixtures
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.ReplayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOST = "cx-synthetic.example.edu"
private const val ORIGIN = "https://" + HOST

/**
 * UNVERIFIED：夹具是按超星智慧教务公开的表单/JSON/表格形态合成的，不代表任何真实学校。
 */
internal class ChaoxingContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.CHAOXING_ACADEMIC
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/chaoxing",
        origin = ORIGIN,
        hosts = setOf(HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2026-2027-1", label = "2026-2027学年第一学期")

    override fun createGateway(): AcademicGateway = ChaoxingGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20260001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy =
        GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/academic/login"), authHost = HOST)
}

class ChaoxingContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/academic/login"), authHost = HOST),
        cookieJar = ProtocolCookieJar(setOf(HOST))
    )

    @Test
    fun 合同检查_超星只读链路必须全部通过() {
        GatewayContractSuite(ChaoxingContractSpec()).runAll()
    }

    @Test
    fun 超星教务会话不得复用学习通账号() = runBlocking {
        val studyHost = "passport2.chaoxing.com"
        val transport = ReplayTransport.fromJson("{\"exchanges\":[]}")
        val studySession = GatewaySession(
            schoolId = "synthetic-school",
            accountId = "synthetic-account",
            origin = "https://" + studyHost,
            transport = transport,
            urlPolicy = GatewayUrlPolicy(setOf(studyHost)),
            cookieJar = ProtocolCookieJar(setOf(studyHost))
        )
        val gateway = ChaoxingGateway()
        val outcome = gateway.authenticate(
            studySession,
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        assertTrue("学习通账号不得用于教务认证", outcome is AuthOutcome.Rejected)
        assertEquals("拒绝时必须一枪不发", 0, transport.consumed.size)
        val grades = gateway.grades(studySession, null)
        val failure = grades as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + grades)
        assertEquals(AcademicError.PERMISSION_REVOKED, failure.code)
        assertEquals(0, transport.consumed.size)
    }

    @Test
    fun 上课时间表达式必须解析出星期节次与周次() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/chaoxing/timetable.json")
        val term = RemoteTerm(sourceId = "2026-2027-1", label = "2026-2027学年第一学期")
        val result = ChaoxingGateway().timetable(session(transport), term)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        val algebra = content.value.courses.single { it.name == "线性代数" }
        assertEquals(listOf(1, 3), algebra.meetings.map { it.weekday })
        assertEquals(listOf(1, 3), algebra.meetings.map { it.startSection })
        assertEquals(listOf(2, 4), algebra.meetings.map { it.endSection })
        assertEquals((1..16).toSet(), algebra.meetings[0].weeks)
        assertEquals("C301", algebra.meetings[0].location)
        val probability = content.value.courses.single { it.name == "概率论" }.meetings.single()
        assertEquals(5, probability.startSection)
        assertEquals((1..8).toSet(), probability.weeks)
        // 节次区间不能被当成周次：9-16 周必须只包含 9..16。
        assertEquals((9..16).toSet(), ChaoxingTimeParser.weeks("星期一 第1-2节 9-16周"))
        assertEquals(emptySet<Int>(), ChaoxingTimeParser.weeks("星期一 第1-2节"))
    }

    @Test
    fun 成绩分页按短页终止且必须读满() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/chaoxing/grades.json")
        val result = ChaoxingGateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(SnapshotCompleteness.COMPLETE, content.value.completeness)
        assertEquals(listOf("CX1001#1", "CX1002#2", "CX1003#3"), content.value.grades.map { it.sourceId })
        assertEquals(
            listOf(
                ORIGIN + "/academic/score/table?page=1",
                ORIGIN + "/academic/score/table?page=2"
            ),
            transport.consumed.map { it.url }
        )
        assertEquals(2.8.toBigDecimal(), content.value.grades[2].gradePoint)
    }

    @Test
    fun 学期JSON解析失败必须映射为结构化错误而不是崩溃() = runBlocking {
        val transport = ReplayTransport.fromJson(
            "{\"exchanges\":[{\"method\":\"GET\",\"url\":\"" + ORIGIN + "/academic/api/terms\"," +
                "\"status\":200,\"headers\":{},\"body\":\"<html>maintenance</html>\"}]}"
        )
        val result = ChaoxingGateway().terms(session(transport))
        val failure = result as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + result)
        assertEquals(AcademicError.SOURCE_DATA_INVALID, failure.code)
    }
}
