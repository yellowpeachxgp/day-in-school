package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.VerificationLevel
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineModelsTest {

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `事件 ID 按首个冒号拆分且稳定键可含冒号`() {
        val id = TimelineEventId("course:term-1:rule-9:2026-03-02")

        assertEquals("course", id.sourceId)
        assertEquals("term-1:rule-9:2026-03-02", id.stableKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件 ID 拒绝缺少 stableKey`() {
        TimelineEventId("course:")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件 ID 拒绝空白字符`() {
        TimelineEventId("course:bad key")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件 ID 拒绝 source 段含斜杠`() {
        TimelineEventId("bad/source:stable")
    }

    @Test
    fun `跨日定时事件使用显式校区时区`() {
        val event = event(
            id = "course:cross-day",
            start = "2026-03-02T14:00:00Z",
            end = "2026-03-02T22:00:00Z"
        )

        assertEquals(zone, event.zone)
        assertEquals("2026-03-02T14:00:00Z", event.start.toString())
        assertEquals("2026-03-02T22:00:00Z", event.end.toString())
        assertFalse(event.isDeadline)
    }

    @Test
    fun `截止时间点为 null end 且按点与窗口相交`() {
        val deadline = event(id = "task:deadline", start = "2026-03-02T10:00:00Z", end = null)
        val containing = TimeWindow(Instant.parse("2026-03-02T09:00:00Z"), Instant.parse("2026-03-02T11:00:00Z"))
        val touchingEnd = TimeWindow(Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-03-02T10:00:00Z"))

        assertTrue(deadline.isDeadline)
        assertTrue(deadline.overlaps(containing))
        assertFalse(deadline.overlaps(touchingEnd))
    }

    @Test
    fun `不确定时间点不是截止点但仍按锚点匹配窗口`() {
        val uncertain = event(
            id = "course:uncertain",
            start = "2026-03-02T10:00:00Z",
            end = null,
            status = EventStatus.TENTATIVE,
            busy = BusyLevel.FREE,
            timeSemantics = TimelineTimeSemantics.UNCERTAIN
        )
        val containing = TimeWindow(Instant.parse("2026-03-02T09:00:00Z"), Instant.parse("2026-03-02T11:00:00Z"))

        assertEquals(TimelineTimeSemantics.UNCERTAIN, uncertain.timeSemantics)
        assertFalse(uncertain.isDeadline)
        assertTrue(uncertain.isTimeUncertain)
        assertTrue(uncertain.overlaps(containing))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TIMED 事件拒绝 null end`() {
        event(id = "course:bad-timed", end = null, timeSemantics = TimelineTimeSemantics.TIMED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `不确定时间不能声明忙闲占用`() {
        event(
            id = "course:bad-uncertain-busy",
            end = null,
            busy = BusyLevel.TENTATIVE,
            timeSemantics = TimelineTimeSemantics.UNCERTAIN
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `取消事件必须明确 FREE`() {
        event(id = "course:bad-cancelled", status = EventStatus.CANCELLED_TODAY)
    }

    @Test
    fun `跨日事件与查询窗口按半开区间相交`() {
        val event = event(id = "course:overnight", start = "2026-03-02T15:00:00Z", end = "2026-03-03T01:00:00Z")
        val nextDay = TimeWindow(Instant.parse("2026-03-03T00:00:00Z"), Instant.parse("2026-03-03T02:00:00Z"))
        val afterEnd = TimeWindow(Instant.parse("2026-03-03T01:00:00Z"), Instant.parse("2026-03-03T02:00:00Z"))

        assertTrue(event.overlaps(nextDay))
        assertFalse(event.overlaps(afterEnd))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `定时事件拒绝零长度 end`() {
        event(id = "course:zero", start = "2026-03-02T10:00:00Z", end = "2026-03-02T10:00:00Z")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件证据不得跨账号`() {
        event(id = "course:bad-provenance").copy(
            provenance = provenance().copy(accountId = AccountId("another-account"))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件来源实体必须与事件 scope 完全一致`() {
        val otherScope = scope.copy(profileId = ProfileId("other-profile"))
        event(id = "course:bad-source").copy(
            source = SourceRef(EntityRef(EntityType.COURSE, "course-1", otherScope), provenance())
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点不得跨学校引用`() {
        event(id = "course:bad-place").copy(
            place = PlaceRef("other/lianhuajie/room/5-111", SchoolId("other"), CampusId("lianhuajie"))
        )
    }

    @Test
    fun `同学校跨校区地点可以用于通勤场景`() {
        val event = event(id = "course:other-campus").copy(
            place = PlaceRef("haut/songshanlu/room/2-101", SchoolId("haut"), CampusId("songshanlu"))
        )

        assertEquals(CampusId("songshanlu"), event.place?.campusId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件 links 不允许重复`() {
        val link = EntityRef(EntityType.EXAM, "exam-1", scope)
        event(id = "course:duplicate-links").copy(links = listOf(link, link))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `revision 不能为负`() {
        event(id = "course:bad-revision").copy(revision = -1)
    }

    @Test
    fun `过滤器按 kind status 和 source 匹配`() {
        val event = event(id = "course:filter")
        val filter = TimelineFilter(
            kinds = setOf(TimelineKind.COURSE),
            statuses = setOf(EventStatus.SCHEDULED),
            sourceIds = setOf("course")
        )

        assertTrue(filter.matches(event))
        assertFalse(filter.copy(kinds = setOf(TimelineKind.EXAM)).matches(event))
        assertFalse(filter.copy(statuses = setOf(EventStatus.DONE)).matches(event))
        assertFalse(filter.copy(sourceIds = setOf("exam")).matches(event))
        assertTrue(TimelineFilter.ALL.matches(event))
    }

    private fun event(
        id: String,
        start: String = "2026-03-02T09:00:00Z",
        end: String? = "2026-03-02T10:00:00Z",
        status: EventStatus = EventStatus.SCHEDULED,
        busy: BusyLevel = BusyLevel.BUSY,
        timeSemantics: TimelineTimeSemantics? = null
    ): TimelineEvent {
        val entity = EntityRef(EntityType.COURSE, "course-1", scope)
        return TimelineEvent(
            id = TimelineEventId(id),
            scope = scope,
            kind = TimelineKind.COURSE,
            title = "高等数学",
            start = Instant.parse(start),
            end = end?.let { Instant.parse(it) },
            timeSemantics = timeSemantics ?: if (end == null) TimelineTimeSemantics.DEADLINE else TimelineTimeSemantics.TIMED,
            zone = zone,
            place = PlaceRef("haut/lianhuajie/room/5-111", SchoolId("haut"), CampusId("lianhuajie")),
            source = SourceRef(entity, provenance()),
            provenance = provenance(),
            status = status,
            busy = busy,
            revision = 1,
            links = listOf(entity)
        )
    }

    private fun provenance(): DataProvenance = DataProvenance(
        providerId = "official-academic",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "academic-system",
        sourceRecordId = "course-1",
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED
    )
}
