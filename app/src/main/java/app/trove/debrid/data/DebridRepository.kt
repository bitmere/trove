package app.trove.debrid.data

import app.trove.debrid.BuildConfig
import app.trove.debrid.core.PlayerComponent
import android.content.Context
import android.content.SharedPreferences
import app.trove.debrid.core.AccountInfo
import app.trove.debrid.core.AddInput
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ServiceCacheResult
import app.trove.debrid.core.checkCachedEverywhere as coreCheckCachedEverywhere
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.CredentialStore
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadRef
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.LibraryDiskCache
import app.trove.debrid.core.LibrarySnapshot
import app.trove.debrid.core.ManualTmdbSearch
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.MetadataDiskCache
import app.trove.debrid.core.OkHttpTransport
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.SimklClient
import app.trove.debrid.core.SIMKL_CLIENT_ID
import app.trove.debrid.core.SimklDeviceCode
import app.trove.debrid.core.SimklDiskCache
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.SimklPollResult
import app.trove.debrid.core.SimklSyncEngine
import app.trove.debrid.core.SimklSyncFailure
import app.trove.debrid.core.SimklSyncResult
import app.trove.debrid.core.SimklTokens
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.core.TorBoxProvider
import app.trove.debrid.core.filterExcluded
import app.trove.debrid.core.parseMediaName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** The [TroveRepository] facade (unification item 5). The work is
 *  split in two: [ProviderAccountRegistry] owns accounts, the active
 *  service, provider factories and caching, and the credential-store
 *  secrets (including the TMDB key); [PreferencesStore] owns the
 *  user's settings and the metadata overrides. This class only
 *  composes them — the download operations that span both parts
 *  (listing, paging, classification, cache checks) and the metadata
 *  resolver wiring, which consumes the registry's TMDB resolver.
 *
 *  Storage and behaviour are unchanged: both parts share the one
 *  SharedPreferences file under the same keys, so existing installs
 *  keep their accounts and settings with no migration, and the
 *  provider cache still lives in exactly one place (the registry),
 *  so switching services or changing a preference never rebuilds a
 *  provider. Call sites and tests construct this class exactly as
 *  before. */
