package cn.edu.haut.campus.feature.settings.dorm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.dorm.state.DormElectricityUiAction
import cn.edu.haut.campus.feature.settings.dorm.state.DormElectricityUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 宿舍水电状态 holder：聚合水电 Flow，并把拆分前 Repository 写动作委托在 ViewModel 内。 */
class DormElectricityViewModel(
    private val repository: CampusRepository,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates), DormElectricityUiAction {
    private val mutableUiState = MutableStateFlow(DormElectricityUiState())
    val uiState: StateFlow<DormElectricityUiState> = mutableUiState.asStateFlow()

    init {
        launchSessionCollect(repository.preferencesFlow) { value -> mutableUiState.update { it.copy(prefs = value) } }
        launchSessionCollect(repository.utilityOnlineRoomsFlow) { value -> mutableUiState.update { it.copy(onlineRooms = value) } }
        launchSessionCollect(repository.utilityDailySamplesFlow) { value -> mutableUiState.update { it.copy(dailySamples = value) } }
        launchSessionCollect(repository.utilityMetersFlow) { value -> mutableUiState.update { it.copy(meters = value) } }
        launchSessionCollect(repository.utilityReadingsFlow) { value -> mutableUiState.update { it.copy(readings = value) } }
    }

    override fun readUtilityAccountResult(): UtilityAccountReadResult = repository.readUtilityAccountResult()

    override suspend fun updateUtilityNotificationConfig(enabled: Boolean?, dailyHour: Int?, lowBalanceThreshold: Float?) =
        repository.updateUtilityNotificationConfig(enabled, dailyHour, lowBalanceThreshold)

    override suspend fun sendTestUtilityNotification(nowEpochMs: Long): Boolean =
        repository.sendTestUtilityNotification(nowEpochMs)

    override suspend fun clearUtilityAccountAndOnlineCache() = repository.clearUtilityAccountAndOnlineCache()

    override suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) =
        repository.setAllowUnverifiedUtilityTransport(allowed)

    override suspend fun saveAndRefreshOnlineUtilities(rawAccount: String?, allowUnverified: Boolean): Result<List<OnlineUtilityRoomSnapshot>> =
        repository.saveAndRefreshOnlineUtilities(rawAccount, allowUnverified)

    override suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?) =
        repository.updateOnlineRoomCampus(roomId, campusCode)

    override suspend fun deleteUtilityMeter(meterId: String) = repository.deleteUtilityMeter(meterId)

    override suspend fun deleteUtilityReading(readingId: String) = repository.deleteUtilityReading(readingId)

    override suspend fun saveUtilityMeter(name: String, campusName: String, roomName: String, unit: String, lowThreshold: Double) =
        repository.saveUtilityMeter(name, campusName, roomName, unit, lowThreshold)

    override suspend fun addUtilityReading(meterId: String, value: Double, topUpValue: Double, note: String) =
        repository.addUtilityReading(meterId, value, topUpValue, note)

    override fun resetStateForInvalidSession() {
        mutableUiState.value = DormElectricityUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DormElectricityViewModel(repository, sessionStates) }
        }
    }
}
