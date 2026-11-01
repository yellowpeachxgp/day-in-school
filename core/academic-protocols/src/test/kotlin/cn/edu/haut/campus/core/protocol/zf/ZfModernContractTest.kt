package cn.edu.haut.campus.core.protocol.zf

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
import org.junit.Test

private const val JW_HOST = "jw.zf-modern.example.edu"
private const val SSO_HOST = "sso.zf-modern.example.edu"
private const val ORIGIN = "https://" + JW_HOST

/**
 * UNVERIFIED：夹具按新正方 jwglxt 公开形态合成，不代表任何真实学校。
 */
internal class ZfModernContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.ZF_MODERN
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/zf-modern",
        origin = ORIGIN,
        hosts = setOf(JW_HOST, SSO_HOST)
    )
    override val contractTerm = RemoteTerm(
        sourceId = "2025-3",
        label = "2025-2026学年第一学期",
        academicYear = 2025,
        ordinal = 1
    )

    override fun createGateway(): AcademicGateway = ZfModernGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20250001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(
        allowedHosts = setOf(JW_HOST, SSO_HOST),
        authPaths = setOf("/cas/login", "/jwglxt/xtgl/login_slogin.html")
    )
}

class ZfModernContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(
            allowedHosts = setOf(JW_HOST, SSO_HOST),
            authPaths = setOf("/cas/login", "/jwglxt/xtgl/login_slogin.html")
        ),
        cookieJar = ProtocolCookieJar(setOf(JW_HOST, SSO_HOST))
    )

    @Test
    fun 合同检查_新正方只读链路必须全部通过() {
        GatewayContractSuite(ZfModernContractSpec()).runAll()
    }

    @Test
    fun 首页无菜单链接时必须映射为PAGE_CHANGED而非空数据() = runBlocking {
        val transport = ReplayTransport.fromJson(
            "{\"exchanges\":[{\"method\":\"GET\",\"url\":\"" + ORIGIN +
                "/jwglxt/xtgl/index_initMenu.html\",\"status\":200,\"body\":\"<html><body>空白改版页</body></html>\"}]}"
        )
        val result = ZfModernGateway().terms(session(transport))
        val failure = result as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + result)
        assertEquals(AcademicError.PAGE_CHANGED, failure.code)
    }

    @Test
    fun 单偶周次表达式必须精确解析() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), ZfWeeks.parseStrict("1-16周(双)"))
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), ZfWeeks.parseStrict("1-16周(单)"))
    }
}
