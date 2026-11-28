package cn.edu.haut.campus.feature.knowledge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.knowledge.KnowledgeRepository
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceUiEffect
import cn.edu.haut.campus.feature.knowledge.ui.CourseSpaceScreen as CourseSpaceUiScreen

internal class CourseSpaceViewModelFactory(
    private val repositoryFactory: () -> KnowledgeRepository,
    private val scope: SchoolAccountScope,
    private val courseId: String,
    private val courseName: String,
    private val termLabel: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CourseSpaceViewModel::class.java))
        return CourseSpaceViewModel(
            repositoryFactory = repositoryFactory,
            scope = scope,
            courseId = courseId,
            courseName = courseName,
            termLabel = termLabel
        ) as T
    }
}

/** 课程空间装配边界：只负责会话 ViewModel、一次性导航/平台 Effect 与 AutoCloseable 生命周期承接。 */
@Composable
fun CourseSpaceRoute(
    repositoryFactory: () -> KnowledgeRepository,
    scope: SchoolAccountScope,
    courseId: String,
    courseName: String,
    termLabel: String,
    sessionGeneration: Long?,
    onOpenMaterial: (String) -> Unit,
    onExportCoursePackage: (String) -> Unit,
    onRequestFilePermission: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val sessionKey = courseSpaceSessionKey(scope.profileId.value, sessionGeneration, courseId)
    val viewModelStoreOwner = rememberCourseSpaceViewModelStoreOwner(sessionKey)
    val factory = remember(repositoryFactory, scope, courseId, courseName, termLabel) {
        CourseSpaceViewModelFactory(
            repositoryFactory = repositoryFactory,
            scope = scope,
            courseId = courseId,
            courseName = courseName,
            termLabel = termLabel
        )
    }
    val viewModel: CourseSpaceViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = factory
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CourseSpaceUiEffect.OpenMaterial -> onOpenMaterial(effect.materialId)
                is CourseSpaceUiEffect.ExportCoursePackage -> onExportCoursePackage(effect.courseId)
                CourseSpaceUiEffect.RequestFilePermission -> onRequestFilePermission()
                CourseSpaceUiEffect.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }

    CourseSpaceUiScreen(state = state, onAction = viewModel::onAction)
}
