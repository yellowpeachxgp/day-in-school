package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.TimeEvidence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * encode/decode 还原保真测试：不确定时间证据（UNRESOLVED/INVALID）必须还原为
 * hasResolvedTime=false 的锚点展示模型，绝不允许缺省回落冒充精确时间。
 */
class CourseOccurrenceTimelineMetadataTest {

    private val fixture = TimelineSourceTestFixtures
    private val scope = fixture.scope
    private val zone = fixture.zone

    private fun occurrence(evidence: TimeEvidence): ClassOccurrence = ClassOccurrence(
        occurrenceId = "2026-03-02_r-math",
        courseId = "c-math",
        ruleId = "r-math",
        overrideId = "ov-1",
        courseName = "高等数学",
        teacher = "张老师",
        colorArgb = 0xFF112233,
        date = LocalDate.of(2026, 3, 2),
        teachingWeek = 3,
        weekday = DayOfWeek.MONDAY,
        startPeriod = 1,
        endPeriod = 2,
        startTime = LocalTime.of(8, 0),
        endTime = LocalTime.of(9, 40),
        rawRoom = "莲花街校区5号教学楼5204",
        buildingId = "b-5",
        isMakeupOrOverridden = true,
        overrideReason = "临时调课",
        userModified = true,
        mergedRuleIds = listOf("r-math", "r-math-2"),
        hasResolvedTime = evidence.hasResolvedTime,
        timeEvidence = evidence
    )

    private fun eventOf(occurrence: ClassOccurrence, uncertain: Boolean): TimelineEvent {
        val start = occurrence.date.atTime(occurrence.startTime).atZone(zone).toInstant()
        val end = if (uncertain) null else occurrence.date.atTime(occurrence.endTime).atZone(zone).toInstant()
        val provenance = DataProvenance(
            providerId = "local.room",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "account-database"
        )
        return TimelineEvent(
            id = TimelineEventId("course:test-evt-1"),
            scope = scope,
            kind = TimelineKind.COURSE,
            title = occurrence.courseName,
            start = start,
            end = end,
            timeSemantics = if (uncertain) TimelineTimeSemantics.UNCERTAIN else TimelineTimeSemantics.TIMED,
            zone = zone,
            place = null,
            source = SourceRef(
                EntityRef(EntityType.COURSE_OCCURRENCE, "test-evt-1", scope),
                provenance
            ),
            provenance = provenance,
            status = EventStatus.SCHEDULED,
            busy = if (uncertain) BusyLevel.FREE else BusyLevel.BUSY,
            revision = 1L,
            sourceMetadata = CourseOccurrenceTimelineMetadata.encode(occurrence)
        )
    }

    @Test
    fun resolvedOccurrenceRoundTripsEveryDomainField() {
        val original = occurrence(TimeEvidence.TIMETABLE_VALID)
        val decoded = CourseOccurrenceTimelineMetadata.decode(eventOf(original, uncertain = false))
        assertEquals(original, decoded)
    }

    @Test
    fun unresolvedOccurrenceKeepsAnchorOnlyPresentationAfterRoundTrip() {
        val original = occurrence(TimeEvidence.UNRESOLVED)
        assertFalse(original.hasResolvedTime)
        val decoded = CourseOccurrenceTimelineMetadata.decode(eventOf(original, uncertain = true))
        assertEquals(original, decoded)
        assertFalse("UNRESOLVED 还原后不得冒充精确时间", decoded.hasResolvedTime)
        assertEquals(TimeEvidence.UNRESOLVED, decoded.timeEvidence)
    }

    @Test
    fun invalidOccurrenceKeepsTimeEvidenceAfterRoundTrip() {
        val original = occurrence(TimeEvidence.INVALID)
        val decoded = CourseOccurrenceTimelineMetadata.decode(eventOf(original, uncertain = true))
        assertEquals(original, decoded)
        assertFalse(decoded.hasResolvedTime)
        assertEquals(TimeEvidence.INVALID, decoded.timeEvidence)
    }

    @Test
    fun estimatedOccurrenceSurvivesRoundTripWithExplicitEvidence() {
        val original = occurrence(TimeEvidence.ESTIMATED)
        val decoded = CourseOccurrenceTimelineMetadata.decode(eventOf(original, uncertain = false))
        assertEquals(original, decoded)
        assertTrue(decoded.hasResolvedTime)
        assertEquals(TimeEvidence.ESTIMATED, decoded.timeEvidence)
    }
}
