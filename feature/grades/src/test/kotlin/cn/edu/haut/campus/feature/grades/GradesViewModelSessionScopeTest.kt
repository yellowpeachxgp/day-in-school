package cn.edu.haut.campus.feature.grades

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

/**
 * W0-06a 审阅阻断回归：Profile 切换不得串数据。
 * 学业页 ViewModel 以 profileId+sessionGeneration 为 key，存放在组合作用域的
 * ViewModelStoreOwner 中；组合销毁（Profile 切换/底栏切换）必须及时清除旧 VM。
 */
class GradesViewModelSessionScopeTest {

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
        val base = gradesSessionKey("profile-a", 1L)
        assertEquals("profile-a:1", base)
        assertNotEquals(base, gradesSessionKey("profile-b", 1L))
        assertNotEquals(base, gradesSessionKey("profile-a", 2L))
        assertEquals("profile-a:0", gradesSessionKey("profile-a", null))
    }

    @Test
    fun profileSwitchProducesNewViewModelAndClearsOld() {
        val harness = GradesCompositionHarness()
        val keyA = gradesSessionKey("profile-a", 1L)
        var ownerA: GradesCompositionViewModelStoreOwner? = null
        var vmA: ProbeViewModel? = null
        harness.compose {
            val owner = rememberGradesViewModelStoreOwner(keyA)
            val vm: ProbeViewModel = viewModel(viewModelStoreOwner = owner, key = keyA, factory = probeFactory)
            SideEffect {
                ownerA = owner
                vmA = vm
            }
        }
        assertNotNull(vmA)
        assertFalse(vmA!!.cleared)

        // Profile 切换：MainActivity 以 key(profileId, generation) 销毁整棵组合子树
        harness.dispose()
        assertTrue("组合销毁后旧 ViewModel 必须立即清除，不得继续收集旧账号数据", vmA!!.cleared)

        val keyB = gradesSessionKey("profile-b", 2L)
        var ownerB: GradesCompositionViewModelStoreOwner? = null
        var vmB: ProbeViewModel? = null
        harness.compose {
            val owner = rememberGradesViewModelStoreOwner(keyB)
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
