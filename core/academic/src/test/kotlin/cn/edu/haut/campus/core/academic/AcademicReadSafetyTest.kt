package cn.edu.haut.campus.core.academic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcademicReadSafetyTest {
    private fun page(page: Int, from: Int, count: Int, total: Int? = null, size: Int = 200): String {
        val json = JSONObject().put("currentPage", page).put("showCount", size)
            .put("items", JSONArray((from until from + count).map { JSONObject().put("id", it) }))
        if (total != null) json.put("totalResult", total)
        return json.toString()
    }
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("应拒绝不完整或不安全输入") }
        catch (e: AcademicReadException) { assertEquals(code, e.code) }
    }

    @Test fun `完整读取超过两百条的成绩`() {
        val requested = mutableListOf<Int>()
        val result = AcademicPageReader.readAll { index, _ ->
            requested += index
            if (index == 1) page(index, 0, 200, 250) else page(index, 200, 50, 250)
        }
        assertEquals(listOf(1, 2), requested)
        assertEquals(250, JSONObject(result).getJSONArray("items").length())
    }
    @Test fun `无总数满页必须继续读取到短页`() {
        var requests = 0
        val result = AcademicPageReader.readAll(pageSize = 2) { index, _ ->
            requests++
            page(index, (index - 1) * 2, if (index == 3) 0 else 2, size = 2)
        }
        assertEquals(3, requests)
        assertEquals(4, JSONObject(result).getJSONArray("items").length())
    }
    @Test fun `采用服务端实际分页大小`() {
        var requests = 0
        AcademicPageReader.readAll { index, _ ->
            requests++
            page(index, (index - 1) * 2, if (index == 2) 1 else 2, size = 2)
        }
        assertEquals(2, requests)
    }
    @Test fun `确认空响应可以返回空快照`() {
        assertEquals(0, JSONObject(AcademicPageReader.readAll { _, _ -> page(1, 0, 0, 0) }).getJSONArray("items").length())
    }
    @Test fun `第二页失败不产生部分成功`() {
        rejected("PAGE_CHANGED") {
            AcademicPageReader.readAll(pageSize = 2) { index, _ ->
                if (index == 1) page(1, 0, 2, 3, 2) else "<html>登录已失效</html>"
            }
        }
    }
    @Test fun `页码错误与循环被拒绝`() {
        rejected("PARTIAL_RESPONSE") { AcademicPageReader.readAll { _, _ -> page(2, 0, 1) } }
        rejected("PAGINATION_LOOP") {
            AcademicPageReader.readAll(pageSize = 2) { index, _ -> page(index, 0, 2, size = 2) }
        }
    }
    @Test fun `总数漂移重复记录与提前结束不会覆盖缓存`() {
        rejected("STALE_SNAPSHOT") {
            AcademicPageReader.readAll(pageSize = 2) { index, _ -> page(index, (index-1)*2, 2, if(index==1) 4 else 5, 2) }
        }
        rejected("PARTIAL_RESPONSE") {
            AcademicPageReader.readAll(pageSize = 2) { index, _ -> page(index, index-1, 2, 4, 2) }
        }
        rejected("PARTIAL_RESPONSE") {
            AcademicPageReader.readAll(pageSize = 2) { index, _ -> page(index, 0, if(index==1) 2 else 0, 3, 2) }
        }
    }
    @Test fun `缺失列表错误计数和记录预算被拒绝`() {
        rejected("PAGE_CHANGED") { AcademicPageReader.readAll { _, _ -> "{}" } }
        rejected("SOURCE_DATA_INVALID") { AcademicPageReader.readAll { _, _ -> "{\"items\":[],\"totalResult\":\"未知\"}" } }
        rejected("PARTIAL_RESPONSE") { AcademicPageReader.readAll(maxRecords = 1) { _, _ -> page(1, 0, 2, 2) } }
    }
    @Test fun `认证跳转只接受精确学校来源且不升级明文地址`() {
        val bad = listOf(
            "https://jwglxt.haut.edu.cn.evil.test/", "https://evil.test/?next=https://mapp.haut.edu.cn",
            "https://mapp.haut.edu.cn@evil.test/", "http://mapp.haut.edu.cn/cas/login",
            "https://mapp.haut.edu.cn:8443/cas/login", "https://127.0.0.1/", "file:///tmp/a"
        )
        bad.forEach { raw -> rejected("UNTRUSTED_REDIRECT") { HautAcademicUrlPolicy.validate(raw) } }
        assertEquals("https://mapp.haut.edu.cn/cas/login?x=1", HautAcademicUrlPolicy.resolve("?x=1", "https://mapp.haut.edu.cn/cas/login"))
        assertEquals(
            "https://mapp.haut.edu.cn/cas/login?service=http%3A%2F%2Fjwglxt.haut.edu.cn%2Fsso%2Fjhlogin",
            HautAcademicUrlPolicy.resolve(
                "http://mapp.haut.edu.cn/cas/login?service=http%3A%2F%2Fjwglxt.haut.edu.cn%2Fsso%2Fjhlogin",
                "https://jwglxt.haut.edu.cn/sso/jhlogin"
            )
        )
        assertEquals(
            "https://jwglxt.haut.edu.cn/sso/jhlogin?ticket=ST-1",
            HautAcademicUrlPolicy.resolve(
                "http://jwglxt.haut.edu.cn/sso/jhlogin?ticket=ST-1",
                "https://mapp.haut.edu.cn/cas/login"
            )
        )
        assertEquals(
            "https://jwglxt.haut.edu.cn/sso/jhlogin;jsessionid=ABC123",
            HautAcademicUrlPolicy.resolve(
                "http://jwglxt.haut.edu.cn/sso/jhlogin;jsessionid=ABC123",
                "https://jwglxt.haut.edu.cn/sso/jhlogin?ticket=ST-1"
            )
        )
        rejected("UNTRUSTED_REDIRECT") {
            HautAcademicUrlPolicy.resolve("http://evil.test/cas/login", "https://jwglxt.haut.edu.cn/sso/jhlogin")
        }
    }
    @Test fun `凭据只可提交认证路径`() {
        HautAcademicUrlPolicy.validate("https://mapp.haut.edu.cn/cas/login", credentialPost = true)
        rejected("UNTRUSTED_REDIRECT") { HautAcademicUrlPolicy.validate("https://jwglxt.haut.edu.cn/", credentialPost = true) }
        rejected("UNTRUSTED_REDIRECT") { HautAcademicUrlPolicy.validate("https://mapp.haut.edu.cn/other", credentialPost = true) }
    }
    @Test fun `Cookie 按路径和来源隔离`() {
        val jar = OfficialAcademicCookieJar(HautAcademicUrlPolicy.CAS_HOST)
        val login = HautAcademicUrlPolicy.validate("https://mapp.haut.edu.cn/cas/login")
        jar.accept(login, mapOf("Set-Cookie" to listOf("ticket=synthetic; Path=/cas; Secure")))
        assertTrue(jar.headers(login).values.flatten().any { it.contains("ticket=") })
        assertTrue(jar.headers(HautAcademicUrlPolicy.validate("https://mapp.haut.edu.cn/other")).values.flatten().none { it.contains("ticket=") })
        try { jar.headers(HautAcademicUrlPolicy.validate("https://jwglxt.haut.edu.cn/")); fail("会话必须隔离") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `成绩排序与成绩修正不改变身份`() {
        val a = "{\"kch_id\":\"A\",\"kcmc\":\"课程甲\",\"cj\":\"80\",\"xnm\":\"2026\",\"xqm\":\"3\"}"
        val b = "{\"kch_id\":\"B\",\"kcmc\":\"课程乙\",\"cj\":\"90\",\"xnm\":\"2026\",\"xqm\":\"3\"}"
        val first = HautZfsoftParser.parseGradesJson("{\"items\":[$a,$b]}").associate { it.courseKey to it.id }
        val second = HautZfsoftParser.parseGradesJson("{\"items\":[$b,${a.replace("80", "85")}]}").associate { it.courseKey to it.id }
        assertEquals(first, second)
    }
    @Test fun `没有时间的考试调整时间不改变身份`() {
        val a = JSONObject().put("kch", "A").put("kcmc", "课程甲").put("ksmc", "期末").put("xnm", "2026").put("xqm", "3")
        val first = HautZfsoftParser.parseExamsJson(JSONObject().put("items", JSONArray().put(a)).toString()).single()
        a.put("kssj", "2026-12-20(09:00-11:00)")
        val second = HautZfsoftParser.parseExamsJson(JSONObject().put("items", JSONArray().put(a)).toString()).single()
        assertEquals(first.id, second.id)
        assertNull(first.examDate)
        assertNotNull(second.examDate)
    }
    @Test fun `缺少区分字段的重复成绩必须报告歧义`() {
        rejected("AMBIGUOUS_IDENTITY") {
            HautZfsoftParser.parseGradesJson("{\"items\":[{\"kcmc\":\"课程\",\"xnm\":\"2026\",\"xqm\":\"3\",\"cj\":\"80\"},{\"kcmc\":\"课程\",\"xnm\":\"2026\",\"xqm\":\"3\",\"cj\":\"90\"}]}")
        }
    }
    @Test fun `实践课程没有时间时保留原文且不制造排课`() {
        val result = HautZfsoftParser.parseTimetableWithBreakdown("{\"kbList\":[],\"sjkList\":[{\"kcmc\":\"实训\",\"qsjsz\":\"第九周\"}]}")
        assertTrue(result.allCandidates.isEmpty())
        assertEquals("第九周", result.unscheduledCourses.single().rawWeeks)
        assertEquals(1, result.practiceCount)
    }
    @Test fun `网络错误和取消不伪装页面变更`() {
        val io = java.io.IOException("合成网络中断")
        try { AcademicPageReader.readAll { _, _ -> throw io }; fail("必须传播网络错误") }
        catch (e: java.io.IOException) { assertSame(io, e) }
        val cancel = java.util.concurrent.CancellationException()
        try { AcademicPageReader.readAll { _, _ -> throw cancel }; fail("必须传播取消") }
        catch (e: java.util.concurrent.CancellationException) { assertSame(cancel, e) }
    }
    @Test fun `相对查询也拒绝片段`() {
        rejected("UNTRUSTED_REDIRECT") { HautAcademicUrlPolicy.resolve("?ticket=synthetic#fragment", "https://mapp.haut.edu.cn/cas/login") }
    }
    @Test fun `不猜测缺失学年成绩和课程时间`() {
        rejected("SOURCE_DATA_INVALID") { HautZfsoftParser.parseGradesJson("""{"items":[{"kcmc":"课程","cj":"90"}]}""") }
        rejected("SOURCE_DATA_INVALID") { HautZfsoftParser.parseGradesJson("""{"items":[{"kcmc":"课程","xnm":"2026","xqm":"3"}]}""") }
        rejected("SOURCE_DATA_INVALID") { HautZfsoftParser.parseTimetableJson("""{"kbList":[{"kcmc":"课程"}]}""") }
        rejected("PAGE_CHANGED") { HautZfsoftParser.parseTimetableJson("{}") }
    }

}
