package cn.edu.haut.campus.feature.settings.library.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.CatalogBookDetail
import cn.edu.haut.campus.core.campusservices.HautLibraryOpacClient
import cn.edu.haut.campus.core.campusservices.LibrarySearchField
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiAction
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiEffect
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiState
import cn.edu.haut.campus.feature.settings.state.resetTransientForExit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 馆藏检索状态 holder：网络请求、分页与详情快照更新都离开 Composable。 */
class LibraryCatalogViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(LibraryCatalogUiState())
    val uiState: StateFlow<LibraryCatalogUiState> = mutableUiState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<LibraryCatalogUiEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<LibraryCatalogUiEffect> = mutableEffects.asSharedFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    init {
        loadHomeOverview()
    }

    fun onAction(action: LibraryCatalogUiAction) {
        when (action) {
            is LibraryCatalogUiAction.UpdateKeyword -> mutableUiState.update { it.copy(keywordInput = action.value) }
            is LibraryCatalogUiAction.SetSelectedField -> mutableUiState.update { it.copy(selectedField = action.field) }
            is LibraryCatalogUiAction.SetOfficialWebMode -> mutableUiState.update { it.copy(showOfficialWebMode = action.enabled) }
            is LibraryCatalogUiAction.ExecuteSearch -> executeSearch(action.query, action.field, action.page)
            is LibraryCatalogUiAction.OpenBookDetail -> openBookDetail(action)
            LibraryCatalogUiAction.CloseBookDetail -> mutableUiState.update {
                it.copy(selectedBook = null, activeBookDetail = null)
            }
            LibraryCatalogUiAction.CloseSearchResults -> mutableUiState.update {
                it.copy(hasSearched = false, searchError = null)
            }
            LibraryCatalogUiAction.ClearKeyword -> mutableUiState.update {
                it.copy(
                    keywordInput = "",
                    hasSearched = if (it.selectedBook == null) false else it.hasSearched,
                    searchError = if (it.selectedBook == null) null else it.searchError
                )
            }
            is LibraryCatalogUiAction.CopyCallNo -> viewModelScope.launch {
                mutableEffects.emit(LibraryCatalogUiEffect.CopyCallNo(action.callNo))
            }
        }
    }

    private fun loadHomeOverview() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isHomeLoading = true) }
            val overview = HautLibraryOpacClient.fetchHomeOverview()
            mutableUiState.update {
                it.copy(
                    hotKeywords = if (overview.hotKeywords.isNotEmpty()) overview.hotKeywords else it.hotKeywords,
                    hotBooks = overview.hotBooks,
                    isHomeLoading = false
                )
            }
        }
    }

    private fun executeSearch(query: String, field: LibrarySearchField, page: Int) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        mutableUiState.update {
            it.copy(
                keywordInput = clean,
                selectedField = field,
                selectedBook = null,
                activeBookDetail = null
            )
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (page == 1) {
                mutableUiState.update {
                    it.copy(
                        hasSearched = true,
                        activeQuery = clean,
                        isSearching = true,
                        searchError = null,
                        currentPage = 1
                    )
                }
            } else {
                mutableUiState.update { it.copy(isLoadingMore = true) }
            }

            val res = HautLibraryOpacClient.searchBooks(
                keyword = clean,
                field = field,
                page = page,
                pageSize = 20
            )
            mutableUiState.update { it.copy(isSearching = false, isLoadingMore = false) }

            res.onSuccess { pageData ->
                mutableUiState.update {
                    it.copy(
                        selectedField = pageData.field,
                        currentPage = pageData.page,
                        totalCount = pageData.totalCount,
                        hasMorePages = pageData.hasMore,
                        resultBooks = if (page == 1) {
                            pageData.books
                        } else {
                            (it.resultBooks + pageData.books).distinctBy { book ->
                                book.bibId.ifBlank { book.index.toString() + "_" + book.title }
                            }
                        }
                    )
                }
            }.onFailure { err ->
                if (page == 1) {
                    mutableUiState.update {
                        it.copy(searchError = err.message ?: "检索失败，请检查网络连接或稍后重试")
                    }
                } else {
                    mutableEffects.emit(LibraryCatalogUiEffect.ShowToast(err.message ?: "加载更多失败"))
                }
            }
        }
    }

    private fun openBookDetail(action: LibraryCatalogUiAction.OpenBookDetail) {
        val book = action.book
        mutableUiState.update {
            it.copy(
                selectedBook = book,
                activeBookDetail = CatalogBookDetail(
                    title = book.title,
                    coverUrl = book.coverUrl,
                    bibliography = book.bibliography,
                    description = book.summary,
                    holdings = book.inlineHoldings,
                    callNo = book.callNo
                )
            )
        }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isDetailLoading = true) }
            HautLibraryOpacClient.fetchBookDetail(book).onSuccess { detail ->
                mutableUiState.update { state ->
                    val resolvedCallNo = detail.callNo.ifBlank { book.callNo }
                    state.copy(
                        activeBookDetail = detail,
                        selectedBook = state.selectedBook?.let { current ->
                            current.copy(
                                callNo = resolvedCallNo,
                                availableCount = if (detail.holdings.isNotEmpty()) detail.availableHoldingsCount else current.availableCount,
                                totalCount = if (detail.holdings.isNotEmpty()) detail.totalHoldingsCount else current.totalCount,
                                inlineHoldings = detail.holdings.ifEmpty { current.inlineHoldings }
                            )
                        }
                    )
                }
            }
            mutableUiState.update { it.copy(isDetailLoading = false) }
        }
    }

    /** 恢复拆分前 remember/rememberSaveable 草稿、检索结果与详情随页面退出重置的生命周期。 */
    fun resetTransientStateOnExit() {
        searchJob?.cancel()
        detailJob?.cancel()
        searchJob = null
        detailJob = null
        mutableUiState.update { it.resetTransientForExit() }
    }

    override fun onCleared() {
        searchJob?.cancel()
        detailJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryCatalogViewModel() }
        }
    }
}
