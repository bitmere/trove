package app.trove.debrid.data

import app.trove.debrid.core.AccountInfo
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.LibrarySnapshot
import app.trove.debrid.core.ManualTmdbSearch
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.MetadataLookup
import app.trove.debrid.core.ServiceCacheResult
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.core.checkCachedEverywhere as coreCheckCachedEverywhere
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.coroutineContext

internal val debridServiceOrder = listOf(
    DebridService.TORBOX,
    DebridService.REAL_DEBRID,
    DebridService.ALLDEBRID,
    DebridService.PREMIUMIZE,
    DebridService.OFFCLOUD,
    DebridService.DEBRID_LINK,
)

internal val defaultExcludeWords = listOf("CAM", "TS")
internal val defaultPreferWords = listOf("x265", "REMUX")

internal fun normalizeReleaseWords(words: List<String>): List<String> =
    words.asSequence().flatMap { it.lineSequence() }.map { it.trim() }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.toList()

/** No secrets: metadata and a provider handle read together off Main in production. */
data class SavedConnection(
    val account: DebridAccount?,
    val hasKey: Boolean,
    val provider: DebridProvider?,
)

/** The outcome for one download, retaining its kind because IDs can repeat across kinds. */
data class BatchDeleteItemResult(val download: DebridDownload, val result: Result<Unit>)

/** A user's picked title for one download, keyed by (kind, id)
 *  because provider IDs repeat across kinds. The pick carries
 *  either a Cinemeta IMDb id or a TMDB id — never both. An id is
 *  not a secret, so this lives in plain preferences, separately
 *  from both the metadata cache and the encrypted credential store. */
data class MetadataOverride(val imdbId: String?, val type: String, val title: String, val tmdbId: Int? = null)

/** Snapshot summary for Settings. */
data class SimklSnapshotInfo(
    val username: String?,
    val entryCount: Int,
    val lastCheckedMs: Long?,
    val lastFailure: app.trove.debrid.core.SimklSyncFailure?,
)

/** One viewport page of one kind. `endReached` is true when the
 *  provider has no more rows for the kind after this page, and
 *  `fetchedCount` is the raw row count before release-word filtering,
 *  so the next offset advances past excluded rows too. */
data class DownloadPage(
    val rows: List<DebridDownload>,
    val endReached: Boolean,
    val fetchedCount: Int = rows.size,
)

/** The seam the ViewModels program against, so tests can use a fake
 *  without an Android Context. The production implementation is
 *  [DebridRepository]. */
interface TroveRepository {
    suspend fun loadLibrarySnapshot(): LibrarySnapshot? = null
    suspend fun saveLibrarySnapshot(snapshot: LibrarySnapshot) = Unit

    suspend fun loadTheme(): ThemePreference = ThemePreference()
    suspend fun saveTheme(theme: ThemePreference) = Unit

    suspend fun defaultPlayer(): PlayerComponent? = null
    suspend fun setDefaultPlayer(player: PlayerComponent?) = Unit
    suspend fun loadDownloadSort(): app.trove.debrid.core.DownloadSort = app.trove.debrid.core.DownloadSort.NEWEST
    suspend fun saveDownloadSort(sort: app.trove.debrid.core.DownloadSort) = Unit
    suspend fun loadExcludeWords(): List<String> = defaultExcludeWords
    suspend fun saveExcludeWords(words: List<String>) = Unit
    suspend fun loadPreferWords(): List<String> = defaultPreferWords
    suspend fun savePreferWords(words: List<String>) = Unit
    suspend fun loadShowClutterFiles(): Boolean = false
    suspend fun saveShowClutterFiles(show: Boolean) = Unit
    suspend fun loadClipboardOffersEnabled(): Boolean = true
    suspend fun saveClipboardOffersEnabled(enabled: Boolean) = Unit
    suspend fun loadDownloadProgressNotificationsEnabled(): Boolean = false
    suspend fun saveDownloadProgressNotificationsEnabled(enabled: Boolean) = Unit
    suspend fun loadBackupSignIn(): BackupSignIn? = null
    suspend fun restoreBackupSignIn(signIn: BackupSignIn): Unit = error("Backup sign-in unavailable")
    suspend fun claimClipboardOffer(text: String?): Boolean = false
    suspend fun resolveMetadata(query: app.trove.debrid.core.MediaQuery): app.trove.debrid.core.MediaMetadata? = null
    suspend fun resolveMetadataForRelease(
        name: String,
        bypassMiss: Boolean = false,
    ): app.trove.debrid.core.MediaMetadata? = resolveMetadata(app.trove.debrid.core.parseMediaName(name))
    suspend fun lookupMetadataForRelease(
        name: String,
        bypassMiss: Boolean = false,
        onPoster: suspend (String) -> Unit = {},
    ): MetadataLookup = MetadataLookup.from(resolveMetadataForRelease(name, bypassMiss))

