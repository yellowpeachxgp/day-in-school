package cn.edu.haut.campus.feature.settings.state

import cn.edu.haut.campus.core.campusservices.LibrarySearchField
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiState
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiState
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiState
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiState
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiState

/**
 * W0-06c 审计修复：把拆分前 remember / rememberSaveable 的“离开即重置”
 * 生命周期显式化。仓库数据与远端缓存保留；这里只重置纯界面瞬态。
 */
internal fun StudyFocusUiState.resetTransientForExit(
    diagnosticSummary: String,
    nowMs: Long
): StudyFocusUiState = copy(
    nowMs = nowMs,
    customMinutesInput = prefs.selectedFocusMinutes.toString(),
    statusBanner = null,
    diagnosticSummary = diagnosticSummary
)

internal fun LibraryCatalogUiState.resetTransientForExit(): LibraryCatalogUiState = copy(
    keywordInput = "",
    selectedField = LibrarySearchField.ALL,
    showOfficialWebMode = false,
    hasSearched = false,
    activeQuery = "",
    isSearching = false,
    isLoadingMore = false,
    searchError = null,
    currentPage = 1,
    totalCount = 0,
    hasMorePages = false,
    resultBooks = emptyList(),
    selectedBook = null,
    activeBookDetail = null,
    isDetailLoading = false
)

internal fun SchoolCenterUiState.resetTransientForExit(): SchoolCenterUiState = copy(
    query = "",
    selectedSchoolId = null,
    showContribution = false,
    showCreateProfile = false,
    creatingProfile = false,
    profileFeedback = null,
    copyFeedback = null
)

internal fun SettingsHomeUiState.resetTransientForExit(): SettingsHomeUiState = copy(
    isDynamicSyncing = false,
    showEditProfileDialog = false
)

internal fun SettingsUpdateUiState.resetTransientForExit(): SettingsUpdateUiState = copy(
    isCheckingRelease = false,
    releaseInfo = null,
    isDownloadingApk = false,
    apkDownloadProgress = 0f,
    releaseStatusMessage = null,
    downloadedApkFile = null,
    showReleaseNotesDialog = false
)

/**
 * 根设置页内部有“我的 / 系统设置”二级切换。拆分前相关 remember 挂在根 SettingsScreen，
 * 因此只在根页随底栏离开时才重置，而不是在二级分区切换时提前重置。
 */
class SettingsTransientResetRegistry {
    private val callbacks = mutableMapOf<Any, () -> Unit>()

    @Synchronized
    fun register(owner: Any, reset: () -> Unit) {
        callbacks[owner] = reset
    }

    @Synchronized
    fun resetAll() {
        callbacks.values.toList().forEach { it.invoke() }
    }
}
