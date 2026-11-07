package cn.edu.haut.campus.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局语义设计 Token（间距、圆角、边框、动效、透明度与标准尺寸），
 * 为 `:app` 与 `:feature:*` 模块提供统一的视觉尺度。
 */
object XPeachTokens {

    object Spacing {
        val XXS: Dp = 2.dp
        val XS: Dp = 4.dp
        val SM: Dp = 6.dp
        val MD: Dp = 8.dp
        val Regular: Dp = 10.dp
        val LG: Dp = 12.dp
        val XL: Dp = 16.dp
        val XXL: Dp = 20.dp
        val XXXL: Dp = 24.dp
        val PageHorizontal: Dp = FlutterM3Tokens.PageHorizontalPadding
        val SectionGap: Dp = FlutterM3Tokens.SectionSpacing
        val CardPadding: Dp = FlutterM3Tokens.CardPadding
        val WellPadding: Dp = FlutterM3Tokens.WellPadding
    }

    object Radius {
        val XS: Dp = 6.dp
        val SM: Dp = 8.dp
        val MD: Dp = 10.dp
        val Control: Dp = FlutterM3Tokens.ControlCornerRadius
        val Well: Dp = FlutterM3Tokens.WellCornerRadius
        val LiquidWell: Dp = FlutterM3Tokens.LiquidWellCornerRadius
        val Card: Dp = FlutterM3Tokens.CardCornerRadius
        val LiquidCard: Dp = FlutterM3Tokens.LiquidCardCornerRadius
        val Sheet: Dp = 24.dp
        val Pill: Dp = 999.dp
    }

    object Border {
        val Hairline: Dp = 0.5.dp
        val Subtle: Dp = 0.75.dp
        val SpecularRim: Dp = 0.9.dp
        val Strong: Dp = 1.dp
        val AccentRing: Dp = 1.5.dp
    }

    object Motion {
        const val FastMs: Int = 160
        const val MediumMs: Int = 220
        const val SlowMs: Int = 320
        const val PressScaleCard: Float = 0.985f
        const val PressScalePill: Float = 0.955f
        const val PressScaleIcon: Float = 0.92f

        fun <T> snappySpring() = spring<T>(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        )
    }

    object Alpha {
        const val SubtleFillLight: Float = 0.08f
        const val SubtleFillDark: Float = 0.16f
        const val ContainerSoft: Float = 0.45f
        const val MutedText: Float = 0.72f
        const val Disabled: Float = 0.38f
    }

    object Size {
        val TopBarIconButton: Dp = FlutterM3Tokens.IconButtonSize
        val ButtonMinHeight: Dp = FlutterM3Tokens.ButtonMinHeight
        val CompactButtonMinHeight: Dp = FlutterM3Tokens.CompactButtonMinHeight
        val FilterPillMinHeight: Dp = FlutterM3Tokens.FilterPillMinHeight
        val CompactFilterPillMinHeight: Dp = FlutterM3Tokens.CompactFilterPillMinHeight
        val SegmentedControlHeight: Dp = FlutterM3Tokens.SegmentedControlMinHeight
        val BadgeMinHeight: Dp = FlutterM3Tokens.BadgeMinHeight
        val LeadingIconBox: Dp = 38.dp
        val LeadingIconGlyph: Dp = 20.dp
    }
}

/**
 * 统一的课程卡片色板（消除 `TimetableScreen` 与 `ImporterScreen` 的重复硬编码）。
 */
object XPeachCoursePalette {
    val SwatchesArgb: List<Long> = listOf(
        0xFF10B981L, // 薄荷晨风
        0xFF0EA5E9L, // 晴空苏打
        0xFFF472B6L, // 白桃乌龙
        0xFF8B5CF6L, // 海盐香芋
        0xFF22C55EL, // 抹茶奶盖
        0xFFFB7185L, // 西柚暖阳
        0xFF0D9488L, // 松石青
        0xFF2563EBL, // 经典蓝
        0xFF7C3AEDL, // 学院紫
        0xFFEA580CL, // 暖阳橙
        0xFFDB2777L, // 樱花粉
        0xFF4F46E5L, // 靛青蓝
    )

