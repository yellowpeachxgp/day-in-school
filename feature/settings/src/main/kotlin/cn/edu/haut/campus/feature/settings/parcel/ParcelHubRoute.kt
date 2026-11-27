package cn.edu.haut.campus.feature.settings.parcel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.extension.ExtensionPluginSlot
import cn.edu.haut.campus.feature.settings.parcel.ui.ParcelHubScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 快递聚合页 Route。 */
@Composable
fun ParcelHubRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val sessionKey = "parcel-hub-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: ParcelHubViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = ParcelHubViewModel.factory(repository, sessionStates)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val parcelCardsContent: @Composable () -> Unit = {
        ExtensionPluginSlot(repository = repository, slotId = "parcel.cards")
    }
    val parcelProvidersContent: @Composable () -> Unit = {
        ExtensionPluginSlot(repository = repository, slotId = "parcel.providers")
    }
    ParcelHubScreenContent(
        state = state,
        actions = viewModel,
        onBack = onBack,
        parcelCardsContent = parcelCardsContent,
        parcelProvidersContent = parcelProvidersContent
    )
}
