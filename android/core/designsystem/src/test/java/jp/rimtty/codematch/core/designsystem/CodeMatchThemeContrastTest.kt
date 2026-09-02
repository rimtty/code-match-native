package jp.rimtty.codematch.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA regression coverage for every foreground/background role we ship. */
class CodeMatchThemeContrastTest {
    @Test
    fun lightSchemeSemanticPairsMeetWcagAaForNormalText() {
        val scheme = CodeMatchLightColorScheme
        val pairs = listOf(
            "primary" to (scheme.onPrimary to scheme.primary),
            "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "secondary" to (scheme.onSecondary to scheme.secondary),
            "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "tertiary" to (scheme.onTertiary to scheme.tertiary),
            "onTertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "error" to (scheme.onError to scheme.error),
            "errorTextOnBackground" to (scheme.error to scheme.background),
            "errorOnErrorContainer" to (scheme.error to scheme.errorContainer),
            "onErrorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            "background" to (scheme.onBackground to scheme.background),
            "surface" to (scheme.onSurface to scheme.surface),
            "surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        )

        pairs.forEach { (role, colors) ->
            assertTrue(
                "$role contrast=${contrastRatio(colors.first, colors.second)}",
                contrastRatio(colors.first, colors.second) >= WCAG_AA_NORMAL_TEXT,
            )
        }
    }

    @Test
    fun brandBaseColorsRemainStableWhenSemanticForegroundsChange() {
        assertTrue(CodeMatchColors.Green == Color(0xFF0E7C58))
        assertTrue(CodeMatchColors.Red == Color(0xFFD44636))
        assertTrue(CodeMatchColors.Amber == Color(0xFFE09620))
        assertTrue(CodeMatchLightColorScheme.onTertiary == Color.Black)
        assertTrue(CodeMatchLightColorScheme.error == CodeMatchColors.ErrorText)
        assertTrue(CodeMatchLightColorScheme.onError == Color.White)
    }

    private companion object {
        const val WCAG_AA_NORMAL_TEXT = 4.5

        fun contrastRatio(first: Color, second: Color): Double {
            val firstLuminance = relativeLuminance(first)
            val secondLuminance = relativeLuminance(second)
            val lighter = maxOf(firstLuminance, secondLuminance)
            val darker = minOf(firstLuminance, secondLuminance)
            return (lighter + 0.05) / (darker + 0.05)
        }

        fun relativeLuminance(color: Color): Double {
            fun linearize(channel: Float): Double {
                val normalized = channel.toDouble()
                return if (normalized <= 0.03928) {
                    normalized / 12.92
                } else {
                    Math.pow((normalized + 0.055) / 1.055, 2.4)
                }
            }

            return 0.2126 * linearize(color.red) +
                0.7152 * linearize(color.green) +
                0.0722 * linearize(color.blue)
        }
    }
}