    fun colorForIndex(index: Int): Long =
        SwatchesArgb[index.mod(SwatchesArgb.size)]
}

/**
 * 室外 GIS 矢量底图与室内楼层平面图的语义色彩 Token，
 * 消除 `CampusMapScreen` 与 `BuildingIndoorMapDialog` 中的散落硬编码色值。
 */
object XPeachMapTokens {
    fun canvasBackground(isDark: Boolean): Color =
        if (isDark) Color(0xFF0F172A) else Color(0xFFEEF4FA)

    fun campusBoundaryFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF152033) else Color(0xFFF6F9FC)

    fun greenSpaceFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF132F25) else Color(0xFFDDF3E4)

    fun waterBodyFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF112E48) else Color(0xFFCFEAFE)

    fun sportsFieldFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF1E3A2F) else Color(0xFFD1FAE5)

    fun roadCasing(isDark: Boolean): Color =
        if (isDark) Color(0xFF1E293B) else Color(0xFFD6E0EB)

    fun roadCore(isDark: Boolean): Color =
        if (isDark) Color(0xFF334155) else Color(0xFFFFFFFF)

    fun buildingShadow(isDark: Boolean): Color =
        if (isDark) Color(0x66000000) else Color(0x240F172A)

    fun indoorFloorPlateFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF162032) else Color(0xFFF8FAFC)

    fun indoorCorridorFill(isDark: Boolean): Color =
        if (isDark) Color(0xFF1E293B) else Color(0xFFEFF4F9)
}

enum class XPeachFeedbackTone {
    INFO,
    SUCCESS,
    WARNING,
    DANGER,
}

/**
 * 统一的顶部/内嵌状态反馈横幅，自动兼容 Flutter M3 与 Liquid Glass 液态毛玻璃折射风格。
 */
@Composable
fun XPeachFeedbackBanner(
    message: String?,
    modifier: Modifier = Modifier,
    tone: XPeachFeedbackTone = XPeachFeedbackTone.INFO,
    leadingIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = expandVertically(tween(XPeachTokens.Motion.MediumMs, easing = FastOutSlowInEasing)) +
            fadeIn(tween(XPeachTokens.Motion.FastMs)),
        exit = shrinkVertically(tween(XPeachTokens.Motion.FastMs)) +
            fadeOut(tween(XPeachTokens.Motion.FastMs)),
    ) {
        val text = message.orEmpty()
        val isDark = XPeachTheme.isDark
        val isLiquid = XPeachTheme.isLiquidGlass
        val hasBackdrop = XPeachTheme.hasActiveBackdrop
        val glassAlpha = XPeachTheme.glassCardAlpha

        val (rawContainer, fgColor, defaultIcon) = when (tone) {
            XPeachFeedbackTone.SUCCESS -> Triple(
                XPeachTheme.successContainer,
                XPeachTheme.onSuccessContainer,
                Icons.Rounded.CheckCircle,
            )
            XPeachFeedbackTone.WARNING -> Triple(
                XPeachTheme.warnContainer,
                XPeachTheme.onWarnContainer,
                Icons.Rounded.WarningAmber,
            )
            XPeachFeedbackTone.DANGER -> Triple(
                XPeachTheme.dangerContainer,
                XPeachTheme.onDangerContainer,
                Icons.Rounded.ErrorOutline,
            )
            XPeachFeedbackTone.INFO -> Triple(
                MaterialTheme.colorScheme.primaryContainer,
                XPeachTheme.readablePrimary,
                Icons.Rounded.Info,
            )
        }

        val containerColor = if (isLiquid || hasBackdrop) {
            rawContainer.copy(alpha = (glassAlpha * 0.88f).coerceIn(0.28f, 0.92f))
        } else {
            rawContainer
        }
        val borderColor = fgColor.copy(alpha = if (isDark) 0.36f else 0.24f)
        val cornerRadius = if (isLiquid) XPeachTokens.Radius.LiquidWell else XPeachTokens.Radius.Well
        val shape = RoundedCornerShape(cornerRadius)

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (isLiquid) {
                        Modifier.liquidGlassEffect(
                            enabled = true,
                            isDark = isDark,
                            cornerRadius = cornerRadius,
                            baseColor = containerColor,
                            material = LiquidGlassMaterial.CLEAR,
                            accentTint = fgColor,
                            nested = true
                        )
                    } else {
                        Modifier
                            .background(containerColor)
                            .border(XPeachTokens.Border.Subtle, borderColor, shape)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = leadingIcon ?: defaultIcon,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = fgColor,
                modifier = Modifier.weight(1f),
            )
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fgColor,
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭提示",
                        tint = fgColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * 统一的圆角搜索输入框（内置搜索图标、一键清空按钮与键盘搜索触发，兼容毛玻璃半透明背景）。
 */
@Composable
fun XPeachSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha
    val shape = RoundedCornerShape(if (isLiquid) XPeachTokens.Radius.LiquidWell else XPeachTokens.Radius.Control)
    val containerBg = if (isLiquid || hasBackdrop) {
        MaterialTheme.colorScheme.surface.copy(alpha = (glassAlpha * 0.82f).coerceIn(0.24f, 0.92f))
    } else {
        MaterialTheme.colorScheme.surface
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                trailingAction?.invoke()
            }
        },
        singleLine = true,
        shape = shape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = containerBg,
            unfocusedContainerColor = containerBg,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 统一的设置项开关行（整行可点击切换，支持主标题、副标题与前置图标）。
 */
