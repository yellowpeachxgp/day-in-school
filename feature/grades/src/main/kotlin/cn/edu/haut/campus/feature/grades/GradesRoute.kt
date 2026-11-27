package cn.edu.haut.campus.feature.grades

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.grades.state.GradesUiEffect
import cn.edu.haut.campus.feature.grades.ui.GradesScreen as GradesUiScreen

private class GradesViewModelFactory(
    private val repository: CampusRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GradesViewModel::class.java))
        return GradesViewModel(repository) as T
    }
}

/** 学业页装配边界：只负责 ViewModel、导航回调与 Android 分享 Intent。 */
@Composable
fun GradesRoute(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToFocus: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    // 会话级 ViewModel：以 profileId+sessionGeneration 为 key，存放在组合作用域的
    // ViewModelStoreOwner 中。Profile 切换产生新 VM；组合销毁（含切走底栏、切换账号）
    // 即清空旧 VM，杜绝旧 VM 继续收集旧 Repository 数据的跨 Profile 串扰。
    val sessionKey = gradesSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberGradesViewModelStoreOwner(sessionKey)
    val factory = remember(repository) { GradesViewModelFactory(repository) }
    val viewModel: GradesViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = factory
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is GradesUiEffect.ShareGradesCsv -> {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "成绩明细.csv")
                        putExtra(Intent.EXTRA_TEXT, effect.csv)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "导出 CSV"))
                }
                GradesUiEffect.NavigateToImporter -> onNavigateToImporter()
                is GradesUiEffect.NavigateToMapWithRoom -> onNavigateToMapWithRoom(effect.room)
                GradesUiEffect.NavigateToLibrarySeats -> onNavigateToLibrarySeats()
                GradesUiEffect.NavigateToLibraryCatalog -> onNavigateToLibraryCatalog()
                GradesUiEffect.NavigateToFocus -> onNavigateToFocus()
                GradesUiEffect.NavigateToEmptyClassrooms -> onNavigateToEmptyClassrooms()
                GradesUiEffect.NavigateToCompetitions -> onNavigateToCompetitions()
            }
        }
    }

    GradesUiScreen(state = state, onAction = viewModel::onAction, resetTrigger = resetTrigger)
}
