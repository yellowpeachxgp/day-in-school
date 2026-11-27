package cn.edu.haut.campus.feature.settings.state

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

/**
 * W0-06c 审计回归：底栏/页面离开或 Profile generation 变化时，
 * 会话型设置 ViewModel 必须随组合作用域立即清空，不得继续收集旧账号数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsSessionViewModelStoreOwnerTest {

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
    fun sessionKeySeparatesProfilesGenerationsAndInactiveSession() {
        val base = settingsSessionKey("profile-a", 1L)
        assertEquals("profile-a:1", base)
        assertNotEquals(base, settingsSessionKey("profile-b", 1L))
        assertNotEquals(base, settingsSessionKey("profile-a", 2L))
        assertNotEquals(base, settingsSessionKey("profile-a", null))
        assertEquals("profile-a:0", settingsSessionKey("profile-a", null))
    }

    @Test
    fun compositionExitClearsViewModelAndSessionSwitchCreatesNewInstance() {
        val harness = SettingsCompositionHarness()
        val keyA = "settings-probe-" + settingsSessionKey("profile-a", 1L)
        var ownerA: SettingsSessionViewModelStoreOwner? = null
        var vmA: ProbeViewModel? = null
        harness.compose {
            val owner = rememberSettingsSessionViewModelStoreOwner(keyA)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyA, factory = probeFactory)
            SideEffect {
                ownerA = owner
                vmA = vm
            }
        }
        assertNotNull(vmA)
        assertFalse(vmA!!.cleared)

        // 底栏切换/覆盖页退出都会销毁对应组合；旧 VM 必须立即停止并清除。
        harness.dispose()
        assertTrue("组合销毁后旧 ViewModel 必须立即清除", vmA!!.cleared)

        val keyB = "settings-probe-" + settingsSessionKey("profile-b", 2L)
        var ownerB: SettingsSessionViewModelStoreOwner? = null
        var vmB: ProbeViewModel? = null
        harness.compose {
            val owner = rememberSettingsSessionViewModelStoreOwner(keyB)
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
}
