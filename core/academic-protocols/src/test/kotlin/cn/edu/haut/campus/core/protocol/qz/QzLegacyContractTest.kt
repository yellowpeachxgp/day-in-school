package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.GatewayContractSpec
import cn.edu.haut.campus.core.protocol.GatewayContractSuite
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GatewayTransport
import cn.edu.haut.campus.core.protocol.GatewayUrlPolicy
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.ProtocolCookieJar
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.ProtocolFixtures
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.ReplayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LEGACY_HOST = "jw.qz-legacy.example.edu"
private const val LEGACY_ORIGIN = "https://" + LEGACY_HOST

/**
 * UNVERIFIED：夹具按老版强智 jsxsd（GBK 编码 + POST 查询条件后 302 到结果页）合成，不代表任何真实学校。
 */
internal class QzLegacyContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.QZ_LEGACY
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/qz-legacy",
        origin = LEGACY_ORIGIN,
        hosts = setOf(LEGACY_HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2024-2025-1", label = "2024-2025学年第一学期")

    override fun createGateway(): AcademicGateway = QzLegacyGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20240001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(
        allowedHosts = setOf(LEGACY_HOST),
        authPaths = setOf("/jsxsd/xk/LoginToXk"),
        authHost = LEGACY_HOST
    )
}

class QzLegacyContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = LEGACY_ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(
            allowedHosts = setOf(LEGACY_HOST),
            authPaths = setOf("/jsxsd/xk/LoginToXk"),
            authHost = LEGACY_HOST
        ),
        cookieJar = ProtocolCookieJar(setOf(LEGACY_HOST))
    )

    @Test
    fun 合同检查_老强智只读链路必须全部通过() {
        GatewayContractSuite(QzLegacyContractSpec()).runAll()
    }

    @Test
    fun 登录页出现图形验证码字段时必须转人工处理() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/qz-legacy/captcha-login.json")
        val outcome = QzLegacyGateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20240001", "synthetic-password")
        )
        val action = outcome as? AuthOutcome.RequiresUserAction ?: error("预期 RequiresUserAction，实际为 " + outcome)
        assertEquals(UserAction.COMPLETE_CAPTCHA, action.action)
        assertEquals(1, transport.consumed.size)
    }

    @Test
    fun 查询轮次必须先POST条件再GET结果页且正确解码GBK() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/qz-legacy/timetable.json")
        val term = RemoteTerm(sourceId = "2024-2025-1", label = "2024-2025学年第一学期")
        val result = QzLegacyGateway().timetable(session(transport), term)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(listOf(HttpMethod.POST, HttpMethod.GET), transport.consumed.map { it.method })
        assertTrue(content.value.courses.any { it.name == "高等数学" })
    }
}
