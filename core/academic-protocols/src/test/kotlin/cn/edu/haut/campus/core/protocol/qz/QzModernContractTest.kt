package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.protocol.AcademicGateway
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
import org.junit.Assert.assertNull
import org.junit.Test

private const val MODERN_HOST = "jw.qz-modern.example.edu"
private const val MODERN_ORIGIN = "https://" + MODERN_HOST

/**
 * UNVERIFIED：夹具按新版强智 jsxsd（JSON + HTML 混用形态）合成，不代表任何真实学校。
 */
internal class QzModernContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.QZ_MODERN
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/qz-modern",
        origin = MODERN_ORIGIN,
        hosts = setOf(MODERN_HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2024:3", label = "2024-2025学年第一学期")

    override fun createGateway(): AcademicGateway = QzModernGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20240001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(
        allowedHosts = setOf(MODERN_HOST),
        authPaths = setOf("/jsxsd/xk/LoginToXk"),
        authHost = MODERN_HOST
    )
}

class QzModernContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = MODERN_ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(
            allowedHosts = setOf(MODERN_HOST),
            authPaths = setOf("/jsxsd/xk/LoginToXk"),
            authHost = MODERN_HOST
        ),
        cookieJar = ProtocolCookieJar(setOf(MODERN_HOST))
    )

    @Test
    fun 合同检查_新强智只读链路必须全部通过() {
        GatewayContractSuite(QzModernContractSpec()).runAll()
    }

    @Test
    fun 课表JSON形态与HTML网格形态都能解析() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/qz-modern/timetable-json.json")
        val term = RemoteTerm(sourceId = "2024:3", label = "2024-2025学年第一学期")
        val result = QzModernGateway().timetable(session(transport), term)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(2, content.value.courses.size)
        val math = content.value.courses.single { it.name == "高等数学" }
        assertEquals(2, math.meetings.size)
    }

    @Test
    fun 课表页改版为提示文案时必须返回PAGE_CHANGED而非空课表() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/qz-modern/timetable-changed.json")
        val term = RemoteTerm(sourceId = "2024:3", label = "2024-2025学年第一学期")
        val result = QzModernGateway().timetable(session(transport), term)
        val failure = result as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + result)
        assertEquals(AcademicError.PAGE_CHANGED, failure.code)
    }

    @Test
    fun 学期编码3与12与16不强行推导学期序数() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/qz-modern/term-trap.json")
        val result = QzModernGateway().terms(session(transport))
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(listOf("2024:3", "2025:16"), content.value.map { it.sourceId })
        content.value.forEach { assertNull(it.ordinal) }
    }
}
