package cn.edu.haut.campus.feature.settings.library.catalog.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.campusservices.CatalogBook
import cn.edu.haut.campus.core.campusservices.CatalogBookDetail
import cn.edu.haut.campus.core.campusservices.HautLibraryOpacClient
import cn.edu.haut.campus.core.campusservices.LibrarySearchField
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 图书馆馆藏检索不可变渲染快照。 */
@Immutable
data class LibraryCatalogUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val keywordInput: String = "",
    val selectedField: LibrarySearchField = LibrarySearchField.ALL,
    val showOfficialWebMode: Boolean = false,
    val hotKeywords: List<String> = HautLibraryOpacClient.DEFAULT_RECOMMENDED_KEYWORDS,
    val hotBooks: List<CatalogBook> = emptyList(),
    val isHomeLoading: Boolean = true,
    val hasSearched: Boolean = false,
    val activeQuery: String = "",
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val searchError: String? = null,
    val currentPage: Int = 1,
    val totalCount: Int = 0,
    val hasMorePages: Boolean = false,
    val resultBooks: List<CatalogBook> = emptyList(),
    val selectedBook: CatalogBook? = null,
    val activeBookDetail: CatalogBookDetail? = null,
    val isDetailLoading: Boolean = false
)
