package app.trove.debrid

import app.trove.debrid.ui.CaptureTick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropCaptureTest {
    @Test
    fun `content changes recapture even without scrolling`() {
        val tick = CaptureTick()
        val list = Any()
        assertTrue(tick.needsCapture(0, list))
        tick.captured(0, list)
        assertFalse(tick.needsCapture(0, list))
        assertTrue(tick.needsCapture(1, list))
        // Tab, query, empty/loading transitions and navigation get a fresh identity.
        val empty = Any()
        assertTrue(tick.needsCapture(0, empty))
        tick.captured(0, empty)
        assertFalse(tick.needsCapture(0, empty))
        assertTrue(tick.needsCapture(0, Any()))
        assertTrue(CaptureTick().needsCapture(0, empty))
    }
}
