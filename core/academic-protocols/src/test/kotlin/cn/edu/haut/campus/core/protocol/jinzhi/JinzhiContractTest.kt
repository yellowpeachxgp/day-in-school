package cn.edu.haut.campus.core.protocol.jinzhi

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
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

private const val JW_HOST = "jw.jinzhi.example.edu"
private const val SSO_HOST = "sso.jinzhi.example.edu"
private const val ORIGIN = "https://" + JW_HOST

/**
 * UNVERIFIED：夹具按金智教育公开部署形态（可配置 basePath + CAS/本地表单认证）合成，不代表任何真实学校。
 */
internal class JinzhiContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.JINZHI
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/jinzhi",
        origin = ORIGIN,
        hosts = setOf(JW_HOST, SSO_HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2024-2025-1", label = "2024-2025学年第一学期")

    override fun createGateway(): AcademicGateway =
        JinzhiGateway(JinzhiEndpoints.fromBaseUrl(ORIGIN + "/academic"))

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20240001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(
        allowedHosts = setOf(JW_HOST, SSO_HOST),
        authPaths = setOf("/cas/login", "/academic/auth/doLogin")
    )
}

class JinzhiContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(
            allowedHosts = setOf(JW_HOST, SSO_HOST),
            authPaths = setOf("/cas/login", "/academic/auth/doLogin")
        ),
        cookieJar = ProtocolCookieJar(setOf(JW_HOST, SSO_HOST))
    )

    private fun gateway(): JinzhiGateway =
        JinzhiGateway(JinzhiEndpoints.fromBaseUrl(ORIGIN + "/academic"))

    @Test
    fun 合同检查_金智只读链路必须全部通过() {
        GatewayContractSuite(JinzhiContractSpec()).runAll()
    }

    @Test
    fun 本地表单登录与可配置basePath必须正常工作() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/jinzhi/form-auth.json")
        val outcome = gateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20240001", "synthetic-password")
        )
        assertTrue("本地表单登录应成功", outcome is AuthOutcome.Authenticated)
        assertEquals(
            listOf(
                ORIGIN + "/academic/auth/login",
                ORIGIN + "/academic/auth/doLogin"
            ),
            transport.consumed.map { it.url }
        )
    }

    @Test
    fun 权限不足提示页必须映射为ACCESS_DENIED() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/jinzhi/denied.json")
        val result = gateway().grades(session(transport), null)
        val failure = result as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + result)
        assertEquals(AcademicError.ACCESS_DENIED, failure.code)
    }

    @Test
    fun 成绩表头别名与周次节次解析必须准确() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/jinzhi/grades-alias.json")
        val result = gateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(3, content.value.grades.size)
        assertEquals("高等数学", content.value.grades.first().courseName)
        assertEquals("88", content.value.grades.first().scoreText)
    }
}
