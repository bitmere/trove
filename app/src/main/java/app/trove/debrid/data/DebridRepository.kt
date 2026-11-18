package app.trove.debrid.data

import app.trove.debrid.core.PlayerComponent
import android.content.Context
import android.content.SharedPreferences
import app.trove.debrid.core.AddInput
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.CredentialStore
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.OkHttpTransport
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.core.TorBoxProvider
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    companion object {
        const val TORBOX_ACCOUNT_ID = "torbox-1"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_DOWNLOAD_SORT = "download_sort"
        private const val KEY_PLAYER_PACKAGE = "default_player_package"
        private const val KEY_PLAYER_ACTIVITY = "default_player_activity"
    }

    override suspend fun loadDownloadSort() = withContext(ioDispatcher) {
        app.trove.debrid.core.DownloadSort.fromStored(prefs.getString(KEY_DOWNLOAD_SORT, null))
    }

    override suspend fun saveDownloadSort(sort: app.trove.debrid.core.DownloadSort) = withContext(ioDispatcher) {
        prefs.edit { putString(KEY_DOWNLOAD_SORT, sort.name) }
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
        SavedConnection(torboxAccount(), hasTorboxKey(), provider())
    }

    override fun torboxAccount(): DebridAccount? =
        accounts().firstOrNull { it.service == DebridService.TORBOX }

    override fun hasTorboxKey(): Boolean = credentialStore.has(TORBOX_ACCOUNT_ID)

    private var activeKey: String? = null
    private var activeProvider: TorBoxProvider? = null

    /** Retain rate budgets and hoster cache for the active credential. */
    @Synchronized
    override fun provider(): DebridProvider? {
        val key = credentialStore.get(TORBOX_ACCOUNT_ID)
        if (key != activeKey) {
            activeKey = key
            activeProvider = key?.let { TorBoxProvider(it, transport) }
        }
        return activeProvider
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
                credentialStore.save(TORBOX_ACCOUNT_ID, trimmed)
                val account = DebridAccount(
                    id = TORBOX_ACCOUNT_ID,
                    service = DebridService.TORBOX,
                    label = "TorBox (${info.detail ?: "Essential"})",
                    addedAtEpochMs = System.currentTimeMillis(),
                    lastValidatedEpochMs = System.currentTimeMillis(),
                )
                writeStored(
                    readStored().filter { it.id != TORBOX_ACCOUNT_ID } + account,
                )
                Result.success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    @Synchronized
    override fun signOutTorbox() {
        activeKey = null
        activeProvider = null
        credentialStore.delete(TORBOX_ACCOUNT_ID)
        writeStored(readStored().filter { it.id != TORBOX_ACCOUNT_ID })
    }

    override suspend fun listDownloads(provider: DebridProvider): Result<List<DebridDownload>> =
        provider.listDownloads()

    override suspend fun delete(provider: DebridProvider, id: String): Result<Unit> =
        provider.delete(id)

    override suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> =
        if (provider is TorBoxProvider) provider.delete(download) else provider.delete(download.id)

    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String): Result<StreamUrl> =
        if (provider is TorBoxProvider) provider.getStreamUrl(download, fileId)
        else provider.getStreamUrl(download.id, fileId)

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
        val hosters = if (provider is TorBoxProvider) {
            try { provider.hosterDomains() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptySet() }
        } else {
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