@Composable
fun XPeachSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XPeachTokens.Radius.MD))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = XPeachTokens.Alpha.Disabled)
                    },
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 统一的可点击服务入口行（支持前置圆形/圆角图标、主副标题、状态徽标与右向箭头）。
 */
@Composable
fun XPeachActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingAccentColor: Color = XPeachTheme.readablePrimary,
    badgeText: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContentColor: Color = XPeachTheme.readablePrimary,
    showChevron: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XPeachTokens.Radius.MD))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(XPeachTokens.Size.LeadingIconBox)
                        .clip(RoundedCornerShape(11.dp))
                        .background(leadingAccentColor.copy(alpha = if (XPeachTheme.isDark) 0.18f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = leadingAccentColor,
                        modifier = Modifier.size(XPeachTokens.Size.LeadingIconGlyph),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!badgeText.isNullOrBlank()) {
                XPeachStatusBadge(
                    text = badgeText,
                    containerColor = badgeContainerColor,
                    contentColor = badgeContentColor,
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 统一的空状态占位组件（支持图标、主标题、引导说明与可选主行动按钮）。
 */
@Composable
fun XPeachEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val resolvedAction = onAction ?: onActionClick
    XPeachWell(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!actionLabel.isNullOrBlank() && resolvedAction != null) {
                Spacer(modifier = Modifier.height(2.dp))
                FilledTonalButton(
                    onClick = resolvedAction,
                    shape = RoundedCornerShape(XPeachTokens.Radius.Control),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * 统一的 Bento 双列紧凑磁贴组件，供「今日校园」、「学业中心」及「新手引导」复用。
 */
@Composable
fun XPeachBentoTile(
    title: String,
    badgeText: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: Color = XPeachTheme.readablePrimary,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) XPeachTokens.Motion.PressScaleCard else 1f,
        animationSpec = XPeachTokens.Motion.snappySpring(),
        label = "bentoTileScale",
    )

    XPeachCard(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badgeText.isNotBlank()) {
                    XPeachStatusBadge(
                        text = badgeText,
                        containerColor = accentColor.copy(alpha = if (XPeachTheme.isDark) 0.20f else 0.12f),
                        contentColor = accentColor,
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
