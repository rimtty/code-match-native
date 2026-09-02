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
    /** Dark semantic error role for text on the light app surface. */
    val ErrorText = Color(0xFFB3261E)
    val Amber = Color(0xFFE09620)
    val Line = Color(0xFFD8DCD6)
}

// Keep the brand colors unchanged while choosing semantic foreground roles
// that remain readable against the slightly saturated tertiary/error fills.
// In particular, white is below WCAG AA on both Amber and Red at normal text
// sizes, so the dark foreground is intentional rather than a brand-color edit.
internal val CodeMatchLightColorScheme = lightColorScheme(
    primary = CodeMatchColors.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F3E4),
    onPrimaryContainer = CodeMatchColors.Ink,
    secondary = CodeMatchColors.Lime,
    onSecondary = CodeMatchColors.Ink,
    secondaryContainer = Color(0xFFEAF8B8),
    onSecondaryContainer = CodeMatchColors.Ink,
    tertiary = CodeMatchColors.Amber,
    onTertiary = Color.Black,
    // Red is retained as the brand token; the darker semantic role keeps
    // error text readable on Paper while still allowing white on filled error
    // controls.
    error = CodeMatchColors.ErrorText,
    onError = Color.White,
    background = CodeMatchColors.Paper,
    onBackground = CodeMatchColors.Ink,
    surface = CodeMatchColors.Paper,
    onSurface = CodeMatchColors.Ink,
    // Keep Muted text above the 4.5:1 AA threshold when it is rendered in a
    // surface-variant card (diagnostics and scanner status cards use this
    // pairing directly).
    surfaceVariant = Color(0xFFF0F2EE),
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
        colorScheme = CodeMatchLightColorScheme,
        typography = Typography(),
        content = content,
    )
}
