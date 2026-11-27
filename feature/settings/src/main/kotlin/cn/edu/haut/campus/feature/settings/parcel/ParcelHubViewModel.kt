package cn.edu.haut.campus.feature.settings.parcel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiApiSimpleResult
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.feature.settings.parcel.state.ParcelHubUiAction
import cn.edu.haut.campus.feature.settings.parcel.state.ParcelHubUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 快递聚合页状态 holder。 */
class ParcelHubViewModel(
    private val repository: CampusRepository,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates), ParcelHubUiAction {
    private val mutableUiState = MutableStateFlow(ParcelHubUiState())
    val uiState: StateFlow<ParcelHubUiState> = mutableUiState.asStateFlow()

    init {
        launchSessionCollect(repository.parcelItemsFlow) { value -> mutableUiState.update { it.copy(parcels = value) } }
        launchSessionCollect(repository.installedExtensionPluginsFlow) { value -> mutableUiState.update { it.copy(plugins = value) } }
    }

    override suspend fun ensureDefaultReferencePluginsInstalled() = repository.ensureDefaultReferencePluginsInstalled()
    override fun getXiaomiAccounts(): List<XiaomiAccountCredential> = repository.getXiaomiAccounts()
    override suspend fun markParcelPickedUp(parcelId: String) = repository.markParcelPickedUp(parcelId)
    override suspend fun deleteParcelById(parcelId: String) = repository.deleteParcelById(parcelId)
    override suspend fun markAllStationParcelsPickedUp(stationName: String) = repository.markAllStationParcelsPickedUp(stationName)
    override suspend fun upsertParcelItemFromInput(
        trackingNumberRaw: String,
        carrierName: String,
        providerId: String,
        pickupCodeInput: String?,
        stationNameInput: String?,
        itemTitleInput: String?,
        rawNotificationText: String?
    ): Result<ParcelItemEntity> = repository.upsertParcelItemFromInput(
        trackingNumberRaw, carrierName, providerId, pickupCodeInput, stationNameInput, itemTitleInput, rawNotificationText
    )
    override suspend fun saveXiaomiAccountFromWebLogin(
        token: String,
        cUserId: String,
        accountId: String,
        detectedPhones: List<String>
    ): Result<XiaomiAccountCredential> = repository.saveXiaomiAccountFromWebLogin(token, cUserId, accountId, detectedPhones)
    override suspend fun removeXiaomiAccount(accountIdOrId: String) = repository.removeXiaomiAccount(accountIdOrId)
    override suspend fun syncXiaomiExpressPackages(): Result<XiaomiSyncSummary> = repository.syncXiaomiExpressPackages()
    override suspend fun sendXiaomiPhoneVerificationCode(accountIdOrId: String, phone: String): Result<XiaomiApiSimpleResult> =
        repository.sendXiaomiPhoneVerificationCode(accountIdOrId, phone)
    override suspend fun verifyAndBindXiaomiPhone(accountIdOrId: String, phone: String, verificationCode: String): Result<XiaomiAccountCredential> =
        repository.verifyAndBindXiaomiPhone(accountIdOrId, phone, verificationCode)
    override suspend fun addXiaomiPhoneDirectly(accountIdOrId: String, phone: String): Result<XiaomiAccountCredential> =
        repository.addXiaomiPhoneDirectly(accountIdOrId, phone)
    override suspend fun unbindXiaomiPhone(accountIdOrId: String, phone: String): Result<XiaomiAccountCredential> =
        repository.unbindXiaomiPhone(accountIdOrId, phone)
    override suspend fun fetchXiaomiParcelDetail(parcelId: String): Result<List<XiaomiTrackPoint>> =
        repository.fetchXiaomiParcelDetail(parcelId)

    override fun resetStateForInvalidSession() {
        mutableUiState.value = ParcelHubUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ParcelHubViewModel(repository, sessionStates) }
        }
    }
}
