package app.trove.debrid.core

/** Which debrid service an account belongs to. */
enum class DebridService {
    REAL_DEBRID,
    ALLDEBRID,
    TORBOX,
    PREMIUMIZE,
    OFFCLOUD,
    DEBRID_LINK,
}

/** One connected debrid account. The credential itself lives in a
 *  [CredentialStore], never in this object. */
data class DebridAccount(
    val id: String,
    val service: DebridService,
    val label: String,
    val addedAtEpochMs: Long,
    val lastValidatedEpochMs: Long? = null,
)

data class DebridFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val path: String? = null,
)

enum class DownloadKind { TORRENT, WEB, USENET }

enum class DownloadStatus { QUEUED, DOWNLOADING, READY, PAUSED, ERROR, UNKNOWN }

data class DebridDownload(
    val id: String,
    val service: DebridService,
    val name: String,
    val sizeBytes: Long?,
    val status: DownloadStatus,
    /** 0.0..1.0; 0 when unknown, 1 when ready. */
    val progress: Float,
    val files: List<DebridFile> = emptyList(),
    val kind: DownloadKind,
    val createdAtEpochMs: Long? = null,
    val errorMessage: String? = null,
    val downloadSpeedBytesPerSec: Long? = null,
    val etaSeconds: Long? = null,
    val seeds: Int? = null,
    val peers: Int? = null,
)

sealed interface AddInput {
    data class Magnet(val magnetUri: String) : AddInput
    data class TorrentFile(val bytes: ByteArray, val fileName: String) : AddInput
    data class HosterLink(val url: String) : AddInput
    data class Nzb(val bytes: ByteArray, val fileName: String) : AddInput
    data class NzbUrl(val url: String) : AddInput
}

data class CachedInfo(val ready: Boolean, val name: String?, val sizeBytes: Long?)

/** Sensitive per-file bearer link; only the selected player receives [url]. */
data class StreamUrl(val url: String, val expiresAtEpochMs: Long? = null) {
    override fun toString(): String = "StreamUrl([redacted])"
}

data class AccountInfo(val username: String?, val isPremium: Boolean, val detail: String?)

enum class AuthFlow { API_KEY, OAUTH_DEVICE, API_KEY_OR_OAUTH_DEVICE }

data class ProviderCapabilities(
    val magnet: Boolean = true,
    val torrentFile: Boolean = true,
    val hosterLink: Boolean = true,
    val nzb: Boolean = false,
    /** True only when the provider has a READ-ONLY cache check. */
    val readOnlyCacheCheck: Boolean = false,
    /** True when a cache probe is possible at all (possibly mutating). */
    val cacheProbeMutating: Boolean = false,
    val fileSelection: Boolean = false,
    val pauseResume: Boolean = false,
    val restart: Boolean = false,
    val streamingApi: Boolean = false,
    val multiFilePick: Boolean = false,
    val maxActiveDownloads: Int? = null,
    val authFlow: AuthFlow,
)

enum class ControlAction { PAUSE, RESUME, RETRY }

/** Typed failures. The UI maps each to exactly one plain-language line. */
sealed class DebridError(message: String) : Exception(message) {
    class AuthInvalid : DebridError("Key/token rejected — re-authenticate")
    class RateLimited(val retryAfterSecs: Long?) : DebridError("Slow down")
    class NotCached : DebridError("Not on this provider's servers")
    class QuotaExceeded(val detail: String) : DebridError("Plan limit hit")
    class UnsupportedCapability(val what: String) : DebridError("This provider can't do that")
    class NameRefused(val detail: String) : DebridError("Provider refused this release name")
    class Network(cause: Throwable) : DebridError("Network failed")
    class Provider(val code: String, val detail: String) : DebridError("Provider error $code" + if (detail.isBlank()) "" else ": $detail")
}
