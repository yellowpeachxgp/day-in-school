package cn.edu.haut.campus.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 极简理性中性底色 + 暖橙/桃色强调色
val HautWarmOrange = Color(0xFFEA580C)
val HautPeachAccent = Color(0xFFFB923C)
val HautRationalSlate = Color(0xFF0F172A)
val HautSlateSecondary = Color(0xFF475569)
val HautSurfaceLight = Color(0xFFF8FAFC)
val HautCardLight = Color(0xFFFFFFFF)
val HautSurfaceDark = Color(0xFF0B1120)
val HautCardDark = Color(0xFF1E293B)

private val LightColors = lightColorScheme(
    primary = HautWarmOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFF059669),
    background = HautSurfaceLight,
    onBackground = HautRationalSlate,
    surface = HautCardLight,
    onSurface = HautRationalSlate,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = HautSlateSecondary
)

private val DarkColors = darkColorScheme(
    primary = HautPeachAccent,
    onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFF9A3412),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF1E3A8A),
    secondaryContainer = Color(0xFF1E40AF),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF34D399),
    background = HautSurfaceDark,
    onBackground = Color(0xFFF8FAFC),
    surface = HautCardDark,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1)
)

val HautTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
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
