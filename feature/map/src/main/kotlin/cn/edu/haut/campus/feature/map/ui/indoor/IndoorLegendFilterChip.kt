package cn.edu.haut.campus.feature.map.ui.indoor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachTheme

@Composable
internal fun IndoorLegendFilterChip(
    label: String,
    dotColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isGlass = XPeachTheme.isLiquidGlass
    val isDark = XPeachTheme.isDark
    val containerColor = when {
        selected && isGlass -> XPeachTheme.readablePrimary.copy(alpha = if (isDark) 0.28f else 0.18f)
        selected && isDark -> XPeachTheme.readablePrimary.copy(alpha = 0.20f)
        selected -> MaterialTheme.colorScheme.primaryContainer
        isGlass -> XPeachTheme.glassChipColor
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val borderColor = when {
        selected -> XPeachTheme.readablePrimary.copy(alpha = if (isGlass) 0.55f else 0.45f)
        isGlass -> XPeachTheme.glassBorderColor
        isDark -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        else -> Color.Transparent
    }
    XPeachGlassSurface(
        shape = RoundedCornerShape(999.dp),
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = if (borderColor == Color.Transparent) 0.dp else 0.8.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    XPeachTheme.readablePrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
