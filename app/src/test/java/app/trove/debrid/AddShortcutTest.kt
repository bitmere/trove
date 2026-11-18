package app.trove.debrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddShortcutTest {
    @Test fun `main action with the shortcut flag opens Add`() {
        assertTrue(requestsAddSheet("android.intent.action.MAIN", openAdd = true))
    }

    @Test fun `ordinary launches and other actions do not open Add`() {
        assertFalse(requestsAddSheet("android.intent.action.MAIN", openAdd = false))
        assertFalse(requestsAddSheet("android.intent.action.VIEW", openAdd = true))
        assertFalse(requestsAddSheet(null, openAdd = true))
    }
}
