package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程名进入 TimelineEvent 信封时的安全化回归：信封必须合法，领域还原必须无损。 */
class CourseTimelineProjectionSafetyTest {
    private val fixture = TimelineSourceTestFixtures

    private fun projectCourseName(rawName: String): Pair<TimelineEvent, ClassOccurrence> {
        val course = fixture.course(
            id = "c-unsafe-name",
            externalId = "manual-unsafe-name",
            name = rawName
        )
        val rule = fixture.rule(
            id = "r-unsafe-name",
            courseId = course.id,
            weeks = setOf(1)
        )
        val snapshot = fixture.scheduleSnapshot(
            courses = listOf(course),
            rules = listOf(rule)
        )
        val events = CourseTimelineProjection.projectAll(
            snapshot = snapshot,
            scope = fixture.scope,
            window = fixture.dayWindow(LocalDate.of(2026, 8, 31))
        )
        val event = events.single { it.kind == TimelineKind.COURSE }
        return event to CourseOccurrenceTimelineMetadata.decode(event)
    }

    @Test
    fun longAndControlCourseNameUsesSafeEnvelopeAndRoundTripsExactly() {
        val rawName = "手工\u0001\n\u0085" + "长".repeat(5_000) + "\u0007\u007F结尾"

        val (event, decoded) = projectCourseName(rawName)

        assertTrue("信封标题不得超过 TimelineEvent 上限", event.title.length <= 256)
        assertTrue("信封标题不得包含控制字符", event.title.none { it.isISOControl() })
        assertTrue(event.title != rawName)
        assertTrue(
            "JSON 转义后的长原文必须分片保存在 sourceMetadata",
            event.sourceMetadata.getValue(CourseOccurrenceTimelineMetadata.COURSE_NAME_JSON_CHUNK_COUNT).toInt() >= 2
        )
        assertEquals("还原给课表显示的课程名必须逐字等于原输入", rawName, decoded.courseName)
    }

    @Test
    fun allControlCourseNameStillProducesNonBlankEnvelopeAndExactRestore() {
        val rawName = "\u0001\u0007\n\u0085\u007F"

        val (event, decoded) = projectCourseName(rawName)

        assertTrue(event.title.isNotBlank())
        assertTrue(event.title.none { it.isISOControl() })
        assertEquals(rawName, decoded.courseName)
    }

    @Test
    fun controlAndOversizedTeacherAndRoomRoundTripExactly() {
        val teacher = "xm\u0001\u0085" + "名".repeat(4_200) + "\u007F"
        val room = "cdmc\u0007\u0085" + "室".repeat(4_200) + "\u0001"
        val course = fixture.course(
            id = "c-unsafe-people-room",
            externalId = "manual-unsafe-people-room",
            name = "普通课程名"
        ).copy(teacher = teacher)
        val rule = fixture.rule(
            id = "r-unsafe-people-room",
            courseId = course.id,
            weeks = setOf(1),
            rawRoom = room
        )
        val snapshot = fixture.scheduleSnapshot(courses = listOf(course), rules = listOf(rule))

        val events = CourseTimelineProjection.projectAll(
            snapshot = snapshot,
            scope = fixture.scope,
            window = fixture.dayWindow(LocalDate.of(2026, 8, 31))
        )
        val event = events.single { it.kind == TimelineKind.COURSE }
        val decoded = CourseOccurrenceTimelineMetadata.decode(event)

        assertTrue(event.sourceMetadata.values.all { value -> value.length <= 4096 && value.none { it.isISOControl() } })
        assertTrue(event.sourceMetadata.getValue("courseOccurrence.teacher.json.chunkCount").toInt() >= 2)
        assertTrue(event.sourceMetadata.getValue("courseOccurrence.rawRoom.json.chunkCount").toInt() >= 2)
        assertEquals("xm/cdmc 教师原文必须逐字还原", teacher, decoded.teacher)
        assertEquals("xm/cdmc 教室原文必须逐字还原", room, decoded.rawRoom)
    }

    @Test
    fun controlAndOversizedOverrideReasonRoundTripsExactly() {
        val reason = "override\u0001\u0085" + "因".repeat(4_200) + "\u007F"
        val course = fixture.course(id = "c-unsafe-reason", externalId = "manual-unsafe-reason", name = "调课课程")
        val rule = fixture.rule(id = "r-unsafe-reason", courseId = course.id, weeks = setOf(1), rawRoom = "原教室")
        val occurrenceOverride = OccurrenceOverride(
            id = "ov-unsafe-reason",
            termId = fixture.term.id,
            courseRuleId = rule.id,
            date = LocalDate.of(2026, 8, 31),
            startPeriod = 1,
            endPeriod = 2,
            room = "调入教室",
            canceled = false,
            reason = reason
        )
        val snapshot = fixture.scheduleSnapshot(
            courses = listOf(course),
            rules = listOf(rule),
            occurrenceOverrides = listOf(occurrenceOverride)
        )

        val events = CourseTimelineProjection.projectAll(
            snapshot = snapshot,
            scope = fixture.scope,
            window = fixture.dayWindow(LocalDate.of(2026, 8, 31))
        )
        val event = events.single { it.kind == TimelineKind.COURSE }
        val decoded = CourseOccurrenceTimelineMetadata.decode(event)

        assertTrue(event.sourceMetadata.getValue("courseOccurrence.overrideReason.json.chunkCount").toInt() >= 2)
        assertEquals(reason, decoded.overrideReason)
        assertTrue(decoded.isMakeupOrOverridden)
    }
}
