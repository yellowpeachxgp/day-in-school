package cn.edu.haut.campus.feature.settings.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog
import cn.edu.haut.campus.feature.settings.hub.state.CampusCommunityUiAction
import cn.edu.haut.campus.feature.settings.hub.state.CampusCommunityUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 校园社区页状态 holder。 */
class CampusCommunityViewModel(
    private val repository: CampusRepository
) : ViewModel(), CampusCommunityUiAction {
    private val mutableUiState = MutableStateFlow(CampusCommunityUiState())
    val uiState: StateFlow<CampusCommunityUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { repository.preferencesFlow.collectLatest { value -> mutableUiState.update { it.copy(prefs = value) } } }
        viewModelScope.launch { repository.studentProfileFlow.collectLatest { value -> mutableUiState.update { it.copy(studentProfile = value) } } }
    }

    override suspend fun refreshCampusCommunityContent(): Result<CampusCommunityCloudCatalog> =
        repository.refreshCampusCommunityContent()
    override suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String) =
        repository.saveCustomClubGroupNumber(clubId, groupNumber)
    override suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) =
        repository.saveCustomChannelUrl(channelId, inviteUrl)

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CampusCommunityViewModel(repository) }
        }
    }
}
