package cn.edu.haut.campus.feature.settings.library.catalog.state

/** 图书馆馆藏检索一次性平台事件。 */
sealed interface LibraryCatalogUiEffect {
    data class CopyCallNo(val callNo: String) : LibraryCatalogUiEffect
    data class ShowToast(val message: String) : LibraryCatalogUiEffect
}
