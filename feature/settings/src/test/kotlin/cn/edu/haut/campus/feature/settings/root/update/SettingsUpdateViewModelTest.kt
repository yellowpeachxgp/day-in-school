package cn.edu.haut.campus.feature.settings.root.update

import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.PresetMirrorNode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 版本更新自动检查回归：慢线路切换代理时必须取消旧检查并用新线路重查。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsUpdateViewModelTest {

    @Test
    fun slowRouteSwitchCancelsStaleCheckAndRetriesWithNewRoute() {
        // viewModelScope 使用 Android main dispatcher；worker 内执行协程断言，测试线程持续驱动主 Looper。
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker = Thread {
            try {
                runBlocking { verifySlowRouteSwitchCancelsStaleCheckAndRetries() }
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

    private suspend fun verifySlowRouteSwitchCancelsStaleCheckAndRetries() {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = AccountSessionCoordinator.getInstance(context)
        coordinator.initialize()
        val profile = coordinator.createOfflineProfile(
            SchoolDirectory.hautProfile(),
            "更新线路-" + UUID.randomUUID().toString().take(8)
        )
        val session = coordinator.switchProfile(profile.id)
        val repository = session.repository
        val firstConfig = repository.getNetworkProxyConfigSnapshot()
        val secondMirror = if (firstConfig.presetMirrorKey == PresetMirrorNode.GH_PROXY_COM.key) {
            PresetMirrorNode.GHPROXY_NET
        } else {
            PresetMirrorNode.GH_PROXY_COM
        }
        val secondConfig = firstConfig.copy(presetMirrorKey = secondMirror.key)
        val firstKey = autoCheckKey(firstConfig)
        val secondKey = autoCheckKey(secondConfig)

        val startedRoutes = CopyOnWriteArrayList<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondMayFinish = CompletableDeferred<Unit>()
        val releaseChecker: suspend (String, NetworkProxyConfig) -> Result<GitHubReleaseInfo> = { _, proxyConfig ->
            val routeKey = autoCheckKey(proxyConfig)
            startedRoutes += routeKey
            if (routeKey == firstKey) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            } else {
                secondStarted.complete(Unit)
                secondMayFinish.await()
                Result.success(releaseInfo(routeKey))
            }
        }
        val viewModel = SettingsUpdateViewModel(
            repository = repository,
            currentInstalledVersion = "0.20.22",
            apkDownloader = { _, _, _ -> error("本测试不下载 APK") },
            apkInstaller = { error("本测试不安装 APK") },
            releaseChecker = releaseChecker
        )

        waitUntil("首条慢线路检查未启动") { firstStarted.isCompleted }
        repository.updateNetworkProxyConfig(secondConfig)

        waitUntil("切换线路后未启动新检查") { secondStarted.isCompleted }
        waitUntil("旧线路慢检查未被取消") { firstCancelled.isCompleted }
        assertEquals(firstKey, startedRoutes.first())
        assertEquals(secondKey, startedRoutes.last())
        assertTrue("旧线路仍在挂起时必须发生一次显式取消", firstCancelled.isCompleted)

        secondMayFinish.complete(Unit)
        waitUntil("新线路检查结果未写回") {
            viewModel.uiState.value.releaseInfo?.resolvedViaRoute == secondKey &&
                !viewModel.uiState.value.isCheckingRelease
        }
        assertEquals(secondKey, viewModel.uiState.value.releaseInfo?.resolvedViaRoute)
        assertEquals("当前已是最新版本（v0.20.22）", viewModel.uiState.value.releaseStatusMessage)
        assertFalse(viewModel.uiState.value.isCheckingRelease)
    }

    private fun autoCheckKey(config: NetworkProxyConfig): String =
        config.mode.key + ":" + config.presetMirrorKey

    private fun releaseInfo(routeKey: String): GitHubReleaseInfo = GitHubReleaseInfo(
        tagName = "v0.20.22",
        versionNormalized = "0.20.22",
        releaseTitle = "测试版本",
        changelog = "",
        publishedAt = "2026-11-27T00:00:00Z",
        htmlUrl = "",
        apkDownloadUrl = null,
        apkFileName = "",
        apkSizeBytes = 0L,
        hasNewerVersion = false,
        currentVersionNormalized = "0.20.22",
        resolvedViaRoute = routeKey
    )

    private suspend fun waitUntil(message: String, condition: () -> Boolean) {
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            if (System.currentTimeMillis() >= deadlineMs) fail(message)
            delay(20L)
        }
    }
}
