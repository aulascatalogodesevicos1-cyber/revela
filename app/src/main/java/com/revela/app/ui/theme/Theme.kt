package com.revela.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFFB547)
val Surface0 = Color(0xFF0E0E10)
val Surface1 = Color(0xFF17171A)
val Surface2 = Color(0xFF222226)
val TextDim = Color(0xFF9A9AA2)
val Live = Color(0xFF3DD68C)
val Danger = Color(0xFFFF5C5C)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1B1200),
    secondary = Color(0xFFB9B9C2),
    background = Surface0,
    onBackground = Color(0xFFEDEDF0),
    surface = Surface1,
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    outline = Color(0xFF3A3A40),
    error = Danger,
)

private val Type = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun RevelaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
