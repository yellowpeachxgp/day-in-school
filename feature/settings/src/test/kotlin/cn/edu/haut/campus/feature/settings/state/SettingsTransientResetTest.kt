package cn.edu.haut.campus.feature.settings.state

import cn.edu.haut.campus.core.campusservices.CatalogBook
import cn.edu.haut.campus.core.campusservices.CatalogBookDetail
import cn.edu.haut.campus.core.campusservices.LibrarySearchField
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiState
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiState
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiState
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiState
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** W0-06c 审计回归：底栏/页面退出后，拆分前 remember 级瞬态必须回到初始值。 */
class SettingsTransientResetTest {

    @Test
    fun studyFocusExitRestoresInputBannerAndDiagnostics() {
        val prefs = AppPreferencesState(selectedFocusMinutes = 45)
        val dirty = StudyFocusUiState(
            prefs = prefs,
            nowMs = 1L,
            customMinutesInput = "137",
            statusBanner = "已开启测试",
            diagnosticSummary = "旧诊断"
        )

        val reset = dirty.resetTransientForExit(diagnosticSummary = "新诊断", nowMs = 99L)

        assertEquals("45", reset.customMinutesInput)
        assertNull(reset.statusBanner)
        assertEquals("新诊断", reset.diagnosticSummary)
        assertEquals(99L, reset.nowMs)
        assertSame(prefs, reset.prefs)
    }

    @Test
    fun libraryCatalogExitRestoresDraftSearchResultsAndDetail() {
        val book = CatalogBook(
            index = 1,
            title = "测试书",
            bibliography = "测试书目",
            summary = "摘要",
            resources = "纸本",
            coverUrl = ""
        )
        val detail = CatalogBookDetail(
            title = "测试书",
            coverUrl = "",
            bibliography = "测试书目",
            description = "详情",
            holdings = emptyList()
        )
        val dirty = LibraryCatalogUiState(
            keywordInput = "kotlin",
            selectedField = LibrarySearchField.TITLE,
            showOfficialWebMode = true,
            hotKeywords = listOf("公共热搜"),
            hasSearched = true,
            activeQuery = "kotlin",
            isSearching = true,
            isLoadingMore = true,
            searchError = "错误",
            currentPage = 3,
            totalCount = 42,
            hasMorePages = true,
            resultBooks = listOf(book),
            selectedBook = book,
            activeBookDetail = detail,
            isDetailLoading = true
        )

        val reset = dirty.resetTransientForExit()

        assertEquals("", reset.keywordInput)
        assertEquals(LibrarySearchField.ALL, reset.selectedField)
        assertFalse(reset.showOfficialWebMode)
        assertFalse(reset.hasSearched)
        assertEquals("", reset.activeQuery)
        assertFalse(reset.isSearching)
        assertFalse(reset.isLoadingMore)
        assertNull(reset.searchError)
        assertEquals(1, reset.currentPage)
        assertEquals(0, reset.totalCount)
        assertFalse(reset.hasMorePages)
        assertEquals(emptyList<CatalogBook>(), reset.resultBooks)
        assertNull(reset.selectedBook)
        assertNull(reset.activeBookDetail)
        assertFalse(reset.isDetailLoading)
        assertEquals(listOf("公共热搜"), reset.hotKeywords)
    }

    @Test
    fun schoolCenterExitRestoresQuerySelectionCreateAndCopyFeedback() {
        val dirty = SchoolCenterUiState(
            query = "河南",
            selectedSchoolId = "school",
            showContribution = true,
            showCreateProfile = true,
            creatingProfile = true,
            profileFeedback = "已创建",
            copyFeedback = "已复制",
            sessionsAvailable = true
        )

        val reset = dirty.resetTransientForExit()

        assertEquals("", reset.query)
        assertNull(reset.selectedSchoolId)
        assertFalse(reset.showContribution)
        assertFalse(reset.showCreateProfile)
        assertFalse(reset.creatingProfile)
        assertNull(reset.profileFeedback)
        assertNull(reset.copyFeedback)
        assertEquals(true, reset.sessionsAvailable)
    }

    @Test
    fun settingsHomeExitRestoresSyncAndProfileDialogOnly() {
        val profile = StudentProfile(id = "stu_local", schoolId = "20230001", displayName = "测试")
        val dirty = SettingsHomeUiState(
            studentProfile = profile,
            isDynamicSyncing = true,
            showEditProfileDialog = true
        )

        val reset = dirty.resetTransientForExit()

        assertFalse(reset.isDynamicSyncing)
        assertFalse(reset.showEditProfileDialog)
        assertSame(profile, reset.studentProfile)
    }

    @Test
    fun settingsUpdateExitRestoresCheckDownloadAndReleaseDialog() {
        val prefs = AppPreferencesState()
        val dirty = SettingsUpdateUiState(
            prefs = prefs,
            isCheckingRelease = true,
            isDownloadingApk = true,
            apkDownloadProgress = 0.7f,
            releaseStatusMessage = "正在下载",
            downloadedApkFile = File("update.apk"),
            showReleaseNotesDialog = true
        )

        val reset = dirty.resetTransientForExit()

        assertFalse(reset.isCheckingRelease)
        assertFalse(reset.isDownloadingApk)
        assertEquals(0f, reset.apkDownloadProgress)
        assertNull(reset.releaseStatusMessage)
        assertNull(reset.downloadedApkFile)
        assertFalse(reset.showReleaseNotesDialog)
        assertSame(prefs, reset.prefs)
    }

    @Test
    fun rootRegistryDefersResetUntilRootSettingsExits() {
        val registry = SettingsTransientResetRegistry()
        var calls = 0
        val owner = Any()
        registry.register(owner) { calls += 1 }
        registry.register(owner) { calls += 1 }

        assertEquals(0, calls)
        registry.resetAll()

        assertEquals(1, calls)
    }
}
