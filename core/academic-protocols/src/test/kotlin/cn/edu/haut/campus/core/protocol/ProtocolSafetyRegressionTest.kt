package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.protocol.jinzhi.JinzhiSchedule
import cn.edu.haut.campus.core.protocol.eams.EamsDocument
import cn.edu.haut.campus.core.protocol.eams.EamsGateway
import cn.edu.haut.campus.core.protocol.eams.EamsParsers
import cn.edu.haut.campus.core.protocol.chengfang.ChengfangHtml
import cn.edu.haut.campus.core.protocol.chengfang.ChengfangParsers
import cn.edu.haut.campus.core.protocol.chaoxing.ChaoxingHtml
import cn.edu.haut.campus.core.protocol.chaoxing.ChaoxingTimeParser
import cn.edu.haut.campus.core.protocol.kingosoft.KingosoftHtml
import cn.edu.haut.campus.core.protocol.kingosoft.KingosoftGateway
import cn.edu.haut.campus.core.protocol.kingosoft.KingosoftParsers
import cn.edu.haut.campus.core.protocol.qz.QzTimetableJson
import cn.edu.haut.campus.core.protocol.zf.ZfHtml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic offline inputs only; these are not school compatibility claims. */
class ProtocolSafetyRegressionTest {
    @Test fun numericJinzhiSectionsAndWeeks() {
        val meeting = JinzhiSchedule.parse("星期一 第1 - 2节 1-8,10周")!!
        assertEquals(1, meeting.weekday)
        assertEquals(1, meeting.startSection)
        assertEquals(2, meeting.endSection)
        assertEquals((1..8).toSet() + 10, meeting.weeks)
        assertEquals(setOf(1, 3, 5, 7), JinzhiSchedule.parseWeeks("星期一 第1-2节 1-8周(单)"))
    }

    @Test fun weeksPreserveMixedTokensAndParity() {
        val parsers = listOf<(String?) -> Set<Int>>(EamsParsers::weeks, ChengfangParsers::weeks, KingosoftParsers::weeks)
        for (parse in parsers) {
            assertEquals((1..8).toSet() + 10, parse("1-8,10周"))
            assertEquals(setOf(2, 4, 6, 8), parse("1-8周（双）"))
            assertTrue(parse("1-999999999周").isEmpty())
            assertTrue(parse("1-8,garbage周").isEmpty())
        }
        assertEquals(setOf(1, 3, 5, 7), ChaoxingTimeParser.weeks("星期一 第1-2节 1-8周(单)"))
        assertEquals((1..8).toSet() + 10, ChaoxingTimeParser.weeks("星期一 第1-2节 1-8,10周"))
        assertTrue(ChaoxingTimeParser.slots("星期一 第1-2节 1-8周;无法解析的安排").isEmpty())
    }

    @Test fun passwordInputHtmlVariantsRemainLoginPages() {
        val detectors = listOf<(String) -> Boolean>(EamsDocument::looksLikeLoginPage, ChengfangHtml::looksLikeLoginPage, ChaoxingHtml::looksLikeLoginPage, KingosoftHtml::looksLikeLoginPage)
        for (detect in detectors) {
            for (html in listOf("<input type='password'>", "<input type = \"password\">", "<INPUT TYPE=PASSWORD>")) assertTrue(html, detect(html))
            assertFalse(detect("<div>type=\"password\"</div>"))
        }
    }

    @Test fun invalidUnicodeEntitiesStayLiteral() {
        assertEquals("&#x110000; &#1114112; &#xD800;", ZfHtml.decodeEntities("&#x110000; &#1114112; &#xD800;"))
        assertEquals("😀", ZfHtml.decodeEntities("&#x1F600;"))
    }

    @Test fun missingOrBrokenTimetableCannotBecomeAuthoritativeEmpty() {
        for (json in listOf("{\"error\":\"maintenance\"}", "{\"kbList\":[{}]}", "{\"kbList\":{}}")) {
            assertThrows(TransportFailure::class.java) { QzTimetableJson.parse(Json.parse(json)) }
        }
        assertTrue(QzTimetableJson.parse(Json.parse("{\"kbList\":[]}")).isEmpty())
    }

    private fun session(requests: MutableList<GatewayRequest>, response: String): GatewaySession = GatewaySession(
        schoolId = "synthetic", accountId = "test", origin = "https://synthetic.example.edu",
        transport = object : GatewayTransport {
            override suspend fun execute(request: GatewayRequest): GatewayResponse {
                requests += request
                return GatewayResponse(200, body = response, finalUrl = request.url)
            }
        },
        urlPolicy = GatewayUrlPolicy(setOf("synthetic.example.edu")),
        cookieJar = ProtocolCookieJar(setOf("synthetic.example.edu"))
    )

    @Test fun requestedKingosoftTermIsSentForGradesAndExams() = runBlocking {
        for (id in listOf("2026-2027-1", "2026-2027-2")) {
            val requests = mutableListOf<GatewayRequest>()
            val session = session(requests, "<html>maintenance</html>")
            val gateway = KingosoftGateway()
            val term = RemoteTerm(sourceId = id, label = id)
            gateway.grades(session, term)
            gateway.exams(session, term)
            assertEquals(2, requests.size)
            for (request in requests) {
                assertTrue(request.url, request.url.contains("xn=2026-2027"))
                assertTrue(request.url, request.url.contains("xq=" + id.last()))
            }
        }
    }

    @Test fun eamsMissingJsonListsAndUnrelatedTablesFailClosed() = runBlocking {
        val gateway = EamsGateway()
        val term = RemoteTerm(sourceId = "2026-1", label = "synthetic")
        val jsonSession = session(mutableListOf(), "{\"data\":{}}")
        assertTrue(gateway.grades(jsonSession, term) is AcademicResult.Failure)
        assertTrue(gateway.exams(jsonSession, term) is AcademicResult.Failure)
        val htmlSession = session(mutableListOf(), "<table><tr><th>maintenance</th></tr></table>")
        assertTrue(gateway.timetable(htmlSession, term) is AcademicResult.Failure)
    }
}
