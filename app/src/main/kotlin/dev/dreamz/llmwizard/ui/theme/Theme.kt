package dev.dreamz.llmwizard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF00696E), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F6), onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365), tertiary = Color(0xFF4D5F7C),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7FAFA), surface = Color(0xFFF7FAFA),
    surfaceVariant = Color(0xFFDAE4E5), onSurfaceVariant = Color(0xFF3F4949),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF80D4DA), onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53), onPrimaryContainer = Color(0xFF9CF1F6),
    secondary = Color(0xFFB1CBCD), tertiary = Color(0xFFB5C7E9),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0E1415), surface = Color(0xFF0E1415),
    surfaceVariant = Color(0xFF3F4949), onSurfaceVariant = Color(0xFFBEC8C9),
)

/** Monospace for scripts, logs and fingerprints: things the user compares character by character. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun LlmWizardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
