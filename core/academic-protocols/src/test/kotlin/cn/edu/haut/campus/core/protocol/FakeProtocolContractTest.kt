package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * 参考实现：它同时是九类协议适配器的模板，也是合同 harness 的自证。
 * 该协议只存在于夹具里，不代表任何真实学校。
 */
private object FakeGateway : AcademicGateway {
    override val descriptor = GatewayDescriptor(
        id = "contract-fake",
        protocol = AcademicProtocolFamily.ZF_MODERN,
        displayName = "合同测试用假协议",
        authMode = AuthMode.FORM_CREDENTIALS
    )

    override suspend fun authenticate(session: GatewaySession, credentials: ProtocolCredentials?): AuthOutcome {
        requireNotNull(credentials) { "假协议需要凭据" }
        return credentials.use {
            val response = session.send(
                GatewayRequest(
                    method = HttpMethod.POST,
                    url = "https://fake.example.edu/login",
                    body = "studentId=" + it.studentId,
                    purpose = RequestPurpose.AUTH,
                    contentType = "application/x-www-form-urlencoded"
                )
            )
            if (response.isSuccess) AuthOutcome.Authenticated else AuthOutcome.Rejected("认证失败")
        }
    }

    override suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>> = guarded {
        val response = session.fetch(GatewayRequest(HttpMethod.GET, "https://fake.example.edu/terms"))
        val root = Json.parse(response.body)
        AcademicResult.Content(
            value = root.arr("terms").orEmpty().map { item ->
                RemoteTerm(
                    sourceId = item.str("id").orEmpty(),
                    label = item.str("label").orEmpty(),
                    academicYear = item.obj("year")?.intOrNull(),
                    ordinal = item.obj("ordinal")?.intOrNull()
                )
            },
            source = provenance()
        )
    }

    override suspend fun timetable(session: GatewaySession, term: RemoteTerm): AcademicResult<TimetableSnapshot> = guarded {
        val response = session.fetch(
            GatewayRequest(HttpMethod.GET, "https://fake.example.edu/timetable?term=" + term.sourceId)
        )
        val courses = Json.parse(response.body).arr("courses").orEmpty().map { item ->
            RemoteCourse(
                sourceId = item.str("id").orEmpty(),
                name = item.str("name").orEmpty(),
                teacher = item.str("teacher"),
                meetings = item.arr("meetings").orEmpty().map { meeting ->
                    RemoteMeeting(
                        weekday = meeting.obj("weekday")?.intOrNull() ?: 1,
                        startSection = meeting.obj("start")?.intOrNull() ?: 1,
                        endSection = meeting.obj("end")?.intOrNull() ?: 1,
                        weeks = parseWeeks(meeting.str("weeks")),
                        location = meeting.str("location")
                    )
                }
            )
        }
        if (courses.isEmpty()) {
            return@guarded AcademicResult.Empty(reason = "当前学期没有课程", authoritative = true)
        }
        AcademicResult.Content(
            value = TimetableSnapshot(term.sourceId, courses, SnapshotCompleteness.COMPLETE),
            source = provenance()
        )
    }

    override suspend fun grades(session: GatewaySession, term: RemoteTerm?): AcademicResult<GradeSnapshot> = guarded {
        val result = PagedJsonReader.readAll { page ->
            val response = session.fetch(
                GatewayRequest(HttpMethod.GET, "https://fake.example.edu/grades?page=" + page)
            )
            if (response.isUnauthorized) throw TransportFailure(AcademicError.SESSION_EXPIRED, "登录已过期")
            if (!response.isSuccess) throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回了失败状态")
            val root = Json.parse(response.body)
            PagedJsonReader.Page(
                items = root.arr("items").orEmpty(),
                total = root.obj("total")?.intOrNull(),
                totalPages = root.obj("totalPage")?.intOrNull(),
                currentPage = root.obj("currentPage")?.intOrNull(),
                pageSize = root.obj("pageSize")?.intOrNull()
            )
        }
        AcademicResult.Content(
            value = GradeSnapshot(
                termSourceId = term?.sourceId,
                grades = result.items.map { item ->
                    RemoteGrade(
                        sourceId = item.str("id").orEmpty(),
                        courseName = item.str("course").orEmpty(),
                        credits = item.str("credit")?.let { BigDecimal(it) },
                        scoreText = item.str("score")
                    )
                },
                completeness = SnapshotCompleteness.COMPLETE
            ),
            source = provenance()
        )
    }

