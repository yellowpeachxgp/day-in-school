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
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IcsTest {

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `ICS 使用 CRLF 且折叠行不超过七十五字节`() {
        val longTitle = "高等数学" + "期末复习".repeat(12) + "😀"
        val ics = StandardIcsExporter.export(listOf(event(title = longTitle)), "课程日历")

        assertTrue(ics.endsWith("\r\n"))
        assertFalse(ics.contains("METHOD:"))
        val withoutStructuralBreaks = ics.replace("\r\n", "")
        assertFalse(withoutStructuralBreaks.contains('\r'))
        assertFalse(withoutStructuralBreaks.contains('\n'))
        val physical = physicalLines(ics)
        assertTrue(physical.any { it.startsWith(" ") })
        physical.forEach { line ->
            assertTrue("物理行超过 75 octets: " + line, line.toByteArray(StandardCharsets.UTF_8).size <= 75)
        }
    }

    @Test
    fun `folding 不拆 UTF8 字符且去折叠后完全还原`() {
        val logical = "SUMMARY:" + "高".repeat(40) + "😀尾"

        val folded = StandardIcsExporter.foldLine(logical)
        val physical = folded.split("\r\n")

        assertTrue(physical.size > 1)
        physical.forEach { line ->
            assertTrue(line.toByteArray(StandardCharsets.UTF_8).size <= 75)
        }
        physical.drop(1).forEach { continuation ->
            assertTrue(continuation.startsWith(" "))
        }
        val unfolded = physical.first() + physical.drop(1).joinToString("") { it.substring(1) }
        assertEquals(logical, unfolded)
    }

    @Test
    fun `TEXT 转义反斜杠分号逗号换行并可逆`() {
        val input = "数学\\;,练习\n第二行"
        val expected = "数学" + "\\\\" + "\\;" + "\\," + "练习" + "\\n" + "第二行"

        assertEquals(expected, StandardIcsExporter.escapeText(input))
        assertEquals(input, IcsParser.unescapeText(expected))
    }

    @Test
    fun `导出 SUMMARY 使用 TEXT 转义`() {
        val ics = StandardIcsExporter.export(listOf(event(title = "数学\\;,提高")), "课程日历")
        val expectedSummary = "SUMMARY:数学" + "\\\\" + "\\;" + "\\," + "提高\r\n"

        assertTrue(ics.contains(expectedSummary))
    }

    @Test
    fun `取消状态映射为标准 CANCELLED 且保留精确私有状态`() {
        val cancelled = event(status = EventStatus.CANCELLED_TODAY, busy = BusyLevel.FREE)

        val ics = StandardIcsExporter.export(listOf(cancelled), "课程日历")
        val snapshot = IcsParser.parseEvents(ics).single()

        assertTrue(ics.contains("STATUS:CANCELLED\r\n"))
        assertTrue(ics.contains("TRANSP:TRANSPARENT\r\n"))
        assertFalse(ics.contains("TRANSP:OPAQUE\r\n"))
        assertTrue(ics.contains("X-DIS-EVENT-STATUS:CANCELLED_TODAY\r\n"))
        assertEquals(EventStatus.CANCELLED_TODAY, snapshot.status)
    }

    @Test
    fun `跨日事件按 UTC 标准时间导出并保留校区时区往返`() {
        val overnight = event(
            start = "2026-03-02T14:00:00Z",
            end = "2026-03-02T22:00:00Z"
        )

        val ics = StandardIcsExporter.export(listOf(overnight), "课程日历")
        val snapshot = IcsParser.parseEvents(ics).single()

        assertTrue(ics.contains("DTSTART:20260302T140000Z\r\n"))
        assertTrue(ics.contains("DTEND:20260302T220000Z\r\n"))
        assertTrue(ics.contains("X-DIS-ZONE:Asia/Shanghai\r\n"))
        assertFalse(ics.contains("TZID="))
        assertEquals(IcsEventSnapshot.from(overnight), snapshot)
        assertEquals(zone, snapshot.zone)
    }

    @Test
    fun `截止点不写 DTEND 且往返保留 null end`() {
        val deadline = event(id = "task:deadline", end = null, kind = TimelineKind.TASK_DUE)

        val ics = StandardIcsExporter.export(listOf(deadline), "任务")
        val snapshot = IcsParser.parseEvents(ics).single()

        assertFalse(ics.contains("DTEND;"))
        assertTrue(ics.contains("X-DIS-DEADLINE:TRUE\r\n"))
        assertNull(snapshot.end)
        assertEquals(TimelineTimeSemantics.DEADLINE, snapshot.timeSemantics)
        assertEquals(IcsEventSnapshot.from(deadline), snapshot)
    }

    @Test
    fun `不确定时间不伪装截止点且按透明锚点往返`() {
        val uncertain = event(
            id = "course:uncertain",
            end = null,
            status = EventStatus.TENTATIVE,
            busy = BusyLevel.FREE,
            timeSemantics = TimelineTimeSemantics.UNCERTAIN
        )

        val ics = StandardIcsExporter.export(listOf(uncertain), "课程日历")
        val snapshot = IcsParser.parseEvents(ics).single()

        assertFalse(ics.contains("DTEND:"))
        assertFalse(ics.contains("X-DIS-DEADLINE:TRUE"))
        assertTrue(ics.contains("X-DIS-TIME-UNCERTAIN:TRUE\r\n"))
        assertTrue(ics.contains("TRANSP:TRANSPARENT\r\n"))
        assertNull(snapshot.end)
        assertEquals(TimelineTimeSemantics.UNCERTAIN, snapshot.timeSemantics)
        assertEquals(IcsEventSnapshot.from(uncertain), snapshot)
    }

    @Test
    fun `非截止点缺 DTEND 按 RFC 零时长语义拒绝`() {
        val zeroDuration = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:course:rfc-zero-duration\r\n" +
            "DTSTART:20260302T090000Z\r\n" +
            "SUMMARY:RFC 零时长事件\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        try {
            IcsParser.parseEvents(zeroDuration)
            fail("预期非截止点缺 DTEND 被拒绝")
        } catch (expected: IcsParseException) {
            assertTrue(expected.message.orEmpty().contains("零时长"))
        }
    }

    @Test
    fun `SEQUENCE 与 X-DIS-REVISION 不一致会拒绝导入`() {
        val exported = StandardIcsExporter.export(listOf(event(revision = 2)), "课程日历")
        val mismatched = exported.replace("SEQUENCE:2\r\n", "SEQUENCE:3\r\n")

        try {
            IcsParser.parseEvents(mismatched)
            fail("预期 SEQUENCE 与 X-DIS-REVISION 冲突被拒绝")
        } catch (expected: IcsParseException) {
            assertTrue(expected.message.orEmpty().contains("X-DIS-REVISION 冲突"))
        }
    }

    @Test
    fun `相同输入导出幂等且 UID 逐字等于 TimelineEventId`() {
        val stable = event(id = "course:term-1:rule-9:2026-03-02")

        val first = StandardIcsExporter.export(listOf(stable), "课程日历")
        val second = StandardIcsExporter.export(listOf(stable.copy()), "课程日历")

        assertEquals(first, second)
        assertTrue(first.contains("UID:course:term-1:rule-9:2026-03-02\r\n"))
        assertEquals(stable.id.value, IcsParser.parseEvents(first).single().uid)
    }

    @Test
    fun `重复导入精确相同 UID 事件幂等去重`() {
        val exported = StandardIcsExporter.export(listOf(event()), "课程日历")
        val duplicated = exported.replace(
            "END:VCALENDAR",
            eventBlock(exported) + "\r\nEND:VCALENDAR"
        )

        val snapshots = IcsParser.parseEvents(duplicated)

        assertEquals(1, snapshots.size)
        assertEquals(event().id.value, snapshots.single().uid)
    }

    @Test
    fun `重复导入高 revision 替换低 revision 且事件 ID 稳定`() {
        val older = event(title = "高等数学", revision = 1)
        val newer = older.copy(title = "高等数学（调后）", revision = 2)
        val olderIcs = StandardIcsExporter.export(listOf(older), "课程日历")
        val newerIcs = StandardIcsExporter.export(listOf(newer), "课程日历")
        val repeatedImport = olderIcs.replace(
            "END:VCALENDAR",
            eventBlock(newerIcs) + "\r\nEND:VCALENDAR"
        )

        val snapshot = IcsParser.parseEvents(repeatedImport).single()

        assertEquals(older.id.value, snapshot.uid)
        assertEquals(2L, snapshot.revision)
        assertEquals("高等数学（调后）", snapshot.title)
    }

    @Test
    fun `重复 UID 同 revision 内容冲突会拒绝导入`() {
        val first = event(title = "高等数学", revision = 1)
        val conflicting = first.copy(title = "另一门课", revision = 1)
        val firstIcs = StandardIcsExporter.export(listOf(first), "课程日历")
        val conflictingIcs = StandardIcsExporter.export(listOf(conflicting), "课程日历")
        val combined = firstIcs.replace(
            "END:VCALENDAR",
            eventBlock(conflictingIcs) + "\r\nEND:VCALENDAR"
        )

        try {
            IcsParser.parseEvents(combined)
            fail("预期重复 UID 冲突被拒绝")
        } catch (expected: IcsParseException) {
            assertTrue(expected.message.orEmpty().contains("重复且内容冲突"))
        }
    }

    @Test
    fun `导出同 revision 冲突检查与最高版本顺序无关`() {
        val low = event(title = "高等数学", revision = 1)
        val conflictingLow = low.copy(title = "另一门课", revision = 1)
        val high = low.copy(title = "高等数学（新）", revision = 2)

        listOf(
            listOf(high, low, conflictingLow),
            listOf(low, conflictingLow, high)
        ).forEach { events ->
            try {
                StandardIcsExporter.export(events, "课程日历")
                fail("预期导出同 revision 冲突被拒绝")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("同 revision 内容冲突"))
            }
        }
    }

    @Test
    fun `导入同 revision 冲突检查与最高版本顺序无关`() {
        val low = event(title = "高等数学", revision = 1)
        val conflictingLow = low.copy(title = "另一门课", revision = 1)
        val high = low.copy(title = "高等数学（新）", revision = 2)
        val lowIcs = StandardIcsExporter.export(listOf(low), "课程日历")
        val conflictingIcs = StandardIcsExporter.export(listOf(conflictingLow), "课程日历")
        val highIcs = StandardIcsExporter.export(listOf(high), "课程日历")
        val highFirst = insertEventBlocks(
            highIcs,
            listOf(eventBlock(lowIcs), eventBlock(conflictingIcs))
        )
        val highLast = insertEventBlocks(
            lowIcs,
            listOf(eventBlock(conflictingIcs), eventBlock(highIcs))
        )

        listOf(highFirst, highLast).forEach { text ->
            try {
                IcsParser.parseEvents(text)
                fail("预期导入同 revision 冲突被拒绝")
            } catch (expected: IcsParseException) {
                assertTrue(expected.message.orEmpty().contains("重复且内容冲突"))
            }
        }
    }

    @Test
    fun `标准 ICS 状态透明度与 SEQUENCE 可作为外部导入回退`() {
        val external = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:course:external\r\n" +
            "DTSTART;TZID=\"Asia/Shanghai\":20260302T090000\r\n" +
            "DTEND;TZID=\"Asia/Shanghai\":20260302T100000\r\n" +
            "SUMMARY:外部课程\r\n" +
            "STATUS:CANCELLED\r\n" +
            "TRANSP:TRANSPARENT\r\n" +
            "SEQUENCE:7\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val snapshot = IcsParser.parseEvents(external).single()

        assertEquals(EventStatus.CANCELLED_TODAY, snapshot.status)
        assertEquals(BusyLevel.FREE, snapshot.busy)
        assertEquals(7L, snapshot.revision)
    }

    @Test
    fun `亚秒精度事件拒绝导出以避免静默截断`() {
        val subsecond = event().copy(start = Instant.parse("2026-03-02T01:00:00.123Z"))

        try {
            StandardIcsExporter.export(listOf(subsecond), "课程日历")
            fail("预期亚秒事件导出被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("亚秒"))
        }
    }

    private fun physicalLines(ics: String): List<String> = ics.removeSuffix("\r\n").split("\r\n")

    private fun insertEventBlocks(baseIcs: String, blocks: List<String>): String = baseIcs.replace(
        "END:VCALENDAR",
        blocks.joinToString("\r\n") + "\r\nEND:VCALENDAR"
    )

    private fun eventBlock(ics: String): String {
        val begin = ics.indexOf("BEGIN:VEVENT")
        val end = ics.indexOf("END:VEVENT", begin) + "END:VEVENT".length
        check(begin >= 0 && end >= "END:VEVENT".length) { "测试 ICS 缺少 VEVENT" }
        return ics.substring(begin, end)
    }

    private fun event(
        id: String = "course:term-1:rule-9:2026-03-02",
        title: String = "高等数学",
        start: String = "2026-03-02T01:00:00Z",
        end: String? = "2026-03-02T02:00:00Z",
        kind: TimelineKind = TimelineKind.COURSE,
        status: EventStatus = EventStatus.SCHEDULED,
        busy: BusyLevel = BusyLevel.BUSY,
        revision: Long = 1,
        timeSemantics: TimelineTimeSemantics? = null
    ): TimelineEvent {
        val entity = EntityRef(EntityType.COURSE, "course-1", scope)
        return TimelineEvent(
            id = TimelineEventId(id),
            scope = scope,
            kind = kind,
            title = title,
            start = Instant.parse(start),
            end = end?.let { Instant.parse(it) },
            timeSemantics = timeSemantics ?: if (end == null) TimelineTimeSemantics.DEADLINE else TimelineTimeSemantics.TIMED,
            zone = zone,
            place = PlaceRef("haut/lianhuajie/room/5-111", SchoolId("haut"), CampusId("lianhuajie")),
            source = SourceRef(entity, provenance()),
            provenance = provenance(),
            status = status,
            busy = busy,
            revision = revision,
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
