package app.trove.debrid.core

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory index over a snapshot: O(1) lookup by any id a row
 *  can carry. Rebuilt on every snapshot save, never mutated in
 *  place by the UI. */
internal class SimklStatusIndex(entries: List<SimklEntry>) {
    private val byImdb = HashMap<String, SimklEntry>()
    private val byTmdb = HashMap<Int, SimklEntry>()
    private val bySimkl = HashMap<Int, SimklEntry>()

    init {
        for (entry in entries) {
            entry.imdbId?.let { byImdb[it] = entry }
            entry.tmdbId?.let { byTmdb[it] = entry }
            bySimkl[entry.simklId] = entry
        }
    }

    fun lookup(imdbId: String?, tmdbId: Int?, simklId: Int? = null): SimklEntry? =
        imdbId?.let { byImdb[it] }
            ?: tmdbId?.let { byTmdb[it] }
            ?: simklId?.let { bySimkl[it] }
}

/** Read-only SIMKL sync, activities-gated, on demand only. There
 *  is no timer, no background job, and no sync on app start: the
 *  caller triggers [sync] from a sheet open, a pull-to-refresh, or
 *  Settings. Automatic triggers are cooled down: when the snapshot
 *  was last checked less than [SYNC_COOLDOWN_MS] ago, [sync]
 *  returns [SimklSyncResult.CooledDown] without a network call and
 *  the badges keep coming from the disk snapshot. Settings "Sync
 *  now" passes `force = true` and always goes live. The loop
 *  follows the docs' two-phase model:
 *
 *  1. `GET /sync/activities`; if the top-level `all` watermark is
 *     unchanged, stop — no `/sync/all-items` call is made.
 *  2. Otherwise fetch only the type/status buckets whose timestamp
 *     moved, with `date_from` = the saved watermark, and merge the
 *     delta (upsert by SIMKL id) into a working copy.
 *  3. If `removed_from_list` moved, reconcile deletions with one
 *     ids-only fetch and drop entries absent from it.
 *  4. Persist the new snapshot only after every fetch succeeded —
 *     a failed sync leaves the last snapshot untouched.
 *
 *  A failed sync never throws: it returns [SimklSyncResult.Failed]
 *  and records the failure for Settings. The list never shows an
 *  error for a stale badge. */
