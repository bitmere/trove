package app.trove.debrid

import app.trove.debrid.update.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateInstallResultReceiverTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = PendingUpdateSession(File(temporary.root, "record"), File(temporary.root, "updates"))
    private fun apk() = File(temporary.root, "updates/fixture.apk").apply { parentFile!!.mkdirs(); writeText("abc"); store().save(7, 21, this, "operation") }
    @Test fun staleAndSpoofedCallbacksDoNothing() = runTest {
        val file = apk()
        for ((id, version, path, op) in listOf(listOf(8, 21L, file.absolutePath, "operation"), listOf(7, 22L, file.absolutePath, "operation"), listOf(7, 21L, "/other.apk", "operation"), listOf(7, 21L, file.absolutePath, "stale"))) {
            handleInstallResult(store(), id as Int, version as Long, path as String, op as String, 0, null,
                { fail("confirm"); false }, { fail("abandon") }, { _, _ -> fail("publish") }, { fail("reopen") })
        }
        assertTrue(file.exists()); assertNotNull(store().load())
    }
    @Test fun successDeletesAndReopensWithoutListenerAndEvenWhenListenerThrows() = runTest {
        for (listenerThrows in listOf(false, true)) {
            val file = apk()
            var reopened = false
            runCatching {
                handleInstallResult(store(), 7, 21, file.absolutePath, "operation", 0, null, { false }, {},
                    { _, result -> assertEquals(UpdateInstallResult.Installed, result); if (listenerThrows) error("disposed") }, { reopened = true })
            }
            assertTrue(reopened); assertFalse(file.exists()); assertNull(store().load())
        }
    }
    @Test fun pendingConfirmationKeepsCorrelationAndFailureKeepsVerifiedApkForRetry() = runTest {
        val file = apk()
        val events = mutableListOf<UpdateInstallResult>()
        handleInstallResult(store(), 7, 21, file.absolutePath, "operation", -1, null, { true }, {}, { _, result -> events += result }, { fail("reopen") })
        assertEquals(listOf(UpdateInstallResult.WaitingForConfirmation), events)
        assertNotNull(store().load())
        handleInstallResult(store(), 7, 21, file.absolutePath, "operation", 1, "cancelled", { false }, {}, { _, result -> events += result }, {})
        assertEquals(UpdateInstallResult.Failed(message = "cancelled"), events.last())
        assertTrue(file.exists()); assertNull(store().load())
    }
    @Test fun missingConfirmationAbandonsOrRetainsUnsettledRecord() = runTest {
        val file = apk()
        handleInstallResult(store(), 7, 21, file.absolutePath, "operation", -1, null, { false }, { error("busy") }, { _, _ -> }, {})
        assertNotNull(store().load())
        handleInstallResult(store(), 7, 21, file.absolutePath, "operation", -1, null, { false }, {}, { _, _ -> }, {})
        assertNull(store().load())
    }
}
