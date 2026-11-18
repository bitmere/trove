package app.trove.debrid.data

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.ServiceCacheResult
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.core.checkCachedEverywhere as coreCheckCachedEverywhere
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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

/** The seam the ViewModels program against, so tests can use a fake
 *  without an Android Context. The production implementation is
 *  [DebridRepository]. */
interface TroveRepository {
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
    suspend fun loadBackupSignIn(): BackupSignIn? = null
    suspend fun restoreBackupSignIn(signIn: BackupSignIn): Unit = error("Backup sign-in unavailable")
    suspend fun claimClipboardOffer(text: String?): Boolean = false
    suspend fun resolveMetadata(query: app.trove.debrid.core.MediaQuery): app.trove.debrid.core.MediaMetadata? = null
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
    suspend fun validateAndSaveRealDebridToken(token: String): Result<DebridAccount> =
        Result.failure(UnsupportedOperationException("Real-Debrid sign-in unavailable"))
    fun signOutRealDebrid() = Unit
    suspend fun validateAndSaveAllDebridKey(key: String): Result<DebridAccount> =
        Result.failure(UnsupportedOperationException("AllDebrid sign-in unavailable"))
    fun signOutAllDebrid() = Unit
    suspend fun validateAndSavePremiumizeKey(key: String): Result<DebridAccount> =
        Result.failure(UnsupportedOperationException("Premiumize sign-in unavailable"))
    fun signOutPremiumize() = Unit
    suspend fun validateAndSaveOffcloudKey(key: String): Result<DebridAccount> =
        Result.failure(UnsupportedOperationException("Offcloud sign-in unavailable"))
    fun signOutOffcloud() = Unit
    suspend fun validateAndSaveDebridLinkKey(key: String): Result<DebridAccount> =
        Result.failure(UnsupportedOperationException("Debrid-Link sign-in unavailable"))
    fun signOutDebridLink() = Unit
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
    suspend fun validateAndSaveTorboxKey(key: String): Result<DebridAccount>
    fun signOutTorbox()
    /** All downloads of every kind this provider holds, newest first.
     *  When [bypassCache] is true the provider is asked to bypass its
     *  server-side list cache; only user-initiated refreshes pass true.
     *  A single method, deliberately NOT a default-delegating pair:
     *  Kotlin `by` delegation forwards un-overridden methods to the
     *  delegate, so a default body calling a 1-arg overload would run
     *  against the delegate and bypass a decorator's override — the
     *  full-suite gate caught exactly that in StartupRegressionTest. */
    suspend fun listDownloads(provider: DebridProvider, bypassCache: Boolean = false): Result<List<DebridDownload>>

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
