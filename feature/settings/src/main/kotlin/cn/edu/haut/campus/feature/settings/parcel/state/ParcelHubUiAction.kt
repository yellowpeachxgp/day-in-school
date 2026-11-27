package cn.edu.haut.campus.feature.settings.parcel.state

import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiApiSimpleResult
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.database.ParcelItemEntity

/** 快递聚合页用户动作面。 */
interface ParcelHubUiAction {
    suspend fun ensureDefaultReferencePluginsInstalled()
    fun getXiaomiAccounts(): List<XiaomiAccountCredential>
    suspend fun markParcelPickedUp(parcelId: String)
    suspend fun deleteParcelById(parcelId: String)
    suspend fun markAllStationParcelsPickedUp(stationName: String)
    suspend fun upsertParcelItemFromInput(
        trackingNumberRaw: String,
        carrierName: String = "校园快递",
        providerId: String = "parcel.manual",
        pickupCodeInput: String? = null,
        stationNameInput: String? = null,
        itemTitleInput: String? = null,
        rawNotificationText: String? = null
    ): Result<ParcelItemEntity>
    suspend fun saveXiaomiAccountFromWebLogin(
        token: String,
        cUserId: String,
        accountId: String,
        detectedPhones: List<String>
    ): Result<XiaomiAccountCredential>
    suspend fun removeXiaomiAccount(accountIdOrId: String)
    suspend fun syncXiaomiExpressPackages(): Result<XiaomiSyncSummary>
    suspend fun sendXiaomiPhoneVerificationCode(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiApiSimpleResult>
    suspend fun verifyAndBindXiaomiPhone(
        accountIdOrId: String,
        phone: String,
        verificationCode: String
    ): Result<XiaomiAccountCredential>
    suspend fun addXiaomiPhoneDirectly(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential>
    suspend fun unbindXiaomiPhone(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential>
    suspend fun fetchXiaomiParcelDetail(parcelId: String): Result<List<XiaomiTrackPoint>>
}
