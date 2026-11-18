package app.trove.debrid

import app.trove.debrid.ui.settings.SettingsDestination
import org.junit.Assert.*
import org.junit.Test

class SettingsDestinationTest {
    @Test fun sixGroupsInOrderWithDistinctTitles() {
        val groups = SettingsDestination.entries
        assertEquals(
            listOf(
                SettingsDestination.PROVIDERS,
                SettingsDestination.LIBRARY,
                SettingsDestination.PLAYBACK,
                SettingsDestination.APPEARANCE,
                SettingsDestination.UPDATES,
                SettingsDestination.BACKUP,
            ),
            groups,
        )
        assertEquals(6, groups.map { it.titleRes }.distinct().size)
        assertEquals(6, groups.map { it.summaryRes }.distinct().size)
        assertTrue(groups.all { it.titleRes != 0 && it.summaryRes != 0 })
    }
}
