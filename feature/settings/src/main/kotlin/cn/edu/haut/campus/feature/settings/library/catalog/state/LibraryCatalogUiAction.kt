package cn.edu.haut.campus.feature.settings.library.catalog.state

import cn.edu.haut.campus.core.campusservices.CatalogBook
import cn.edu.haut.campus.core.campusservices.LibrarySearchField

/** 图书馆馆藏检索用户意图。 */
sealed interface LibraryCatalogUiAction {
    data class UpdateKeyword(val value: String) : LibraryCatalogUiAction
    data class SetSelectedField(val field: LibrarySearchField) : LibraryCatalogUiAction
    data class SetOfficialWebMode(val enabled: Boolean) : LibraryCatalogUiAction
    data class ExecuteSearch(
        val query: String,
        val field: LibrarySearchField,
        val page: Int
    ) : LibraryCatalogUiAction
    data class OpenBookDetail(val book: CatalogBook) : LibraryCatalogUiAction
    data object CloseBookDetail : LibraryCatalogUiAction
    data object CloseSearchResults : LibraryCatalogUiAction
    data object ClearKeyword : LibraryCatalogUiAction
    data class CopyCallNo(val callNo: String) : LibraryCatalogUiAction
}
