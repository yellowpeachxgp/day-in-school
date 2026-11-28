package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamTimelineSourceTest {
    private val fixture = TimelineSourceTestFixtures
    private val scope = fixture.scope
    private val zone = fixture.zone

    @Test
    fun 考试按显式校区时区投影为精确忙碌事件并解析地点() = runTest {
        val exam = exam()
        val place = PlaceRef("haut/lianhuajie/room/5-111", SchoolId("haut"), CampusId("lianhuajie"))
        val source = ExamTimelineSource(
            inputProvider = ExamTimelineInputProvider { _, _ -> flowOf(snapshot(listOf(exam))) },
            placeResolver = TimelinePlaceResolver { _, _, _, _ -> place }
        )

        val event = source.observe(scope, fixture.dayWindow(exam.examDate!!)).first().single()

        assertEquals("exam:exam-1", event.id.value)
        assertEquals(TimelineKind.EXAM, event.kind)
        assertEquals("高等数学", event.title)
        assertEquals(Instant.parse("2026-09-20T01:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-09-20T03:00:00Z"), event.end)
        assertEquals(zone, event.zone)
        assertEquals(place, event.place)
        assertEquals(EventStatus.SCHEDULED, event.status)
        assertEquals(BusyLevel.BUSY, event.busy)
    }

    @Test
    fun 考试缺结束时间不冒充精确时段而保留tentative点() = runTest {
        val exam = exam(endTime = null)
        val source = ExamTimelineSource(ExamTimelineInputProvider { _, _ -> flowOf(snapshot(listOf(exam))) })

        val event = source.observe(scope, fixture.dayWindow(exam.examDate!!)).first().single()

        assertEquals(EventStatus.TENTATIVE, event.status)
        assertEquals(BusyLevel.FREE, event.busy)
        assertNull(event.end)
        assertEquals(TimelineTimeSemantics.UNCERTAIN, event.timeSemantics)
        assertFalse(event.isDeadline)
        assertEquals(exam.examDate!!.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant(), event.start)
    }

    @Test
    fun 考试反向时间不冒充有效时段() = runTest {
        val exam = exam(endTime = LocalTime.of(8, 0))
        val source = ExamTimelineSource(ExamTimelineInputProvider { _, _ -> flowOf(snapshot(listOf(exam))) })

        val event = source.observe(scope, fixture.dayWindow(exam.examDate!!)).first().single()

        assertEquals(EventStatus.TENTATIVE, event.status)
        assertNull(event.end)
        assertEquals(TimelineTimeSemantics.UNCERTAIN, event.timeSemantics)
        assertFalse(event.isDeadline)
    }

    @Test
    fun 只有日期的考试是不确定锚点且导出不成截止点() = runTest {
        val exam = exam(startTime = null, endTime = null)
        val source = ExamTimelineSource(ExamTimelineInputProvider { _, _ -> flowOf(snapshot(listOf(exam))) })

        val event = source.observe(scope, fixture.dayWindow(exam.examDate!!)).first().single()
        val ics = StandardIcsExporter.export(listOf(event), "考试")
        val roundTrip = IcsParser.parseEvents(ics).single()

        assertEquals(exam.examDate!!.atStartOfDay(zone).toInstant(), event.start)
        assertNull(event.end)
        assertEquals(TimelineTimeSemantics.UNCERTAIN, event.timeSemantics)
        assertFalse(event.isDeadline)
        assertTrue(event.isTimeUncertain)
        assertFalse(ics.contains("X-DIS-DEADLINE:TRUE"))
        assertTrue(ics.contains("X-DIS-TIME-UNCERTAIN:TRUE\\r\\n"))
        assertTrue(ics.contains("TRANSP:TRANSPARENT\\r\\n"))
        assertEquals(TimelineTimeSemantics.UNCERTAIN, roundTrip.timeSemantics)
    }

    @Test
    fun 无日期考试无法时间投影且不伪造日期() = runTest {
        val exam = exam(examDate = null)
        val source = ExamTimelineSource(ExamTimelineInputProvider { _, _ -> flowOf(snapshot(listOf(exam))) })

        val events = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 20))).first()

        assertEquals(emptyList<TimelineEvent>(), events)
    }

    @Test
    fun 考试多次同步ID不变而revision前进() = runTest {
        val exam = exam()
        val firstSnapshot = snapshot(listOf(exam), revision = 3)
        val secondSnapshot = snapshot(listOf(exam.copy(courseName = "高等数学（期末）")), revision = 4)
        val source = ExamTimelineSource(
            ExamTimelineInputProvider { _, _ -> flowOf(firstSnapshot, secondSnapshot) }
        )

        val emissions = source.observe(scope, fixture.dayWindow(exam.examDate!!)).take(2).toList()

        assertEquals("exam:exam-1", emissions[0].single().id.value)
        assertEquals("exam:exam-1", emissions[1].single().id.value)
        assertEquals(3, emissions[0].single().revision)
        assertEquals(4, emissions[1].single().revision)
    }

    @Test
    fun 考试输入证据跨账号时failclosed() {
        val badSnapshot = ExamTimelineSnapshot(
            zone = zone,
            provenance = fixture.provenance(accountId = AccountId("other-account")),
            revision = 1,
            exams = listOf(exam())
        )
        val source = ExamTimelineSource(ExamTimelineInputProvider { _, _ -> flowOf(badSnapshot) })

        assertSourceFailure<IllegalArgumentException> {
            runTest { source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 20))).first() }
        }
    }

    private fun exam(
        examDate: LocalDate? = LocalDate.of(2026, 9, 20),
        startTime: LocalTime? = LocalTime.of(9, 0),
        endTime: LocalTime? = LocalTime.of(11, 0)
    ): ExamScheduleItem = ExamScheduleItem(
        id = "exam-1",
        termId = fixture.term.id,
        courseCode = "MATH001",
        courseName = "高等数学",
        examBatchName = "期末",
        examStageLabel = "期末考试",
        examFormat = "闭卷",
        rawTimeText = "2026-09-20 09:00-11:00",
        examDate = examDate,
        startTime = startTime,
        endTime = endTime,
        rawRoom = "W111",
        seatNumber = "01",
        campusName = "莲花街校区",
        syncedAt = Instant.parse("2026-09-01T00:00:00Z")
    )

    private fun snapshot(exams: List<ExamScheduleItem>, revision: Long = 1): ExamTimelineSnapshot = ExamTimelineSnapshot(
        zone = zone,
        provenance = fixture.provenance(),
        revision = revision,
        exams = exams
    )
}
