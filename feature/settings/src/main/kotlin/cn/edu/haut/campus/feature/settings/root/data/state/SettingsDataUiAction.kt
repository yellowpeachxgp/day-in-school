package cn.edu.haut.campus.feature.settings.root.data.state

/** 隐私与数据分区用户动作面；粒度与拆分前调用一致。 */
interface SettingsDataUiAction {
    suspend fun setCampusServiceFeatureFlag(
        utilitiesEnabled: Boolean? = null,
        libraryNativeEnabled: Boolean? = null,
        librarySeatEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        showTodayFocusQuickPill: Boolean? = null,
        timetableInterchangeEnabled: Boolean? = null
    )
    suspend fun setAutoSyncEnabled(enabled: Boolean)
    suspend fun setGpaPrivacyShield(enabled: Boolean)
    suspend fun resetOnboardingForGuide()
    suspend fun wipeAllPersonalData()
}
