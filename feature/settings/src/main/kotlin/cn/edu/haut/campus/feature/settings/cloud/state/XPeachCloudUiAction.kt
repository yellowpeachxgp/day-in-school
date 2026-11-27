package cn.edu.haut.campus.feature.settings.cloud.state

import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity

/** 云账号与扩展页用户动作面；不包含任何手填安装入口。 */
interface XPeachCloudUiAction {
    val profileId: String
    suspend fun ensureDefaultReferencePluginsInstalled()
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult>
    suspend fun refreshXpeachWalletBalance(): Double?
    suspend fun saveVerifiedXpeachCloudAccount(profile: XpeachCloudAccountProfile): CloudIdentityBindingEntity
    suspend fun unlinkXpeachCloudAccountAndRevokeCloudPlans()
    suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult>
    suspend fun setExtensionPluginEnabled(pluginId: String, enabled: Boolean)
    suspend fun removeExtensionPlugin(pluginId: String)
}
