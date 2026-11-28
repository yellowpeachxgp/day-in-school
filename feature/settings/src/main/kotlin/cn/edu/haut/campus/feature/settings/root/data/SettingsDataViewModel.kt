package cn.edu.haut.campus.feature.settings.root.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.data.state.SettingsDataUiAction
import cn.edu.haut.campus.feature.settings.root.data.state.SettingsDataUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 隐私与数据分区状态 holder。 */
class SettingsDataViewModel(
    private val repository: CampusRepository
) : ViewModel(), SettingsDataUiAction {
    private val mutableUiState = MutableStateFlow(SettingsDataUiState())
    val uiState: StateFlow<SettingsDataUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                mutableUiState.update { it.copy(prefs = prefs) }
            }
        }
    }

    override suspend fun setCampusServiceFeatureFlag(
        utilitiesEnabled: Boolean?,
        libraryNativeEnabled: Boolean?,
        librarySeatEnabled: Boolean?,
        focusEnabled: Boolean?,
        showTodayFocusQuickPill: Boolean?,
        timetableInterchangeEnabled: Boolean?
    ) = repository.setCampusServiceFeatureFlag(
        utilitiesEnabled,
        libraryNativeEnabled,
        librarySeatEnabled,
        focusEnabled,
        showTodayFocusQuickPill,
        timetableInterchangeEnabled
    )
    override suspend fun setAutoSyncEnabled(enabled: Boolean) = repository.setAutoSyncEnabled(enabled)
    override suspend fun setGpaPrivacyShield(enabled: Boolean) = repository.setGpaPrivacyShield(enabled)
    override suspend fun resetOnboardingForGuide() = repository.resetOnboardingForGuide()
    override suspend fun wipeAllPersonalData() = repository.wipeAllPersonalData()

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsDataViewModel(repository) }
        }
    }
}
