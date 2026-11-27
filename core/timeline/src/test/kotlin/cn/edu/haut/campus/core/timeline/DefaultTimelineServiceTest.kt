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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTimelineServiceTest {

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val window = TimeWindow(Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-03-02T13:00:00Z"))

    @Test
    fun `服务合并来源排序并重新裁剪窗口`() = runTest {
        val exam = event("exam:exam-1", "2026-03-02T08:30:00Z", "2026-03-02T09:30:00Z", kind = TimelineKind.EXAM)
        val course = event("course:course-1", "2026-03-02T10:00:00Z", "2026-03-02T11:00:00Z")
        val outside = event("course:outside", "2026-03-02T13:00:00Z", "2026-03-02T14:00:00Z")
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(course, outside))),
            FakeSource("exam", setOf(TimelineKind.EXAM), listOf(listOf(exam)))
        )

        val all = service.observe(scope, window, TimelineFilter.ALL).first()
        val courseOnly = service.observe(scope, window, TimelineFilter(kinds = setOf(TimelineKind.COURSE))).first()
        val examSourceOnly = service.observe(scope, window, TimelineFilter(sourceIds = setOf("exam"))).first()

        assertEquals(listOf("exam:exam-1", "course:course-1"), all.map { it.id.value })
        assertEquals(listOf("course:course-1"), courseOnly.map { it.id.value })
        assertEquals(listOf("exam:exam-1"), examSourceOnly.map { it.id.value })
    }

    @Test
    fun `多次同步同一课程事件 ID 不变而高 revision 替换`() = runTest {
        val firstSync = event("course:stable-course", revision = 1)
        val secondSync = firstSync.copy(title = "高等数学（调后）", revision = 2)
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(firstSync), listOf(secondSync)))
        )

        val emissions = service.observe(scope, window, TimelineFilter.ALL).take(2).toList()

        assertEquals("course:stable-course", emissions[0].single().id.value)
        assertEquals("course:stable-course", emissions[1].single().id.value)
        assertEquals(2, emissions[1].single().revision)
    }

    @Test
    fun `完全重复投影幂等去重且高 revision 获胜`() = runTest {
        val older = event("course:dedupe", revision = 1)
        val newer = older.copy(title = "高等数学（新）", revision = 2)
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(older, older, newer)))
        )

        val result = service.observe(scope, window, TimelineFilter.ALL).first()

        assertEquals(1, result.size)
        assertEquals(2, result.single().revision)
    }

    @Test
    fun `同 ID 同 revision 内容冲突会 fail closed`() {
        val first = event("course:conflict", revision = 1)
        val conflicting = first.copy(title = "另一门课", revision = 1)
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(first, conflicting)))
        )

        assertFailsWith<TimelineConsistencyException> {
            runTest { service.observe(scope, window, TimelineFilter.ALL).first() }
        }
    }

    @Test
    fun `source 伪造其他来源前缀会 fail closed`() {
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(event("exam:forged"))))
        )

        assertFailsWith<TimelineConsistencyException> {
            runTest { service.observe(scope, window, TimelineFilter.ALL).first() }
        }
    }

    @Test
    fun `跨账号投影会 fail closed`() {
        val otherScope = scope.copy(accountId = AccountId("other-account"))
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(event("course:cross-account", scope = otherScope))))
        )

        assertFailsWith<TimelineConsistencyException> {
            runTest { service.observe(scope, window, TimelineFilter.ALL).first() }
        }
    }

    @Test
    fun `source 投影未声明的 kind 会 fail closed`() {
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.EXAM), listOf(listOf(event("course:wrong-kind"))))
        )

        assertFailsWith<TimelineConsistencyException> {
            runTest { service.observe(scope, window, TimelineFilter.ALL).first() }
        }
    }

    @Test
    fun `调休表示中今日停课不占忙而调入事件占忙`() = runTest {
        val cancelled = event(
            "course:monday-cancelled",
            "2026-03-02T09:00:00Z",
            "2026-03-02T10:00:00Z",
            status = EventStatus.CANCELLED_TODAY
        )
        val makeup = event(
            "course:saturday-makeup",
            "2026-03-02T12:00:00Z",
            "2026-03-02T13:00:00Z",
            status = EventStatus.RESCHEDULED
        )
        val exactBusy = event("course:exact", "2026-03-02T08:07:00Z", "2026-03-02T08:08:00Z")
        val freeEvent = event("personal:free", "2026-03-02T10:00:00Z", "2026-03-02T11:00:00Z", busy = BusyLevel.FREE)
        val deadline = event("task:deadline", "2026-03-02T11:00:00Z", null, kind = TimelineKind.TASK_DUE)
        val service = DefaultTimelineService(
            FakeSource(
                "course",
                setOf(TimelineKind.COURSE, TimelineKind.PERSONAL, TimelineKind.TASK_DUE),
                listOf(listOf(cancelled, makeup, exactBusy, freeEvent.copyIdPrefix("course"), deadline.copyIdPrefix("course")))
            )
        )

        val result = service.busyBlocks(scope, window, Duration.ofMinutes(30))

        assertEquals(
            listOf(
                BusyBlock(Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-03-02T08:30:00Z"), BusyLevel.BUSY),
                BusyBlock(Instant.parse("2026-03-02T12:00:00Z"), Instant.parse("2026-03-02T13:00:00Z"), BusyLevel.BUSY)
            ),
            result
        )
    }

    @Test
    fun `freeSlots 使用精确调入占用并释放今日停课时间`() = runTest {
        val cancelled = event(
            "course:cancelled",
            "2026-03-02T10:00:00Z",
            "2026-03-02T11:00:00Z",
            status = EventStatus.CANCELLED_TODAY
        )
        val busy = event("course:busy", "2026-03-02T09:00:00Z", "2026-03-02T10:00:00Z")
        val makeup = event(
            "course:makeup",
            "2026-03-02T12:00:00Z",
            "2026-03-02T13:00:00Z",
            status = EventStatus.RESCHEDULED
        )
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(cancelled, busy, makeup)))
        )

        val result = service.freeSlots(
            scope = scope,
            window = window,
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(zone = zone)
        )

        assertEquals(
            listOf(
                FreeSlot(Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-03-02T09:00:00Z")),
                FreeSlot(Instant.parse("2026-03-02T10:00:00Z"), Instant.parse("2026-03-02T12:00:00Z"))
            ),
            result
        )
    }

    @Test
    fun `过滤前先解决 revision 最新取消不会让旧日程复活`() = runTest {
        val older = event("course:cancelled-latest", revision = 1)
        val newerCancelled = older.copy(status = EventStatus.CANCELLED_TODAY, revision = 2)
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(older, newerCancelled)))
        )

        val scheduledOnly = service.observe(
            scope,
            window,
            TimelineFilter(statuses = setOf(EventStatus.SCHEDULED))
        ).first()

        assertEquals(emptyList<TimelineEvent>(), scheduledOnly)
    }

    @Test
    fun `过滤前先解决 revision 移出窗口的新版本会淘汰窗内旧版本`() = runTest {
        val older = event("course:moved-outside", revision = 1)
        val newerOutside = older.copy(
            start = Instant.parse("2026-03-02T13:00:00Z"),
            end = Instant.parse("2026-03-02T14:00:00Z"),
            revision = 2
        )
        val service = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(older, newerOutside)))
        )

        val result = service.observe(scope, window, TimelineFilter.ALL).first()

        assertEquals(emptyList<TimelineEvent>(), result)
    }

    @Test
    fun `同 revision 冲突检查与最高版本出现顺序无关`() {
        val low = event("course:ordered-conflict", revision = 1)
        val conflictingLow = low.copy(title = "另一门课", revision = 1)
        val high = low.copy(title = "高等数学（新）", revision = 2)
        val highFirst = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(high, low, conflictingLow)))
        )
        val highLast = DefaultTimelineService(
            FakeSource("course", setOf(TimelineKind.COURSE), listOf(listOf(low, conflictingLow, high)))
        )

        assertFailsWith<TimelineConsistencyException> {
            runTest { highFirst.observe(scope, window, TimelineFilter.ALL).first() }
        }
        assertFailsWith<TimelineConsistencyException> {
            runTest { highLast.observe(scope, window, TimelineFilter.ALL).first() }
        }
    }

    private fun TimelineEvent.copyIdPrefix(source: String): TimelineEvent =
        copy(id = TimelineEventId(source + ":" + id.stableKey))

    private class FakeSource(
        override val id: String,
        override val kinds: Set<TimelineKind>,
        private val emissions: List<List<TimelineEvent>>
    ) : TimelineSource {
        override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> = flow {
            emissions.forEach { emit(it) }
        }
    }

    private fun event(
        id: String,
        start: String = "2026-03-02T09:00:00Z",
        end: String? = "2026-03-02T10:00:00Z",
        kind: TimelineKind = TimelineKind.COURSE,
        status: EventStatus = EventStatus.SCHEDULED,
        busy: BusyLevel = BusyLevel.BUSY,
        revision: Long = 1,
        scope: SchoolAccountScope = this.scope
    ): TimelineEvent {
        val entity = EntityRef(EntityType.COURSE, "entity-" + id.substringAfter(':'), scope)
        return TimelineEvent(
            id = TimelineEventId(id),
            scope = scope,
            kind = kind,
            title = "高等数学",
            start = Instant.parse(start),
            end = end?.let { Instant.parse(it) },
            zone = zone,
            place = PlaceRef("haut/lianhuajie/room/5-111", SchoolId("haut"), CampusId("lianhuajie")),
            source = SourceRef(entity, provenance(scope)),
            provenance = provenance(scope),
            status = status,
            busy = busy,
            revision = revision,
            links = listOf(entity)
        )
    }

    private fun provenance(scope: SchoolAccountScope): DataProvenance = DataProvenance(
        providerId = "official-academic",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "academic-system",
        sourceRecordId = "course-1",
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED
    )

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("抛出了非预期异常: " + error, error)
        }
        throw AssertionError("预期抛出 " + T::class.java.name + " 但成功完成")
    }
}
