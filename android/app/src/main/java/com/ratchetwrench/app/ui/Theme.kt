// Theme.kt — colours and shapes, matching the web app.
// Light / Dark / System is chosen in Settings and remembered on the phone.
package com.ratchetwrench.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Extra colours Material doesn't have (maintenance vs repair, statuses). */
data class Extra(
    val maint: Color, val maintSoft: Color, val repair: Color, val repairSoft: Color,
    val warn: Color, val warnSoft: Color, val ok: Color, val okSoft: Color, val muted: Color,
)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color(0xFF0D9488), Color(0xFFE3F6F4), Color(0xFFDC2626), Color(0xFFFDEAEA), Color(0xFFB45309), Color(0xFFFEF3E2),
        Color(0xFF15803D), Color(0xFFE6F6EC), Color(0xFF5B6577))
}

private val Light = lightColorScheme(
    primary = Color(0xFFEA580C), onPrimary = Color.White, primaryContainer = Color(0xFFFFF1E8), onPrimaryContainer = Color(0xFFC2410C),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF111827), surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F6), onSurfaceVariant = Color(0xFF5B6577), outline = Color(0xFFE3E7EE), outlineVariant = Color(0xFFE3E7EE),
    error = Color(0xFFDC2626), surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Color.White,
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFB7A35), onPrimary = Color(0xFF1B0D05), primaryContainer = Color(0xFF3A1F12), onPrimaryContainer = Color(0xFFFD9A63),
    background = Color(0xFF0B1120), onBackground = Color(0xFFE8ECF3), surface = Color(0xFF121A2B), onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF1A2438), onSurfaceVariant = Color(0xFF9AA6BB), outline = Color(0xFF26324A), outlineVariant = Color(0xFF26324A),
    error = Color(0xFFF87171), surfaceContainer = Color(0xFF121A2B), surfaceContainerHigh = Color(0xFF1A2438), surfaceContainerLow = Color(0xFF121A2B),
)
private val DarkExtra = Extra(Color(0xFF2DD4BF), Color(0xFF0F3330), Color(0xFFF87171), Color(0xFF3B1618), Color(0xFFFBBF24), Color(0xFF3A2A0C),
    Color(0xFF4ADE80), Color(0xFF12321F), Color(0xFF9AA6BB))

@Composable
fun RatchetTheme(themeSetting: String, content: @Composable () -> Unit) {
    val dark = when (themeSetting) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LocalExtra.current) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp)),
            content = content,
        )
    }
}
