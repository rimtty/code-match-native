package jp.rimtty.codematch.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand tokens shared by the Android UI. Keep feature code on semantic roles. */
object CodeMatchColors {
    val Ink = Color(0xFF151B18)
    val Muted = Color(0xFF65706A)
    val Paper = Color(0xFFF4F3EC)
    val Green = Color(0xFF0E7C58)
    val Lime = Color(0xFFC8F36A)
    val Red = Color(0xFFD44636)
    val Amber = Color(0xFFE09620)
    val Line = Color(0xFFD8DCD6)
}

private val LightColorScheme = lightColorScheme(
    primary = CodeMatchColors.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F3E4),
    onPrimaryContainer = CodeMatchColors.Ink,
    secondary = CodeMatchColors.Lime,
    onSecondary = CodeMatchColors.Ink,
    secondaryContainer = Color(0xFFEAF8B8),
    onSecondaryContainer = CodeMatchColors.Ink,
    tertiary = CodeMatchColors.Amber,
    onTertiary = Color.White,
    error = CodeMatchColors.Red,
    onError = Color.White,
    background = CodeMatchColors.Paper,
    onBackground = CodeMatchColors.Ink,
    surface = CodeMatchColors.Paper,
    onSurface = CodeMatchColors.Ink,
    surfaceVariant = Color(0xFFE8EBE5),
    onSurfaceVariant = CodeMatchColors.Muted,
    outline = CodeMatchColors.Muted,
    outlineVariant = CodeMatchColors.Line,
)

/**
 * Code Match's Material 3 entry point.
 *
 * Dynamic color and a dark color scheme are intentionally not used yet:
 * keeping the semantic brand roles stable is important while matching the iOS
 * light theme. Dark-theme support remains a separate, unverified milestone.
 */
@Composable
fun CodeMatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography(),
        content = content,
    )
}
