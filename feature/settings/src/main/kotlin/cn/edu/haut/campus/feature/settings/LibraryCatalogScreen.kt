package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.feature.settings.library.catalog.LibraryCatalogRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun LibraryCatalogScreen(onBack: () -> Unit) {
    LibraryCatalogRoute(onBack = onBack)
}
