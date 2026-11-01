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
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LEGACY_HOST = "jw.zf-legacy.example.edu"
private const val LEGACY_ORIGIN = "https://" + LEGACY_HOST

/**
 * UNVERIFIED：夹具按旧正方 ASP.NET WebForms 形态合成，不代表任何真实学校。
 */
internal class ZfLegacyContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.ZF_LEGACY
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/zf-legacy",
        origin = LEGACY_ORIGIN,
        hosts = setOf(LEGACY_HOST)
    )
    override val contractTerm = RemoteTerm(
        sourceId = "2025-1",
        label = "2025-2026 1",
        academicYear = 2025,
        ordinal = 1
    )

    override fun createGateway(): AcademicGateway = ZfLegacyGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20250001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy = GatewayUrlPolicy(
        allowedHosts = setOf(LEGACY_HOST),
        authPaths = setOf("/default2.aspx"),
        authHost = LEGACY_HOST
    )
}

class ZfLegacyContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = LEGACY_ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(
            allowedHosts = setOf(LEGACY_HOST),
            authPaths = setOf("/default2.aspx"),
            authHost = LEGACY_HOST
        ),
        cookieJar = ProtocolCookieJar(setOf(LEGACY_HOST))
    )

    @Test
    fun 合同检查_旧正方只读链路必须全部通过() {
        GatewayContractSuite(ZfLegacyContractSpec()).runAll()
    }

    @Test
    fun 查询表单POST必须携带隐藏字段VIEWSTATE() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/zf-legacy/grades.json")
        val result = ZfLegacyGateway().grades(session(transport), null)
        assertTrue(result is AcademicResult.Content)
        val post = transport.consumed.last()
        assertTrue("POST body 必须回填 __VIEWSTATE", post.body.orEmpty().contains("__VIEWSTATE=v1"))
    }

    @Test
    fun 缺少某项功能菜单但存在其它菜单时返回UNSUPPORTED() = runBlocking {
        val transport = ReplayTransport.fromJson(
            "{\"exchanges\":[{\"method\":\"GET\",\"url\":\"" + LEGACY_ORIGIN +
                "/xs_main.aspx\",\"status\":200,\"body\":\"<html><body><a href=\\\"/xskbcx.aspx\\\">学生个人课表</a></body></html>\"}]}"
        )
        val result = ZfLegacyGateway().exams(session(transport), null)
        val failure = result as? AcademicResult.Failure ?: error("预期 Failure，实际为 " + result)
        assertEquals(AcademicError.UNSUPPORTED, failure.code)
    }
}
