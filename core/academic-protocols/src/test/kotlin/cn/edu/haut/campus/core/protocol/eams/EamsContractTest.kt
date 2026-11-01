package cn.edu.haut.campus.core.protocol.eams

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

private const val HOST = "eams-synthetic.example.edu"
private const val ORIGIN = "https://" + HOST

/**
 * UNVERIFIED：夹具是按 EAMS 公开的动态登录 action 与 JSON 接口形态合成的，不代表任何真实学校。
 */
internal class EamsContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.EAMS
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/eams",
        origin = ORIGIN,
        hosts = setOf(HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2026-2027-1", label = "2026-2027学年第一学期")

    override fun createGateway(): AcademicGateway = EamsGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20260001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy =
        GatewayUrlPolicy(
            setOf(HOST),
            authPaths = setOf("/eams/login", "/eams/login.do"),
            authHost = HOST
        )
}

class EamsContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/eams/login", "/eams/login.do"), authHost = HOST),
        cookieJar = ProtocolCookieJar(setOf(HOST))
    )

    @Test
    fun 合同检查_EAMS只读链路必须全部通过() {
        GatewayContractSuite(EamsContractSpec()).runAll()
    }

    @Test
    fun 登录必须提交到页面声明的动态action() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/eams/auth.json")
        val outcome = EamsGateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        assertTrue("合法凭据必须认证成功", outcome is AuthOutcome.Authenticated)
        assertEquals(3, transport.consumed.size)
        val post = transport.consumed[1]
        assertEquals(HttpMethod.POST, post.method)
        assertEquals(
            "登录目标必须原样使用页面 action（含一次性随机参数）",
            ORIGIN + "/eams/login.do?method=login&random=0.8471",
            post.url
        )
        assertTrue("必须回填页面隐藏域 lt", post.body.orEmpty().contains("lt=LT-synthetic-0001"))
        assertTrue("必须提交账号字段", post.body.orEmpty().contains("username=20260001"))
        assertEquals("登录成功后必须落到首页", ORIGIN + "/eams/index", transport.consumed[2].url)
    }

    @Test
    fun 学期JSON不可用时必须回退到HTML学期表() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/eams/terms-html.json")
        val result = EamsGateway().terms(session(transport))
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(listOf("2026-2027-1"), content.value.map { it.sourceId })
        assertEquals("2026-2027学年第一学期", content.value[0].label)
        assertEquals(
            listOf(ORIGIN + "/eams/term/list.json", ORIGIN + "/eams/term/list.html"),
            transport.consumed.map { it.url }
        )
    }

    @Test
    fun 成绩JSON分页必须读满总条数() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/eams/grades.json")
        val result = EamsGateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(SnapshotCompleteness.COMPLETE, content.value.completeness)
        assertEquals(listOf("g-1", "g-2", "g-3"), content.value.grades.map { it.sourceId })
        assertEquals(
            listOf(
                ORIGIN + "/eams/grade/list.json?page=1",
                ORIGIN + "/eams/grade/list.json?page=2"
            ),
            transport.consumed.map { it.url }
        )
        assertEquals(3.9.toBigDecimal(), content.value.grades[1].gradePoint)
    }

    @Test
    fun 培养方案只读解析不得进入UI且字段缺失保持null() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/eams/training-plan.json")
        val gateway = EamsGateway()
        val result = gateway.trainingPlan(session(transport), "CP-2026")
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        val plan = content.value
        assertEquals("CP-2026", plan.programId)
        assertEquals("2026级计算机科学与技术专业培养方案", plan.title)
        assertEquals(160.toBigDecimal(), plan.requiredCredits)
        assertEquals(3, plan.entries.size)
        assertEquals("E1001", plan.entries[0].courseCode)
        assertEquals("数据结构", plan.entries[0].courseName)
        assertEquals("专业必修", plan.entries[0].category)
        assertEquals(4.toBigDecimal(), plan.entries[0].credits)
        assertEquals("第3学期", plan.entries[0].suggestedTerm)
        assertTrue("培养方案只读解析不得声明写能力", gateway.descriptor.writeCapabilities.isEmpty())
        assertEquals(0, transport.remaining())
    }
}
