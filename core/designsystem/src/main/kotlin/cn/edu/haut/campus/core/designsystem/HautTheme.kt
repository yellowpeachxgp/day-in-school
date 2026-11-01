package cn.edu.haut.campus.core.designsystem

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val XPeachBrandLight = Color(0xFF0066CC)
val XPeachBrandHoverLight = Color(0xFF0052A3)
val XPeachBrandSoftLight = Color(0xFFEAF3FF)
val XPeachBrandBorderLight = Color(0xFFC2DDFB)

val XPeachBrandDark = Color(0xFF479BFF)
val XPeachBrandSoftDark = Color(0xFF1A2B42)
val XPeachBrandBorderDark = Color(0xFF2B4C7E)

val XPeachOk = Color(0xFF16A34A)
val XPeachOkSoft = Color(0xFFEAF7EE)
val XPeachWarn = Color(0xFFD97706)
val XPeachWarnSoft = Color(0xFFFEF3C7)
val XPeachDanger = Color(0xFFDC2626)
val XPeachDangerSoft = Color(0xFFFEE2E2)
val XPeachInfo = Color(0xFF0284C7)

// 轻盈柔和的底色体系：以微灰蓝底色衬托纯白卡片，弱化硬边框
val XPeachBgLight = Color(0xFFF5F7FA)
val XPeachCardLight = Color(0xFFFFFFFF)
val XPeachWellLight = Color(0xFFF2F5F9)
val XPeachBorderLight = Color(0xFFE8ECF2)
val XPeachBorderStrongLight = Color(0xFFD8E0EA)
val XPeachTextLight = Color(0xFF111827)
val XPeachText2Light = Color(0xFF374151)
val XPeachMutedLight = Color(0xFF6B7280)

val XPeachBgDark = Color(0xFF111317)
val XPeachCardDark = Color(0xFF1B1E24)
val XPeachWellDark = Color(0xFF232730)
val XPeachSidebarDark = Color(0xFF15181E)
val XPeachBorderDark = Color(0xFF282D37)
val XPeachBorderStrongDark = Color(0xFF363C4A)
val XPeachTextDark = Color(0xFFF3F4F6)
val XPeachText2Dark = Color(0xFFD1D5DB)
val XPeachMutedDark = Color(0xFF9CA3AF)

private val LightColors = lightColorScheme(
    primary = XPeachBrandLight,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftLight,
    onPrimaryContainer = XPeachBrandLight,
    secondary = XPeachInfo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = XPeachOk,
    onTertiary = Color.White,
    tertiaryContainer = XPeachOkSoft,
    onTertiaryContainer = Color(0xFF15803D),
    error = XPeachDanger,
    onError = Color.White,
    errorContainer = XPeachDangerSoft,
    onErrorContainer = Color(0xFFB91C1C),
    background = XPeachBgLight,
    onBackground = XPeachTextLight,
    surface = XPeachCardLight,
    onSurface = XPeachTextLight,
    surfaceVariant = XPeachWellLight,
    onSurfaceVariant = XPeachMutedLight,
    outline = XPeachBorderStrongLight,
    outlineVariant = XPeachBorderLight
)

private val DarkColors = darkColorScheme(
    primary = XPeachBrandDark,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftDark,
    onPrimaryContainer = Color(0xFF7CC0FF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF16324A),
    onSecondaryContainer = Color(0xFF7DD3FC),
    tertiary = Color(0xFF22C55E),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF143823),
    onTertiaryContainer = Color(0xFF4ADE80),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF3F1D1D),
    onErrorContainer = Color(0xFFF87171),
    background = XPeachBgDark,
    onBackground = XPeachTextDark,
    surface = XPeachCardDark,
    onSurface = XPeachTextDark,
    surfaceVariant = XPeachWellDark,
    onSurfaceVariant = XPeachMutedDark,
    outline = XPeachBorderStrongDark,
    outlineVariant = XPeachBorderDark
)

val HautTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

enum class PresetThemePalette(
    val key: String,
    val label: String,
    val subtitle: String,
    val seedArgb: Long
) {
    HAUT_BLUE("HAUT_BLUE", "工大蓝", "经典沉稳蓝", 0xFF0066CC),
    PINE_TEAL("PINE_TEAL", "松石青", "清透青碧色", 0xFF0D9488),
    JADE_GREEN("JADE_GREEN", "翡翠绿", "自然护眼绿", 0xFF16A34A),
    AMBER_GOLD("AMBER_GOLD", "琥珀金", "暖阳活力橙", 0xFFD97706),
    CORAL_RED("CORAL_RED", "朱砂红", "明快珊瑚红", 0xFFE11D48),
    IRIS_PURPLE("IRIS_PURPLE", "鸢尾紫", "静谧学院紫", 0xFF7C3AED),
    SAKURA_PINK("SAKURA_PINK", "樱花粉", "柔和春日粉", 0xFFDB2777),
    SLATE_INK("SLATE_INK", "玄墨灰", "极简水墨灰", 0xFF334155);

    companion object {
        fun fromKey(key: String): PresetThemePalette? = entries.firstOrNull { it.key == key }
    }
}

