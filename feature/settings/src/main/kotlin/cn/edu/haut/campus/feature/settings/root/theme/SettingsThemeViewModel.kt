package cn.edu.haut.campus.feature.settings.root.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.theme.state.SettingsThemeUiAction
import cn.edu.haut.campus.feature.settings.root.theme.state.SettingsThemeUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 主题与外观分区状态 holder；只聚合偏好并委托分区写动作。 */
class SettingsThemeViewModel(
    private val repository: CampusRepository
) : ViewModel(), SettingsThemeUiAction {
    private val mutableUiState = MutableStateFlow(SettingsThemeUiState())
    val uiState: StateFlow<SettingsThemeUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                mutableUiState.update { it.copy(prefs = prefs) }
            }
        }
    }

    override suspend fun setThemeMode(mode: String) = repository.setThemeMode(mode)
    override suspend fun setUiDesignStyle(styleKey: String) = repository.setUiDesignStyle(styleKey)
    override suspend fun applyCuratedThemePreset(designStyleKey: String, paletteKey: String, clearWallpaper: Boolean) =
        repository.applyCuratedThemePreset(designStyleKey, paletteKey, clearWallpaper)
    override suspend fun setUseDynamicColor(enabled: Boolean) = repository.setUseDynamicColor(enabled)
    override suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long?) =
        repository.setThemePalette(paletteKey, customSeedArgb)
    override suspend fun saveCustomWallpaperTheme(
        localPath: String,
        primarySeedArgb: Long,
        extractedSwatches: List<Long>,
        enableLiquidGlass: Boolean
    ) = repository.saveCustomWallpaperTheme(localPath, primarySeedArgb, extractedSwatches, enableLiquidGlass)
    override suspend fun saveExtractedDesktopSwatches(primarySeedArgb: Long, extractedSwatches: List<Long>) =
        repository.saveExtractedDesktopSwatches(primarySeedArgb, extractedSwatches)
    override suspend fun updateWallpaperVisualParams(alpha: Float?, blurDp: Float?, cardAlpha: Float?) =
        repository.updateWallpaperVisualParams(alpha, blurDp, cardAlpha)
    override suspend fun resetWallpaperVisualParams() = repository.resetWallpaperVisualParams()
    override suspend fun clearCustomWallpaperTheme() = repository.clearCustomWallpaperTheme()
    override suspend fun updateHomeCardLayout(order: List<String>, hidden: Set<String>, fullWidth: Set<String>) =
        repository.updateHomeCardLayout(order, hidden, fullWidth)
    override suspend fun resetHomeCardLayout() = repository.resetHomeCardLayout()

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsThemeViewModel(repository) }
        }
    }
}
