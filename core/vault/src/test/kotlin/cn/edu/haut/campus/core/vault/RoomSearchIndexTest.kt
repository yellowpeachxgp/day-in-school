package cn.edu.haut.campus.core.vault

import androidx.room.Room
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.ExamScheduleEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomSearchIndexTest {
    private lateinit var database: HautDatabase
    private lateinit var index: RoomSearchIndex
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_search"),
        profileId = ProfileId("prof_search"),
        campusId = CampusId("lianhuajie")
    )

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        index = RoomSearchIndex(database, clock = { Instant.parse("2026-01-02T03:04:05Z") })
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun acceptanceQueriesHitAliasDiscreteAndRoomCode() = runBlocking {
        index.upsert(document("course-1", EntityType.COURSE, "高等数学A（一）", "张老师", aliases = listOf("高数")))
        index.upsert(document("course-2", EntityType.COURSE, "离散数学", "李老师"))
        index.upsert(document("exam-1", EntityType.EXAM, "数据结构", "莲花街校区 学生宿舍F01 W111"))

        val advanced = index.query(scope, SearchQuery(scope, "高数"))
        assertEquals(listOf("course-1"), advanced.hits.map { it.document.ref.id })
        assertEquals(SearchHitChannel.ALIAS, advanced.hits[0].channel)

        val discrete = index.query(scope, SearchQuery(scope, "离散"))
        assertEquals(listOf("course-2"), discrete.hits.map { it.document.ref.id })

        val room = index.query(scope, SearchQuery(scope, "W111"))
        assertEquals(listOf("exam-1"), room.hits.map { it.document.ref.id })
        val roomPrefix = index.query(scope, SearchQuery(scope, "w11"))
        assertEquals(listOf("exam-1"), roomPrefix.hits.map { it.document.ref.id })

        val skippedBigram = index.query(scope, SearchQuery(scope, "离数"))
        assertFalse(skippedBigram.hits.any { it.document.ref.id == "course-2" })
    }

    @Test
    fun updateAndDeleteKeepExternalContentFtsSynchronized() = runBlocking {
        val ref = EntityRef(EntityType.COURSE, "course-sync", scope)
        index.upsert(document("course-sync", EntityType.COURSE, "高等数学A（一）", "原正文"))
        assertEquals(1, index.query(scope, SearchQuery(scope, "高等数学")).totalCount)

        index.upsert(document("course-sync", EntityType.COURSE, "线性代数", "新正文", aliases = listOf("线代")))
        assertEquals(0, index.query(scope, SearchQuery(scope, "高等数学")).totalCount)
        assertEquals(1, index.query(scope, SearchQuery(scope, "线代")).totalCount)

        assertEquals(1, index.delete(ref))
        assertEquals(0, index.query(scope, SearchQuery(scope, "线代")).totalCount)
        val integrity = index.verifyIntegrity()
        assertTrue(integrity.toString(), integrity.isConsistent)
    }

    @Test
    fun singleChineseCharacterUsesNormalizedTextFallbackAndOperatorsStayLiteral() = runBlocking {
        index.upsert(document("course-1", EntityType.COURSE, "离散数学", "李老师"))

        val single = index.query(scope, SearchQuery(scope, "数"))
        assertTrue(single.hits.any { it.document.ref.id == "course-1" })

        val operators = index.query(scope, SearchQuery(scope, "OR AND NOT NEAR * - " + 34.toChar()))
        assertEquals(0, operators.totalCount)
    }

    @Test
    fun contributorsRebuildAcceptanceCorpusAndVerifyIntegrity() = runBlocking {
        database.campusDao().upsertCourse(
            CourseEntity(
                id = "course-1",
                externalId = "MATH101",
                name = "高等数学A（一）",
                teacher = "张老师",
                colorArgb = 1L,
                creditPlain = "5.0",
                source = "OFFICIAL_JW",
                userModified = false,
                lastSyncEpochMilli = 1L
            )
        )
        database.campusDao().upsertExamSchedules(listOf(
            ExamScheduleEntity(
                id = "exam-1",
                termId = "2025-2026-1",
                courseCode = "DS201",
                courseName = "数据结构",
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
                syncedAtEpochMilli = 1L,
                originTermId = "2025-2026-1"
            )
        ))

        val report = index.rebuild(
            scope,
            listOf(
                CourseSearchIndexContributor(database),
                ExamScheduleSearchIndexContributor(database),
                GradeAttemptSearchIndexContributor(database),
                PersonalEventSearchIndexContributor(database)
            )
        )

        assertEquals(2, report.indexedDocCount)
        assertTrue(report.integrity.isConsistent)
        assertEquals(listOf("course-1"), index.query(scope, SearchQuery(scope, "高数")).hits.map { it.document.ref.id })
        assertEquals(listOf("exam-1"), index.query(scope, SearchQuery(scope, "W111")).hits.map { it.document.ref.id })
    }

    @Test
    fun searchResultsAreScopeIsolated() = runBlocking {
        index.upsert(document("shared-id", EntityType.COURSE, "离散数学", "当前账号"))
        val otherScope = scope.copy(accountId = AccountId("acct_other"), profileId = ProfileId("prof_other"))
        index.upsert(
            SearchDocument(
                ref = EntityRef(EntityType.COURSE, "shared-id", otherScope),
                type = EntityType.COURSE,
                title = "离散数学",
                body = "另一个账号"
            )
        )

        val page = index.query(scope, SearchQuery(scope, "离散"))
        assertEquals(1, page.totalCount)
        assertEquals("当前账号", page.hits[0].document.body)
    }

    private fun document(
        id: String,
        type: EntityType,
        title: String,
        body: String,
        aliases: List<String> = emptyList()
    ): SearchDocument = SearchDocument(
        ref = EntityRef(type, id, scope),
        type = type,
        title = title,
        body = body,
        aliases = aliases,
        deeplink = "dayinschool://test/" + id
    )
}
