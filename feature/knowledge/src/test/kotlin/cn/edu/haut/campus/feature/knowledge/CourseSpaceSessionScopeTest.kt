package cn.edu.haut.campus.feature.knowledge

import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.knowledge.ArchiveMaterialRequest
import cn.edu.haut.campus.core.knowledge.CourseMaterial
import cn.edu.haut.campus.core.knowledge.KnowledgeExportSummary
import cn.edu.haut.campus.core.knowledge.KnowledgeRepository
import cn.edu.haut.campus.core.knowledge.KnowledgeSearchHit
import cn.edu.haut.campus.core.knowledge.ReviewTask
import cn.edu.haut.campus.core.knowledge.ReviewTaskState
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.io.File
import java.io.OutputStream
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

/** W3-04 审阅回归：课程空间必须随 profile/generation/course 建会话，组合退出即关闭独占 Repository。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CourseSpaceSessionScopeTest {
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_knowledge_ui"),
        profileId = ProfileId("prof_a")
    )

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
    fun sessionKeySeparatesProfileGenerationAndCourse() {
        val base = courseSpaceSessionKey("profile-a", 1L, "linear-algebra")
        assertEquals("profile-a:1:linear-algebra", base)
        assertNotEquals(base, courseSpaceSessionKey("profile-b", 1L, "linear-algebra"))
        assertNotEquals(base, courseSpaceSessionKey("profile-a", 2L, "linear-algebra"))
        assertNotEquals(base, courseSpaceSessionKey("profile-a", 1L, "advanced-algebra"))
        assertEquals("profile-a:0:linear-algebra", courseSpaceSessionKey("profile-a", null, "linear-algebra"))
    }

    @Test
    fun compositionExitClearsViewModelAndSessionSwitchCreatesNewInstance() {
        val harness = CourseSpaceCompositionHarness()
        val keyA = courseSpaceSessionKey("profile-a", 1L, "linear-algebra")
        var ownerA: CourseSpaceCompositionViewModelStoreOwner? = null
        var vmA: ProbeViewModel? = null
        harness.compose {
            val owner = rememberCourseSpaceViewModelStoreOwner(keyA)
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

        val keyB = courseSpaceSessionKey("profile-b", 2L, "linear-algebra")
        var ownerB: CourseSpaceCompositionViewModelStoreOwner? = null
        var vmB: ProbeViewModel? = null
        harness.compose {
            val owner = rememberCourseSpaceViewModelStoreOwner(keyB)
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
    fun compositionExitClosesExclusiveKnowledgeRepository() {
        val repository = CloseTrackingKnowledgeRepository()
        val harness = CourseSpaceCompositionHarness()
        val key = courseSpaceSessionKey(scope.profileId.value, 1L, "linear-algebra")
        val factory = CourseSpaceViewModelFactory(
            repositoryFactory = { repository },
            scope = scope,
            courseId = "linear-algebra",
            courseName = "线性代数",
            termLabel = "2025 秋"
        )
        var viewModel: CourseSpaceViewModel? = null
        harness.compose {
            val owner = rememberCourseSpaceViewModelStoreOwner(key)
            val vm: CourseSpaceViewModel = viewModel(viewModelStoreOwner = owner, key = key, factory = factory)
            SideEffect { viewModel = vm }
        }
        assertNotNull(viewModel)
        assertEquals(0, repository.closeCount)

        harness.dispose()
        assertEquals("课程空间组合销毁必须关闭本会话独占 Repository", 1, repository.closeCount)
    }

    @Test
    fun routeInstallsCompositionOwnerSessionKeyAndRepositoryClose() {
        val repoRoot = File("../..").canonicalFile
        val route = File(repoRoot, "feature/knowledge/src/main/kotlin/cn/edu/haut/campus/feature/knowledge/CourseSpaceRoute.kt").readText()
        val viewModel = File(repoRoot, "feature/knowledge/src/main/kotlin/cn/edu/haut/campus/feature/knowledge/CourseSpaceViewModel.kt").readText()

        assertTrue(route.contains("courseSpaceSessionKey(scope.profileId.value, sessionGeneration, courseId)"))
        assertTrue(route.contains("rememberCourseSpaceViewModelStoreOwner(sessionKey)"))
        assertTrue(route.contains("viewModelStoreOwner = viewModelStoreOwner"))
        assertTrue(route.contains("key = sessionKey"))
        assertTrue(route.contains("viewModel.effects.collect"))
        assertTrue(viewModel.contains("repository.close()"))
    }

    private class CloseTrackingKnowledgeRepository : KnowledgeRepository {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }

        override suspend fun archiveMaterial(request: ArchiveMaterialRequest, actor: Actor): CourseMaterial =
            throw UnsupportedOperationException("test repository does not archive")

        override suspend fun getMaterial(scope: SchoolAccountScope, materialId: String): CourseMaterial? = null

        override suspend fun listMaterials(scope: SchoolAccountScope, courseId: String?, teachingWeek: Int?): List<CourseMaterial> =
            emptyList()

        override suspend fun searchMaterials(
            scope: SchoolAccountScope,
            text: String,
            courseId: String?,
            teachingWeek: Int?,
            limit: Int
        ): List<KnowledgeSearchHit> = emptyList()

        override suspend fun setExtractionConfirmed(
            scope: SchoolAccountScope,
            materialId: String,
            confirmed: Boolean,
            actor: Actor
        ): CourseMaterial = throw UnsupportedOperationException("test repository has no materials")

        override suspend fun upsertReviewTask(task: ReviewTask, actor: Actor): ReviewTask = task

        override suspend fun listReviewTasks(scope: SchoolAccountScope, courseId: String?, state: ReviewTaskState?): List<ReviewTask> =
            emptyList()

        override suspend fun exportCoursePackage(
            scope: SchoolAccountScope,
            courseId: String,
            output: OutputStream,
            teachingWeeks: Set<Int>
        ): KnowledgeExportSummary = KnowledgeExportSummary("test", 0, 0, courseId)
    }
}
