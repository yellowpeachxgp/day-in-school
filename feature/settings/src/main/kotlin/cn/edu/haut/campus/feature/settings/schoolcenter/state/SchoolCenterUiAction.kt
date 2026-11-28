package cn.edu.haut.campus.feature.settings.schoolcenter.state

import cn.edu.haut.campus.core.model.SchoolProfile

/** 学校中心用户意图。 */
sealed interface SchoolCenterUiAction {
    data class UpdateQuery(val value: String) : SchoolCenterUiAction
    data class SelectSchool(val schoolId: String?) : SchoolCenterUiAction
    data class SetShowContribution(val visible: Boolean) : SchoolCenterUiAction
    data class SetShowCreateProfile(val visible: Boolean) : SchoolCenterUiAction
    data class CreateOfflineProfile(val school: SchoolProfile, val label: String) : SchoolCenterUiAction
    data object CopyContributionTemplate : SchoolCenterUiAction
    data class CopyContributionFeedback(val succeeded: Boolean) : SchoolCenterUiAction
}
