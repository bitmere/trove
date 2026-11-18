package app.trove.debrid

import app.trove.debrid.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingUpdateSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(now: () -> Long = { 100L }) = PendingUpdateSession(File(temporary.root, "record"), File(temporary.root, "updates"), now)
    private fun apk() = File(temporary.root, "updates/fixture.apk").apply { parentFile!!.mkdirs(); writeText("abc") }
    @Test fun durableExactCorrelationAcrossNewStoreInstance() {
        val file = apk()
        store().save(7, 21, file, "operation")
        assertTrue(store().matches(7, 21, file.absolutePath, "operation"))
        assertFalse(store().matches(8, 21, file.absolutePath, "operation"))
        assertFalse(store().matches(7, 22, file.absolutePath, "operation"))
        assertFalse(store().matches(7, 21, file.absolutePath, "stale"))
        assertFalse(store().matches(7, 21, "/other.apk", "operation"))
        store().clear("stale")
        assertNotNull(store().load())
        store().clear("operation")
        assertNull(store().load())
    }
    @Test fun expiredRecordsDoNotAcceptCallbacksButMustStillBeSettled() {
        val file = apk()
        store().save(7, 21, file, "operation")
        val expired = store { PendingUpdateSession.MAX_AGE_MS + 101 }
        assertFalse(expired.matches(7, 21, file.absolutePath, "operation"))
        assertTrue(runCatching { expired.abandon { error("busy") } }.isFailure)
        assertNotNull(expired.load())
        var abandoned = -1
        expired.abandon { abandoned = it }
        assertEquals(7, abandoned)
        assertNull(expired.load())
    }
    @Test fun rejectsUnownedPathOversizeAndCorruption() {
        assertTrue(runCatching { store().save(7, 21, temporary.newFile("outside.apk"), "operation") }.isFailure)
        val record = File(temporary.root, "record")
        record.writeText("x".repeat(4097)); assertNull(store().load())
        record.writeText("id=broken"); assertNull(store().load())
    }
}
