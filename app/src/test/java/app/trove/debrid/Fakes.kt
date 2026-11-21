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
import app.trove.debrid.core.DownloadRef
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.MediaCandidate
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.MediaQuery
import app.trove.debrid.core.ProviderCapabilities
import app.trove.debrid.core.ShareClassifier
import app.trove.debrid.core.ShareInput
import app.trove.debrid.core.SimklDeviceCode
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.SimklPollResult
import app.trove.debrid.core.SimklSyncResult
import app.trove.debrid.core.StreamUrl
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.SavedConnection
import app.trove.debrid.data.SimklSnapshotInfo
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultExcludeWords
import app.trove.debrid.data.defaultPreferWords
import app.trove.debrid.data.normalizeReleaseWords
import app.trove.debrid.ui.settings.providerSectionDescriptors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
    /** When set, getStreamUrl throws this instead of returning a result.
     *  Used to prove a cancelled playback never sticks the resolving state. */
    var throwOnStreamUrl: Exception? = null
    val playedFileIds = mutableListOf<String>()
    var throwOnPlay: Boolean = false
    var deletedIds = mutableListOf<String>()
    val deleteFailures = mutableMapOf<String, Exception>()
    var addedInputs = mutableListOf<AddInput>()

    override suspend fun validateAccount() =
        Result.success(AccountInfo(username = "user@example.com", isPremium = false, detail = "Essential"))

    override suspend fun listDownloads() = Result.success(downloads.toList())

    /** When set, getDownload throws this instead of returning a row. Used to
     *  prove a cancelled/failed lookup never sticks the resolving state. */
    var getDownloadFailure: Exception? = null

    override suspend fun getDownload(id: String): Result<DebridDownload> {
        getDownloadFailure?.let { throw it }
        return Result.success(downloads.first { it.id == id })
    }

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
        throwOnStreamUrl?.let { throw it }
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
open class FakeRepository(
    var provider: FakeProvider? = FakeProvider(),
    var cachedForHash: Map<String, CachedInfo>? = mapOf(
        "aabbccddeeff00112233445566778899aabbccdd" to
            CachedInfo(ready = true, name = "Dune Part Two 2024", sizeBytes = 1_000_000L),
    ),
    /** When true, TorBox validation THROWS instead of returning
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
    var savedTmdbKey: String? = null
    /** When false, the fake rejects TMDB keys at validation time. */
    var tmdbKeyValid: Boolean = true
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

    override suspend fun validateAndSave(
        service: DebridService,
        credential: String,
        factory: (String, HttpTransport) -> DebridProvider,
        label: (AccountInfo?) -> String,
    ): Result<DebridAccount> {
        if (service == DebridService.TORBOX) {
            validateCalls++
            if (validateDelayMs > 0) kotlinx.coroutines.delay(validateDelayMs)
            if (throwOnValidate) throw IllegalStateException("boom")
            if (credential == "bad-key") return Result.failure(DebridError.AuthInvalid())
            savedKey = credential
        } else {
            val trimmed = credential.trim()
            val rejected = if (service == DebridService.REAL_DEBRID) "bad-token" else "bad-key"
            if (trimmed.isEmpty() || trimmed == rejected) return Result.failure(DebridError.AuthInvalid())
            setCredential(service, trimmed)
            selectedService = service
        }
        return Result.success(accounts().first { it.service == service })
    }

    override fun signOut(service: DebridService) {
        setCredential(service, null)
        if (selectedService == service) selectedService = activeService()
    }

    private fun setCredential(service: DebridService, credential: String?) {
        when (service) {
            DebridService.TORBOX -> savedKey = credential
            DebridService.REAL_DEBRID -> savedRealDebridToken = credential
            DebridService.ALLDEBRID -> savedAllDebridKey = credential
            DebridService.PREMIUMIZE -> savedPremiumizeKey = credential
            DebridService.OFFCLOUD -> savedOffcloudKey = credential
            DebridService.DEBRID_LINK -> savedDebridLinkKey = credential
        }
    }

    override suspend fun listDownloads(provider: DebridProvider, bypassCache: Boolean) = provider.listDownloads()
    override suspend fun delete(provider: DebridProvider, id: String) = provider.delete(id)

    override suspend fun deleteMany(provider: DebridProvider, downloads: List<DebridDownload>) =
        super<TroveRepository>.deleteMany(provider, downloads)
    override suspend fun play(provider: DebridProvider, download: DebridDownload, fileId: String) =
        provider.getStreamUrl(DownloadRef(download.kind, download.id), fileId)

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

    /** Per-download picks for picker tests, keyed by (kind, id). */
    var candidatesByQuery: (MediaQuery) -> List<MediaCandidate> = { emptyList() }
    var metadataById: Map<Pair<String, String>, MediaMetadata> = emptyMap()
    var metadataByTmdbId: Map<Pair<Int, String>, MediaMetadata> = emptyMap()
    var tmdbConfigured: Boolean = false
    val savedMetadataOverrides = mutableMapOf<Pair<DownloadKind, String>, MetadataOverride>()

    override suspend fun resolveMetadataCandidates(query: MediaQuery) = candidatesByQuery(query)
    override suspend fun resolveMetadataById(imdbId: String, type: String) = metadataById[imdbId to type]
    override suspend fun resolveMetadataByTmdbId(tmdbId: Int, type: String) = metadataByTmdbId[tmdbId to type]
    override fun isTmdbConfigured(): Boolean = tmdbConfigured
    override suspend fun loadMetadataOverride(ref: DownloadRef) = savedMetadataOverrides[ref.kind to ref.id]
    override suspend fun saveMetadataOverride(ref: DownloadRef, pick: MetadataOverride) {
        savedMetadataOverrides[ref.kind to ref.id] = pick
    }
    override suspend fun clearMetadataOverride(ref: DownloadRef) {
        savedMetadataOverrides.remove(ref.kind to ref.id)
    }

    override fun hasTmdbKey(): Boolean = savedTmdbKey != null
    override fun maskedTmdbKey(): String =
        savedTmdbKey?.let { if (it.length <= 4) "••••" else "•".repeat(8) + it.takeLast(4) } ?: ""
    override suspend fun saveTmdbKey(key: String) {
        require(key.trim().isNotEmpty()) { "TMDB key is empty" }
        savedTmdbKey = key.trim()
    }
    override suspend fun clearTmdbKey() {
        savedTmdbKey = null
    }
    override suspend fun validateTmdbKey(key: String): Result<Unit> =
        if (key.trim().isNotEmpty() && tmdbKeyValid) Result.success(Unit)
        else Result.failure(IllegalStateException("TMDB key was not accepted"))

    // ---- SIMKL (read-only status badges) ----
    var simklConfigured: Boolean = false
    var simklSignedIn: Boolean = false
    var simklUsername: String? = null
    var simklEntries: List<SimklEntry> = emptyList()
    var simklDeviceCode: SimklDeviceCode? = null
    var simklPollResult: SimklPollResult = SimklPollResult.Pending
    var simklSyncResult: SimklSyncResult = SimklSyncResult.Unchanged
    var simklSyncCalls: Int = 0
    var simklSignOutCalls: Int = 0
    private val simklChangesFlow = MutableStateFlow(0)

    override fun isSimklConfigured(): Boolean = simklConfigured
    override fun isSimklSignedIn(): Boolean = simklSignedIn
    override suspend fun simklSnapshotInfo(): SimklSnapshotInfo? =
        if (simklEntries.isEmpty() && simklUsername == null) null
        else SimklSnapshotInfo(simklUsername, simklEntries.size, 1_700_000_000_000L, null)
    override val simklSnapshotChanges: Flow<Int> get() = simklChangesFlow
    override fun simklEntryFor(imdbId: String?, tmdbId: Int?): SimklEntry? =
        simklEntries.firstOrNull {
            (imdbId != null && it.imdbId == imdbId) || (tmdbId != null && it.tmdbId == tmdbId)
        }
    override suspend fun loadSimklSnapshot() = Unit
    override suspend fun syncSimkl(): SimklSyncResult {
        simklSyncCalls++
        if (simklSyncResult is SimklSyncResult.Changed || simklSyncResult == SimklSyncResult.Unchanged) {
            simklChangesFlow.value++
        }
        return simklSyncResult
    }
    override suspend fun startSimklDeviceFlow(): Result<SimklDeviceCode> =
        simklDeviceCode?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("SIMKL unavailable"))
    override suspend fun pollSimklDeviceCode(deviceCode: SimklDeviceCode): SimklPollResult {
        if (simklPollResult is SimklPollResult.Authorized) simklSignedIn = true
        return simklPollResult
    }
    override suspend fun signOutSimkl() {
        simklSignOutCalls++
        simklSignedIn = false
        simklEntries = emptyList()
        simklUsername = null
        simklChangesFlow.value++
    }
}

internal suspend fun TroveRepository.saveTestCredential(service: DebridService, credential: String): Result<DebridAccount> {
    val descriptor = providerSectionDescriptors.getValue(service)
    return validateAndSave(service, credential, descriptor.factory, descriptor.accountLabel)
}