    suspend fun replaceFailedPoster(name: String, metadata: MediaMetadata): MediaMetadata? = null

    val metadataOrderChanges: Flow<Int> get() = emptyFlow()

    /** Title matches for the picker when the automatic match fails.
     *  Listing candidates never resolves one. */
    suspend fun resolveMetadataCandidates(
        query: app.trove.debrid.core.MediaQuery,
    ): List<app.trove.debrid.core.MediaCandidate> = emptyList()
    /** Detail for a user-picked candidate id. */
    suspend fun resolveMetadataById(imdbId: String, type: String): app.trove.debrid.core.MediaMetadata? = null
    /** Detail for a user-picked TMDB candidate id. */
    suspend fun resolveMetadataByTmdbId(tmdbId: Int, type: String): app.trove.debrid.core.MediaMetadata? = null
    /** Manual TMDB link search for the picker: title, themoviedb.org
     *  link, or bare TMDB id. Explicit user intent — runs even when
     *  the automatic candidates exist. */
    suspend fun searchManualTmdbLink(
        input: String,
        fallbackType: String?,
    ): ManualTmdbSearch = ManualTmdbSearch.NoMatch
    /** Whether a TMDB key is configured, for the picker's no-key hint. */
    fun isTmdbConfigured(): Boolean = false
    /** The stored pick for one download, or null when the download
     *  resolves normally. Keyed by (kind, id). */
    suspend fun loadMetadataOverride(ref: app.trove.debrid.core.DownloadRef): MetadataOverride? = null
    suspend fun saveMetadataOverride(ref: app.trove.debrid.core.DownloadRef, pick: MetadataOverride) = Unit
    /** Removing the pick restores the parsed title and normal resolution. */
    suspend fun clearMetadataOverride(ref: app.trove.debrid.core.DownloadRef) = Unit
    /** Best-effort: clears the durable metadata disk cache when one is wired in. */
    suspend fun clearCachedMetadata() = Unit
    /** Whether the user's own TMDB key is stored (encrypted). */
    fun hasTmdbKey(): Boolean = false
    /** Masked form of the stored TMDB key, e.g. "••••••••ab12" —
     *  the only form a key may take outside the credential store. */
    fun maskedTmdbKey(): String = ""
    /** Stores the user's own TMDB key, encrypted. Blank keys are rejected. */
    suspend fun saveTmdbKey(key: String) = Unit
    /** Removes the stored TMDB key; later lookups skip TMDB silently. */
    suspend fun clearTmdbKey() = Unit
    /** Checks a TMDB key against the live API without storing it, so
     *  a mistyped key is caught at save time instead of silently
     *  disabling the fallback. */
    suspend fun validateTmdbKey(key: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("TMDB key unavailable"))

    // ---- SIMKL (read-only status badges) ----

