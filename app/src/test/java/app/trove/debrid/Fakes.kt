package app.trove.debrid

import app.trove.debrid.core.AccountInfo
import app.trove.debrid.core.AddInput
import app.trove.debrid.core.AuthFlow
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.ProviderCapabilities
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.ShareInput
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.data.TroveRepository

fun sampleDownload(
    id: String = "11",
    name: String = "Dune Part Two 2024",
    kind: DownloadKind = DownloadKind.TORRENT,
    status: DownloadStatus = DownloadStatus.READY,
    fileCount: Int = 1,
) = DebridDownload(
    id = id,
    service = DebridService.TORBOX,
    name = name,
    kind = kind,
    status = status,
    progress = if (status == DownloadStatus.READY) 1f else 0.5f,
    sizeBytes = 1_000_000L,
    files = (1..fileCount).map {
        app.trove.debrid.core.DebridFile(id = "$it", name = "file$it.mkv", sizeBytes = 1_000L)
    },
    createdAtEpochMs = 1_000L,
)

class FakeProvider(
    var downloads: MutableList<DebridDownload> = mutableListOf(),
    var cached: Map<String, CachedInfo>? = mapOf(
        "aabbcc" to CachedInfo(ready = true, name = "Dune Part Two 2024", sizeBytes = 1_000_000L),
    ),
    var playUrl: String = "https://example.com/play/abc123",
) : DebridProvider {
    override val service: DebridService = DebridService.TORBOX
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        authFlow = AuthFlow.API_KEY,
        streamingApi = true,
        readOnlyCacheCheck = true,
        multiFilePick = true,
        nzb = true,
    )
    var addFailure: DebridError? = null
    var playFailure: Exception? = null
    val playedFileIds = mutableListOf<String>()
    var throwOnPlay: Boolean = false
    var deletedIds = mutableListOf<String>()
    var addedInputs = mutableListOf<AddInput>()

    override suspend fun validateAccount() =
        Result.success(AccountInfo(username = "user@example.com", isPremium = false, detail = "Essential"))

    override suspend fun listDownloads() = Result.success(downloads.toList())

    override suspend fun getDownload(id: String) =
        Result.success(downloads.first { it.id == id })

    override suspend fun add(input: AddInput): Result<DebridDownload> {
        addedInputs += input
        addFailure?.let { return Result.failure(it) }
        val added = sampleDownload(id = "99", name = "Added")
        downloads += added
        return Result.success(added)
    }

    override suspend fun checkCached(hashes: List<String>) = Result.success(cached)

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> {
        playedFileIds += fileId
        if (throwOnPlay) throw IllegalStateException("boom")
        return playFailure?.let { Result.failure(it) } ?: Result.success(StreamUrl(url = playUrl))
    }

    override suspend fun control(id: String, action: app.trove.debrid.core.ControlAction) =
        Result.failure<Unit>(DebridError.UnsupportedCapability("control"))

    override suspend fun delete(id: String): Result<Unit> {
        deletedIds += id
        downloads.removeAll { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun supportsUrl(url: String) = Result.success(true)
}

/** In-memory repository for ViewModel tests. No Android Context. */
class FakeRepository(
    var provider: FakeProvider? = FakeProvider(),
    var cachedForHash: Map<String, CachedInfo>? = mapOf(
        "aabbccddeeff00112233445566778899aabbccdd" to
            CachedInfo(ready = true, name = "Dune Part Two 2024", sizeBytes = 1_000_000L),
    ),
    /** When true, validateAndSaveTorboxKey THROWS instead of returning
     *  a failed Result — the production bug the crash fix guards. */
    var throwOnValidate: Boolean = false,
    /** Artificial delay inside validate, for duplicate-submit tests. */
    var validateDelayMs: Long = 0L,
) : TroveRepository {
    var savedPlayer: app.trove.debrid.core.PlayerComponent? = null
    override suspend fun defaultPlayer() = savedPlayer
    override suspend fun setDefaultPlayer(player: app.trove.debrid.core.PlayerComponent?) { savedPlayer = player }
    var savedKey: String? = null
    var addedClassified = mutableListOf<ClassifiedShare>()
    var validateCalls: Int = 0
    var cacheDelayMs: Long = 0

    override fun accounts(): List<DebridAccount> =
        if (savedKey != null) {
            listOf(
                DebridAccount(
                    id = "torbox-1",
                    service = DebridService.TORBOX,
                    label = "TorBox (Essential)",
                    addedAtEpochMs = 1L,
                    lastValidatedEpochMs = 1L,
                ),
            )
        } else {
            emptyList()
        }

    override fun torboxAccount(): DebridAccount? = accounts().firstOrNull()
    override fun hasTorboxKey(): Boolean = savedKey != null
    override fun provider(): DebridProvider? = provider

    override suspend fun validateAndSaveTorboxKey(key: String): Result<DebridAccount> {
        validateCalls++
        if (validateDelayMs > 0) kotlinx.coroutines.delay(validateDelayMs)
        if (throwOnValidate) throw IllegalStateException("boom")
        if (key == "bad-key") return Result.failure(DebridError.AuthInvalid())
        savedKey = key
        return Result.success(torboxAccount()!!)
    }

    override fun signOutTorbox() {
        savedKey = null
    }

    override suspend fun listDownloads(provider: DebridProvider) = provider.listDownloads()
    override suspend fun delete(provider: DebridProvider, id: String) = provider.delete(id)
    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String) =
        provider.getStreamUrl(download.id, fileId)

    override suspend fun checkCached(provider: DebridProvider, hash: String): Result<Map<String, CachedInfo>?> {
        kotlinx.coroutines.delay(cacheDelayMs)
        return Result.success(cachedForHash)
    }

    override suspend fun addClassified(provider: DebridProvider, classified: ClassifiedShare): Result<DebridDownload> {
        addedClassified += classified
        val input = when (classified) {
            is ClassifiedShare.Magnet -> AddInput.Magnet(classified.magnetUri)
            is ClassifiedShare.TorrentFile -> AddInput.TorrentFile(classified.bytes, classified.fileName)
            is ClassifiedShare.Nzb -> AddInput.Nzb(classified.bytes, classified.fileName)
            is ClassifiedShare.HosterLink -> AddInput.HosterLink(classified.url)
            is ClassifiedShare.TorrentUrl -> AddInput.TorrentFile(ByteArray(0), "x.torrent")
            is ClassifiedShare.NzbUrl -> AddInput.Nzb(ByteArray(0), "x.nzb")
            is ClassifiedShare.UnsupportedHoster -> return Result.failure(IllegalArgumentException("Unsupported hoster"))
            is ClassifiedShare.NotShareable -> return Result.failure(IllegalArgumentException("Nothing to add"))
        }
        return provider.add(input)
    }

    override suspend fun classifyShared(
        text: String?,
        fileBytes: ByteArray?,
        fileName: String?,
        mimeType: String?,
    ): ClassifiedShare = ShareClassifier.classify(
        ShareInput(text, fileBytes, fileName, mimeType),
        emptySet(),
    )

    override suspend fun downloadFileBytes(url: String): Result<ByteArray> =
        Result.success("fake-bytes".toByteArray())
}
