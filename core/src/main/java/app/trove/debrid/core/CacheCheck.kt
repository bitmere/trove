package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class CacheState { CACHED, NOT_CACHED, UNKNOWN }

data class ServiceCacheResult(
    val service: DebridService,
    val state: CacheState,
    val info: CachedInfo? = null,
)

suspend fun checkCachedEverywhere(
    providers: List<DebridProvider>,
    hash: String,
): List<ServiceCacheResult> = coroutineScope {
    providers.map { provider ->
        async {
            val unknown = ServiceCacheResult(provider.service, CacheState.UNKNOWN)
            try {
                if (!provider.capabilities.readOnlyCacheCheck) return@async unknown
                val entries = provider.checkCached(listOf(hash)).getOrThrow() ?: return@async unknown
                val info = entries[hash] ?: entries.entries
                    .firstOrNull { it.key.equals(hash, ignoreCase = true) }?.value
                if (info?.ready == true) ServiceCacheResult(provider.service, CacheState.CACHED, info)
                else ServiceCacheResult(provider.service, CacheState.NOT_CACHED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unknown
            }
        }
    }.awaitAll()
}

fun selectAddService(
    results: List<ServiceCacheResult>,
    activeService: DebridService?,
): DebridService? {
    val cached = results.filter { it.state == CacheState.CACHED }
    return cached.firstOrNull { it.service == activeService }?.service
        ?: cached.firstOrNull()?.service
        ?: activeService
}