    override suspend fun exams(session: GatewaySession, term: RemoteTerm?): AcademicResult<ExamSnapshot> = guarded {
        val response = session.fetch(GatewayRequest(HttpMethod.GET, "https://fake.example.edu/exams"))
        val exams = Json.parse(response.body).arr("exams").orEmpty().map { item ->
            RemoteExam(
                sourceId = item.str("id").orEmpty(),
                courseName = item.str("course").orEmpty(),
                date = item.str("date")?.let { LocalDate.parse(it) },
                startTime = item.str("start")?.let { LocalTime.parse(it) },
                endTime = item.str("end")?.let { LocalTime.parse(it) },
                location = item.str("room"),
                seat = item.str("seat")
            )
        }
        AcademicResult.Content(
            value = ExamSnapshot(term?.sourceId, exams, SnapshotCompleteness.COMPLETE),
            source = provenance()
        )
    }

    private fun provenance() = cn.edu.haut.campus.core.model.DataProvenance(
        providerId = descriptor.id,
        schoolId = cn.edu.haut.campus.core.model.SchoolId("contract-school"),
        accountId = cn.edu.haut.campus.core.model.AccountId("contract-account"),
        sourceSystem = "fixture"
    )

    private fun parseWeeks(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val parts = raw.split(',')
        val weeks = mutableSetOf<Int>()
        for (part in parts) {
            val range = part.trim().split('-')
            if (range.size == 2) {
                val start = range[0].toIntOrNull() ?: return emptySet()
                val end = range[1].toIntOrNull() ?: return emptySet()
                if (start > end) return emptySet()
                for (week in start..end) weeks += week
            } else {
                weeks += part.trim().toIntOrNull() ?: return emptySet()
            }
        }
        return weeks
    }

    private suspend fun <T> guarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
        block()
    } catch (failure: TransportFailure) {
        AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
    }
}

class FakeProtocolContractTest {

    private val spec = object : GatewayContractSpec {
        override val protocol = AcademicProtocolFamily.ZF_MODERN
        override val fixtures = ProtocolFixtures(
            basePath = "fixtures/fake",
            origin = "https://fake.example.edu",
            hosts = setOf("fake.example.edu")
        )
        override val contractTerm = RemoteTerm(sourceId = "2026-1", label = "2026-2027 第一学期")
        override fun createGateway(): AcademicGateway = FakeGateway
        override fun credentials(): ProtocolCredentials = ProtocolCredentials.of("20260001", "test-password")
        override fun urlPolicy(): GatewayUrlPolicy =
            GatewayUrlPolicy(setOf("fake.example.edu"), authPaths = setOf("/login"), authHost = "fake.example.edu")
    }

    @Test
    fun 参考协议必须通过九项合同检查() {
        GatewayContractSuite(spec).runAll()
    }

    @Test
    fun 缺少夹具时必须显式失败而不是静默通过() {
        val transport = ReplayTransport.fromJson("{\"exchanges\":[]}")
        val failure = runCatching {
            kotlinx.coroutines.runBlocking {
                transport.execute(GatewayRequest(HttpMethod.GET, "https://fake.example.edu/terms"))
            }
        }.exceptionOrNull()
        assertTrue(failure is TransportFailure)
        assertTrue((failure as TransportFailure).error == AcademicError.SOURCE_DATA_INVALID)
    }
}
