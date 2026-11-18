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
    @Test fun symlinkedFilesAncestorRoundTrip() {
        // Android filesDir lives under /data/user/0, a symlink to /data/data.
        val real = File(temporary.root, "real").apply { mkdirs() }
        val alias = File(temporary.root, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val updatesDir = File(alias, "updates").apply { mkdirs() }
        val session = PendingUpdateSession(File(temporary.root, "record"), updatesDir)
        val file = File(updatesDir, "fixture.apk").apply { writeText("abc") }
        assertNotEquals(file.absolutePath, file.canonicalPath)
        assertTrue(session.isOwnedPath(file.absolutePath))
        session.save(7, 21, file, "operation")
        assertTrue(session.matches(7, 21, file.absolutePath, "operation"))
        assertNotNull(PendingUpdateSession(File(temporary.root, "record"), updatesDir).load())
    }
    @Test fun rejectsLeafSymlinkPointingOutsideUpdates() {
        val updatesDir = File(temporary.root, "updates").apply { mkdirs() }
        val outside = File(temporary.root, "outside.apk").apply { writeText("abc") }
        val link = File(updatesDir, "evil.apk")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val session = store()
        assertFalse(session.isOwnedPath(link.absolutePath))
        assertTrue(runCatching { session.save(7, 21, link, "operation") }.isFailure)
    }
}
