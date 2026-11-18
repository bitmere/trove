package app.trove.debrid

import app.trove.debrid.update.*
import org.junit.Assert.*
import org.junit.Test

class ApkInstallerTest {
    @Test fun signerMismatchRotationAndMultipleSigners() {
        assertFalse(compatibleSignerSets(setOf("old"), setOf("other"), setOf("other"), false))
        assertTrue(compatibleSignerSets(setOf("old"), setOf("new"), setOf("old", "new"), false))
        assertFalse(compatibleSignerSets(setOf("old"), setOf("new"), setOf("old", "new"), true))
        assertTrue(compatibleSignerSets(setOf("a", "b"), setOf("b", "a"), emptySet(), true))
        assertFalse(compatibleSignerSets(emptySet(), emptySet(), emptySet(), false))
    }

    @Test fun cancellationNeverLaunchesFallback() {
        val failure = runCatching {
            installAttempt(true, {}, { throw kotlinx.coroutines.CancellationException() }, { fail("cancel fallback"); InstallOutcome.SystemInstallerLaunched })
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }
    @Test fun noPermissionCreatesNoSessionOrFallback() {
        assertEquals(InstallOutcome.NeedsUnknownSources, installAttempt(false,
            { fail("abandon") }, { fail("session"); 7 }, { fail("fallback"); InstallOutcome.SystemInstallerLaunched }))
    }
    @Test fun previousSessionMustSettleAndFallbackOnlyAfterSessionThrows() {
        var fallback = 0
        assertEquals(InstallOutcome.Error(UpdateIssue.PREVIOUS_SESSION), installAttempt(true,
            { error("busy") }, { fail("session"); 7 }, { fallback++; InstallOutcome.SystemInstallerLaunched }))
        assertEquals(0, fallback)
        val calls = mutableListOf<String>()
        assertEquals(InstallOutcome.SessionStarted(7), installAttempt(true,
            { calls += "abandon previous" }, { calls += "session"; 7 }, { fail("fallback"); InstallOutcome.SystemInstallerLaunched }))
        assertEquals(listOf("abandon previous", "session"), calls)
        assertEquals(InstallOutcome.SystemInstallerLaunched, installAttempt(true, {}, { error("session") }, { fallback++; InstallOutcome.SystemInstallerLaunched }))
        assertEquals(1, fallback)
    }
    private fun adapter(calls: MutableList<String>, failAt: String? = null, cleanupFails: Boolean = false) = object : InstallSessionAdapter {
        private fun stage(name: String) { calls += name; if (name == failAt) error(name) }
        override fun create(): Int { stage("create"); return 7 }
        override fun open(id: Int) { assertEquals(7, id); stage("open") }
        override fun writeAndSync() { stage("write+fsync") }
        override fun commit(id: Int) { stage("commit") }
        override fun abandon(id: Int) { calls += "abandon"; if (cleanupFails) error("cleanup") }
        override fun close() { calls += "close" }
    }
    @Test fun writesAndSyncsBeforeCommitAndAlwaysCloses() {
        val calls = mutableListOf<String>()
        assertEquals(7, commitInstallSession(adapter(calls)))
        assertEquals(listOf("create", "open", "write+fsync", "commit", "close"), calls)
    }
    @Test fun failuresAbandonCreatedSessionAndFailedCleanupBlocksFallback() {
        for (stage in listOf("open", "write+fsync", "commit")) {
            val calls = mutableListOf<String>()
            assertEquals(stage, runCatching { commitInstallSession(adapter(calls, stage)) }.exceptionOrNull()?.message)
            assertEquals(listOf("abandon", "close"), calls.takeLast(2))
            val outcome = installAttempt(true, {}, { commitInstallSession(adapter(mutableListOf(), stage, true)) }, { fail("unsettled fallback"); InstallOutcome.SystemInstallerLaunched })
            assertEquals(InstallOutcome.Error(UpdateIssue.PREVIOUS_SESSION), outcome)
        }
    }
}
