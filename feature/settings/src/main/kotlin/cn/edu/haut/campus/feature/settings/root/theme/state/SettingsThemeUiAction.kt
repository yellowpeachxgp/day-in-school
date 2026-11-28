package cn.edu.haut.campus.feature.settings.root.theme.state

/** 主题与外观分区用户动作面；粒度与拆分前 Repository 调用一致。 */
interface SettingsThemeUiAction {
    suspend fun setThemeMode(mode: String)
    suspend fun setUiDesignStyle(styleKey: String)
    suspend fun applyCuratedThemePreset(
        designStyleKey: String,
        paletteKey: String,
        clearWallpaper: Boolean = false
    )
    suspend fun setUseDynamicColor(enabled: Boolean)
    suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long? = null)
    suspend fun saveCustomWallpaperTheme(
        localPath: String,
        primarySeedArgb: Long,
        extractedSwatches: List<Long>,
        enableLiquidGlass: Boolean = false
    )
    suspend fun saveExtractedDesktopSwatches(
        primarySeedArgb: Long,
        extractedSwatches: List<Long>
    )
    suspend fun updateWallpaperVisualParams(
        alpha: Float? = null,
        blurDp: Float? = null,
        cardAlpha: Float? = null
    )
    suspend fun resetWallpaperVisualParams()
    suspend fun clearCustomWallpaperTheme()
    suspend fun updateHomeCardLayout(
        order: List<String>,
        hidden: Set<String>,
        fullWidth: Set<String>
    )
    suspend fun resetHomeCardLayout()
}
