package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewTaskCompatibilityTest {
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_review_compat"),
        profileId = ProfileId("prof_review_compat")
    )
    private val course = CourseAssociation(
        courseId = "linear-algebra",
        courseName = "线性代数",
        courseCode = "MATH201",
        termId = "2025-autumn",
        teachingWeek = 7
    )
    private val now: Instant = Instant.parse("2026-03-02T00:00:00Z")

    @Test
    fun w304ReviewTaskDocumentWithoutSchedulingFieldsDecodesWithConservativeDefaults() {
        val legacy = jsonObjectOf(
            "format" to jsonString(REVIEW_TASK_FORMAT),
            "deleted" to jsonBool(false),
            "id" to jsonString("review-legacy"),
            "scope" to encodeScope(scope),
            "course" to encodeCourseAssociation(course),
            "materialId" to jsonString(null),
            "title" to jsonString("复习旧任务"),
            "note" to jsonString(null),
            "state" to jsonString(ReviewTaskState.TODO.name),
            "dueAt" to jsonString(null),
            "estimatedMinutes" to jsonInt(60),
            "revision" to jsonInt(1),
            "createdAt" to jsonString(now.toString()),
            "updatedAt" to jsonString(now.toString())
        )

        val decoded = decodeReviewTaskDocument(legacy)!!

        assertEquals(0, decoded.masteryPercent)
        assertEquals(0, decoded.successfulReviewCount)
        assertEquals(0, decoded.lapseCount)
        assertNull(decoded.lastReviewedAt)
        assertNull(decoded.schedule)
    }

    @Test
    fun reviewTaskScheduleRoundTripsThroughCanonicalDocument() {
        val eventId = TimelineReviewScheduler.reviewBlockEventId("review-roundtrip", 1, 1)
        val block = ReviewBlock(
            id = "rb-roundtrip",
            repetitionIndex = 1,
            targetStart = now,
            start = now,
            end = now.plusSeconds(1800),
            state = ReviewBlockState.PLANNED,
            timelineEventId = eventId
        )
        val schedule = ReviewSchedule(
            algorithm = SpacedRepetitionPolicy.ALGORITHM_ID,
            planRevision = 1,
            generatedAt = now,
            zone = ZoneId.of("Asia/Shanghai"),
            deadlineAt = now.plusSeconds(7 * 24 * 3600),
            blocks = listOf(block)
        )
        val task = ReviewTask(
            id = "review-roundtrip",
            scope = scope,
            course = course,
            materialId = null,
            title = "复习特征值",
            note = "带排程快照",
            state = ReviewTaskState.TODO,
            dueAt = null,
            estimatedMinutes = 60,
            revision = 1,
            createdAt = now,
            updatedAt = now,
            masteryPercent = 35,
            successfulReviewCount = 0,
            lapseCount = 0,
            lastReviewedAt = null,
            schedule = schedule
        )

        val decoded = decodeReviewTaskDocument(encodeReviewTaskDocument(task))

        assertEquals(task, decoded)
    }
}
