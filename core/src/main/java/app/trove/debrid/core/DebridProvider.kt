package app.trove.debrid.core

data class DownloadRef(val kind: DownloadKind, val id: String)

/** Everything the UI ever asks a provider to do. One method per user
 *  intent, not one per endpoint — the adapter hides how many HTTP
 *  calls that takes. */
interface DebridProvider {
    val service: DebridService
    val capabilities: ProviderCapabilities

    /** Cheap "who am I" check; also the first call after sign-in. */
    suspend fun validateAccount(): Result<AccountInfo>

    /** All downloads of every kind this provider holds, newest first. */
    suspend fun listDownloads(): Result<List<DebridDownload>>

    /** Same list, but the provider is asked to bypass any server-side
     *  list cache. Default is the cached [listDownloads]: only
     *  providers whose list endpoint caches (TorBox) override this.
     *  Callers use it only for user-initiated refreshes — a bypassed
     *  load costs uncached calls against the provider's rate limit. */
    suspend fun listDownloadsFresh(): Result<List<DebridDownload>> = listDownloads()

    /** One download's fresh state (for polling progress). */
    suspend fun getDownload(id: String): Result<DebridDownload>

    suspend fun getDownload(ref: DownloadRef): Result<DebridDownload> = getDownload(ref.id)

    /** Add a magnet / .torrent file / hoster link / NZB. The input is
     *  already classified by the share/add flow. Returns the created
     *  download; adapters that need a second step (RD selectFiles) do
     *  it inside this call. */
    suspend fun add(input: AddInput): Result<DebridDownload>

    /** Read-only "is this already cached?" — returns null when the
     *  provider has no read-only probe (RD, DL): the caller must then
     *  decide whether to run a mutating probe or skip the check. */
    suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?>

    /** Resolve a playable/downloadable URL for one file of a ready
     *  download. MUST return a presigned/link URL that does not expose
     *  the API key. The UI hands this URL to Android's ACTION_VIEW
     *  chooser — any player may take it. */
    suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl>

    suspend fun getStreamUrl(ref: DownloadRef, fileId: String): Result<StreamUrl> = getStreamUrl(ref.id, fileId)

    /** Pause/resume where supported; failure(UnsupportedCapability)
     *  otherwise. */
    suspend fun control(id: String, action: ControlAction): Result<Unit>

    suspend fun delete(id: String): Result<Unit>

    suspend fun delete(ref: DownloadRef): Result<Unit> = delete(ref.id)

    suspend fun hosterDomains(): Set<String> = emptySet()

    /** Hoster support for the share flow: "can this provider take
     *  this URL?" */
    suspend fun supportsUrl(url: String): Result<Boolean>
}
