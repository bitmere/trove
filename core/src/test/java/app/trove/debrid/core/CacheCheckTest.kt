package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CacheCheckTest {
    private val hash = "aabbcc"
    private val hit = CachedInfo(true, "Cached torrent", 100L)

    @Test
    fun `mixed results preserve provider order and match hashes ignoring case`() = runBlocking {
        val providers = listOf(
            Probe(DebridService.PREMIUMIZE) { Result.success(mapOf(hash.uppercase() to hit)) },
            Probe(DebridService.OFFCLOUD) { Result.success(mapOf(hash to hit.copy(ready = false))) },
            Probe(DebridService.TORBOX) { Result.success(emptyMap()) },
            Probe(DebridService.REAL_DEBRID) { Result.success(null) },
        )

        assertEquals(
            listOf(
                ServiceCacheResult(DebridService.PREMIUMIZE, CacheState.CACHED, hit),
                ServiceCacheResult(DebridService.OFFCLOUD, CacheState.NOT_CACHED),
                ServiceCacheResult(DebridService.TORBOX, CacheState.NOT_CACHED),
                ServiceCacheResult(DebridService.REAL_DEBRID, CacheState.UNKNOWN),
            ),
            checkCachedEverywhere(providers, hash),
        )
    }

    @Test
    fun `provider without a read only probe is never called`() = runBlocking {
        val provider = Probe(DebridService.ALLDEBRID, readOnlyCacheCheck = false) {
            error("Unexpected cache check")
        }

        assertEquals(
            listOf(ServiceCacheResult(provider.service, CacheState.UNKNOWN)),
            checkCachedEverywhere(listOf(provider), hash),
        )
        assertEquals(0, provider.calls)
    }

    @Test
    fun `failed result preserves other providers results`() = runBlocking {
        assertIsolatedFailure { Result.failure(IllegalStateException("Cache check failed")) }
    }

    @Test
    fun `thrown exception preserves other providers results`() = runBlocking {
        assertIsolatedFailure { error("Cache check failed") }
    }

    @Test
    fun `cancellation wrapped in a result still propagates`() = runBlocking {
        val provider = Probe(DebridService.TORBOX) { Result.failure(CancellationException()) }

        try {
            checkCachedEverywhere(listOf(provider), hash)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun `select cached service with active service as tie break and fallback`() {
        val torbox = ServiceCacheResult(DebridService.TORBOX, CacheState.NOT_CACHED)
        val premiumize = ServiceCacheResult(DebridService.PREMIUMIZE, CacheState.CACHED, hit)
        val offcloud = ServiceCacheResult(DebridService.OFFCLOUD, CacheState.CACHED, hit)

        assertEquals(premiumize.service, selectAddService(listOf(torbox, premiumize), torbox.service))
        assertEquals(offcloud.service, selectAddService(listOf(premiumize, offcloud), offcloud.service))
        assertEquals(premiumize.service, selectAddService(listOf(torbox, premiumize, offcloud), torbox.service))
        assertEquals(torbox.service, selectAddService(listOf(torbox), torbox.service))
        assertNull(selectAddService(listOf(torbox), null))
    }

    private suspend fun assertIsolatedFailure(probe: () -> Result<Map<String, CachedInfo>?>) {
        val failing = Probe(DebridService.TORBOX, response = probe)
        val cached = Probe(DebridService.PREMIUMIZE) { Result.success(mapOf(hash to hit)) }

        assertEquals(
            listOf(
                ServiceCacheResult(failing.service, CacheState.UNKNOWN),
                ServiceCacheResult(cached.service, CacheState.CACHED, hit),
            ),
            checkCachedEverywhere(listOf(failing, cached), hash),
        )
    }

    private class Probe(
        override val service: DebridService,
        readOnlyCacheCheck: Boolean = true,
        private val response: () -> Result<Map<String, CachedInfo>?>,
    ) : DebridProvider {
        override val capabilities = ProviderCapabilities(
            readOnlyCacheCheck = readOnlyCacheCheck,
            authFlow = AuthFlow.API_KEY,
        )
        var calls = 0
            private set

        override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> {
            calls++
            return response()
        }

        override suspend fun validateAccount(): Result<AccountInfo> = throw UnsupportedOperationException()
        override suspend fun listDownloads(): Result<List<DebridDownload>> = throw UnsupportedOperationException()
        override suspend fun getDownload(id: String): Result<DebridDownload> = throw UnsupportedOperationException()
        override suspend fun add(input: AddInput): Result<DebridDownload> = throw UnsupportedOperationException()
        override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> =
            throw UnsupportedOperationException()
        override suspend fun control(id: String, action: ControlAction): Result<Unit> =
            throw UnsupportedOperationException()
        override suspend fun delete(id: String): Result<Unit> = throw UnsupportedOperationException()
        override suspend fun supportsUrl(url: String): Result<Boolean> = throw UnsupportedOperationException()
    }
}
