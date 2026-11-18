package app.trove.debrid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.trove.debrid.R

/** Tonal surfaces, strong accents and expressive rounded geometry. */
private object TroveColors {
    val Paper = Color(0xFFF7FAF6)
    val Ink = Color(0xFF171717)
    val Hairline = Color(0xFFE7E7E7)
    val Jade = Color(0xFF007D60) // Accessible jade for text and white-on-primary controls
    val Error = Color(0xFFBA1A1A)
    val Chip = Color(0xFFF2F2F2)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

// Every Material role is intentional: sheets/dialogs must never inherit purple defaults.
private val TroveColorScheme = lightColorScheme(

    primaryFixed = Color(0xFFB5F1DB), primaryFixedDim = Color(0xFF91D5BB),
    onPrimaryFixed = Color(0xFF002116), onPrimaryFixedVariant = Color(0xFF00513D),
    secondaryFixed = Color(0xFFD0E8DC), secondaryFixedDim = Color(0xFFB4CCBF),
    onSecondaryFixed = Color(0xFF0A2017), onSecondaryFixedVariant = Color(0xFF354B40),
    tertiaryFixed = Color(0xFFC2E8FC), tertiaryFixedDim = Color(0xFFA6CDDF),
    onTertiaryFixed = Color(0xFF001F2A), onTertiaryFixedVariant = Color(0xFF254B59),
    primary = TroveColors.Jade,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F1DB),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4D635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DC),
    onSecondaryContainer = Color(0xFF0A2017),
    tertiary = Color(0xFF3E6373),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E8FC),
    onTertiaryContainer = Color(0xFF001F2A),
    error = TroveColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = TroveColors.Paper,
    onBackground = TroveColors.Ink,
    surface = TroveColors.Paper,
    onSurface = TroveColors.Ink,
    surfaceVariant = TroveColors.Chip,
    onSurfaceVariant = Color(0xFF4C5B54),
    outline = Color(0xFF718078),
    outlineVariant = TroveColors.Hairline,
    surfaceTint = TroveColors.Jade,
    inverseSurface = Color(0xFF29322D),
    inverseOnSurface = Color(0xFFEDF2ED),
    inversePrimary = Color(0xFF91D5BB),
    scrim = Color.Black,
    surfaceBright = TroveColors.Paper,
    surfaceDim = Color(0xFFD9DED9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F7F4),
    surfaceContainer = Color(0xFFEFF2EE),
    surfaceContainerHigh = Color(0xFFE9ECE8),
    surfaceContainerHighest = Color(0xFFE3E7E2),
)

private val TroveDarkColorScheme = darkColorScheme(

    primaryFixed = Color(0xFFB5F1DB), primaryFixedDim = Color(0xFF91D5BB),
    onPrimaryFixed = Color(0xFF002116), onPrimaryFixedVariant = Color(0xFF00513D),
    secondaryFixed = Color(0xFFD0E8DC), secondaryFixedDim = Color(0xFFB4CCBF),
    onSecondaryFixed = Color(0xFF0A2017), onSecondaryFixedVariant = Color(0xFF354B40),
    tertiaryFixed = Color(0xFFC2E8FC), tertiaryFixedDim = Color(0xFFA6CDDF),
    onTertiaryFixed = Color(0xFF001F2A), onTertiaryFixedVariant = Color(0xFF254B59),
    primary = Color(0xFF91D5BB), onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513D), onPrimaryContainer = Color(0xFFB5F1DB),
    secondary = Color(0xFFB4CCBF), onSecondary = Color(0xFF20352B),
    secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFD0E8DC),
    tertiary = Color(0xFFA6CDDF), onTertiary = Color(0xFF0B3441),
    tertiaryContainer = Color(0xFF254B59), onTertiaryContainer = Color(0xFFC2E8FC),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surfaceVariant = Color(0xFF3F4942), surfaceTint = Color(0xFF91D5BB),
    surfaceBright = Color(0xFF363B35), surfaceDim = Color(0xFF101511),
    inverseSurface = Color(0xFFE0E5DF), inverseOnSurface = Color(0xFF29322D),
    inversePrimary = Color(0xFF006C51), scrim = Color.Black,
    background = Color(0xFF101511), onBackground = Color(0xFFE0E5DF),
    surface = Color(0xFF101511), onSurface = Color(0xFFE0E5DF),
    onSurfaceVariant = Color(0xFFBFC9C0), outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF3F4942),
    surfaceContainerLowest = Color(0xFF0B100C), surfaceContainerLow = Color(0xFF191E19),
    surfaceContainer = Color(0xFF1D221D), surfaceContainerHigh = Color(0xFF272C27),
    surfaceContainerHighest = Color(0xFF323731),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal,
                 family: FontFamily = Inter) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
)

private val TroveTypography = Typography(
    displayLarge = type(57, 64, FontWeight.Bold, SpaceGrotesk), displayMedium = type(45, 52, FontWeight.Bold, SpaceGrotesk), displaySmall = type(36, 44, FontWeight.SemiBold, SpaceGrotesk),
    headlineLarge = type(36, 44, FontWeight.Bold, SpaceGrotesk), headlineMedium = type(30, 38, FontWeight.Bold, SpaceGrotesk), headlineSmall = type(24, 32, FontWeight.SemiBold, SpaceGrotesk),
    titleLarge = type(22, 28, FontWeight.Medium),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 16, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium),
)

@Composable
fun TroveTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) TroveDarkColorScheme else TroveColorScheme,
        typography = TroveTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
