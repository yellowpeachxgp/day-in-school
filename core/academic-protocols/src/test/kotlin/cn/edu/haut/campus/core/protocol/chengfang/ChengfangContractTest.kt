package cn.edu.haut.campus.core.protocol.chengfang

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.AcademicGateway
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.GatewayContractSpec
import cn.edu.haut.campus.core.protocol.GatewayContractSuite
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.GatewayTransport
import cn.edu.haut.campus.core.protocol.GatewayUrlPolicy
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.ProtocolCookieJar
import cn.edu.haut.campus.core.protocol.ProtocolCredentials
import cn.edu.haut.campus.core.protocol.ProtocolFixtures
import cn.edu.haut.campus.core.protocol.RemoteTerm
import cn.edu.haut.campus.core.protocol.ReplayTransport
import cn.edu.haut.campus.core.protocol.RequestPurpose
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOST = "cf-synthetic.example.edu"
private const val ORIGIN = "https://" + HOST

/**
 * UNVERIFIED：夹具是按乘方教务公开的表单/表格形态合成的，不代表任何真实学校。
 */
internal class ChengfangContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.CHENGFANG
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/chengfang",
        origin = ORIGIN,
        hosts = setOf(HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2026-1", label = "2026-2027学年第一学期")

    override fun createGateway(): AcademicGateway = ChengfangGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20260001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy =
        GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/academic/login.jsp"), authHost = HOST)
}

class ChengfangContractTest {

    private fun session(
        transport: GatewayTransport,
        policy: GatewayUrlPolicy = GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/academic/login.jsp"), authHost = HOST)
    ): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = policy,
        cookieJar = ProtocolCookieJar(setOf(HOST))
    )

    private fun exchange(method: String, url: String, body: String, status: Int = 200): JsonValue.Obj =
        JsonValue.Obj(
            mapOf(
                "method" to JsonValue.Str(method),
                "url" to JsonValue.Str(url),
                "status" to JsonValue.Num(status.toString()),
                "headers" to JsonValue.Obj(emptyMap()),
                "body" to JsonValue.Str(body)
            )
        )

    private fun transportOf(vararg exchanges: JsonValue.Obj): ReplayTransport =
        ReplayTransport.fromJson(
            Json.canonical(JsonValue.Obj(mapOf("exchanges" to JsonValue.Arr(exchanges.toList()))))
        )

    @Test
    fun 合同检查_乘方只读链路必须全部通过() {
        GatewayContractSuite(ChengfangContractSpec()).runAll()
    }

    @Test
    fun 登录必须回填页面隐藏域并以表单编码提交() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/chengfang/auth.json")
        val outcome = ChengfangGateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        assertTrue("合法凭据必须认证成功", outcome is AuthOutcome.Authenticated)
        assertEquals("认证必须依次访问登录页、提交表单、落到首页", 3, transport.consumed.size)
        val post = transport.consumed[1]
        assertEquals(HttpMethod.POST, post.method)
        assertEquals(RequestPurpose.AUTH, post.purpose)
        assertEquals("application/x-www-form-urlencoded", post.contentType)
        val body = post.body.orEmpty()
        assertTrue("必须带上页面里的 csrfToken", body.contains("csrfToken=synthetic-cf-token-001"))
        assertTrue("必须带上页面里的 loginType", body.contains("loginType=student"))
        assertTrue("必须带上学号字段", body.contains("userName=20260001"))
        assertEquals("夹具必须被完整消费", 0, transport.remaining())
    }

    @Test
    fun 成绩必须按页序读完全部分页() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/chengfang/grades.json")
        val result = ChengfangGateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(SnapshotCompleteness.COMPLETE, content.value.completeness)
        assertEquals(3, content.value.grades.size)
        assertEquals(listOf("CF101#1", "CF202#2", "CF303#3"), content.value.grades.map { it.sourceId })
        assertEquals(
            listOf(
                ORIGIN + "/academic/score/list.jsp?page=1",
                ORIGIN + "/academic/score/list.jsp?page=2"
            ),
            transport.consumed.map { it.url }
        )
        assertEquals(3.6.toBigDecimal(), content.value.grades[0].gradePoint)
    }

    @Test
    fun 登录页要求验证码时必须交还用户而不是空转() = runBlocking {
        val captchaPage = """
            <form id="loginForm" method="post" action="/academic/login.jsp">
            <input type="hidden" name="csrfToken" value="synthetic-cf-token-001">
            <input type="text" name="userName">
            <input type="password" name="userPassword">
            <input type="text" name="checkCode" id="checkCode">
            </form>
        """.trimIndent()
        val transport = transportOf(exchange("GET", ORIGIN + "/academic/login.jsp", captchaPage))
        val outcome = ChengfangGateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        val action = outcome as? AuthOutcome.RequiresUserAction
            ?: error("验证码必须交还用户，实际为 " + outcome)
        assertEquals(cn.edu.haut.campus.core.model.UserAction.COMPLETE_CAPTCHA, action.action)
        assertEquals("识别到验证码后不得提交任何凭据", 1, transport.consumed.size)
    }

    @Test
    fun 成绩表缺少绩点列时不得编造数值() = runBlocking {
        val page = """
            <table id="gradeTable">
            <tr><th>序号</th><th>课程代码</th><th>课程名称</th><th>学分</th><th>成绩</th><th>备注</th></tr>
            <tr><td>1</td><td>CF101</td><td>高等数学</td><td></td><td>88</td><td>缓考</td></tr>
            </table>
            <div class="pager">共 1 条记录，第 1/1 页</div>
        """.trimIndent()
        val transport = transportOf(
            exchange("GET", ORIGIN + "/academic/score/list.jsp?page=1", page)
        )
        val result = ChengfangGateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        val grade = content.value.grades.single()
        assertEquals("高等数学", grade.courseName)
        assertEquals("88", grade.scoreText)
        assertNull("缺少绩点列时必须保持 null", grade.gradePoint)
        assertNull("空学分单元格不得解释为 0", grade.credits)
        assertNull("未声明的考核方式列不得被猜测", grade.category)
    }

    @Test
    fun 空课表必须发布权威空快照且适配器不声明写能力() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/chengfang/empty-timetable.json")
        val gateway = ChengfangGateway()
        val result = gateway.timetable(session(transport), RemoteTerm(sourceId = "2026-1", label = "2026-2027学年第一学期"))
        val empty = result as? AcademicResult.Empty ?: error("预期 Empty，实际为 " + result)
        assertTrue("空课表必须是权威结论", empty.authoritative)
        assertTrue("乘方适配器不得声明任何写能力", gateway.descriptor.writeCapabilities.isEmpty())
        assertEquals(
            setOf(
                cn.edu.haut.campus.core.protocol.GatewayCapability.TERMS_READ,
                cn.edu.haut.campus.core.protocol.GatewayCapability.TIMETABLE_READ,
                cn.edu.haut.campus.core.protocol.GatewayCapability.GRADES_READ,
                cn.edu.haut.campus.core.protocol.GatewayCapability.EXAMS_READ
            ),
            gateway.descriptor.readCapabilities
        )
        assertEquals(0, transport.remaining())
    }
}
