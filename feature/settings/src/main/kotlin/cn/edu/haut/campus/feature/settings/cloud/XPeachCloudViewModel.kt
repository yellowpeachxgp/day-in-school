package cn.edu.haut.campus.feature.settings.cloud

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.feature.settings.cloud.state.XPeachCloudUiAction
import cn.edu.haut.campus.feature.settings.cloud.state.XPeachCloudUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 云账号页状态 holder：聚合云身份、预约计划与已安装扩展 Flow。 */
class XPeachCloudViewModel(
    private val repository: CampusRepository,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates), XPeachCloudUiAction {
    private val mutableUiState = MutableStateFlow(XPeachCloudUiState())
    val uiState: StateFlow<XPeachCloudUiState> = mutableUiState.asStateFlow()

    init {
        launchSessionCollect(repository.cloudIdentityBindingFlow) { value -> mutableUiState.update { it.copy(cloudBinding = value) } }
        launchSessionCollect(repository.libraryBookingPlansFlow) { value -> mutableUiState.update { it.copy(bookingPlans = value) } }
        launchSessionCollect(repository.installedExtensionPluginsFlow) { value -> mutableUiState.update { it.copy(plugins = value) } }
    }

    override val profileId: String get() = repository.profileId
    override suspend fun ensureDefaultReferencePluginsInstalled() = repository.ensureDefaultReferencePluginsInstalled()
    override suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> = repository.syncWithCampusCloud()
    override suspend fun refreshXpeachWalletBalance(): Double? = repository.refreshXpeachWalletBalance()
    override suspend fun saveVerifiedXpeachCloudAccount(profile: XpeachCloudAccountProfile): CloudIdentityBindingEntity =
        repository.saveVerifiedXpeachCloudAccount(profile)
    override suspend fun unlinkXpeachCloudAccountAndRevokeCloudPlans() =
        repository.unlinkXpeachCloudAccountAndRevokeCloudPlans()
    override suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult> =
        repository.reconfirmAndRestoreCloudBookingPlans()
    override suspend fun setExtensionPluginEnabled(pluginId: String, enabled: Boolean) =
        repository.setExtensionPluginEnabled(pluginId, enabled)
    override suspend fun removeExtensionPlugin(pluginId: String) = repository.removeExtensionPlugin(pluginId)

    override fun resetStateForInvalidSession() {
        mutableUiState.value = XPeachCloudUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { XPeachCloudViewModel(repository, sessionStates) }
        }
    }
}
