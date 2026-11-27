package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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

/** W0-06 审阅阻断回归：Profile 切换不得串数据；组合销毁必须清理旧 VM。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayViewModelSessionScopeTest {

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
    fun sessionKeySeparatesProfilesAndGenerations() {
        val base = todaySessionKey("profile-a", 1L)
        assertEquals("profile-a:1", base)
        assertNotEquals(base, todaySessionKey("profile-b", 1L))
        assertNotEquals(base, todaySessionKey("profile-a", 2L))
        assertEquals("profile-a:0", todaySessionKey("profile-a", null))
    }

    @Test
    fun profileSwitchProducesNewViewModelAndClearsOld() {
        val harness = TodayCompositionHarness()
        val keyA = todaySessionKey("profile-a", 1L)
        var ownerA: TodayCompositionViewModelStoreOwner? = null
        var vmA: ProbeViewModel? = null
        harness.compose {
            val owner = rememberTodayViewModelStoreOwner(keyA)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyA, factory = probeFactory)
            SideEffect {
                ownerA = owner
                vmA = vm
            }
        }
        assertNotNull(vmA)
        assertFalse(vmA!!.cleared)

        harness.dispose()
        assertTrue("组合销毁后旧 ViewModel 必须立即清除，不得继续收集旧账号数据", vmA!!.cleared)

        val keyB = todaySessionKey("profile-b", 2L)
        var ownerB: TodayCompositionViewModelStoreOwner? = null
        var vmB: ProbeViewModel? = null
        harness.compose {
            val owner = rememberTodayViewModelStoreOwner(keyB)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyB, factory = probeFactory)
            SideEffect {
                ownerB = owner
                vmB = vm
            }
        }
        assertNotNull(vmB)
        assertNotSame("Profile 切换后必须是新的 ViewModelStoreOwner", ownerA, ownerB)
        assertNotSame("Profile 切换后必须是新的 ViewModel，不得复用旧实例", vmA, vmB)
        assertFalse(vmB!!.cleared)

        harness.dispose()
        assertTrue(vmB!!.cleared)
    }
}
