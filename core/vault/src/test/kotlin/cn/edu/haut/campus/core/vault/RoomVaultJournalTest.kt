package cn.edu.haut.campus.core.vault

import androidx.room.Room
import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.CourseScheduleRuleEntity
import cn.edu.haut.campus.core.database.ExamScheduleEntity
import cn.edu.haut.campus.core.database.GradeAttemptEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.OccurrenceOverrideEntity
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.InMemoryEventOutbox
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomVaultJournalTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_test"),
        profileId = ProfileId("prof_test"),
        campusId = CampusId("lianhuajie")
    )
    private val courseRef = EntityRef(EntityType.COURSE, "course-1", scope)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun writerCapturesCreateUpdateDeleteAndPublishesSmallReferenceEvent() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val search = RoomSearchIndex(database)
        val course = courseRow()

        journal.write(courseRef, Actor.USER, actorDetail = "test-create") {
            database.campusDao().upsertCourse(course)
        }
        assertEquals(1, search.query(scope, SearchQuery(scope, "高数")).totalCount)
        journal.write(courseRef, Actor.USER) {
            database.campusDao().upsertCourse(course)
        }
        journal.write(courseRef, Actor.USER) {
            database.campusDao().upsertCourse(course.copy(name = "高等数学A（二）"))
        }
        journal.write(courseRef, Actor.USER) {
            database.campusDao().deleteCourseById(course.id)
        }
        assertEquals(0, search.query(scope, SearchQuery(scope, "高数")).totalCount)

        val history = journal.history(courseRef).first()
        assertEquals(listOf(ChangeKind.CREATED, ChangeKind.UPDATED, ChangeKind.DELETED), history.map { it.op })
        assertEquals(3, database.vaultDao().countEntityJournal())
        assertNull(history[0].before)
        assertTrue(history[0].after?.text?.contains("高等数学A（一）") == true)
        assertTrue(history[1].after?.text?.contains("高等数学A（二）") == true)
        assertNull(history[2].after)

        val events = outbox.pendingUnacked("automation")
        assertEquals(3, events.size)
        assertTrue(events.all { it.envelope.eventType == "vault.entity.changed" })
        assertTrue(events[0].envelope.payload.text.contains("changeId"))
        assertFalse(events[0].envelope.payload.text.contains("高等数学"))
    }

    @Test
    fun failedBusinessWriteRollsBackEntityAndLeavesNoJournal() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)

        val error = runCatching {
            journal.write(courseRef, Actor.USER) {
                database.campusDao().upsertCourse(courseRow())
                error("合成业务写入失败")
            }
        }.exceptionOrNull()

        assertNotNull(error)
        assertNull(database.campusDao().getCourseById(courseRef.id))
        assertEquals(0, RoomSearchIndex(database).query(scope, SearchQuery(scope, "高数")).totalCount)
        assertEquals(0, database.vaultDao().countEntityJournal())
        assertEquals(0, outbox.unackedCount("automation"))
    }

    @Test
    fun revertRestoresBeforeSnapshotAndMarksOriginalJournal() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val original = courseRow()
        journal.write(courseRef, Actor.USER) { database.campusDao().upsertCourse(original) }
        journal.write(courseRef, Actor.USER) {
            database.campusDao().upsertCourse(original.copy(name = "被误改的课程名", teacher = "误操作"))
        }
        val update = journal.history(courseRef).first().first { it.op == ChangeKind.UPDATED }

        assertEquals(0, RoomSearchIndex(database).query(scope, SearchQuery(scope, "高数")).totalCount)
        val result = journal.revert(update.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals(1, RoomSearchIndex(database).query(scope, SearchQuery(scope, "高数")).totalCount)
        val restored = database.campusDao().getCourseById(courseRef.id)
        assertEquals(original.name, restored?.name)
        assertEquals(original.teacher, restored?.teacher)
        val history = journal.history(courseRef).first()
        val originalRow = history.first { it.changeId == update.changeId }
        assertEquals(result.revertChangeId, originalRow.revertedByChangeId)
        val reversal = history.first { it.changeId == result.revertChangeId }
        assertEquals(ChangeKind.UPDATED, reversal.op)
        assertEquals("revert:" + update.changeId, reversal.actorDetail)

        val second = journal.revert(update.changeId, Actor.USER)
        assertEquals(RevertStatus.ALREADY_REVERTED, second.status)
        assertEquals(result.revertChangeId, second.revertChangeId)
    }

    @Test
    fun revertDeletedCourseAggregateRestoresCourseRulesAndOverrides() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val course = courseRow()
        journal.write(courseRef, Actor.SYNC) {
            database.campusDao().upsertCourse(course)
        }
        journal.write(courseRef, Actor.SYNC) {
            database.campusDao().deleteCourseById(course.id)
        }
        val delete = journal.history(courseRef).first().first { it.op == ChangeKind.DELETED }

        val result = journal.revert(delete.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals(course.name, database.campusDao().getCourseById(course.id)?.name)
        val reversal = journal.history(courseRef).first().first { it.changeId == result.revertChangeId }
        assertEquals(ChangeKind.RESTORED, reversal.op)
    }

    @Test
    fun gradeAndExamSnapshotStoresRoundTripThroughWriter() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val gradeRef = EntityRef(EntityType.GRADE_ATTEMPT, "grade-1", scope)
        val examRef = EntityRef(EntityType.EXAM, "exam-1", scope)

        journal.write(gradeRef, Actor.SYNC) {
            database.campusDao().upsertGradeAttempts(listOf(gradeRow()))
        }
        journal.write(examRef, Actor.SYNC) {
            database.campusDao().upsertExamSchedules(listOf(examRow()))
        }

        assertEquals(2, database.vaultDao().countEntityJournal())
        assertTrue(journal.history(gradeRef).first()[0].after?.text?.contains("离散数学") == true)
        assertTrue(journal.history(examRef).first()[0].after?.text?.contains("期末考试") == true)
    }

    @Test
    fun writeManyCapturesAtomicBatchWithSharedWriteGroup() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val gradeRef = EntityRef(EntityType.GRADE_ATTEMPT, "grade-1", scope)
        val examRef = EntityRef(EntityType.EXAM, "exam-1", scope)

        journal.writeMany(
            refsProvider = { listOf(gradeRef, examRef) },
            actor = Actor.SYNC,
            actorDetail = "batch-test",
            writeGroupId = "group-grade-exam"
        ) {
            database.withTransaction {
                database.campusDao().upsertGradeAttempts(listOf(gradeRow()))
                database.campusDao().upsertExamSchedules(listOf(examRow()))
            }
        }

        val rows = database.vaultDao().getJournalGroup("group-grade-exam")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.actorDetail == "batch-test" })
        assertEquals(setOf("GRADE_ATTEMPT", "EXAM"), rows.map { it.entityType }.toSet())
        assertEquals(2, outbox.pendingUnacked("automation").size)
    }

    @Test
    fun courseAggregateRevertExcludesAndRenewsOccurrenceMutationToken() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val original = courseRow()
        journal.write(courseRef, Actor.USER) {
            database.campusDao().upsertCourse(original)
            database.campusDao().upsertRule(ruleRow())
            database.campusDao().upsertOccurrenceOverrides(listOf(overrideRow(mutationToken = "frozen-token")))
        }
        journal.write(courseRef, Actor.USER) {
            database.campusDao().upsertCourse(original.copy(name = "误改课程名"))
        }
        val update = journal.history(courseRef).first().first { it.op == ChangeKind.UPDATED }

        val result = journal.revert(update.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals(original.name, database.campusDao().getCourseById(courseRef.id)?.name)
        val restoredOverride = database.campusDao().getOccurrenceOverridesSnapshot().first { it.id == "ov-1" }
        assertTrue(restoredOverride.mutationToken.isNotBlank())
        assertFalse(restoredOverride.mutationToken == "frozen-token")
        assertFalse(update.after?.text?.contains("mutationToken") == true)
        assertFalse(update.after?.text?.contains("frozen-token") == true)
    }

    @Test
    fun revertGradeUpdateRestoresRowAndMarksJournal() = runBlocking {
        val outbox = InMemoryEventOutbox()
        val journal = newJournal(outbox)
        val gradeRef = EntityRef(EntityType.GRADE_ATTEMPT, "grade-1", scope)
        journal.write(gradeRef, Actor.SYNC) {
            database.campusDao().upsertGradeAttempts(listOf(gradeRow()))
        }
        journal.write(gradeRef, Actor.SYNC) {
            database.campusDao().upsertGradeAttempts(listOf(gradeRow().copy(rawGrade = "59", numericScorePlain = "59")))
        }
        val update = journal.history(gradeRef).first().first { it.op == ChangeKind.UPDATED }

        val result = journal.revert(update.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals("92", database.campusDao().getGradeAttemptById("grade-1")?.rawGrade)
        val history = journal.history(gradeRef).first()
        assertEquals(result.revertChangeId, history.first { it.changeId == update.changeId }.revertedByChangeId)
        assertEquals(ChangeKind.UPDATED, history.first { it.changeId == result.revertChangeId }.op)
    }

    @Test
    fun sensitiveSnapshotKeysFailClosedBeforeBusinessWrite() = runBlocking {
        val sensitiveStore = object : EntitySnapshotStore {
            override val type: EntityType = EntityType.PROFILE
            override suspend fun read(ref: EntityRef): CanonicalJson =
                CanonicalJson.of("""{"displayName":"合成","password":"do-not-store"}""")
            override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) = Unit
        }
        val journal = RoomVaultJournal(
            database = database,
            outbox = InMemoryEventOutbox(),
            snapshotStores = listOf(sensitiveStore),
            clock = { Instant.parse("2026-01-02T03:04:05Z") },
            idGenerator = { "test-id" }
        )
        val profileRef = EntityRef(EntityType.PROFILE, "profile-1", scope)
        var businessWriteRan = false

        val error = runCatching {
            journal.write(profileRef, Actor.USER) { businessWriteRan = true }
        }.exceptionOrNull()

        assertTrue(error is SensitiveSnapshotException)
        assertFalse(businessWriteRan)
        assertEquals(0, database.vaultDao().countEntityJournal())
    }

    private fun newJournal(outbox: InMemoryEventOutbox): RoomVaultJournal {
        var id = 0
        return RoomVaultJournal(
            database = database,
            outbox = outbox,
            snapshotStores = listOf(
                CourseEntitySnapshotStore(database),
                GradeAttemptSnapshotStore(database),
                ExamScheduleSnapshotStore(database)
            ),
            clock = { Instant.parse("2026-01-02T03:04:05Z") },
            idGenerator = { "test-" + (++id) },
            searchIndex = RoomSearchIndex(database)
        )
    }

    private fun courseRow() = CourseEntity(
        id = courseRef.id,
        externalId = "ext-course-1",
        name = "高等数学A（一）",
        teacher = "张老师",
        colorArgb = 0xFFAA00L,
        creditPlain = "5.0",
        source = "OFFICIAL_JW",
        userModified = false,
        lastSyncEpochMilli = 100L
    )

    private fun ruleRow() = CourseScheduleRuleEntity(
        id = "rule-1",
        courseId = courseRef.id,
        termId = "2025-2026-1",
        weekdayIso = 1,
        startPeriod = 1,
        endPeriod = 2,
        weeksCsv = "1,2,3",
        rawRoom = "W111",
        buildingId = null,
        customStartTimeIso = null,
        customEndTimeIso = null,
        userModified = false
    )

    private fun overrideRow(mutationToken: String) = OccurrenceOverrideEntity(
        id = "ov-1",
        termId = "2025-2026-1",
        courseRuleId = "rule-1",
        courseId = courseRef.id,
        dateIso = "2026-01-05",
        startPeriod = 1,
        endPeriod = 2,
        startAtIso = null,
        endAtIso = null,
        room = "W111",
        buildingId = null,
        canceled = true,
        reason = "合成停课",
        moveId = null,
        moveRole = null,
        mutationToken = mutationToken
    )

    private fun gradeRow() = GradeAttemptEntity(
        id = "grade-1",
        termId = "2025-2026-1",
        academicYear = "2025-2026",
        termNumber = 1,
        courseKey = "MATH210",
        name = "离散数学",
        category = "REQUIRED",
        creditsPlain = "3.0",
        rawGrade = "92",
        numericScorePlain = "92",
        gradePointPlain = "4.2",
        attemptType = "FIRST",
        passed = true,
        isCourseReplacement = false,
        source = "OFFICIAL_JW",
        syncedAtEpochMilli = 100L
    )

    private fun examRow() = ExamScheduleEntity(
        id = "exam-1",
        termId = "2025-2026-1",
        courseCode = "MATH210",
        courseName = "离散数学",
        examBatchName = "期末考试",
        examStageLabel = "期末",
        examFormat = "闭卷",
        rawTimeText = "2026-01-10 09:00-11:00",
        examDateIso = "2026-01-10",
        startTimeIso = "09:00",
        endTimeIso = "11:00",
        rawRoom = "莲花街校区 学生宿舍F01 W111",
        seatNumber = "01",
        campusName = "莲花街校区",
        isRetake = false,
        syncedAtEpochMilli = 100L,
        originTermId = "2025-2026-1"
    )
}
