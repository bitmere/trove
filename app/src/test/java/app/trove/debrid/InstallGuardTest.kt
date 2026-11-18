package app.trove.debrid

import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class InstallGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun session(updatesDir: File) =
        PendingUpdateSession(File(temporary.root, "record"), updatesDir)

    private fun release(size: Long, sha256: String) =
        LatestRelease("0.2.0", 21, "https://example.invalid/Trove-v0.2.0.apk", sha256, "notes", size)

    private fun apk(dir: File, name: String = "fixture.apk", content: String = "abc"): File =
        File(dir, name).apply { parentFile!!.mkdirs(); writeText(content) }

    @Test fun passesThroughSymlinkedFilesAncestor() {
        val real = File(temporary.root, "real").apply { mkdirs() }
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val updatesDir = File(alias, "updates")
        val file = apk(updatesDir)
        assertNotEquals(file.absolutePath, file.canonicalPath)
        assertNull(verifyInstallGuard(session(updatesDir), file, release(3, sha256(file))))
    }

    @Test fun ownershipFailureSkipsReadsAndReportsNothingObserved() {
        val updatesDir = File(temporary.root, "updates").apply { mkdirs() }
        val outside = File(temporary.root, "outside.apk").apply { writeText("abc") }
        val link = File(updatesDir, "evil.apk")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val failure = verifyInstallGuard(session(updatesDir), link, release(3, sha256(outside)))
        assertNotNull(failure)
        failure!!
        assertEquals(UpdateIssue.OWNERSHIP, failure.issue)
        assertEquals(InstallCheck.FAIL, failure.diagnostics.ownership)
        assertEquals(InstallCheck.NOT_CHECKED, failure.diagnostics.size)
        assertEquals(InstallCheck.NOT_CHECKED, failure.diagnostics.sha256)
        assertEquals(3L, failure.diagnostics.expectedSize)
        assertNull(failure.diagnostics.observedSize)
        assertNull(failure.diagnostics.observedSha256)
        assertNull(failure.diagnostics.deleted)
        assertTrue(failure.diagnostics.toString().contains("ownership: fail"))
        assertTrue(failure.diagnostics.toString().contains("size: not_checked"))
    }

    @Test fun sizeFailureReportsExpectedVsObservedAndSkipsHash() {
        val updatesDir = File(temporary.root, "updates")
        val file = apk(updatesDir)
        val failure = verifyInstallGuard(session(updatesDir), file, release(4, sha256(file)))
        assertNotNull(failure)
        failure!!
        assertEquals(UpdateIssue.VERIFICATION, failure.issue)
        assertEquals(InstallCheck.PASS, failure.diagnostics.ownership)
        assertEquals(InstallCheck.FAIL, failure.diagnostics.size)
        assertEquals(InstallCheck.NOT_CHECKED, failure.diagnostics.sha256)
        assertEquals(4L, failure.diagnostics.expectedSize)
        assertEquals(3L, failure.diagnostics.observedSize)
        assertNull(failure.diagnostics.observedSha256)
    }

    @Test fun hashFailureReportsExpectedVsObserved() {
        val updatesDir = File(temporary.root, "updates")
        val file = apk(updatesDir)
        val failure = verifyInstallGuard(session(updatesDir), file, release(3, "b".repeat(64)))
        assertNotNull(failure)
        failure!!
        assertEquals(UpdateIssue.VERIFICATION, failure.issue)
        assertEquals(InstallCheck.PASS, failure.diagnostics.ownership)
        assertEquals(InstallCheck.PASS, failure.diagnostics.size)
        assertEquals(InstallCheck.FAIL, failure.diagnostics.sha256)
        assertEquals("b".repeat(64), failure.diagnostics.expectedSha256)
        assertEquals(sha256(file), failure.diagnostics.observedSha256)
    }
}
