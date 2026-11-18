package app.trove.debrid.data

import app.trove.debrid.core.PlayerComponent
import android.content.Context
import android.content.SharedPreferences
import app.trove.debrid.core.AllDebridProvider
import app.trove.debrid.core.ClipboardOffer
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
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.OffcloudProvider
import app.trove.debrid.core.DebridLinkProvider
import app.trove.debrid.core.OkHttpTransport
import app.trove.debrid.core.PremiumizeProvider
import app.trove.debrid.core.RealDebridProvider
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.core.TorBoxProvider
import app.trove.debrid.core.filterExcluded
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns accounts and the active provider. Plain account metadata
 *  (service, label) lives in ordinary SharedPreferences; secrets live
 *  only in the [CredentialStore]. */
class DebridRepository(
    private val prefs: SharedPreferences,
    private val credentialStore: CredentialStore,
    private val transport: HttpTransport = OkHttpTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TroveRepository {

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
    )

    private val _theme = MutableStateFlow(ThemePreference())
    val theme = _theme.asStateFlow()

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_ACCENT = "theme_accent"
        const val TORBOX_ACCOUNT_ID = "torbox-1"
        const val REAL_DEBRID_ACCOUNT_ID = "realdebrid-1"
        const val ALLDEBRID_ACCOUNT_ID = "alldebrid-1"
        const val PREMIUMIZE_ACCOUNT_ID = "premiumize-1"
        const val OFFCLOUD_ACCOUNT_ID = "offcloud-1"
        const val DEBRID_LINK_ACCOUNT_ID = "debridlink-1"
        private const val KEY_ACTIVE_SERVICE = "active_service"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_EXCLUDE_WORDS = "exclude_words"
        private const val KEY_PREFER_WORDS = "prefer_words"
        private const val KEY_DOWNLOAD_SORT = "download_sort"
        private const val KEY_SHOW_CLUTTER_FILES = "show_clutter_files"
        private const val KEY_CLIPBOARD_OFFERS_ENABLED = "clipboard_offers_enabled"
        private const val KEY_OFFERED_CLIPBOARD_HASHES = "offered_clipboard_hashes"
        private const val KEY_PLAYER_PACKAGE = "default_player_package"
        private const val KEY_PLAYER_ACTIVITY = "default_player_activity"
    }

    private fun readTheme(): ThemePreference {
        val saved = prefs.all
        return ThemePreference(
            ThemeMode.fromStored(saved[KEY_THEME_MODE] as? String),
            ThemeAccent.fromStored(saved[KEY_THEME_ACCENT] as? String),
        )
    }

    fun loadStartupTheme() {
        _theme.value = readTheme()
    }

    override suspend fun loadTheme() = withContext(ioDispatcher) { readTheme() }

    override suspend fun saveTheme(theme: ThemePreference) = withContext(ioDispatcher) {
        // Raw Editor API is intentional: the commit() result is checked.
        check(prefs.edit()
            .putString(KEY_THEME_MODE, theme.mode.name)
            .putString(KEY_THEME_ACCENT, theme.accent.name)
            .commit())
        _theme.value = theme
    }

    override suspend fun loadDownloadSort() = withContext(ioDispatcher) {
        app.trove.debrid.core.DownloadSort.fromStored(prefs.getString(KEY_DOWNLOAD_SORT, null))
    }

    override suspend fun saveDownloadSort(sort: app.trove.debrid.core.DownloadSort) = withContext(ioDispatcher) {
        prefs.edit { putString(KEY_DOWNLOAD_SORT, sort.name) }
    }

    override suspend fun loadExcludeWords() = withContext(ioDispatcher) {
        readReleaseWords(KEY_EXCLUDE_WORDS, defaultExcludeWords)
    }

    override suspend fun saveExcludeWords(words: List<String>) =
        writeReleaseWords(KEY_EXCLUDE_WORDS, words)

    override suspend fun loadPreferWords() = withContext(ioDispatcher) {
        readReleaseWords(KEY_PREFER_WORDS, defaultPreferWords)
    }

    override suspend fun savePreferWords(words: List<String>) =
        writeReleaseWords(KEY_PREFER_WORDS, words)

    // An absent key uses the defaults; an empty string represents a cleared list.
    private fun readReleaseWords(key: String, defaults: List<String>): List<String> =
        prefs.getString(key, null)?.let { normalizeReleaseWords(listOf(it)) } ?: defaults

    private suspend fun writeReleaseWords(key: String, words: List<String>) = withContext(ioDispatcher) {
        prefs.edit { putString(key, normalizeReleaseWords(words).joinToString("\n")) }
    }

    override suspend fun loadShowClutterFiles() = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_SHOW_CLUTTER_FILES, false)
    }

    override suspend fun saveShowClutterFiles(show: Boolean) = withContext(ioDispatcher) {
        prefs.edit { putBoolean(KEY_SHOW_CLUTTER_FILES, show) }
    }

    override suspend fun loadClipboardOffersEnabled() = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, true)
    }

    override suspend fun saveClipboardOffersEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            synchronized(prefs) {
                // Raw Editor API is intentional: the commit() result is checked.
                check(prefs.edit().putBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, enabled).commit())
            }
        }
    }

    override suspend fun claimClipboardOffer(text: String?): Boolean = withContext(ioDispatcher) {
        synchronized(prefs) {
            if (!prefs.getBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, true)) return@synchronized false
            val offered = prefs.getStringSet(KEY_OFFERED_CLIPBOARD_HASHES, null).orEmpty()
            val hash = ClipboardOffer.newLinkHash(text, offered) ?: return@synchronized false
            // Raw Editor API is intentional: commit() is this function's Boolean result.
            prefs.edit().putStringSet(KEY_OFFERED_CLIPBOARD_HASHES, offered + hash).commit()
        }
    }

    // Acquire before dispatching: an immediate Play waits for an in-flight settings write.
    private val playerPreferencesMutex = Mutex()

    override suspend fun defaultPlayer(): PlayerComponent? = playerPreferencesMutex.withLock {
        withContext(ioDispatcher) {
            val packageName = prefs.getString(KEY_PLAYER_PACKAGE, null)
            val activityName = prefs.getString(KEY_PLAYER_ACTIVITY, null)
            if (packageName.isNullOrBlank() || activityName.isNullOrBlank()) null
            else PlayerComponent(packageName, activityName)
        }
    }

    override suspend fun setDefaultPlayer(player: PlayerComponent?) = playerPreferencesMutex.withLock {
        withContext(ioDispatcher) {
            prefs.edit {
                if (player == null) {
                    remove(KEY_PLAYER_PACKAGE)
                    remove(KEY_PLAYER_ACTIVITY)
                } else {
                    putString(KEY_PLAYER_PACKAGE, player.packageName)
                    putString(KEY_PLAYER_ACTIVITY, player.activityName)
                }
            }
        }
    }

    private val metadataResolver = app.trove.debrid.core.CinemetaResolver(transport)

    override suspend fun resolveMetadata(query: app.trove.debrid.core.MediaQuery) =
        withContext(ioDispatcher) { metadataResolver.resolve(query) }

    override fun accounts(): List<DebridAccount> = readStored()

    override suspend fun loadConnection(): SavedConnection = withContext(ioDispatcher) {
        synchronized(this@DebridRepository) {
            val activeProvider = provider()
            SavedConnection(
                accounts().firstOrNull { it.service == activeProvider?.service },
                activeProvider != null,
                activeProvider,
            )
        }
    }

    override fun torboxAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.TORBOX }

    override fun hasTorboxKey(): Boolean = credentialStore.has(TORBOX_ACCOUNT_ID)

    override fun realDebridAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.REAL_DEBRID }

    override fun hasRealDebridKey(): Boolean = credentialStore.has(REAL_DEBRID_ACCOUNT_ID)

    override fun allDebridAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.ALLDEBRID }

    override fun hasAllDebridKey(): Boolean = credentialStore.has(ALLDEBRID_ACCOUNT_ID)

    override fun premiumizeAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.PREMIUMIZE }

    override fun hasPremiumizeKey(): Boolean = credentialStore.has(PREMIUMIZE_ACCOUNT_ID)

    override fun offcloudAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.OFFCLOUD }

    override fun debridLinkAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.DEBRID_LINK }

    override fun hasOffcloudKey(): Boolean = credentialStore.has(OFFCLOUD_ACCOUNT_ID)
    override fun hasDebridLinkKey(): Boolean = credentialStore.has(DEBRID_LINK_ACCOUNT_ID)

    override suspend fun loadBackupSignIn(): BackupSignIn? = withContext(ioDispatcher) {
        synchronized(this@DebridRepository) {
            if (!hasTorboxKey()) return@synchronized null
            val account = torboxAccount() ?: return@synchronized null
            val key = credentialStore.get(TORBOX_ACCOUNT_ID) ?: return@synchronized null
            BackupSignIn(key, account.label, account.addedAtEpochMs, account.lastValidatedEpochMs)
        }
    }

    override suspend fun restoreBackupSignIn(signIn: BackupSignIn) = withContext(ioDispatcher) {
        val account = DebridAccount(
            id = TORBOX_ACCOUNT_ID,
            service = DebridService.TORBOX,
            label = signIn.label,
            addedAtEpochMs = signIn.addedAt,
            lastValidatedEpochMs = signIn.lastValidated,
        )
        storeTorboxAccount(signIn.key, account)
    }

    @Synchronized
    private fun storeTorboxAccount(key: String, account: DebridAccount) {
        val accounts = readStored().filter { it.id != TORBOX_ACCOUNT_ID } + account
        credentialStore.save(TORBOX_ACCOUNT_ID, key)
        writeStored(accounts)
    }

    private fun credential(service: DebridService): String? = when (service) {
        DebridService.TORBOX -> credentialStore.get(TORBOX_ACCOUNT_ID)
        DebridService.REAL_DEBRID -> credentialStore.get(REAL_DEBRID_ACCOUNT_ID)
        DebridService.ALLDEBRID -> credentialStore.get(ALLDEBRID_ACCOUNT_ID)
        DebridService.PREMIUMIZE -> credentialStore.get(PREMIUMIZE_ACCOUNT_ID)
        DebridService.OFFCLOUD -> credentialStore.get(OFFCLOUD_ACCOUNT_ID)
        DebridService.DEBRID_LINK -> credentialStore.get(DEBRID_LINK_ACCOUNT_ID)
        else -> null
    }

    private fun preferredService(): DebridService {
        val storedService = prefs.getString(KEY_ACTIVE_SERVICE, null)
        return DebridService.entries.firstOrNull { it.name == storedService } ?: DebridService.TORBOX
    }

    private fun activeCredential(): Pair<DebridService, String>? {
        val preferred = preferredService()
        credential(preferred)?.let { return preferred to it }
        return debridServiceOrder.firstNotNullOfOrNull { service ->
            if (service == preferred) null else credential(service)?.let { service to it }
        }
    }

    @Synchronized
    override fun activeService(): DebridService? = activeCredential()?.first

    override suspend fun setActiveService(service: DebridService) = withContext(ioDispatcher) {
        synchronized(this@DebridRepository) {
            if (credential(service) == null) return@synchronized
            prefs.edit { putString(KEY_ACTIVE_SERVICE, service.name) }
        }
    }

    private class CachedProvider(val credential: String, val provider: DebridProvider)
    private val providers = mutableMapOf<DebridService, CachedProvider>()

    /** Retain each service's rate budgets and hoster cache across switches. */
    @Synchronized
    override fun provider(): DebridProvider? {
        val preferred = preferredService()
        return providerFor(preferred) ?: debridServiceOrder.firstNotNullOfOrNull { service ->
            if (service == preferred) null else providerFor(service)
        }
    }

    override suspend fun loadProviderFor(service: DebridService): DebridProvider? =
        withContext(ioDispatcher) { providerFor(service) }

    @Synchronized
    override fun providerFor(service: DebridService): DebridProvider? {
        val key = credential(service) ?: return null
        providers[service]?.takeIf { it.credential == key }?.let { return it.provider }
        val provider = when (service) {
            DebridService.TORBOX -> TorBoxProvider(key, transport)
            DebridService.REAL_DEBRID -> RealDebridProvider(key, transport)
            DebridService.ALLDEBRID -> AllDebridProvider(key, transport)
            DebridService.PREMIUMIZE -> PremiumizeProvider(key, transport)
            DebridService.OFFCLOUD -> OffcloudProvider(key, transport)
            DebridService.DEBRID_LINK -> DebridLinkProvider(key, transport)
            else -> return null
        }
        providers[service] = CachedProvider(key, provider)
        return provider
    }

    /** Validates the key against TorBox, then stores it. The whole
     *  operation — network, encrypted store and metadata — runs off
     *  Main, and every ordinary failure comes back as a failed
     *  [Result], never as a thrown exception: a function declared to
     *  return Result must not throw. Cancellation still cancels.
     *  A rejected key leaves any existing account untouched. */
    override suspend fun validateAndSaveTorboxKey(key: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = key.trim()
                require(trimmed.isNotEmpty()) { "Key is empty" }
                val provider = TorBoxProvider(trimmed, transport)
                val info = provider.validateAccount().getOrThrow()
                val account = DebridAccount(
                    id = TORBOX_ACCOUNT_ID,
                    service = DebridService.TORBOX,
                    label = "TorBox (${info.detail ?: "Essential"})",
                    addedAtEpochMs = System.currentTimeMillis(),
                    lastValidatedEpochMs = System.currentTimeMillis(),
                )
                storeTorboxAccount(trimmed, account)
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun validateAndSaveRealDebridToken(token: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = token.trim()
                require(trimmed.isNotEmpty()) { "Token is empty" }
                val info = RealDebridProvider(trimmed, transport).validateAccount().getOrThrow()
                val now = System.currentTimeMillis()
                val account = DebridAccount(
                    id = REAL_DEBRID_ACCOUNT_ID,
                    service = DebridService.REAL_DEBRID,
                    label = "Real-Debrid (${info.detail})",
                    addedAtEpochMs = now,
                    lastValidatedEpochMs = now,
                )
                synchronized(this@DebridRepository) {
                    val accounts = readStored().filter { it.id != REAL_DEBRID_ACCOUNT_ID } + account
                    credentialStore.save(REAL_DEBRID_ACCOUNT_ID, trimmed)
                    prefs.edit {
                        putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
                        putString(KEY_ACTIVE_SERVICE, DebridService.REAL_DEBRID.name)
                    }
                }
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun validateAndSaveAllDebridKey(key: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = key.trim()
                require(trimmed.isNotEmpty()) { "Key is empty" }
                val info = AllDebridProvider(trimmed, transport).validateAccount().getOrThrow()
                val now = System.currentTimeMillis()
                val account = DebridAccount(
                    id = ALLDEBRID_ACCOUNT_ID,
                    service = DebridService.ALLDEBRID,
                    label = "AllDebrid (${info.detail})",
                    addedAtEpochMs = now,
                    lastValidatedEpochMs = now,
                )
                synchronized(this@DebridRepository) {
                    val accounts = readStored().filter { it.id != ALLDEBRID_ACCOUNT_ID } + account
                    credentialStore.save(ALLDEBRID_ACCOUNT_ID, trimmed)
                    prefs.edit {
                        putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
                        putString(KEY_ACTIVE_SERVICE, DebridService.ALLDEBRID.name)
                    }
                }
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun validateAndSavePremiumizeKey(key: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = key.trim()
                require(trimmed.isNotEmpty()) { "Key is empty" }
                val info = PremiumizeProvider(trimmed, transport).validateAccount().getOrThrow()
                val now = System.currentTimeMillis()
                val account = DebridAccount(
                    id = PREMIUMIZE_ACCOUNT_ID,
                    service = DebridService.PREMIUMIZE,
                    label = "Premiumize (${info.detail})",
                    addedAtEpochMs = now,
                    lastValidatedEpochMs = now,
                )
                synchronized(this@DebridRepository) {
                    val accounts = readStored().filter { it.id != PREMIUMIZE_ACCOUNT_ID } + account
                    credentialStore.save(PREMIUMIZE_ACCOUNT_ID, trimmed)
                    prefs.edit {
                        putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
                        putString(KEY_ACTIVE_SERVICE, DebridService.PREMIUMIZE.name)
                    }
                }
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun validateAndSaveOffcloudKey(key: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = key.trim()
                require(trimmed.isNotEmpty()) { "Key is empty" }
                OffcloudProvider(trimmed, transport).validateAccount().getOrThrow()
                val now = System.currentTimeMillis()
                val account = DebridAccount(
                    id = OFFCLOUD_ACCOUNT_ID,
                    service = DebridService.OFFCLOUD,
                    label = "Offcloud",
                    addedAtEpochMs = now,
                    lastValidatedEpochMs = now,
                )
                synchronized(this@DebridRepository) {
                    val accounts = readStored().filter { it.id != OFFCLOUD_ACCOUNT_ID } + account
                    credentialStore.save(OFFCLOUD_ACCOUNT_ID, trimmed)
                    prefs.edit {
                        putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
                        putString(KEY_ACTIVE_SERVICE, DebridService.OFFCLOUD.name)
                    }
                }
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun validateAndSaveDebridLinkKey(key: String): Result<DebridAccount> =
        withContext(ioDispatcher) {
            try {
                val trimmed = key.trim()
                require(trimmed.isNotEmpty()) { "Key is empty" }
                val info = DebridLinkProvider(trimmed, transport).validateAccount().getOrThrow()
                val now = System.currentTimeMillis()
                val account = DebridAccount(
                    id = DEBRID_LINK_ACCOUNT_ID,
                    service = DebridService.DEBRID_LINK,
                    label = "Debrid-Link (${info.detail})",
                    addedAtEpochMs = now,
                    lastValidatedEpochMs = now,
                )
                synchronized(this@DebridRepository) {
                    val accounts = readStored().filter { it.id != DEBRID_LINK_ACCOUNT_ID } + account
                    credentialStore.save(DEBRID_LINK_ACCOUNT_ID, trimmed)
                    prefs.edit {
                        putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
                        putString(KEY_ACTIVE_SERVICE, DebridService.DEBRID_LINK.name)
                    }
                }
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override fun signOutTorbox() = signOut(DebridService.TORBOX, TORBOX_ACCOUNT_ID)

    override fun signOutRealDebrid() = signOut(DebridService.REAL_DEBRID, REAL_DEBRID_ACCOUNT_ID)
    override fun signOutAllDebrid() = signOut(DebridService.ALLDEBRID, ALLDEBRID_ACCOUNT_ID)
    override fun signOutPremiumize() = signOut(DebridService.PREMIUMIZE, PREMIUMIZE_ACCOUNT_ID)
    override fun signOutOffcloud() = signOut(DebridService.OFFCLOUD, OFFCLOUD_ACCOUNT_ID)
    override fun signOutDebridLink() = signOut(DebridService.DEBRID_LINK, DEBRID_LINK_ACCOUNT_ID)

    @Synchronized
    private fun signOut(service: DebridService, accountId: String) {
        val wasPreferred = preferredService() == service
        providers.remove(service)
        credentialStore.delete(accountId)
        val remainingAccounts = readStored().filter { it.id != accountId }
        val fallback = if (wasPreferred) activeService() else null
        prefs.edit {
            putString(KEY_ACCOUNTS, StoredAccounts.encode(remainingAccounts))
            if (wasPreferred) {
                if (fallback == null) remove(KEY_ACTIVE_SERVICE)
                else putString(KEY_ACTIVE_SERVICE, fallback.name)
            }
        }
    }

    override suspend fun listDownloads(provider: DebridProvider): Result<List<DebridDownload>> =
        withContext(ioDispatcher) {
            provider.listDownloads().map { downloads ->
                filterExcluded(downloads, readReleaseWords(KEY_EXCLUDE_WORDS, defaultExcludeWords))
            }
        }

    override suspend fun delete(provider: DebridProvider, id: String): Result<Unit> =
        provider.delete(id)

    override suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> =
        if (provider is TorBoxProvider) provider.delete(download) else provider.delete(download.id)

    override suspend fun deleteMany(
        provider: DebridProvider,
        downloads: List<DebridDownload>,
    ): List<BatchDeleteItemResult> = withContext(ioDispatcher) {
        super<TroveRepository>.deleteMany(provider, downloads)
    }

    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String): Result<StreamUrl> =
        if (provider is TorBoxProvider) provider.getStreamUrl(download, fileId)
        else provider.getStreamUrl(download.id, fileId)

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
            when (provider) {
                is TorBoxProvider -> provider.hosterDomains()
                is RealDebridProvider -> provider.hosterDomains()
                is AllDebridProvider -> provider.hosterDomains()
                is PremiumizeProvider -> provider.hosterDomains()
                is DebridLinkProvider -> provider.hosterDomains()
                else -> emptySet()
            }
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

    private fun readStored(): List<DebridAccount> =
        StoredAccounts.decode(prefs.getString(KEY_ACCOUNTS, null))

    private fun writeStored(accounts: List<DebridAccount>) {
        prefs.edit { putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts)) }
    }
}
