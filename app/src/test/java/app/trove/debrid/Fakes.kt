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
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.ProviderCapabilities
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.ShareInput
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.data.SavedConnection
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultExcludeWords
import app.trove.debrid.data.defaultPreferWords
import app.trove.debrid.data.normalizeReleaseWords

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
    override val service: DebridService = DebridService.TORBOX,
) : DebridProvider {
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        authFlow = AuthFlow.API_KEY,
        streamingApi = true,
        readOnlyCacheCheck = true,
        multiFilePick = true,
        nzb = true,
    )
    var cacheDelayMs: Long = 0
    var addFailure: DebridError? = null
    var playFailure: Exception? = null
    val playedFileIds = mutableListOf<String>()
    var throwOnPlay: Boolean = false
    var deletedIds = mutableListOf<String>()
    val deleteFailures = mutableMapOf<String, Exception>()
    var addedInputs = mutableListOf<AddInput>()

    override suspend fun validateAccount() =
        Result.success(AccountInfo(username = "user@example.com", isPremium = false, detail = "Essential"))

    override suspend fun listDownloads() = Result.success(downloads.toList())

    override suspend fun getDownload(id: String) =
        Result.success(downloads.first { it.id == id })

    override suspend fun add(input: AddInput): Result<DebridDownload> {
        addedInputs += input
        addFailure?.let { return Result.failure(it) }
        val added = sampleDownload(id = "99", name = "Added").copy(service = service)
        downloads += added
        return Result.success(added)
    }

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> {
        kotlinx.coroutines.delay(cacheDelayMs)
        return Result.success(cached)
    }

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> {
        playedFileIds += fileId
        if (throwOnPlay) throw IllegalStateException("boom")
        return playFailure?.let { Result.failure(it) } ?: Result.success(StreamUrl(url = playUrl))
    }

    override suspend fun control(id: String, action: app.trove.debrid.core.ControlAction) =
        Result.failure<Unit>(DebridError.UnsupportedCapability("control"))

    override suspend fun delete(id: String): Result<Unit> {
        deletedIds += id
        deleteFailures[id]?.let { return Result.failure(it) }
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
    var realDebridProvider: FakeProvider = FakeProvider(service = DebridService.REAL_DEBRID),
    var allDebridProvider: FakeProvider = FakeProvider(service = DebridService.ALLDEBRID),
    var premiumizeProvider: FakeProvider = FakeProvider(service = DebridService.PREMIUMIZE),
    var offcloudProvider: FakeProvider = FakeProvider(service = DebridService.OFFCLOUD),
    var debridlinkProvider: FakeProvider = FakeProvider(service = DebridService.DEBRID_LINK),
) : TroveRepository {
    var savedExcludeWords = defaultExcludeWords
    var savedPreferWords = defaultPreferWords
    var savedSort = DownloadSort.NEWEST

    override suspend fun loadDownloadSort() = savedSort
    override suspend fun saveDownloadSort(sort: DownloadSort) { savedSort = sort }

    override suspend fun loadExcludeWords() = savedExcludeWords
    override suspend fun saveExcludeWords(words: List<String>) {
        savedExcludeWords = normalizeReleaseWords(words)
    }

    override suspend fun loadPreferWords() = savedPreferWords
    override suspend fun savePreferWords(words: List<String>) {
        savedPreferWords = normalizeReleaseWords(words)
    }

    var savedShowClutterFiles = false
    override suspend fun loadShowClutterFiles() = savedShowClutterFiles
    override suspend fun saveShowClutterFiles(show: Boolean) {
        savedShowClutterFiles = show
    }

    var savedPlayer: app.trove.debrid.core.PlayerComponent? = null
    override suspend fun defaultPlayer() = savedPlayer
    override suspend fun setDefaultPlayer(player: app.trove.debrid.core.PlayerComponent?) { savedPlayer = player }
    var savedKey: String? = null
    var addedClassified = mutableListOf<ClassifiedShare>()
    var validateCalls: Int = 0

    private var savedRealDebridToken: String? = null
    private var savedAllDebridKey: String? = null
    private var savedPremiumizeKey: String? = null
    private var savedOffcloudKey: String? = null
    private var savedDebridLinkKey: String? = null
    private var selectedService: DebridService? = DebridService.TORBOX

    override fun accounts(): List<DebridAccount> = listOfNotNull(
        torboxAccount(), realDebridAccount(), allDebridAccount(), premiumizeAccount(), offcloudAccount(), debridLinkAccount(),
    )

    override fun torboxAccount(): DebridAccount? = if (hasTorboxKey()) {
        DebridAccount("torbox-1", DebridService.TORBOX, "TorBox (Essential)", 1L, 1L)
    } else null

    override fun realDebridAccount(): DebridAccount? = if (hasRealDebridKey()) {
        DebridAccount("realdebrid-1", DebridService.REAL_DEBRID, "Real-Debrid (Premium)", 1L, 1L)
    } else null

    override fun allDebridAccount(): DebridAccount? = if (hasAllDebridKey()) {
        DebridAccount("alldebrid-1", DebridService.ALLDEBRID, "AllDebrid (Premium)", 1L, 1L)
    } else null

    override fun premiumizeAccount(): DebridAccount? = if (hasPremiumizeKey()) {
        DebridAccount("premiumize-1", DebridService.PREMIUMIZE, "Premiumize (Premium)", 1L, 1L)
    } else null

    override fun offcloudAccount(): DebridAccount? = if (hasOffcloudKey()) {
        DebridAccount("offcloud-1", DebridService.OFFCLOUD, "Offcloud", 1L, 1L)
    } else null

    override fun debridLinkAccount(): DebridAccount? = if (hasDebridLinkKey()) {
        DebridAccount("debridlink-1", DebridService.DEBRID_LINK, "Debrid-Link (Premium)", 1L, 1L)
    } else null

    override fun hasTorboxKey(): Boolean = savedKey != null
    override fun hasRealDebridKey(): Boolean = savedRealDebridToken != null
    override fun hasAllDebridKey(): Boolean = savedAllDebridKey != null
    override fun hasPremiumizeKey(): Boolean = savedPremiumizeKey != null
    override fun hasOffcloudKey(): Boolean = savedOffcloudKey != null
    override fun hasDebridLinkKey(): Boolean = savedDebridLinkKey != null

    private fun hasCredential(service: DebridService?): Boolean = when (service) {
        DebridService.TORBOX -> hasTorboxKey()
        DebridService.REAL_DEBRID -> hasRealDebridKey()
        DebridService.ALLDEBRID -> hasAllDebridKey()
        DebridService.PREMIUMIZE -> hasPremiumizeKey()
        DebridService.OFFCLOUD -> hasOffcloudKey()
        DebridService.DEBRID_LINK -> hasDebridLinkKey()
        else -> false
    }

    override fun activeService(): DebridService? = when {
        hasCredential(selectedService) -> selectedService
        hasTorboxKey() -> DebridService.TORBOX
        hasRealDebridKey() -> DebridService.REAL_DEBRID
        hasAllDebridKey() -> DebridService.ALLDEBRID
        hasPremiumizeKey() -> DebridService.PREMIUMIZE
        hasOffcloudKey() -> DebridService.OFFCLOUD
        hasDebridLinkKey() -> DebridService.DEBRID_LINK
        else -> null
    }

    override suspend fun setActiveService(service: DebridService) {
        if (hasCredential(service)) selectedService = service
    }

    override fun provider(): DebridProvider? = when (activeService() ?: selectedService) {
        DebridService.REAL_DEBRID -> realDebridProvider
        DebridService.ALLDEBRID -> allDebridProvider
        DebridService.PREMIUMIZE -> premiumizeProvider
        DebridService.OFFCLOUD -> offcloudProvider
        DebridService.DEBRID_LINK -> debridlinkProvider
        // Existing ViewModel fixtures inject a TorBox provider without seeding a key.
        DebridService.TORBOX -> provider
        else -> null
    }

    override fun providerFor(service: DebridService): DebridProvider? = when (service) {
        // Existing ViewModel fixtures inject a TorBox provider without seeding a key.
        DebridService.TORBOX -> provider
        DebridService.REAL_DEBRID -> realDebridProvider.takeIf { hasRealDebridKey() }
        DebridService.ALLDEBRID -> allDebridProvider.takeIf { hasAllDebridKey() }
        DebridService.PREMIUMIZE -> premiumizeProvider.takeIf { hasPremiumizeKey() }
        DebridService.OFFCLOUD -> offcloudProvider.takeIf { hasOffcloudKey() }
        DebridService.DEBRID_LINK -> debridlinkProvider.takeIf { hasDebridLinkKey() }
    }

    override suspend fun loadConnection(): SavedConnection {
        val service = activeService()
        return SavedConnection(accounts().firstOrNull { it.service == service }, hasCredential(service), provider())
    }

    override suspend fun validateAndSaveRealDebridToken(token: String): Result<DebridAccount> {
        val trimmed = token.trim()
        if (trimmed.isEmpty() || trimmed == "bad-token") return Result.failure(DebridError.AuthInvalid())
        savedRealDebridToken = trimmed
        selectedService = DebridService.REAL_DEBRID
        return Result.success(checkNotNull(realDebridAccount()))
    }

    override fun signOutRealDebrid() {
        savedRealDebridToken = null
        if (selectedService == DebridService.REAL_DEBRID) selectedService = activeService()
    }

    override suspend fun validateAndSaveAllDebridKey(key: String): Result<DebridAccount> {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == "bad-key") return Result.failure(DebridError.AuthInvalid())
        savedAllDebridKey = trimmed
        selectedService = DebridService.ALLDEBRID
        return Result.success(checkNotNull(allDebridAccount()))
    }

    override fun signOutAllDebrid() {
        savedAllDebridKey = null
        if (selectedService == DebridService.ALLDEBRID) selectedService = activeService()
    }

    override suspend fun validateAndSavePremiumizeKey(key: String): Result<DebridAccount> {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == "bad-key") return Result.failure(DebridError.AuthInvalid())
        savedPremiumizeKey = trimmed
        selectedService = DebridService.PREMIUMIZE
        return Result.success(checkNotNull(premiumizeAccount()))
    }

    override fun signOutPremiumize() {
        savedPremiumizeKey = null
        if (selectedService == DebridService.PREMIUMIZE) selectedService = activeService()
    }

    override suspend fun validateAndSaveOffcloudKey(key: String): Result<DebridAccount> {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == "bad-key") return Result.failure(DebridError.AuthInvalid())
        savedOffcloudKey = trimmed
        selectedService = DebridService.OFFCLOUD
        return Result.success(checkNotNull(offcloudAccount()))
    }

    override suspend fun validateAndSaveDebridLinkKey(key: String): Result<DebridAccount> {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == "bad-key") return Result.failure(DebridError.AuthInvalid())
        savedDebridLinkKey = trimmed
        selectedService = DebridService.DEBRID_LINK
        return Result.success(checkNotNull(debridLinkAccount()))
    }

    override fun signOutOffcloud() {
        savedOffcloudKey = null
        if (selectedService == DebridService.OFFCLOUD) selectedService = activeService()
    }

    override fun signOutDebridLink() {
        savedDebridLinkKey = null
        if (selectedService == DebridService.DEBRID_LINK) selectedService = activeService()
    }

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
        if (selectedService == DebridService.TORBOX) selectedService = activeService()
    }

    override suspend fun listDownloads(provider: DebridProvider, bypassCache: Boolean) = provider.listDownloads()
    override suspend fun delete(provider: DebridProvider, id: String) = provider.delete(id)

    override suspend fun deleteMany(provider: DebridProvider, downloads: List<DebridDownload>) =
        super<TroveRepository>.deleteMany(provider, downloads)
    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String) =
        provider.getStreamUrl(download.id, fileId)

    override suspend fun checkCached(provider: DebridProvider, hash: String): Result<Map<String, CachedInfo>?> =
        Result.success(cachedForHash)

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
