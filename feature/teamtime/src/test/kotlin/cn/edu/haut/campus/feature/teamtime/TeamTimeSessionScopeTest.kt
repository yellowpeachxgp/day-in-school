package cn.edu.haut.campus.feature.teamtime

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W4-07 组合 owner 回归：profile/generation 改变即换 VM，离开组合立即清理在途工作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TeamTimeSessionScopeTest {
    private class ProbeViewModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private val probeFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProbeViewModel() as T
    }

    @Test
    fun sessionKeySeparatesProfileAndGeneration() {
        val base = teamTimeSessionKey("profile-a", 1L)
        assertEquals("profile-a:1", base)
        assertNotEquals(base, teamTimeSessionKey("profile-b", 1L))
        assertNotEquals(base, teamTimeSessionKey("profile-a", 2L))
        assertEquals("profile-a:0", teamTimeSessionKey("profile-a", null))
    }

    @Test
    fun compositionExitClearsViewModelAndSessionSwitchCreatesNewInstance() {
        val harness = TeamTimeCompositionHarness()
        val keyA = teamTimeSessionKey("profile-a", 1L)
        var ownerA: TeamTimeCompositionViewModelStoreOwner? = null
        var vmA: ProbeViewModel? = null
        harness.compose {
            val owner = rememberTeamTimeViewModelStoreOwner(keyA)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyA, factory = probeFactory)
            SideEffect {
                ownerA = owner
                vmA = vm
            }
        }
        assertNotNull(vmA)
        assertFalse(vmA!!.cleared)

        harness.dispose()
        assertTrue("组合销毁后旧 ViewModel 必须立即清除", vmA!!.cleared)

        val keyB = teamTimeSessionKey("profile-b", 2L)
        var ownerB: TeamTimeCompositionViewModelStoreOwner? = null
        var vmB: ProbeViewModel? = null
        harness.compose {
            val owner = rememberTeamTimeViewModelStoreOwner(keyB)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyB, factory = probeFactory)
            SideEffect {
                ownerB = owner
                vmB = vm
            }
        }
        assertNotNull(vmB)
        assertNotSame("Profile 切换后必须是新的 ViewModelStoreOwner", ownerA, ownerB)
        assertNotSame("Profile 切换后必须是新的 ViewModel", vmA, vmB)
        assertFalse(vmB!!.cleared)

        harness.dispose()
        assertTrue(vmB!!.cleared)
    }

    @Test
    fun routeInstallsCompositionOwnerSessionKeyAndEffects() {
        val route = java.io.File("../..").canonicalFile.resolve(
            "feature/teamtime/src/main/kotlin/cn/edu/haut/campus/feature/teamtime/TeamTimeRoute.kt",
        ).readText()

        assertTrue(route.contains("teamTimeSessionKey(activeSession.profileId, activeSession.generation)"))
        assertTrue(route.contains("rememberTeamTimeViewModelStoreOwner(sessionKey)"))
        assertTrue(route.contains("viewModelStoreOwner = viewModelStoreOwner"))
        assertTrue(route.contains("key = sessionKey"))
        assertTrue(route.contains("viewModel.effects.collect"))
    }
}

/** 纯 JVM 组合测试基座：同步驱动初始组合与 dispose。 */
private class TeamTimeCompositionHarness {
    private object UnitApplier : Applier<Unit> {
        override val current: Unit = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
        override fun onEndChanges() {}
    }

    private val recomposer = Recomposer(EmptyCoroutineContext)
    private var composition: Composition? = null

    fun compose(content: @Composable () -> Unit) {
        dispose()
        val next = Composition(UnitApplier, recomposer)
        composition = next
        next.setContent(content)
    }

    fun dispose() {
        composition?.dispose()
        composition = null
    }
}
