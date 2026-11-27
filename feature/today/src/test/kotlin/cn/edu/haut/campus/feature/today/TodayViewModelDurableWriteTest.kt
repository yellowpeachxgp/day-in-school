package cn.edu.haut.campus.feature.today

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.room.Room
import cn.edu.haut.campus.core.database.AccountProfileEntity
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import cn.edu.haut.campus.core.reminder.AlarmScheduler
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.reminder.ScheduleOutcome
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
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
                .single { it.parameterCount == 5 }
            constructor.isAccessible = true
            val repository = constructor.newInstance(context, database, dataStore, profile, null) as CampusRepository
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


/** 端到端级 JVM 竞态：停课 dispatch 后立刻清 owner，覆盖行仍落 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayViewModelDurableWriteTest {

    private object NoopAlarmScheduler : AlarmScheduler {
        override fun schedule(task: ReminderTaskDomain, preferExact: Boolean): ScheduleOutcome =
            ScheduleOutcome.Failed("TEST_NOOP")

        override fun cancel(taskId: String, generation: Int) = Unit
    }

    @Test
    fun cancelClassOccurrenceSurvivesImmediateViewModelClearRace() = runBlocking {
        val fixture = DurableWriteViewModelFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_today_durable_" + System.nanoTime()
        )
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writeFinished = CompletableDeferred<Unit>()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val termId = "2026-2027-1"
            fixture.repository.addManualCourseWithRule(
                termId = termId,
                name = "待停课合成课",
                teacher = null,
                rawRoom = "合成教室",
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = setOf(1),
                colorArgb = 0xFF0072BC
            )
            val rule = fixture.dao.getRulesSnapshot().single { it.termId == termId }
            val course = fixture.dao.getCoursesSnapshot().single { it.id == rule.courseId }
            val occurrence = ClassOccurrence(
                occurrenceId = "occ_durable_cancel",
                courseId = course.id,
                ruleId = rule.id,
                courseName = course.name,
                colorArgb = course.colorArgb,
                date = LocalDate.of(2026, 10, 12),
                teachingWeek = 7,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(9, 40),
                rawRoom = "合成教室"
            )
            val detailState = TimelineOccurrenceState(
                occurrence = occurrence,
                status = ClassStatus.UPCOMING,
                topPx = 0f,
                heightPx = 1f,
                statusText = "未开始"
            )
            val executor = TodayWriteExecutor(
                writeScope = writeScope,
                runner = object : TodaySessionWriteRunner {
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
            val viewModel = TodayViewModel(
                repository = fixture.repository,
                reminderCoordinator = createReminderCoordinator(fixture),
                writeExecutor = executor
            )

            viewModel.onAction(TodayUiAction.CancelClassOccurrence(detailState, termId))
            withTimeout(2_000) { writeStarted.await() }
            clearViewModelForNavigationRace(viewModel)
            releaseWrite.complete(Unit)

            withTimeout(2_000) { writeFinished.await() }
            assertTrue(
                "ViewModel owner 已清理后，停课覆盖仍必须写入 Room",
                fixture.dao.getOccurrenceOverridesSnapshot().any {
                    it.courseRuleId == rule.id && it.dateIso == occurrence.date.toString() && it.canceled
                }
            )
        } finally {
            releaseWrite.complete(Unit)
            writeScope.cancel()
            fixture.close()
        }
    }

    private fun createReminderCoordinator(fixture: DurableWriteViewModelFixture): ReminderCoordinator {
        val constructor = ReminderCoordinator::class.java.declaredConstructors
            .single { it.parameterCount == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(
            RuntimeEnvironment.getApplication(),
            fixture.repository,
            NoopAlarmScheduler,
            AccountBackgroundScope(fixture.profile.id, 1L)
        ) as ReminderCoordinator
    }

    @Test
    fun sameKindConcurrentCompletionsStayBoundToOwnOperationToken() = runBlocking {
        val fixture = DurableWriteViewModelFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_today_token_" + System.nanoTime()
        )
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val starts = AtomicInteger(0)
        try {
            val executor = TodayWriteExecutor(
                writeScope = writeScope,
                runner = object : TodaySessionWriteRunner {
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
            val viewModel = TodayViewModel(
                repository = fixture.repository,
                reminderCoordinator = createReminderCoordinator(fixture),
                writeExecutor = executor
            )

            viewModel.onAction(TodayUiAction.SelectCurrentTerm("term-token-a", writeToken = "token-a"))
            viewModel.onAction(TodayUiAction.SelectCurrentTerm("term-token-b", writeToken = "token-b"))
            withTimeout(2_000) { firstStarted.await() }
            withTimeout(2_000) { secondStarted.await() }
            assertEquals(setOf("token-a", "token-b"), viewModel.uiState.value.pendingWrites.map { it.token }.toSet())

            secondGate.complete(Unit)
            withTimeout(2_000) {
                while (!viewModel.uiState.value.writeCompletions.containsKey("token-b")) yield()
            }
            assertFalse("同 kind 的第二次完成不得被第一次操作误消费", viewModel.uiState.value.writeCompletions.containsKey("token-a"))
            val completionB = viewModel.uiState.value.writeCompletions.getValue("token-b")
            assertEquals("token-b", completionB.token)
            assertTrue(completionB.isSuccess)
            assertTrue(viewModel.uiState.value.pendingWrites.any { it.token == "token-a" })

            viewModel.onAction(TodayUiAction.ConsumeWriteCompletion("token-b"))
            assertFalse(viewModel.uiState.value.writeCompletions.containsKey("token-b"))

            firstGate.complete(Unit)
            withTimeout(2_000) {
                while (!viewModel.uiState.value.writeCompletions.containsKey("token-a")) yield()
            }
            val completionA = viewModel.uiState.value.writeCompletions.getValue("token-a")
            assertEquals("token-a", completionA.token)
            assertTrue(completionA.isSuccess)
            assertTrue(viewModel.uiState.value.pendingWrites.isEmpty())
        } finally {
            firstGate.complete(Unit)
            secondGate.complete(Unit)
            writeScope.cancel()
            fixture.close()
        }
    }
}