    /** Whether the SIMKL V2 app is registered (client id minted).
     *  A client id saved in Settings configures it; otherwise the
     *  compile-time placeholder leaves it unconfigured. */
    fun isSimklConfigured(): Boolean = false
    /** The user's own SIMKL client id, or null when none is saved.
     *  Public value; stored encrypted. */
    fun simklClientId(): String? = null
    /** Saves the user's own SIMKL client id, encrypted. A changed
     *  id wipes tokens + the cached library; re-saving the same id
     *  is a no-op. Blank ids are rejected. */
    suspend fun saveSimklClientId(id: String) = Unit
    /** Removes the saved SIMKL client id; also wipes tokens + the
     *  cached library. Sign-out keeps the id. */
    suspend fun clearSimklClientId() = Unit
    /** Whether SIMKL OAuth tokens are stored. */
    fun isSimklSignedIn(): Boolean = false
    /** Snapshot summary for Settings, or null when never synced. */
    suspend fun simklSnapshotInfo(): SimklSnapshotInfo? = null
    /** Snapshot version; increments whenever the SIMKL snapshot
     *  lands or clears. */
    val simklSnapshotChanges: Flow<Int> get() = emptyFlow()
    /** O(1) lookup of the signed-in user's SIMKL entry by
     *  enrichment id. Null when not signed in or no match. */
    fun simklEntryFor(imdbId: String?, tmdbId: Int?): app.trove.debrid.core.SimklEntry? = null
    /** Loads the disk snapshot into memory (no network). */
    suspend fun loadSimklSnapshot() = Unit
    /** On-demand sync (activities-gated). Never called on a timer;
     *  failures leave the last snapshot in place. `force` bypasses
     *  the automatic-sync cooldown; only an explicit user sync
     *  (Settings "Sync now") passes it. */
    suspend fun syncSimkl(force: Boolean = false): app.trove.debrid.core.SimklSyncResult =
        app.trove.debrid.core.SimklSyncResult.NotSignedIn
    /** Starts the device/PIN flow. */
    suspend fun startSimklDeviceFlow(): Result<app.trove.debrid.core.SimklDeviceCode> =
        Result.failure(UnsupportedOperationException("SIMKL unavailable"))
    /** One poll of the device-code grant. */
    suspend fun pollSimklDeviceCode(
        deviceCode: app.trove.debrid.core.SimklDeviceCode,
    ): app.trove.debrid.core.SimklPollResult =
        app.trove.debrid.core.SimklPollResult.Failed("SIMKL unavailable")
    /** Best-effort revoke + clear tokens + clear snapshot. */
    suspend fun signOutSimkl() = Unit

    fun accounts(): List<DebridAccount>
    fun torboxAccount(): DebridAccount?
    fun hasTorboxKey(): Boolean
    fun realDebridAccount(): DebridAccount? = null
    fun allDebridAccount(): DebridAccount? = null
    fun premiumizeAccount(): DebridAccount? = null
    fun offcloudAccount(): DebridAccount? = null
    fun debridLinkAccount(): DebridAccount? = null
    fun hasRealDebridKey(): Boolean = false
    fun hasAllDebridKey(): Boolean = false
    fun hasPremiumizeKey(): Boolean = false
    fun hasOffcloudKey(): Boolean = false
    fun hasDebridLinkKey(): Boolean = false
    fun activeService(): DebridService? = torboxAccount()?.let { DebridService.TORBOX }
    suspend fun setActiveService(service: DebridService) = Unit
    suspend fun validateAndSave(
        service: DebridService,
        credential: String,
        factory: (String, HttpTransport) -> DebridProvider,
        label: (AccountInfo?) -> String,
    ): Result<DebridAccount> {
        val name = when (service) {
            DebridService.TORBOX -> "TorBox"
            DebridService.REAL_DEBRID -> "Real-Debrid"
            DebridService.ALLDEBRID -> "AllDebrid"
            DebridService.PREMIUMIZE -> "Premiumize"
            DebridService.OFFCLOUD -> "Offcloud"
            DebridService.DEBRID_LINK -> "Debrid-Link"
        }
        return Result.failure(UnsupportedOperationException("$name sign-in unavailable"))
    }
    fun signOut(service: DebridService) = Unit
    fun provider(): DebridProvider?
    fun providerFor(service: DebridService): DebridProvider? =
        provider()?.takeIf { it.service == service }

