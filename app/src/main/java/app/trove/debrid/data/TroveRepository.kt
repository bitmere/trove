package app.trove.debrid.data

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.StreamUrl

/** No secrets: metadata and a provider handle read together off Main in production. */
data class SavedConnection(
    val account: DebridAccount?,
    val hasKey: Boolean,
    val provider: DebridProvider?,
)

/** The seam the ViewModels program against, so tests can use a fake
 *  without an Android Context. The production implementation is
 *  [DebridRepository]. */
interface TroveRepository {
    suspend fun defaultPlayer(): PlayerComponent? = null
    suspend fun setDefaultPlayer(player: PlayerComponent?) = Unit
    suspend fun loadDownloadSort(): app.trove.debrid.core.DownloadSort = app.trove.debrid.core.DownloadSort.NEWEST
    suspend fun saveDownloadSort(sort: app.trove.debrid.core.DownloadSort) = Unit
    suspend fun resolveMetadata(query: app.trove.debrid.core.MediaQuery): app.trove.debrid.core.MediaMetadata? = null
    fun accounts(): List<DebridAccount>
    fun torboxAccount(): DebridAccount?
    fun hasTorboxKey(): Boolean
    fun provider(): DebridProvider?
    /** Production overrides this to dispatch disk and Keystore access to IO. */
    suspend fun loadConnection(): SavedConnection =
        SavedConnection(torboxAccount(), hasTorboxKey(), provider())
    suspend fun validateAndSaveTorboxKey(key: String): Result<DebridAccount>
    fun signOutTorbox()
    suspend fun listDownloads(provider: DebridProvider): Result<List<DebridDownload>>
    suspend fun delete(provider: DebridProvider, id: String): Result<Unit>
    suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> = delete(provider, download.id)
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
