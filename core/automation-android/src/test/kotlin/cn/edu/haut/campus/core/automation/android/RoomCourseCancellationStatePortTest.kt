package cn.edu.haut.campus.core.automation.android

import androidx.room.Room
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.CourseScheduleRuleEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.OccurrenceOverrideEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomCourseCancellationStatePortTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-cancel-state"),
        profileId = ProfileId("prof-cancel-state"),
        campusId = CampusId("haut-lianhuajie")
    )
    private val courseRef = EntityRef(EntityType.COURSE, "course-1", scope)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.campusDao().upsertCourse(
            CourseEntity(
                id = courseRef.id,
                externalId = null,
                name = "高等数学",
                teacher = "测试教师",
                colorArgb = 0xFF053968,
                creditPlain = null,
                source = "MANUAL",
                userModified = true,
                lastSyncEpochMilli = null
            )
        )
        database.campusDao().upsertRules(
            listOf(
                CourseScheduleRuleEntity(
                    id = "rule-1",
                    courseId = courseRef.id,
                    termId = "2026-2027-1",
                    weekdayIso = 1,
                    startPeriod = 1,
                    endPeriod = 2,
                    weeksCsv = "1,2",
                    rawRoom = "W111",
                    buildingId = null,
                    customStartTimeIso = null,
                    customEndTimeIso = null,
                    userModified = false
                )
            )
        )
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun exactAttributedRowsMustRemainCanceledAtExecutionTime() = runBlocking {
        upsertOverride(id = "ov_cancel", canceled = true)
        upsertOverride(id = "ov_other", canceled = true)
        val port = RoomCourseCancellationStatePort(database, scope)

        assertEquals(true, port.hasActiveCancellation(scope, courseRef, setOf("ov_cancel")))

        upsertOverride(id = "ov_cancel", canceled = false)

        assertEquals(false, port.hasActiveCancellation(scope, courseRef, setOf("ov_cancel")))
        assertEquals("课程级 status fallback 仍应看到其它 active cancellation", true, port.hasActiveCancellation(scope, courseRef, emptySet()))
    }

    private suspend fun upsertOverride(id: String, canceled: Boolean) {
        database.campusDao().upsertOccurrenceOverrides(
            listOf(
                OccurrenceOverrideEntity(
                    id = id,
                    termId = "2026-2027-1",
                    courseRuleId = "rule-1",
                    courseId = courseRef.id,
                    dateIso = "2026-10-12",
                    startPeriod = 1,
                    endPeriod = 2,
                    startAtIso = null,
                    endAtIso = null,
                    room = "W111",
                    buildingId = null,
                    canceled = canceled,
                    reason = if (canceled) "单次停课" else null,
                    moveId = null,
                    moveRole = null,
                    mutationToken = "token-$id-$canceled"
                )
            )
        )
    }
}
