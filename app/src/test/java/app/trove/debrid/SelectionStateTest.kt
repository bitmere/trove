package app.trove.debrid

import app.trove.debrid.ui.downloads.SelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    @Test
    fun `toggle adds and removes an ID without changing earlier states`() {
        val empty = SelectionState()
        val selected = empty.toggle("1")
        val deselected = selected.toggle("1")

        assertTrue(empty.isEmpty)
        assertTrue(selected.isSelecting)
        assertTrue(selected.isSelected("1"))
        assertEquals(1, selected.count)
        assertTrue(deselected.isEmpty)
        assertFalse(deselected.isSelecting)
    }

    @Test
    fun `select all replaces selection with unique IDs and clear empties it`() {
        val selected = SelectionState(setOf("old")).selectAll(listOf("1", "2", "1"))
        val cleared = selected.clear()

        assertEquals(setOf("1", "2"), selected.selectedIds)
        assertEquals(2, selected.count)
        assertEquals(0, cleared.count)
        assertTrue(cleared.isEmpty)
    }
}
