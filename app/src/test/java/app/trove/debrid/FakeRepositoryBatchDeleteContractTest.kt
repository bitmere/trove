package app.trove.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeRepositoryBatchDeleteContractTest {
    @Test
    fun `batch delegates to single delete once per selected item`() = runTest {
        val first = sampleDownload(id = "1")
        val second = sampleDownload(id = "2")
        val provider = FakeProvider(mutableListOf(first, second))

        val outcomes = FakeRepository(provider).deleteMany(provider, listOf(first, first, second))

        assertEquals(listOf(first, second), outcomes.map { it.download })
        assertTrue(outcomes.all { it.result.isSuccess })
        assertEquals(listOf("1", "2"), provider.deletedIds)
        assertTrue(provider.downloads.isEmpty())
    }

    @Test
    fun `cancellation returned by delete stops the batch`() = runTest {
        val first = sampleDownload(id = "1")
        val second = sampleDownload(id = "2")
        val provider = FakeProvider(mutableListOf(first, second))
        val cancellation = CancellationException("cancel delete")
        provider.deleteFailures[first.id] = cancellation

        try {
            FakeRepository(provider).deleteMany(provider, listOf(first, second))
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(listOf("1"), provider.deletedIds)
        assertEquals(listOf(first, second), provider.downloads)
    }
}
