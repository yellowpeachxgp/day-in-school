package cn.edu.haut.campus.feature.timetable

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.room.Room
import cn.edu.haut.campus.core.database.AccountProfileEntity
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.model.OccurrenceRule
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteKind
import cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 组装内存 Room/DataStore 与真实 CampusRepository；反射只用于跨模块测试装配，不进入生产路径。 */
private class DurableWriteViewModelFixture(
    val database: HautDatabase,
    val repository: CampusRepository,
    private val storeFile: File,
    private val dataStoreScope: CoroutineScope,
    val profile: AccountProfileEntity
) {
    val dao get() = database.campusDao()

    fun close() {
        database.close()
        dataStoreScope.cancel()
        storeFile.delete()
    }

    companion object {
        fun create(context: Context, profileSeed: String): DurableWriteViewModelFixture {
            val database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val storeFile = File.createTempFile("durable_write_vm_test_", ".preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { storeFile }
            )
            val profile = AccountProfileEntity(
                id = profileSeed,
                accountId = "acct_" + profileSeed,
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "耐用写合成账号",
                isLegacy = false,
                databaseName = "durable_write_test.db",
                preferencesName = "durable_write_test_prefs",
                createdAtEpochMs = 1_000L
            )
            val repository = instantiateRepository(context, database, dataStore, profile)
            return DurableWriteViewModelFixture(database, repository, storeFile, dataStoreScope, profile)
        }

        private fun instantiateRepository(
            context: Context,
            database: HautDatabase,
            dataStore: DataStore<Preferences>,
            profile: AccountProfileEntity
        ): CampusRepository {
            val constructor = CampusRepository::class.java.declaredConstructors
                .single { it.parameterCount == 6 }
            constructor.isAccessible = true
            val repository = constructor.newInstance(context, database, dataStore, profile, null, null) as CampusRepository
            val sessionField = CampusRepository::class.java.getDeclaredField("activeSessionGeneration")
            sessionField.isAccessible = true
            sessionField.set(repository, 1L)
            return repository
        }
    }
}

private fun clearViewModelForNavigationRace(viewModel: ViewModel) {
    val method = ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(viewModel)
}


