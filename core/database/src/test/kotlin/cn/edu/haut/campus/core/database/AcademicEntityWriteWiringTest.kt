package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.SourceRef
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AcademicEntityWriteWiringTest {
    private var fixture: RepairTestFixture? = null

    @After
    fun closeFixture() {
        fixture?.close()
        fixture = null
    }

    @Test
    fun gradeImportUsesNestedWriteBoundaryAndCoversReplacedRows() = runBlocking {
        val (f, port) = newFixture()
        f.dao.upsertGradeAttempts(listOf(gradeAttempt("grade-old").toEntity()))

        f.repository.saveImportedGrades(
            attempts = listOf(gradeAttempt("grade-new")),
            replaceExisting = true
        )

        val call = port.calls.single { it.actorDetail == "grades-import" }
        assertEquals(Actor.SYNC, call.actor)
        assertEquals(setOf("grade-old", "grade-new"), call.refs(EntityType.GRADE_ATTEMPT))
        assertEquals(listOf("grade-new"), f.dao.getGradeAttemptsSnapshot().map { it.id })
    }

    @Test
    fun examImportUsesNestedWriteBoundaryAndCoversClearedRows() = runBlocking {
        val (f, port) = newFixture()
        val termId = "2026-2027-1"
        f.dao.upsertExamSchedules(listOf(examItem("exam-old", termId).toEntity()))

        f.repository.saveImportedExams(
            exams = listOf(examItem("exam-new", termId)),
            replaceExisting = true,
            targetTermId = termId,
            targetTermComplete = true
        )

        val call = port.calls.single { it.actorDetail == "exams-import" }
        assertEquals(Actor.SYNC, call.actor)
        assertTrue(call.refs(EntityType.EXAM).containsAll(setOf("exam-old", "exam-new")))
        assertEquals(listOf("exam-new"), f.dao.getExamSchedulesSnapshot().map { it.id })
    }

    @Test
    fun manualCourseAddUsesCourseAggregateWriteBoundary() = runBlocking {
        val (f, port) = newFixture()

        f.repository.addManualCourseWithRule(
            termId = "2026-2027-1",
            name = "合成手工课",
            teacher = "合成教师",
            rawRoom = "W111",
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = setOf(1, 2),
            colorArgb = 0xFF0D9488
        )

        val courseId = f.dao.getCoursesSnapshot().single { it.name == "合成手工课" }.id
        val call = port.calls.single { it.actorDetail == "manual-course-add" }
        assertEquals(Actor.USER, call.actor)
        assertEquals(setOf(courseId), call.refs(EntityType.COURSE))
        assertTrue(f.dao.getRulesSnapshot().any { it.courseId == courseId })
    }

    private fun newFixture(): Pair<RepairTestFixture, RecordingWritePort> {
        val port = RecordingWritePort()
        val f = RepairTestFixture.create(
            context = RuntimeEnvironment.getApplication(),
            profileSeed = "prof_write_wiring_" + System.nanoTime(),
            academicEntityWritePort = port
        )
        fixture = f
        return f to port
    }

    private class RecordingWritePort : AcademicEntityWritePort {
        data class Call(
            val refs: List<EntityRef>,
            val actor: Actor,
            val actorDetail: String?,
            val writeGroupId: String?
        ) {
            fun refs(type: EntityType): Set<String> = refs.filter { it.type == type }.map { it.id }.toSet()
        }

        val calls = mutableListOf<Call>()

        override suspend fun <T> write(
            ref: EntityRef,
            actor: Actor,
            actorDetail: String?,
            source: SourceRef?,
            writeGroupId: String?,
            block: suspend () -> T
        ): T = writeMany(
            refsProvider = { listOf(ref) },
            actor = actor,
            actorDetail = actorDetail,
            source = source,
            writeGroupId = writeGroupId,
            block = block
        )

        override suspend fun <T> writeMany(
            refsProvider: suspend () -> List<EntityRef>,
            actor: Actor,
            actorDetail: String?,
            source: SourceRef?,
            writeGroupId: String?,
            block: suspend () -> T
        ): T {
            val refs = refsProvider()
            val result = block()
            calls += Call(refs = refs, actor = actor, actorDetail = actorDetail, writeGroupId = writeGroupId)
            return result
        }
    }

    private fun gradeAttempt(id: String) = GradeAttempt(
        id = id,
        termId = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        courseKey = "CS101",
        name = "合成成绩",
        credits = BigDecimal("2.0"),
        rawGrade = "80",
        syncedAt = Instant.parse("2026-11-01T00:00:00Z")
    )

    private fun examItem(id: String, termId: String) = ExamScheduleItem(
        id = id,
        termId = termId,
        courseCode = "CS101",
        courseName = "合成考试",
        examBatchName = "期末考试",
        examStageLabel = "期末集中考",
        examFormat = "闭卷",
        rawTimeText = "2026-06-20 09:00-11:00",
        examDate = LocalDate.of(2026, 6, 20),
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(11, 0),
        rawRoom = "W111",
        syncedAt = Instant.parse("2026-11-01T00:00:00Z")
    )
}
