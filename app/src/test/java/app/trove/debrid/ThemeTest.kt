package app.trove.debrid

import androidx.compose.ui.graphics.Color
import app.trove.debrid.data.ThemeAccent
import app.trove.debrid.data.ThemeMode
import app.trove.debrid.ui.theme.troveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test fun storedThemeValuesAndDefaults() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.name)) }
        ThemeAccent.entries.forEach { assertEquals(it, ThemeAccent.fromStored(it.name)) }
        listOf(null, "", "unsupported").forEach { value ->
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(value))
            assertEquals(ThemeAccent.JADE, ThemeAccent.fromStored(value))
        }
    }

    @Test fun oledUsesPureBlackSurfaces() {
        val scheme = troveColorScheme(ThemeMode.OLED, ThemeAccent.OCEAN, systemDark = false)
        listOf(
            scheme.background, scheme.surface, scheme.surfaceVariant, scheme.surfaceBright,
            scheme.surfaceDim, scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
            scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
        ).forEach { assertEquals(Color.Black, it) }
        val dark = troveColorScheme(ThemeMode.DARK, ThemeAccent.OCEAN, systemDark = false)
        assertEquals(dark.onSurface, scheme.onSurface)
        assertEquals(dark.primary, scheme.primary)
    }

    @Test fun accentsHaveDistinctPrimariesInLightAndDark() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            val primaries = ThemeAccent.entries.map { troveColorScheme(mode, it, systemDark = false).primary }
            assertEquals(ThemeAccent.entries.size, primaries.toSet().size)
        }
    }

    @Test fun systemFollowsDeviceAppearance() {
        listOf(false, true).forEach { systemDark ->
            val explicitMode = if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT
            val system = troveColorScheme(ThemeMode.SYSTEM, ThemeAccent.JADE, systemDark = systemDark)
            val explicit = troveColorScheme(explicitMode, ThemeAccent.JADE, systemDark = !systemDark)
            assertEquals(explicit.background, system.background)
        }
    }
}