fun buildSeedColorScheme(seedArgb: Long, darkTheme: Boolean): androidx.compose.material3.ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seedArgb.toInt(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.18f, 0.92f)
    val value = hsv[2].coerceIn(0.28f, 0.92f)

    fun hsvColor(h: Float, s: Float, v: Float): Color {
        val normH = ((h % 360f) + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(normH, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    }

    return if (!darkTheme) {
        val primary = hsvColor(hue, sat, value.coerceIn(0.42f, 0.82f))
        val primaryContainer = hsvColor(hue, (sat * 0.22f).coerceIn(0.06f, 0.22f), 0.98f)
        val onPrimaryContainer = hsvColor(hue, (sat * 0.95f).coerceIn(0.45f, 0.95f), 0.42f)
        val secondary = hsvColor(hue + 18f, (sat * 0.75f).coerceIn(0.25f, 0.78f), 0.68f)
        val secondaryContainer = hsvColor(hue + 18f, (sat * 0.18f).coerceIn(0.05f, 0.18f), 0.97f)
        val onSecondaryContainer = hsvColor(hue + 18f, (sat * 0.85f).coerceIn(0.4f, 0.85f), 0.38f)
        val bgTint = hsvColor(hue, (sat * 0.05f).coerceIn(0.01f, 0.045f), 0.975f)
        val wellTint = hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.07f), 0.955f)
        val borderTint = hsvColor(hue, (sat * 0.11f).coerceIn(0.03f, 0.09f), 0.915f)
        val borderStrong = hsvColor(hue, (sat * 0.14f).coerceIn(0.04f, 0.12f), 0.86f)

        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = XPeachOk,
            onTertiary = Color.White,
            tertiaryContainer = XPeachOkSoft,
            onTertiaryContainer = Color(0xFF15803D),
            error = XPeachDanger,
            onError = Color.White,
            errorContainer = XPeachDangerSoft,
            onErrorContainer = Color(0xFFB91C1C),
            background = bgTint,
            onBackground = XPeachTextLight,
            surface = XPeachCardLight,
            onSurface = XPeachTextLight,
            surfaceVariant = wellTint,
            onSurfaceVariant = XPeachMutedLight,
            outline = borderStrong,
            outlineVariant = borderTint
        )
    } else {
        val primaryDark = hsvColor(hue, (sat * 0.68f).coerceIn(0.28f, 0.72f), 0.96f)
        val primaryContainerDark = hsvColor(hue, (sat * 0.62f).coerceIn(0.22f, 0.65f), 0.28f)
        val onPrimaryContainerDark = hsvColor(hue, (sat * 0.48f).coerceIn(0.18f, 0.52f), 0.98f)
        val secondaryDark = hsvColor(hue + 18f, (sat * 0.58f).coerceIn(0.22f, 0.62f), 0.90f)
        val secondaryContainerDark = hsvColor(hue + 18f, (sat * 0.55f).coerceIn(0.2f, 0.58f), 0.26f)
        val onSecondaryContainerDark = hsvColor(hue + 18f, (sat * 0.42f).coerceIn(0.16f, 0.48f), 0.95f)

        darkColorScheme(
            primary = primaryDark,
            onPrimary = Color(0xFF09111E),
            primaryContainer = primaryContainerDark,
            onPrimaryContainer = onPrimaryContainerDark,
            secondary = secondaryDark,
            onSecondary = Color(0xFF09111E),
            secondaryContainer = secondaryContainerDark,
            onSecondaryContainer = onSecondaryContainerDark,
            tertiary = Color(0xFF22C55E),
            onTertiary = Color(0xFF052E16),
            tertiaryContainer = Color(0xFF143823),
            onTertiaryContainer = Color(0xFF4ADE80),
            error = Color(0xFFEF4444),
            onError = Color.White,
            errorContainer = Color(0xFF3F1D1D),
            onErrorContainer = Color(0xFFF87171),
            background = XPeachBgDark,
            onBackground = XPeachTextDark,
            surface = XPeachCardDark,
            onSurface = XPeachTextDark,
            surfaceVariant = XPeachWellDark,
            onSurfaceVariant = XPeachMutedDark,
            outline = XPeachBorderStrongDark,
            outlineVariant = XPeachBorderDark
        )
    }
}

@Composable
fun HautCampusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "SYSTEM",
    dynamicColor: Boolean = false,
    themePaletteKey: String = "HAUT_BLUE",
    customSeedArgb: Long = 0xFF0066CC,
    content: @Composable () -> Unit
) {
    val effectiveDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> darkTheme
    }
    val resolvedSeedArgb = when (themePaletteKey) {
        "CUSTOM" -> customSeedArgb
        else -> PresetThemePalette.fromKey(themePaletteKey)?.seedArgb ?: 0xFF0066CC
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themePaletteKey == "HAUT_BLUE" -> {
            if (effectiveDark) DarkColors else LightColors
        }
        else -> buildSeedColorScheme(resolvedSeedArgb, effectiveDark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HautTypography,
        content = content
    )
}

@Composable
fun XPeachCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun XPeachWell(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    borderColor: Color = Color.Transparent,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (borderColor != Color.Transparent) BorderStroke(0.8.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun XPeachTopBar(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!badgeText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            XPeachStatusBadge(text = badgeText)
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.padding(top = 1.dp))
                        if (onSubtitleClick != null) {
                            Text(
                                text = "$subtitle ▾",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onSubtitleClick)
                                    .padding(vertical = 2.dp)
                            )
                        } else {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
fun XPeachIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun XPeachIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun XPeachFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(0.8.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun XPeachFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(0.8.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        leadingIcon()
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun <T> XPeachSegmentedTabs(
    options: List<T>,
    selectedOption: T,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (option in options) {
            val isSelected = option == selectedOption
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelProvider(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun XPeachSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val indices = items.indices.toList()
    XPeachSegmentedTabs(
        options = indices,
        selectedOption = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        labelProvider = { idx -> items.getOrElse(idx) { "" } },
        onSelect = onSelect,
        modifier = modifier
    )
}

@Composable
fun XPeachStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = Color.Transparent
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.8.dp, borderColor, RoundedCornerShape(20.dp))
                } else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