class DebridRepository(
    private val registry: ProviderAccountRegistry,
    private val preferences: PreferencesStore,
    private val transport: HttpTransport = OkHttpTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val metadataDiskCache: MetadataDiskCache? = null,
    private val libraryDiskCache: LibraryDiskCache? = null,
    private val simklDiskCache: SimklDiskCache? = null,
    private val simklAppVersion: String = "dev",
) : TroveRepository {

    /** Primary entry point for tests and manual wiring: both parts
     *  share one SharedPreferences file, one credential store. */
    constructor(
        prefs: SharedPreferences,
        credentialStore: CredentialStore,
        transport: HttpTransport = OkHttpTransport(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        metadataDiskCache: MetadataDiskCache? = null,
        libraryDiskCache: LibraryDiskCache? = null,
        simklDiskCache: SimklDiskCache? = null,
        simklAppVersion: String = "dev",
    ) : this(
        ProviderAccountRegistry(prefs, credentialStore, transport),
        PreferencesStore(prefs, ioDispatcher),
        transport,
        ioDispatcher,
        metadataDiskCache,
        libraryDiskCache,
        simklDiskCache,
        simklAppVersion,
    )

    /** Production entry point: accounts live in "trove_accounts". */
    constructor(
        context: Context,
        credentialStore: CredentialStore,
        transport: HttpTransport = OkHttpTransport(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context.getSharedPreferences("trove_accounts", Context.MODE_PRIVATE),
        credentialStore,
        transport,
        ioDispatcher,
        MetadataDiskCache(File(context.filesDir, "metadata")),
        LibraryDiskCache(File(context.filesDir, "library-cache")),
        SimklDiskCache(File(context.filesDir, "simkl")),
        BuildConfig.VERSION_NAME,
    )

    companion object {
        const val TORBOX_ACCOUNT_ID = ProviderAccountRegistry.TORBOX_ACCOUNT_ID
        const val REAL_DEBRID_ACCOUNT_ID = ProviderAccountRegistry.REAL_DEBRID_ACCOUNT_ID
        const val ALLDEBRID_ACCOUNT_ID = ProviderAccountRegistry.ALLDEBRID_ACCOUNT_ID
        const val PREMIUMIZE_ACCOUNT_ID = ProviderAccountRegistry.PREMIUMIZE_ACCOUNT_ID
        const val OFFCLOUD_ACCOUNT_ID = ProviderAccountRegistry.OFFCLOUD_ACCOUNT_ID
        const val DEBRID_LINK_ACCOUNT_ID = ProviderAccountRegistry.DEBRID_LINK_ACCOUNT_ID
        /** TMDB is not a debrid service: its per-user API Read Access
         *  Token lives in the same encrypted store under its own id,
         *  and is never backed up or shipped. */
        const val TMDB_ACCOUNT_ID = ProviderAccountRegistry.TMDB_ACCOUNT_ID
        /** SIMKL tokens live in the same encrypted store under
         *  their own id, and are never backed up or shipped. */
        const val SIMKL_ACCOUNT_ID = ProviderAccountRegistry.SIMKL_ACCOUNT_ID
        /** The user's own SIMKL client id lives in the same
         *  encrypted store under its own id. A saved value
         *  overrides the compile-time placeholder. */
        const val SIMKL_CLIENT_ID_ACCOUNT_ID = ProviderAccountRegistry.SIMKL_CLIENT_ID_ACCOUNT_ID
    }

    // ---- Preferences (delegated) ----

    val theme get() = preferences.theme

    fun loadStartupTheme() {
        preferences.loadStartupTheme()
    }

    override suspend fun loadTheme() = preferences.loadTheme()

    override suspend fun saveTheme(theme: ThemePreference) = preferences.saveTheme(theme)

    override suspend fun loadDownloadSort() = preferences.loadDownloadSort()

    override suspend fun saveDownloadSort(sort: app.trove.debrid.core.DownloadSort) =
        preferences.saveDownloadSort(sort)

    override suspend fun loadExcludeWords() = preferences.loadExcludeWords()

    override suspend fun saveExcludeWords(words: List<String>) =
        preferences.saveExcludeWords(words)

    override suspend fun loadPreferWords() = preferences.loadPreferWords()

    override suspend fun savePreferWords(words: List<String>) =
        preferences.savePreferWords(words)

    override suspend fun loadShowClutterFiles() = preferences.loadShowClutterFiles()

    override suspend fun saveShowClutterFiles(show: Boolean) =
        preferences.saveShowClutterFiles(show)

    override suspend fun loadClipboardOffersEnabled() = preferences.loadClipboardOffersEnabled()

    override suspend fun saveClipboardOffersEnabled(enabled: Boolean) =
        preferences.saveClipboardOffersEnabled(enabled)

    override suspend fun claimClipboardOffer(text: String?): Boolean =
        preferences.claimClipboardOffer(text)

    override suspend fun defaultPlayer(): PlayerComponent? = preferences.defaultPlayer()

    override suspend fun setDefaultPlayer(player: PlayerComponent?) =
        preferences.setDefaultPlayer(player)

    // ---- Metadata (wired here: Cinemeta + the registry's TMDB
    // resolver + the disk cache; overrides live in the prefs store) ----

    private val metadataResolver = app.trove.debrid.core.CinemetaResolver(
        transport,
        diskCache = metadataDiskCache,
        revalidationScope = metadataDiskCache?.let { CoroutineScope(SupervisorJob() + ioDispatcher) },
        tmdbResolver = registry.tmdbResolver,
    )

    override suspend fun resolveMetadata(query: app.trove.debrid.core.MediaQuery) =
        withContext(ioDispatcher) { metadataResolver.resolve(query) }

    override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean) =
        withContext(ioDispatcher) { metadataResolver.resolve(app.trove.debrid.core.mediaQueryVariants(name), bypassMiss) }

    override suspend fun lookupMetadataForRelease(
        name: String,
        bypassMiss: Boolean,
        onPoster: suspend (String) -> Unit,
    ) = withContext(ioDispatcher) {
        metadataResolver.resolveOutcome(app.trove.debrid.core.mediaQueryVariants(name), bypassMiss, onPoster)
    }

    override suspend fun replaceFailedPoster(name: String, metadata: MediaMetadata) =
        withContext(ioDispatcher) { metadataResolver.replaceFailedPoster(parseMediaName(name), metadata) }

    override suspend fun resolveMetadataCandidates(query: app.trove.debrid.core.MediaQuery) =
        withContext(ioDispatcher) { metadataResolver.candidates(query) }

    override suspend fun resolveMetadataById(imdbId: String, type: String) =
        withContext(ioDispatcher) { metadataResolver.resolveById(imdbId, type) }

    override suspend fun resolveMetadataByTmdbId(tmdbId: Int, type: String) =
        withContext(ioDispatcher) { metadataResolver.resolveByTmdbId(tmdbId, type) }

    override suspend fun searchManualTmdbLink(input: String, fallbackType: String?): ManualTmdbSearch =
        withContext(ioDispatcher) {
            registry.tmdbResolver?.searchManualLink(input, fallbackType) ?: ManualTmdbSearch.NoMatch
        }

    override fun isTmdbConfigured(): Boolean = metadataResolver.isTmdbConfigured()

    override suspend fun loadMetadataOverride(ref: DownloadRef) =
        preferences.loadMetadataOverride(ref)

    override suspend fun saveMetadataOverride(ref: DownloadRef, pick: MetadataOverride) =
        preferences.saveMetadataOverride(ref, pick)

    override suspend fun clearMetadataOverride(ref: DownloadRef) =
        preferences.clearMetadataOverride(ref)

    override suspend fun clearCachedMetadata() {
        withContext(ioDispatcher) { metadataDiskCache?.clear() }
    }

    // ---- TMDB key (owned by the registry: a credential-store secret) ----

    override fun hasTmdbKey(): Boolean = registry.hasTmdbKey()

    override fun maskedTmdbKey(): String = registry.maskedTmdbKey()

    private val metadataOrder = MutableStateFlow(0)
    private val tmdbKeyWrites = Mutex()
    override val metadataOrderChanges = metadataOrder.asStateFlow()

    override suspend fun saveTmdbKey(key: String): Unit = withContext(ioDispatcher) {
        tmdbKeyWrites.withLock {
            val previous = registry.tmdbKey()
            registry.saveTmdbKey(key)
            if (previous != key.trim()) metadataOrder.update { it + 1 }
        }
    }

    override suspend fun clearTmdbKey(): Unit = withContext(ioDispatcher) {
        tmdbKeyWrites.withLock {
            val wasConfigured = registry.tmdbKey() != null
            registry.deleteTmdbKey()
            if (wasConfigured) metadataOrder.update { it + 1 }
        }
    }

    override suspend fun validateTmdbKey(key: String): Result<Unit> = withContext(ioDispatcher) {
        registry.validateTmdbKey(key)
    }

    // ---- SIMKL (read-only status badges; on-demand sync only) ----

    private val simklClient = SimklClient(
        transport,
        clientId = { registry.simklClientId() ?: SIMKL_CLIENT_ID },
        appVersion = simklAppVersion,
        tokens = { registry.simklTokensJson()?.let { SimklTokens.decode(it) } },
        onTokens = { registry.saveSimklTokensJson(it.encode()) },
    )
    private val simklEngine = SimklSyncEngine(simklClient, simklDiskCache)
    private val simklSnapshotVersion = MutableStateFlow(0)
    override val simklSnapshotChanges = simklSnapshotVersion.asStateFlow()

    override fun isSimklConfigured(): Boolean = simklClient.isConfigured

    override fun isSimklSignedIn(): Boolean = registry.hasSimklTokens()

    /** The user's own SIMKL client id, or null when none is saved. */
    override fun simklClientId(): String? = registry.simklClientId()

    /** Saves the user's own SIMKL client id (encrypted). A changed
     *  ID wipes the tokens and the cached library — both were
     *  minted under the old ID, so the user signs in again under
     *  the new one. Re-saving the same ID is a no-op and keeps
     *  them. */
    override suspend fun saveSimklClientId(id: String) {
        withContext(ioDispatcher) {
            val trimmed = id.trim()
            require(trimmed.isNotEmpty()) { "SIMKL client ID is empty" }
            if (registry.simklClientId() == trimmed) return@withContext
            // Best-effort revoke under the OLD id first: after the
            // save, the client would send the new id with the old
            // grant. Local state is cleared below regardless.
            try {
                simklClient.revoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Revoke is best-effort; continue with the wipe.
            }
            registry.saveSimklClientId(trimmed)
            registry.deleteSimklTokens()
            simklEngine.clear()
            simklSnapshotVersion.update { it + 1 }
        }
    }

    /** Removes the saved SIMKL client id; also wipes tokens and the
     *  cached library, returning the app to the not-configured
     *  state. Signing out keeps the ID — only this clears it. */
    override suspend fun clearSimklClientId() {
        withContext(ioDispatcher) {
            if (registry.simklClientId() == null && !registry.hasSimklTokens()) return@withContext
            try {
                simklClient.revoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Revoke is best-effort; continue with the wipe.
            }
            registry.deleteSimklClientId()
            registry.deleteSimklTokens()
            simklEngine.clear()
            simklSnapshotVersion.update { it + 1 }
        }
    }

    override suspend fun simklSnapshotInfo(): SimklSnapshotInfo? = withContext(ioDispatcher) {
        val snapshot = simklEngine.loadSnapshot() ?: return@withContext null
        SimklSnapshotInfo(
            username = snapshot.username,
            entryCount = snapshot.entries.size,
            lastCheckedMs = snapshot.lastCheckedMs,
            lastFailure = snapshot.lastError?.let { runCatching { SimklSyncFailure.valueOf(it) }.getOrNull() },
        )
    }

    override fun simklEntryFor(imdbId: String?, tmdbId: Int?): SimklEntry? =
        simklEngine.entryFor(imdbId, tmdbId)

    override suspend fun loadSimklSnapshot() {
        withContext(ioDispatcher) { simklEngine.loadSnapshot() }
    }

    override suspend fun syncSimkl(force: Boolean): SimklSyncResult = withContext(ioDispatcher) {
        val result = simklEngine.sync(force)
        when (result) {
            is SimklSyncResult.Changed, SimklSyncResult.Unchanged ->
                simklSnapshotVersion.update { it + 1 }
            is SimklSyncResult.Failed -> {
                if (result.failure == SimklSyncFailure.AUTH) {
                    // The grant is gone: sign out and clear the
                    // snapshot rather than serving stale badges.
                    registry.deleteSimklTokens()
                    simklEngine.clear()
                    simklSnapshotVersion.update { it + 1 }
                } else {
                    simklSnapshotVersion.update { it + 1 }
                }
            }
            SimklSyncResult.NotSignedIn, SimklSyncResult.NotConfigured,
            SimklSyncResult.CooledDown -> Unit
        }
        result
    }

    override suspend fun startSimklDeviceFlow(): Result<SimklDeviceCode> = withContext(ioDispatcher) {
        try {
            Result.success(simklClient.startDeviceFlow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pollSimklDeviceCode(deviceCode: SimklDeviceCode): SimklPollResult =
        withContext(ioDispatcher) {
            try {
                simklClient.pollDeviceCode(deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SimklPollResult.Failed("poll_failed")
            }
        }

    override suspend fun signOutSimkl() {
        withContext(ioDispatcher) {
            try {
                simklClient.revoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Revoke is best-effort; local state is cleared below regardless.
            }
            registry.deleteSimklTokens()
            simklEngine.clear()
            simklSnapshotVersion.update { it + 1 }
        }
    }

    // ---- Accounts + provider registry (delegated) ----

    override fun accounts(): List<DebridAccount> = registry.accounts()

    override suspend fun loadLibrarySnapshot(): LibrarySnapshot? =
        withContext(ioDispatcher) { libraryDiskCache?.load() }

    override suspend fun saveLibrarySnapshot(snapshot: LibrarySnapshot) {
        withContext(ioDispatcher) { libraryDiskCache?.save(snapshot) }
    }

    override suspend fun loadConnection(): SavedConnection = withContext(ioDispatcher) {
        registry.loadConnection()
    }

    override fun torboxAccount(): DebridAccount? =
        registry.accountFor(DebridService.TORBOX)

    override fun hasTorboxKey(): Boolean = registry.hasCredential(DebridService.TORBOX)

    override fun realDebridAccount(): DebridAccount? =
        registry.accountFor(DebridService.REAL_DEBRID)

    override fun hasRealDebridKey(): Boolean = registry.hasCredential(DebridService.REAL_DEBRID)

    override fun allDebridAccount(): DebridAccount? =
        registry.accountFor(DebridService.ALLDEBRID)

    override fun hasAllDebridKey(): Boolean = registry.hasCredential(DebridService.ALLDEBRID)

    override fun premiumizeAccount(): DebridAccount? =
        registry.accountFor(DebridService.PREMIUMIZE)

    override fun hasPremiumizeKey(): Boolean = registry.hasCredential(DebridService.PREMIUMIZE)

    override fun offcloudAccount(): DebridAccount? =
        registry.accountFor(DebridService.OFFCLOUD)

    override fun debridLinkAccount(): DebridAccount? =
        registry.accountFor(DebridService.DEBRID_LINK)

    override fun hasOffcloudKey(): Boolean = registry.hasCredential(DebridService.OFFCLOUD)
    override fun hasDebridLinkKey(): Boolean = registry.hasCredential(DebridService.DEBRID_LINK)

    override suspend fun loadBackupSignIn(): BackupSignIn? = withContext(ioDispatcher) {
        registry.loadBackupSignIn()
    }

    override suspend fun restoreBackupSignIn(signIn: BackupSignIn) = withContext(ioDispatcher) {
        registry.restoreBackupSignIn(signIn)
    }

    override fun activeService(): DebridService? = registry.activeService()

    override suspend fun setActiveService(service: DebridService) = withContext(ioDispatcher) {
        registry.setActiveService(service)
    }

    override fun provider(): DebridProvider? = registry.provider()

    override suspend fun loadProviderFor(service: DebridService): DebridProvider? =
        withContext(ioDispatcher) { registry.providerFor(service) }

    override fun providerFor(service: DebridService): DebridProvider? =
        registry.providerFor(service)

    /** A rejected credential leaves the current account untouched. */
    override suspend fun validateAndSave(
        service: DebridService,
        credential: String,
        factory: (String, HttpTransport) -> DebridProvider,
        label: (AccountInfo?) -> String,
    ): Result<DebridAccount> = withContext(ioDispatcher) {
        registry.validateAndSave(service, credential, factory, label)
    }

    override fun signOut(service: DebridService) {
        registry.signOut(service)
    }

    // ---- Download operations (compose both parts) ----

    override suspend fun listDownloads(provider: DebridProvider, bypassCache: Boolean): Result<List<DebridDownload>> =
        withContext(ioDispatcher) {
            val result = if (bypassCache) provider.listDownloadsFresh() else provider.listDownloads()
            result.map { downloads ->
                filterExcluded(downloads, preferences.currentExcludeWords())
            }
        }

    /** Viewport paging seam (TorBox-only network paging). A TorBox
     *  page is one mylist call for one kind, so a large library never
     *  has to load whole kinds up front; other providers keep the
     *  default slice-of-full-list path. `endReached` is computed from
     *  the RAW page size, before release-word filtering, so excluded
     *  rows on a page cannot end the tab early. A Usenet page failure
     *  is returned as-is — the full sync swallows it, a page does not. */
    override suspend fun listDownloadPage(
        provider: DebridProvider,
        kind: DownloadKind,
        offset: Int,
        limit: Int,
        bypassCache: Boolean,
    ): Result<DownloadPage> = withContext(ioDispatcher) {
        val torBox = provider as? TorBoxProvider
            ?: return@withContext super<TroveRepository>.listDownloadPage(
                provider, kind, offset, limit, bypassCache,
            )
        torBox.listPage(kind, offset, limit, bypassCache).map { raw ->
            val rows = filterExcluded(raw, preferences.currentExcludeWords())
            DownloadPage(rows, raw.size < limit, fetchedCount = raw.size)
        }
    }

    override suspend fun delete(provider: DebridProvider, id: String): Result<Unit> =
        provider.delete(id)

    override suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> =
        provider.delete(DownloadRef(download.kind, download.id))

    override suspend fun deleteMany(
        provider: DebridProvider,
        downloads: List<DebridDownload>,
    ): List<BatchDeleteItemResult> = withContext(ioDispatcher) {
        super<TroveRepository>.deleteMany(provider, downloads)
    }

    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String): Result<StreamUrl> =
        provider.getStreamUrl(DownloadRef(download.kind, download.id), fileId)

    override suspend fun checkCachedEverywhere(hash: String): List<ServiceCacheResult> =
        withContext(ioDispatcher) { coreCheckCachedEverywhere(signedInProviders(), hash) }

    override suspend fun checkCached(provider: DebridProvider, hash: String): Result<Map<String, CachedInfo>?> =
        provider.checkCached(listOf(hash))

    /** Downloads a .torrent/.nzb URL to bytes so it can be added as a
     *  file. No auth header — these are public links. */
    override suspend fun downloadFileBytes(url: String): Result<ByteArray> = try {
        Result.success(transport.executeBytes(
            app.trove.debrid.core.HttpRequest(method = "GET", url = url),
        ))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Classifies shared input, then resolves URL-only cases that need
     *  a fetch (TorrentUrl/NzbUrl) into file adds. */
    override suspend fun addClassified(
        provider: DebridProvider,
        classified: ClassifiedShare,
    ): Result<DebridDownload> = try {
        val input: AddInput = when (classified) {
            is ClassifiedShare.Magnet -> AddInput.Magnet(classified.magnetUri)
            is ClassifiedShare.TorrentFile -> AddInput.TorrentFile(classified.bytes, classified.fileName)
            is ClassifiedShare.Nzb -> AddInput.Nzb(classified.bytes, classified.fileName)
            is ClassifiedShare.HosterLink -> AddInput.HosterLink(classified.url)
            is ClassifiedShare.TorrentUrl -> {
                val bytes = downloadFileBytes(classified.url).getOrThrow()
                AddInput.TorrentFile(bytes, classified.fileName)
            }
            is ClassifiedShare.NzbUrl -> {
                val bytes = downloadFileBytes(classified.url).getOrThrow()
                AddInput.Nzb(bytes, classified.fileName)
            }
            is ClassifiedShare.UnsupportedHoster ->
                throw IllegalArgumentException("Unsupported hoster")
            is ClassifiedShare.NotShareable ->
                throw IllegalArgumentException("Nothing to add")
        }
        provider.add(input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Classifies text shared into the app. Hoster support is checked
     *  against the live provider when one is available; without a
     *  provider every http(s) URL is treated as a hoster link and the
     *  Add sheet warns before adding. */
    override suspend fun classifyShared(
        text: String?,
        fileBytes: ByteArray?,
        fileName: String?,
        mimeType: String?,
    ): ClassifiedShare = withContext(ioDispatcher) {
        val provider = provider()
        val hosters = try {
            provider?.hosterDomains() ?: emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        ShareClassifier.classify(
            app.trove.debrid.core.ShareInput(text, fileBytes, fileName, mimeType),
            hosters,
        )
    }
}
