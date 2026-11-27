package cn.edu.haut.campus.feature.settings

import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.root.home.SettingsHomeViewModel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Profile 切换防串扰回归：旧 generation 的 ViewModel 不得继续吸收旧库发射。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsProfileSessionGuardTest {

    @Test
    fun oldHomeViewModelStopsUpdatingAfterProfileSwitch() {
        // Robolectric 测试本体运行在 Android main thread；viewModelScope 也依赖该 Looper。
        // 因此把 runBlocking 放到 worker，main thread 在这里持续驱动 Looper，避免互相等待。
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker = Thread {
            try {
                runBlocking { verifyOldHomeViewModelStopsUpdatingAfterProfileSwitch() }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        worker.start()
        while (!done.await(20L, TimeUnit.MILLISECONDS)) {
            ShadowLooper.shadowMainLooper().idle()
        }
        ShadowLooper.shadowMainLooper().idle()
        worker.join(1_000L)
        failure.get()?.let { throw it }
    }

    private suspend fun verifyOldHomeViewModelStopsUpdatingAfterProfileSwitch() {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = AccountSessionCoordinator.getInstance(context)
        val firstSession = coordinator.initialize()
        val oldRepository = firstSession.repository
        val viewModel = SettingsHomeViewModel(oldRepository, coordinator.states)
        val beforeMarker = "切换前-" + UUID.randomUUID().toString().take(8)

        oldRepository.saveStudentProfile(
            StudentProfile(id = "stu_local", schoolId = "20230001", displayName = beforeMarker)
        )
        waitUntil("旧 ViewModel 未收到切换前资料") {
            viewModel.uiState.value.studentProfile?.displayName == beforeMarker
        }

        val secondProfile = coordinator.createOfflineProfile(
            SchoolDirectory.hautProfile(),
            "防串扰-" + UUID.randomUUID().toString().take(8)
        )
        val secondSession = coordinator.switchProfile(secondProfile.id)
        assertNotEquals(firstSession.profileId, secondSession.profileId)
        assertNotEquals(firstSession.generation, secondSession.generation)

        waitUntil("旧 ViewModel 未在切换后清空状态") {
            viewModel.uiState.value.studentProfile == null
        }

        val afterMarker = "切换后串扰-" + UUID.randomUUID().toString().take(8)
        oldRepository.saveStudentProfile(
            StudentProfile(id = "stu_local", schoolId = "20230001", displayName = afterMarker)
        )
        waitFor(900L)

        assertNull(viewModel.uiState.value.studentProfile)
    }

    private suspend fun waitUntil(message: String, condition: () -> Boolean) {
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            if (System.currentTimeMillis() >= deadlineMs) fail(message)
            delay(20L)
        }
    }

    private suspend fun waitFor(durationMs: Long) {
        val deadlineMs = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadlineMs) {
            delay(20L)
        }
    }
}
