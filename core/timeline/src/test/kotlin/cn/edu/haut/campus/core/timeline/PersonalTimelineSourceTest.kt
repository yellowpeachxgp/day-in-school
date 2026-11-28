package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.AccountId
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalTimelineSourceTest {
    private val fixture = TimelineSourceTestFixtures
    private val scope = fixture.scope
    private val zone = fixture.zone

    @Test
    fun 个人日程跨日投影保留起止时区状态和忙闲() = runTest {
        val record = record(
            id = "evening-lab",
            start = Instant.parse("2026-09-14T15:30:00Z"),
            end = Instant.parse("2026-09-15T01:00:00Z")
        )
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(record)) })
        val secondDay = TimeWindow(Instant.parse("2026-09-14T16:00:00Z"), Instant.parse("2026-09-15T16:00:00Z"))

        val event = source.observe(scope, secondDay).first().single()

        assertEquals("personal:evening-lab", event.id.value)
        assertEquals(TimelineKind.PERSONAL, event.kind)
        assertEquals(record.title, event.title)
        assertEquals(record.start, event.start)
        assertEquals(record.end, event.end)
        assertEquals(zone, event.zone)
        assertEquals(EventStatus.SCHEDULED, event.status)
        assertEquals(BusyLevel.BUSY, event.busy)
        assertEquals(9, event.revision)
    }

    @Test
    fun 个人截止点end为null且按半开窗口匹配() = runTest {
        val record = record(id = "deadline", start = Instant.parse("2026-09-14T10:00:00Z"), end = null)
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(record)) })
        val containing = TimeWindow(Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"))
        val touchingEnd = TimeWindow(Instant.parse("2026-09-14T08:00:00Z"), Instant.parse("2026-09-14T10:00:00Z"))

        assertEquals(1, source.observe(scope, containing).first().size)
        assertEquals(emptyList<TimelineEvent>(), source.observe(scope, touchingEnd).first())
    }

    @Test
    fun 个人日程证据跨账号时failclosed() {
        val bad = record(id = "bad-scope").copy(
            provenance = fixture.provenance(accountId = AccountId("other-account"))
        )
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(bad)) })

        assertSourceFailure<IllegalArgumentException> {
            runTest {
                source.observe(
                    scope,
                    TimeWindow(Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"))
                ).first()
            }
        }
    }

    @Test
    fun 个人日程重复ID时failclosed() {
        val record = record(id = "duplicate")
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(record, record)) })

        assertSourceFailure<IllegalArgumentException> {
            runTest {
                source.observe(
                    scope,
                    TimeWindow(Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"))
                ).first()
            }
        }
    }

    @Test
    fun PersonalEventDraft校验personal前缀和正时长() {
        PersonalEventDraft(
            id = TimelineEventId("personal:valid"),
            title = " valid ",
            start = Instant.parse("2026-09-14T10:00:00Z"),
            end = null,
            zone = zone
        )
        assertSourceFailure<IllegalArgumentException> {
            PersonalEventDraft(
                id = TimelineEventId("course:not-personal"),
                title = "bad",
                start = Instant.parse("2026-09-14T10:00:00Z"),
                end = null,
                zone = zone
            )
        }
        assertSourceFailure<IllegalArgumentException> {
            PersonalEventDraft(
                title = "bad-end",
                start = Instant.parse("2026-09-14T10:00:00Z"),
                end = Instant.parse("2026-09-14T10:00:00Z"),
                zone = zone
            )
        }
    }

    @Test
    fun 取消的个人日程状态原样进入时间流() = runTest {
        val record = record(id = "cancelled-personal").copy(status = EventStatus.CANCELLED_TODAY, end = null)
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(record)) })

        val event = source.observe(
            scope,
            TimeWindow(Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"))
        ).first().single()

        assertEquals(EventStatus.CANCELLED_TODAY, event.status)
        assertNull(event.end)
    }

    private fun record(
        id: String,
        start: Instant = Instant.parse("2026-09-14T09:00:00Z"),
        end: Instant? = Instant.parse("2026-09-14T10:00:00Z")
    ): PersonalEventRecord = PersonalEventRecord(
        id = id,
        title = "个人日程",
        start = start,
        end = end,
        zone = zone,
        place = null,
        status = EventStatus.SCHEDULED,
        busy = BusyLevel.BUSY,
        revision = 9,
        provenance = fixture.provenance()
    )
}
