package cn.edu.haut.campus.feature.settings.hub.state

import cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog

/** 校园社区目录用户动作面。 */
interface CampusCommunityUiAction {
    suspend fun refreshCampusCommunityContent(): Result<CampusCommunityCloudCatalog>
    suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String)
    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String)
}
