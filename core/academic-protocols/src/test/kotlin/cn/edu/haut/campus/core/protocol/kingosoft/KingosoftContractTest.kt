package cn.edu.haut.campus.core.protocol.kingosoft

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
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

private const val HOST = "qg-synthetic.example.edu"
private const val ORIGIN = "https://" + HOST

/**
 * UNVERIFIED：夹具是按青果教务公开的 ASP.NET 表单/网格形态合成的，不代表任何真实学校。
 */
internal class KingosoftContractSpec : GatewayContractSpec {
    override val protocol = AcademicProtocolFamily.KINGOSOFT
    override val fixtures = ProtocolFixtures(
        basePath = "fixtures/kingosoft",
        origin = ORIGIN,
        hosts = setOf(HOST)
    )
    override val contractTerm = RemoteTerm(sourceId = "2026-2027-1", label = "2026-2027学年第一学期")

    override fun createGateway(): AcademicGateway = KingosoftGateway()

    override fun credentials(): ProtocolCredentials =
        ProtocolCredentials.of("20260001", "synthetic-password")

    override fun urlPolicy(): GatewayUrlPolicy =
        GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/Default.aspx"), authHost = HOST)
}

class KingosoftContractTest {

    private fun session(transport: GatewayTransport): GatewaySession = GatewaySession(
        schoolId = "synthetic-school",
        accountId = "synthetic-account",
        origin = ORIGIN,
        transport = transport,
        urlPolicy = GatewayUrlPolicy(setOf(HOST), authPaths = setOf("/Default.aspx"), authHost = HOST),
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
    fun 合同检查_青果只读链路必须全部通过() {
        GatewayContractSuite(KingosoftContractSpec()).runAll()
    }

    @Test
    fun 学期下拉必须交叉组合学年与学期() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/kingosoft/terms.json")
        val result = KingosoftGateway().terms(session(transport))
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(
            listOf("2026-2027-1", "2026-2027-2"),
            content.value.map { it.sourceId }
        )
        assertEquals("2026-2027学年第一学期", content.value[0].label)
        assertEquals(2026, content.value[0].academicYear)
        assertEquals(1, content.value[0].ordinal)
        assertEquals(2, content.value[1].ordinal)
        assertEquals(0, transport.remaining())
    }

    @Test
    fun 网格课表必须按星期列与节次行还原上课安排() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/kingosoft/timetable.json")
        val term = RemoteTerm(sourceId = "2026-2027-1", label = "2026-2027学年第一学期")
        val result = KingosoftGateway().timetable(session(transport), term)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(SnapshotCompleteness.COMPLETE, content.value.completeness)
        assertEquals("2026-2027-1", content.value.termSourceId)
        assertEquals(listOf("高等数学", "大学物理", "大学英语"), content.value.courses.map { it.name })
        val math = content.value.courses.single { it.name == "高等数学" }.meetings.single()
        assertEquals(1, math.weekday)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals((1..16).toSet(), math.weeks)
        assertEquals("A101", math.location)
        assertEquals("张老师", math.teacher)
        val english = content.value.courses.single { it.name == "大学英语" }.meetings.single()
        assertEquals(2, english.weekday)
        assertEquals(3, english.startSection)
        assertEquals(4, english.endSection)
    }

    @Test
    fun 登录必须使用页面声明的回发目标并回填隐藏域() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/kingosoft/auth.json")
        val outcome = KingosoftGateway().authenticate(
            session(transport),
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        assertTrue("合法凭据必须认证成功", outcome is AuthOutcome.Authenticated)
        assertEquals(3, transport.consumed.size)
        val post = transport.consumed[1]
        assertEquals(HttpMethod.POST, post.method)
        assertEquals("必须提交到页面 form 声明的回发地址", ORIGIN + "/Default.aspx?login=true", post.url)
        val body = post.body.orEmpty()
        assertTrue(body.contains("__VIEWSTATE=synthetic-viewstate-001"))
        assertTrue(body.contains("__EVENTVALIDATION=synthetic-eventvalidation-001"))
        assertTrue(body.contains("TextBox1=20260001"))
        assertEquals("登录成功后必须落到首页", ORIGIN + "/xs_main.aspx?xh=20260001", transport.consumed[2].url)
    }

    @Test
    fun 登录后读取必须带上学号查询参数() = runBlocking {
        val loginPage = """
            <form name="form1" method="post" action="./Default.aspx?login=true" id="form1">
            <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="synthetic-viewstate-002" />
            <input name="TextBox1" type="text" id="TextBox1" />
            <input name="TextBox2" type="password" id="TextBox2" />
            </form>
        """.trimIndent()
        val homePage = """<div id="Label1">欢迎您：20260001</div>"""
        val termPage = """
            <select name="ddlXN" id="ddlXN"><option value="2026-2027">2026-2027</option></select>
            <select name="ddlXQ" id="ddlXQ"><option value="1">第一学期</option></select>
        """.trimIndent()
        val transport = transportOf(
            exchange("GET", ORIGIN + "/Default.aspx", loginPage),
            exchange("POST", ORIGIN + "/Default.aspx?login=true", homePage),
            exchange("GET", ORIGIN + "/xskbcx.aspx?xh=20260001&gnmkdm=N121603", termPage)
        )
        val gateway = KingosoftGateway()
        val outcome = gateway.authenticate(
            session(transport),
            ProtocolCredentials.of("20260001", "synthetic-password")
        )
        assertTrue("合法凭据必须认证成功", outcome is AuthOutcome.Authenticated)
        val result = gateway.terms(session(transport))
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(listOf("2026-2027-1"), content.value.map { it.sourceId })
        assertEquals(
            "登录后所有查询都必须带上学号 xh",
            ORIGIN + "/xskbcx.aspx?xh=20260001&gnmkdm=N121603",
            transport.consumed.last().url
        )
    }

    @Test
    fun 成绩分页必须按总页数读完并终止() = runBlocking {
        val transport = ReplayTransport.fromResource("fixtures/kingosoft/grades.json")
        val result = KingosoftGateway().grades(session(transport), null)
        val content = result as? AcademicResult.Content ?: error("预期 Content，实际为 " + result)
        assertEquals(SnapshotCompleteness.COMPLETE, content.value.completeness)
        assertEquals(3, content.value.grades.size)
        assertEquals(
            listOf(
                ORIGIN + "/xscjcx.aspx?gnmkdm=N121605&page=1",
                ORIGIN + "/xscjcx.aspx?gnmkdm=N121605&page=2"
            ),
            transport.consumed.map { it.url }
        )
        assertEquals("必修", content.value.grades[0].category)
        assertEquals(3.6.toBigDecimal(), content.value.grades[0].gradePoint)
    }
}
