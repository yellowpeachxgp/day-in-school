package cn.edu.haut.campus.feature.settings.dorm.state

import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult

/**
 * 宿舍水电用户动作面。W0-06c 纯搬移阶段保持原方法粒度，ViewModel 负责委托到领域 Repository；
 * UI 只看见动作接口，不再 import Repository。
 */
interface DormElectricityUiAction {
    fun readUtilityAccountResult(): UtilityAccountReadResult
    suspend fun updateUtilityNotificationConfig(
        enabled: Boolean? = null,
        dailyHour: Int? = null,
        lowBalanceThreshold: Float? = null
    )
    suspend fun sendTestUtilityNotification(nowEpochMs: Long = System.currentTimeMillis()): Boolean
    suspend fun clearUtilityAccountAndOnlineCache()
    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean)
    suspend fun saveAndRefreshOnlineUtilities(
        rawAccount: String? = null,
        allowUnverified: Boolean = false
    ): Result<List<OnlineUtilityRoomSnapshot>>
    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?)
    suspend fun deleteUtilityMeter(meterId: String)
    suspend fun deleteUtilityReading(readingId: String)
    suspend fun saveUtilityMeter(
        name: String,
        campusName: String,
        roomName: String,
        unit: String,
        lowThreshold: Double
    )
    suspend fun addUtilityReading(
        meterId: String,
        value: Double,
        topUpValue: Double = 0.0,
        note: String = ""
    )
}
