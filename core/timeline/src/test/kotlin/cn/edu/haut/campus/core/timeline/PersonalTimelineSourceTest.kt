package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
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
    fun 个人日程跨日投影保留完整身份起止时区状态和忙闲() = runTest {
        val sourceEntity = EntityRef(EntityType.COURSE, "c-math", scope)
        val record = record(
            id = "evening-lab",
            start = Instant.parse("2026-09-14T15:30:00Z"),
            end = Instant.parse("2026-09-15T01:00:00Z"),
            sourceEntity = sourceEntity
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
        assertEquals(sourceEntity, event.source.entity)
    }

    @Test
    fun personalSource声明personalEvents全部首批宿主kind() {
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(emptyList()) })

        assertEquals(
            setOf(
                TimelineKind.PERSONAL,
                TimelineKind.TASK_DUE,
                TimelineKind.GROUP_MEETING,
                TimelineKind.REVIEW_BLOCK
            ),
            source.kinds
        )
    }

    @Test
    fun 待办截止点end为null且按半开窗口匹配() = runTest {
        val record = record(
            id = "deadline",
            kind = TimelineKind.TASK_DUE,
            start = Instant.parse("2026-09-14T10:00:00Z"),
            end = null
        )
        val source = PersonalTimelineSource(PersonalEventTimelineInputProvider { _, _ -> flowOf(listOf(record)) })
        val containing = TimeWindow(Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"))
        val touchingEnd = TimeWindow(Instant.parse("2026-09-14T08:00:00Z"), Instant.parse("2026-09-14T10:00:00Z"))

        val event = source.observe(scope, containing).first().single()
        assertEquals(TimelineKind.TASK_DUE, event.kind)
        assertNull(event.end)
        assertEquals(emptyList<TimelineEvent>(), source.observe(scope, touchingEnd).first())
    }

    @Test
    fun 个人日程记录scope与请求不一致时failclosed() {
        val foreignScope = scope.copy(profileId = ProfileId("other-profile"))
        val bad = record(id = "bad-scope", scope = foreignScope)
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
    fun PersonalEventDraft校验personal前缀kind和正时长() {
        validDraft(id = TimelineEventId("personal:valid"))
        assertSourceFailure<IllegalArgumentException> {
            validDraft(id = TimelineEventId("course:not-personal"))
        }
        assertSourceFailure<IllegalArgumentException> {
            validDraft(kind = TimelineKind.FOCUS)
        }
        assertSourceFailure<IllegalArgumentException> {
            validDraft(
                start = Instant.parse("2026-09-14T10:00:00Z"),
                end = Instant.parse("2026-09-14T10:00:00Z")
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

    private fun validDraft(
        id: TimelineEventId? = null,
        kind: TimelineKind = TimelineKind.PERSONAL,
        start: Instant = Instant.parse("2026-09-14T10:00:00Z"),
        end: Instant? = null
    ): PersonalEventDraft = PersonalEventDraft(
        id = id,
        scope = scope,
        kind = kind,
        title = "个人日程",
        start = start,
        end = end,
        zone = zone,
        provenance = fixture.provenance()
    )

    private fun record(
        id: String,
        scope: SchoolAccountScope = this.scope,
        kind: TimelineKind = TimelineKind.PERSONAL,
        start: Instant = Instant.parse("2026-09-14T09:00:00Z"),
        end: Instant? = Instant.parse("2026-09-14T10:00:00Z"),
        sourceEntity: EntityRef? = null
    ): PersonalEventRecord = PersonalEventRecord(
        id = TimelineEventId("personal:" + id),
        scope = scope,
        kind = kind,
        title = "个人日程",
        start = start,
        end = end,
        zone = zone,
        place = null,
        status = EventStatus.SCHEDULED,
        busy = BusyLevel.BUSY,
        revision = 9,
        provenance = fixture.provenance(),
        sourceEntity = sourceEntity
    )
}