class SimklSyncEngine(
    private val client: SimklClient,
    private val cache: SimklDiskCache?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val syncMutex = Mutex()
    private var loaded = false

    @Volatile
    private var snapshot: SimklSnapshot? = null

    @Volatile
    private var index = SimklStatusIndex(emptyList())

    /** Loads the disk snapshot once, for badge lookups before any
     *  sync has run. Safe to call repeatedly. */
    suspend fun loadSnapshot(): SimklSnapshot? {
        if (loaded) return snapshot
        val disk = cache?.load()
        if (disk != null) setSnapshot(disk)
        loaded = true
        return snapshot
    }

    fun currentSnapshot(): SimklSnapshot? = snapshot

    fun entryFor(imdbId: String?, tmdbId: Int?): SimklEntry? = index.lookup(imdbId, tmdbId)

    /** Clears the in-memory snapshot and the disk cache (sign-out).
     *  Tokens are cleared by the caller. */
    suspend fun clear() {
        syncMutex.withLock {
            snapshot = null
            index = SimklStatusIndex(emptyList())
            loaded = true
            cache?.clear()
        }
    }

    suspend fun sync(force: Boolean = false): SimklSyncResult {
        if (!client.isConfigured) return SimklSyncResult.NotConfigured
        if (client.currentTokens() == null) return SimklSyncResult.NotSignedIn
        return syncMutex.withLock {
            val previous = loadSnapshot()
            if (previous != null && previous.quotaBlockedUntilMs > now()) {
                return@withLock SimklSyncResult.Failed(SimklSyncFailure.QUOTA)
            }
            // Cooldown: automatic triggers (sheet open, pull-to-refresh)
            // share one activities check per window instead of one per
            // trigger. The persisted lastCheckedMs survives restarts, so
            // the window holds across app launches. A first sync (no
            // snapshot) always runs, and an explicit sync forces.
            if (!force && previous != null && now() - previous.lastCheckedMs < SYNC_COOLDOWN_MS) {
                return@withLock SimklSyncResult.CooledDown
            }
            val activities = try {
                client.activities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SimklAuthException) {
                return@withLock SimklSyncResult.Failed(SimklSyncFailure.AUTH)
            } catch (e: SimklRateLimited) {
                return@withLock recordQuotaBlock(previous, e)
            } catch (e: SimklApiException) {
                return@withLock recordFailure(previous, SimklSyncFailure.API)
            } catch (_: Exception) {
                return@withLock recordFailure(previous, SimklSyncFailure.NETWORK)
            }
            // Quota spent on an earlier call in this process: stop
            // before the bucket fetches.
            if (client.lastRateLimitRemaining == 0) {
                return@withLock recordQuotaBlock(previous, null)
            }
            if (previous != null && activities.all != null && activities.all == previous.activities.all) {
                val username = if (activities.settingsAll != null &&
                    activities.settingsAll != previous.activities.settingsAll
                ) {
                    runCatching { client.userSettings().username }.getOrNull() ?: previous.username
                } else previous.username
                val updated = previous.copy(
                    username = username,
                    lastCheckedMs = now(),
                    lastError = null,
                )
                setSnapshot(updated)
                cache?.save(updated)
                return@withLock SimklSyncResult.Unchanged
            }
            syncChanged(previous, activities)
        }
    }

    private suspend fun syncChanged(
        previous: SimklSnapshot?,
        activities: SimklActivities,
    ): SimklSyncResult {
        val dateFrom = previous?.activities?.all
        val merged = LinkedHashMap<Int, SimklEntry>()
        previous?.entries?.forEach { merged[it.simklId] = it }
        try {
            for (type in SimklType.entries) {
                for (status in statusesFor(type)) {
                    val key = "${domainKey(type)}.${status.apiValue}"
                    val savedBucket = previous?.activities?.buckets?.get(key)
                    if (previous != null && savedBucket == activities.buckets[key]) continue
                    val rows = client.allItems(type, status, dateFrom)
                    for (entry in rows) merged[entry.simklId] = entry
                    if (client.lastRateLimitRemaining == 0) {
                        return recordQuotaBlock(previous, null)
                    }
                }
            }
            val removedMoved = previous != null && SimklType.entries.any { type ->
                val key = "${domainKey(type)}.removed_from_list"
                previous.activities.buckets[key] != activities.buckets[key]
            }
            if (removedMoved) {
                val live = client.allItemIds()
                merged.keys.retainAll(live)
                if (client.lastRateLimitRemaining == 0) {
                    return recordQuotaBlock(previous, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SimklAuthException) {
            return recordFailure(previous, SimklSyncFailure.AUTH)
        } catch (e: SimklRateLimited) {
            return recordQuotaBlock(previous, e)
        } catch (e: SimklApiException) {
            return recordFailure(previous, SimklSyncFailure.API)
        } catch (_: Exception) {
            return recordFailure(previous, SimklSyncFailure.NETWORK)
        }
        val username = try {
            if (previous == null || activities.settingsAll != previous.activities.settingsAll) {
                client.userSettings().username
            } else previous.username
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            previous?.username
        }
        val updated = SimklSnapshot(
            activitiesJson = activitiesJson(activities),
            activities = activities,
            username = username,
            entries = merged.values.toList(),
            lastCheckedMs = now(),
            quotaBlockedUntilMs = 0L,
            lastError = null,
        )
        setSnapshot(updated)
        cache?.save(updated)
        return SimklSyncResult.Changed(updated.entries.size)
    }

    private fun statusesFor(type: SimklType): List<SimklStatus> = when (type) {
        SimklType.MOVIE -> listOf(
            SimklStatus.PLAN_TO_WATCH,
            SimklStatus.COMPLETED,
            SimklStatus.DROPPED,
        )
        SimklType.SHOW, SimklType.ANIME -> SimklStatus.entries
    }

    private fun domainKey(type: SimklType): String = when (type) {
        SimklType.MOVIE -> "movies"
        SimklType.SHOW -> "tv_shows"
        SimklType.ANIME -> "anime"
    }

    /** The snapshot persists the raw activities response verbatim,
     *  so the watermark round-trips exactly as returned. */
    private fun activitiesJson(activities: SimklActivities): String {
        val json = JsonObject().apply {
            activities.all?.let { addProperty("all", it) }
            activities.settingsAll?.let {
                add("settings", JsonObject().apply { addProperty("all", it) })
            }
            val domains = mutableMapOf<String, JsonObject>()
            for ((key, value) in activities.buckets) {
                val (domain, field) = key.split(".", limit = 2)
                val block = domains.getOrPut(domain) { JsonObject() }
                block.addProperty(field, value)
            }
            for ((domain, block) in domains) add(domain, block)
        }
        return json.toString()
    }

    private fun recordQuotaBlock(
        previous: SimklSnapshot?,
        rateLimited: SimklRateLimited?,
    ): SimklSyncResult {
        val blockedUntil = rateLimited?.retryAfterMs?.let { now() + it }
            ?: client.nextQuotaResetMs()
        if (previous != null) {
            val updated = previous.copy(
                quotaBlockedUntilMs = blockedUntil,
                lastCheckedMs = now(),
                lastError = SimklSyncFailure.QUOTA.name,
            )
            setSnapshot(updated)
            cache?.save(updated)
        }
        return SimklSyncResult.Failed(SimklSyncFailure.QUOTA)
    }

    private fun recordFailure(previous: SimklSnapshot?, failure: SimklSyncFailure): SimklSyncResult {
        if (previous != null) {
            val updated = previous.copy(lastCheckedMs = now(), lastError = failure.name)
            setSnapshot(updated)
            cache?.save(updated)
        }
        return SimklSyncResult.Failed(failure)
    }

    private fun setSnapshot(value: SimklSnapshot) {
        snapshot = value
        index = SimklStatusIndex(value.entries)
    }

    companion object {
        /** Minimum gap between automatic (unforced) sync checks.
         *  Badges are served from the disk snapshot in between. */
        const val SYNC_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