    /** Production dispatches the credential lookup to IO. */
    suspend fun loadProviderFor(service: DebridService): DebridProvider? = providerFor(service)

    fun signedInProviders(): List<DebridProvider> = debridServiceOrder.mapNotNull { providerFor(it) }

    suspend fun checkCachedEverywhere(hash: String): List<ServiceCacheResult> =
        coreCheckCachedEverywhere(signedInProviders(), hash)

    /** Production overrides this to dispatch disk and Keystore access to IO. */
    suspend fun loadConnection(): SavedConnection =
        SavedConnection(torboxAccount(), hasTorboxKey(), provider())
    /** All downloads of every kind this provider holds, newest first.
     *  When [bypassCache] is true the provider is asked to bypass its
     *  server-side list cache; only user-initiated refreshes pass true.
     *  A single method, deliberately NOT a default-delegating pair:
     *  Kotlin `by` delegation forwards un-overridden methods to the
     *  delegate, so a default body calling a 1-arg overload would run
     *  against the delegate and bypass a decorator's override — the
     *  full-suite gate caught exactly that in StartupRegressionTest. */
    suspend fun listDownloads(provider: DebridProvider, bypassCache: Boolean = false): Result<List<DebridDownload>>

    /** One page of one kind, for per-tab viewport paging (`limit=100`
     *  per append). The default slices the provider's full list, so
     *  providers without a per-kind page API keep working unchanged;
     *  the production repository overrides this for TorBox with a
     *  single network page. This default calls the abstract
     *  [listDownloads] (not an overload of itself), so a `by`
     *  decorator's override still applies. A failure is returned
     *  as-is, including for Usenet — unlike the full sync, a page
     *  error is surfaced so the UI can retry just that page. */
    /** One page of one kind, newest first. TorBox overrides this with
     *  a single network call per page; other providers use the default
     *  slice of the full list below.
     *
     *  TRAP (same as the listDownloads comment below): this default
     *  calls the virtual [listDownloads], so a `by`-delegating
     *  decorator that overrides only [listDownloads] is BYPASSED —
     *  the call runs against the delegate. A decorator that wants to
     *  fail or fake the list must override [listDownloadPage] itself,
     *  as StartupRegressionTest does. */
    suspend fun listDownloadPage(
        provider: DebridProvider,
        kind: DownloadKind,
        offset: Int,
        limit: Int,
        bypassCache: Boolean = false,
    ): Result<DownloadPage> {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be > 0" }
        return listDownloads(provider, bypassCache).map { all ->
            val kindRows = all.filter { it.kind == kind }
            val page = kindRows.drop(offset).take(limit)
            DownloadPage(page, offset + page.size >= kindRows.size)
        }
    }

    suspend fun delete(provider: DebridProvider, id: String): Result<Unit>
    suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> = delete(provider, download.id)

    /** One outcome per distinct kind and ID, in first-occurrence order. Cancellation aborts the batch. */
    suspend fun deleteMany(
        provider: DebridProvider,
        downloads: List<DebridDownload>,
    ): List<BatchDeleteItemResult> {
        val seen = mutableSetOf<Pair<DownloadKind, String>>()
        val outcomes = mutableListOf<BatchDeleteItemResult>()
        for (download in downloads) {
            coroutineContext.ensureActive()
            if (!seen.add(download.kind to download.id)) continue
            val result = try {
                delete(provider, download).also {
                    val failure = it.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            outcomes += BatchDeleteItemResult(download, result)
        }
        return outcomes
    }

    suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String): Result<StreamUrl>
    suspend fun checkCached(provider: DebridProvider, hash: String): Result<Map<String, CachedInfo>?>
    suspend fun addClassified(provider: DebridProvider, classified: ClassifiedShare): Result<DebridDownload>
    suspend fun classifyShared(
        text: String?,
        fileBytes: ByteArray?,
        fileName: String?,
        mimeType: String?,
    ): ClassifiedShare
    suspend fun downloadFileBytes(url: String): Result<ByteArray>
}
