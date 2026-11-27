package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 校园频道、社团群号与公共校园内容缓存服务；网络地址仍经 facade preferencesFlow 读取。 */
internal class CampusContentService(
    private val environment: CampusRepositoryEnvironment,
    private val runtimePort: CampusServicesRuntimePort
) {
    private val preferences = environment.preferences
    private val schoolId: String get() = environment.accountProfile.schoolId
    private val preferencesFlow get() = runtimePort.preferencesFlow

    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) {
        preferences.edit { prefs ->
            val current = decodeKeyValueMap(prefs[CampusRepositoryPrefKeys.CUSTOM_CHANNEL_URLS]).toMutableMap()
            if (inviteUrl.isBlank()) {
                current.remove(channelId)
            } else {
                current[channelId] = inviteUrl.trim()
            }
            prefs[CampusRepositoryPrefKeys.CUSTOM_CHANNEL_URLS] = encodeKeyValueMap(current)
        }
    }

    suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String) {
        preferences.edit { prefs ->
            val current = decodeKeyValueMap(prefs[CampusRepositoryPrefKeys.CUSTOM_CLUB_GROUPS]).toMutableMap()
            if (groupNumber.isBlank()) {
                current.remove(clubId)
            } else {
                current[clubId] = groupNumber.trim()
            }
            prefs[CampusRepositoryPrefKeys.CUSTOM_CLUB_GROUPS] = encodeKeyValueMap(current)
        }
    }

    /**
     * 从云端控制平面 (`GET /api/v1/campus-content?school_id=haut`) 实时拉取最新的校园频道与学生社团名录，
     * 并持久化到本地 DataStore 供全端响应式渲染。
     */
    suspend fun refreshCampusCommunityContent(): Result<cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                )
                val (catalog, rawJson) = CampusCloudOidcHelper.fetchPublicCampusContent(
                    schoolId = schoolId,
                    cloudBaseUrl = baseUrl
                ) ?: throw IllegalStateException("无法连接云端获取最新校园频道与社团内容")
                val now = System.currentTimeMillis()
                preferences.edit { prefs ->
                    prefs.remove(CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_JSON_LEGACY_V1)
                    prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_JSON] = rawJson
                    prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS] = now
                }
                catalog
            }
        }

}
