package app.trove.debrid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.trove.debrid.data.ThemeAccent
import app.trove.debrid.data.ThemeMode

/** Layout rhythm shared by library, account and sheets. */
object TroveSpace {
    val micro = 4.dp
    val small = 8.dp
    val compact = 12.dp
    val medium = 16.dp
    val page = 20.dp
    val large = 24.dp
    val section = 32.dp
}

object TroveElevation {
    val card = 1.dp
    val floating = 6.dp
}

private data class AccentColors(
    val lightPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
)

private fun ThemeAccent.colors() = when (this) {
    ThemeAccent.JADE -> null
    ThemeAccent.OCEAN -> AccentColors(
        Color(0xFF17638C), Color(0xFFC9E6FF), Color(0xFF001E30),
        Color(0xFF97CCF2), Color(0xFF00344F), Color(0xFF004B70),
    )
    ThemeAccent.EMBER -> AccentColors(
        Color(0xFF934813), Color(0xFFFFDBCA), Color(0xFF321200),
        Color(0xFFFFB68D), Color(0xFF522300), Color(0xFF713504),
    )
    ThemeAccent.ROSE -> AccentColors(
        Color(0xFF984363), Color(0xFFFFD9E4), Color(0xFF3E001E),
        Color(0xFFFFB0CA), Color(0xFF5E1135), Color(0xFF7B2B4B),
    )
}

internal fun troveColorScheme(
    mode: ThemeMode,
    accent: ThemeAccent,
    systemDark: Boolean,
): ColorScheme {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.OLED -> true
    }
    val base = if (dark) TroveDarkColorScheme else TroveColorScheme
    val scheme = accent.colors()?.let { colors ->
        base.copy(
            primary = if (dark) colors.darkPrimary else colors.lightPrimary,
            onPrimary = if (dark) colors.darkOnPrimary else Color.White,
            primaryContainer = if (dark) colors.darkContainer else colors.lightContainer,
            onPrimaryContainer = if (dark) colors.lightContainer else colors.lightOnContainer,
            primaryFixed = colors.lightContainer,
            primaryFixedDim = colors.darkPrimary,
            onPrimaryFixed = colors.lightOnContainer,
            onPrimaryFixedVariant = colors.darkContainer,
            surfaceTint = if (dark) colors.darkPrimary else colors.lightPrimary,
            inversePrimary = if (dark) colors.lightPrimary else colors.darkPrimary,
        )
    } ?: base
    return if (mode == ThemeMode.OLED) scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color.Black,
        surfaceBright = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
    ) else scheme
}