/** 端到端级 JVM 竞态：ViewModel dispatch 后立刻清 owner，手动新增课仍落 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimetableViewModelDurableWriteTest {

    @Test
    fun addManualCourseSurvivesImmediateViewModelClearRace() = runBlocking {
        val fixture = DurableWriteViewModelFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_tt_durable_" + System.nanoTime()
        )
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writeFinished = CompletableDeferred<Unit>()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val executor = TimetableWriteExecutor(
                writeScope = writeScope,
                runner = object : TimetableSessionWriteRunner {
                    override suspend fun <T : Any> withActiveSessionWrite(
                        profileId: String,
                        sessionGeneration: Long,
                        write: suspend () -> T
                    ): T? {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        return write()
                    }
                },
                afterSuccessfulWrite = { writeFinished.complete(Unit) }
            )
            val viewModel = TimetableViewModel(fixture.repository, executor)
            val termId = "2026-2027-1"

            viewModel.onAction(
                TimetableUiAction.AddManualCourse(
                    termId = termId,
                    name = "离场竞态新增课",
                    teacher = null,
                    rawRoom = "竞态教室",
                    weekday = DayOfWeek.MONDAY.value,
                    startPeriod = 1,
                    endPeriod = 2,
                    weeks = setOf(1),
                    colorArgb = 0xFF0072BC
                )
            )
            withTimeout(2_000) { writeStarted.await() }
            clearViewModelForNavigationRace(viewModel)
            releaseWrite.complete(Unit)

            withTimeout(2_000) { writeFinished.await() }
            val writtenCourse = fixture.dao.getCoursesSnapshot().firstOrNull { it.name == "离场竞态新增课" }
            assertTrue("ViewModel owner 已清理后，新增课程仍必须写入 Room", writtenCourse != null)
            assertTrue(
                fixture.dao.getRulesSnapshot().any { it.courseId == writtenCourse?.id && it.termId == termId }
            )
        } finally {
            releaseWrite.complete(Unit)
            writeScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun sameKindConcurrentCompletionsStayBoundToOwnOperationToken() = runBlocking {
        val fixture = DurableWriteViewModelFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_tt_token_" + System.nanoTime()
        )
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val starts = AtomicInteger(0)
        try {
            val executor = TimetableWriteExecutor(
                writeScope = writeScope,
                runner = object : TimetableSessionWriteRunner {
                    override suspend fun <T : Any> withActiveSessionWrite(
                        profileId: String,
                        sessionGeneration: Long,
                        write: suspend () -> T
                    ): T? {
                        val ordinal = starts.incrementAndGet()
                        if (ordinal == 1) {
                            firstStarted.complete(Unit)
                            firstGate.await()
                        } else {
                            secondStarted.complete(Unit)
                            secondGate.await()
                        }
                        return write()
                    }
                }
            )
            val viewModel = TimetableViewModel(fixture.repository, executor)
            val firstOperationKey = "operation-a"
            val secondOperationKey = "operation-b"

            viewModel.onAction(
                TimetableUiAction.AddManualCourse(
                    termId = "2026-2027-1",
                    name = "令牌并发课A",
                    teacher = null,
                    rawRoom = "A101",
                    weekday = DayOfWeek.MONDAY.value,
                    startPeriod = 1,
                    endPeriod = 2,
                    weeks = setOf(1),
                    colorArgb = 0xFF0072BC,
                    writeToken = firstOperationKey
                )
            )
            viewModel.onAction(
                TimetableUiAction.AddManualCourse(
                    termId = "2026-2027-1",
                    name = "令牌并发课B",
                    teacher = null,
                    rawRoom = "B101",
                    weekday = DayOfWeek.TUESDAY.value,
                    startPeriod = 3,
                    endPeriod = 4,
                    weeks = setOf(1),
                    colorArgb = 0xFF0072BC,
                    writeToken = secondOperationKey
                )
            )
            withTimeout(2_000) { firstStarted.await() }
            withTimeout(2_000) { secondStarted.await() }
            assertEquals(setOf(firstOperationKey, secondOperationKey), viewModel.uiState.value.pendingWrites.map { it.token }.toSet())

            secondGate.complete(Unit)
            withTimeout(2_000) {
                while (!viewModel.uiState.value.writeCompletions.containsKey(secondOperationKey)) yield()
            }
            assertFalse("同 kind 的第二次完成不得被第一次操作误消费", viewModel.uiState.value.writeCompletions.containsKey(firstOperationKey))
            val completionB = viewModel.uiState.value.writeCompletions.getValue(secondOperationKey)
            assertEquals(secondOperationKey, completionB.token)
            assertTrue(completionB.isSuccess)
            assertTrue(viewModel.uiState.value.pendingWrites.any { it.token == firstOperationKey })

            viewModel.onAction(TimetableUiAction.ConsumeWriteCompletion(secondOperationKey))
            assertFalse(viewModel.uiState.value.writeCompletions.containsKey(secondOperationKey))

            firstGate.complete(Unit)
            withTimeout(2_000) {
                while (!viewModel.uiState.value.writeCompletions.containsKey(firstOperationKey)) yield()
            }
            val completionA = viewModel.uiState.value.writeCompletions.getValue(firstOperationKey)
            assertEquals(firstOperationKey, completionA.token)
            assertTrue(completionA.isSuccess)
            assertTrue(viewModel.uiState.value.pendingWrites.isEmpty())
        } finally {
            firstGate.complete(Unit)
            secondGate.complete(Unit)
            writeScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun practicalMatrixActionWritesUiModelThroughDurableRepositoryBoundary() = runBlocking {
        val fixture = DurableWriteViewModelFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_tt_matrix_" + System.nanoTime()
        )
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val executor = TimetableWriteExecutor(
                writeScope = writeScope,
                runner = object : TimetableSessionWriteRunner {
                    override suspend fun <T : Any> withActiveSessionWrite(
                        profileId: String,
                        sessionGeneration: Long,
                        write: suspend () -> T
                    ): T? = write()
                }
            )
            val viewModel = TimetableViewModel(fixture.repository, executor)
            val matrixOperationKey = "matrix-operation"
            val entity = UnscheduledAcademicCourseEntity(
                termId = "2026-2027-1",
                sourceKey = "matrix-source-key",
                sourceId = "matrix-source",
                name = "矩阵实践课",
                teacher = "合成教师",
                creditPlain = "2.0",
                rawWeeks = "14-16周",
                category = "集中实践",
                syncedAtEpochMilli = 1L
            )
            fixture.dao.upsertUnscheduledCourses(listOf(entity))
            val uiModel = UnscheduledPracticalCourseUiModel(
                termId = entity.termId,
                sourceKey = entity.sourceKey,
                sourceId = entity.sourceId,
                name = entity.name,
                teacher = entity.teacher,
                creditPlain = entity.creditPlain,
                rawWeeks = entity.rawWeeks,
                category = entity.category,
                syncedAtEpochMilli = entity.syncedAtEpochMilli
            )

            viewModel.onAction(
                TimetableUiAction.SchedulePracticalCourseMatrix(
                    unscheduled = uiModel,
                    occurrences = listOf(
                        OccurrenceRule(
                            weekday = DayOfWeek.WEDNESDAY,
                            startPeriod = 7,
                            endPeriod = 8,
                            weeks = setOf(14),
                            rawRoom = "工程中心B",
                            customStartTime = LocalTime.of(13, 30),
                            customEndTime = LocalTime.of(16, 30)
                        )
                    ),
                    writeToken = matrixOperationKey
                )
            )
            withTimeout(2_000) {
                while (!viewModel.uiState.value.writeCompletions.containsKey(matrixOperationKey)) yield()
            }
            val completion = viewModel.uiState.value.writeCompletions.getValue(matrixOperationKey)
            assertEquals(TimetableWriteKind.SchedulePracticalCourseMatrix, completion.kind)
            assertTrue(completion.errorMessage, completion.isSuccess)
            assertTrue(fixture.dao.getUnscheduledCoursesSnapshot().isEmpty())
            val rule = fixture.dao.getRulesSnapshot().single()
            assertEquals(DayOfWeek.WEDNESDAY.value, rule.weekdayIso)
            assertEquals("13:30", rule.customStartTimeIso)
            assertEquals("16:30", rule.customEndTimeIso)
        } finally {
            writeScope.cancel()
            fixture.close()
        }
    }
}
