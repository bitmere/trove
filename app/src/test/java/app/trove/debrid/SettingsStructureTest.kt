package app.trove.debrid

import app.trove.debrid.ui.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural shape of Settings: the seven groups, in order. */
class SettingsStructureTest {
    @Test fun sevenGroupsInOrderWithDistinctTitles() {
        val groups = SettingsDestination.entries
        assertEquals(
            listOf(
                SettingsDestination.PROVIDERS,
                SettingsDestination.LIBRARY,
                SettingsDestination.SIMKL,
                SettingsDestination.PLAYBACK,
                SettingsDestination.APPEARANCE,
                SettingsDestination.UPDATES,
                SettingsDestination.BACKUP,
            ),
            groups,
        )
        assertEquals(7, groups.map { it.titleRes }.distinct().size)
        assertEquals(7, groups.map { it.summaryRes }.distinct().size)
        assertTrue(groups.all { it.titleRes != 0 && it.summaryRes != 0 })
    }
}
