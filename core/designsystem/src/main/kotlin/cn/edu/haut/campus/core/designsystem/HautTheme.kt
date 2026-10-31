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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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

// ==========================================================================
// XPeach SSO — Unified Flat Design System (shadcn/ui Aligned with xpeach.codes)
// Clean Neutral Surfaces · 1dp Crisp Borders · 8–12dp Standard Radii
// Zero Liquid Glass · Zero Specular Glare · Dual Adaptive Theme
// ==========================================================================

val XPeachBrandLight = Color(0xFF0072BC)
val XPeachBrandHoverLight = Color(0xFF005FA3)
val XPeachBrandSoftLight = Color(0xFFEBF5FB)
val XPeachBrandBorderLight = Color(0xFFB3D7EE)

val XPeachBrandDark = Color(0xFF1D74D6)
val XPeachBrandSoftDark = Color(0xFF1B2C42)
val XPeachBrandBorderDark = Color(0xFF2B5485)

val XPeachOk = Color(0xFF16A34A)
val XPeachOkSoft = Color(0xFFEAF7EE)
val XPeachWarn = Color(0xFFD97706)
val XPeachWarnSoft = Color(0xFFFEF3C7)
val XPeachDanger = Color(0xFFDC2626)
val XPeachDangerSoft = Color(0xFFFEE2E2)
val XPeachInfo = Color(0xFF0284C7)

// Light Surfaces & Ink
val XPeachBgLight = Color(0xFFFAFAFA)
val XPeachCardLight = Color(0xFFFFFFFF)
val XPeachWellLight = Color(0xFFF7F7F7)
val XPeachBorderLight = Color(0xFFE5E5E5)
val XPeachBorderStrongLight = Color(0xFFD4D4D4)
val XPeachTextLight = Color(0xFF0A0A0A)
val XPeachText2Light = Color(0xFF404040)
val XPeachMutedLight = Color(0xFF666666)

// Dark Surfaces & Ink
val XPeachBgDark = Color(0xFF1E1E1E)
val XPeachCardDark = Color(0xFF292929)
val XPeachWellDark = Color(0xFF242424)
val XPeachSidebarDark = Color(0xFF1B1B1B)
val XPeachBorderDark = Color(0xFF383838)
val XPeachBorderStrongDark = Color(0xFF4A4A4A)
val XPeachTextDark = Color(0xFFF3F3F3)
val XPeachText2Dark = Color(0xFFD4D4D4)
val XPeachMutedDark = Color(0xFFB5B5B5)

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
    onPrimaryContainer = Color(0xFF66B2FF),
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
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp
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
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun HautCampusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HautTypography,
        content = content
    )
}

// ==========================================================================
// XPeach Reusable Flat shadcn/ui Primitives
// ==========================================================================

/**
 * XPeach 标准扁平卡片：0dp 阴影、1dp 清晰描边、12dp 圆角、纯白/深灰实色底面。
 */
@Composable
fun XPeachCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    cornerRadius: Dp = 12.dp,
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
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * XPeach 扁平内嵌槽位容器（对应 xpeach-sso 的 `--glass-bg-well` / `--surface-2`）。
 */
@Composable
fun XPeachWell(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
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
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * XPeach 扁平顶部栏（对应 xpeach-sso `.portal-topbar` / `.mobile-topbar`）：
 * 纯净底色 + 品牌方块徽章 + 标题/副标题 + 右侧 32dp 扁平描边图标按钮 + 底部 1dp 分割线。
 */
@Composable
fun XPeachTopBar(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    // XPeach Brand Mark (28dp rounded 7dp square)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "日",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!badgeText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            XPeachStatusBadge(
                                text = badgeText,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            )
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * XPeach 32dp 扁平描边图标按钮（对应 xpeach-sso `.icon-btn`）。
 */
@Composable
fun XPeachIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
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
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * XPeach 扁平胶囊筛选标签（对应 xpeach-sso `.filter-pill`）。
 */
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
        MaterialTheme.colorScheme.surfaceVariant
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
            color = fg
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
        MaterialTheme.colorScheme.surfaceVariant
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        leadingIcon()
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg
        )
    }
}

/**
 * XPeach 扁平分段切换栏（对应 xpeach-sso `.tabs-bar` / `.center-tabs`）。
 */
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
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (option in options) {
            val isSelected = option == selectedOption
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp)
                            )
                        } else Modifier
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
                    }
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

/**
 * XPeach 语义状态徽章（对应 xpeach-sso `.badge`）。
 */
@Composable
fun XPeachStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
