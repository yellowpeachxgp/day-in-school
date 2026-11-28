package cn.edu.haut.campus.feature.degree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.degree.DegreeRepository
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.feature.degree.ui.DegreeScreen
import java.time.LocalDate

internal class DegreeViewModelFactory(
    private val repositoryFactory: () -> DegreeRepository,
    private val gradeAttemptsProvider: suspend () -> List<GradeAttempt>,
    private val nowProvider: () -> LocalDate
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DegreeViewModel::class.java))
        return DegreeViewModel(repositoryFactory, gradeAttemptsProvider, nowProvider) as T
    }
}

/**
 * 毕业进度看板装配边界（W3-01 骨架）。
 *
 * 与 W3-04 一致：主导航接入不在本切片内，避免抢占 MainActivity/AppGraph 热点；
 * 集成方只需要注入当前 profile 的 DegreeRepository 工厂与成绩读取通道，
 * 并以 profileId+sessionGeneration 作为 viewModel key 保证账号切换即重建。
 */
@Composable
fun DegreeRoute(
    repositoryFactory: () -> DegreeRepository,
    gradeAttemptsProvider: suspend () -> List<GradeAttempt>,
    sessionKey: String,
    onBack: () -> Unit,
    nowProvider: () -> LocalDate = { LocalDate.now() }
) {
    val viewModel: DegreeViewModel = viewModel(
        key = "degree-" + sessionKey,
        factory = DegreeViewModelFactory(repositoryFactory, gradeAttemptsProvider, nowProvider)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DegreeScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack
    )
}
