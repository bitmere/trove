package app.trove.debrid.update

import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Connection owns its stream; close always releases the network resource. */
interface UpdateConnection : Closeable {
    val status: Int
    val length: Long?
    val link: String? get() = null
    val location: String?
    val input: InputStream
    val contentRange: String? get() = null
    val chunked: Boolean get() = false
}
fun interface UpdateStream { fun open(request: HttpRequest): UpdateConnection }

enum class UpdateFraming { CONTENT_LENGTH, CHUNKED, UNSPECIFIED }

/** Only locally computed or numeric fields. Never retain raw headers, URLs or exception text.
 * One record travels with the final error in memory; no history, disk or saved-state copy.
 */
data class UpdateDiagnostics internal constructor(
    val count: Long,
    val total: Long?,
    val digest: String,
    val framing: UpdateFraming,
    val status: Int?,
    val expectedTotal: Long?,
)

class ApkDownload(private val stream: UpdateStream, private val beforeDownload: () -> Unit = {}) {
    companion object {
        private val gate = Mutex()
        private const val ATTEMPTS = 3
        private val contentRangePattern = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }

    /** A partial file belongs only to this call. Three request chains, 8 KB buffers, no stale resume. */
    suspend fun download(
        filesDir: File,
        release: LatestRelease,
        maxBytes: Long = UpdatePolicy.MAX_APK_BYTES,
        progress: (Long, Long?) -> Unit,
    ): File = gate.withLock {
        if (!UpdatePolicy.isAssetUrl(release.apkUrl, release.tag, "Trove-${release.tag}.apk") || release.size !in 1..maxBytes || !UpdatePolicy.isHash(release.sha256)) throw UpdateException(UpdateIssue.MANIFEST)
        beforeDownload()
        val directory = File(filesDir, "updates").apply { check(mkdirs() || isDirectory) }
        val target = File.createTempFile("trove-", ".apk", directory)
        var resumeTotal: Long? = null
        try {
            repeat(ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                val offset = if (resumeTotal != null) target.length() else 0L
                var count = offset
                var total: Long? = null
                val expectedTotal = release.size
                var status: Int? = null
                var framing = UpdateFraming.UNSPECIFIED
                var receivedConnection = false
                val digest = MessageDigest.getInstance("SHA-256")
                var computedDigest: String? = null
                try {
                    if (offset > 0) {
                        target.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                digest.update(buffer, 0, read)
                            }
                        }
                    }
                    val headers = if (offset > 0) UPDATE_NO_CACHE_HEADERS + ("Range" to "bytes=$offset-") else UPDATE_NO_CACHE_HEADERS
                    withResponse(release.apkUrl, headers) { connection ->
                        receivedConnection = true
                        status = connection.status
                        total = connection.length
                        framing = when {
                            connection.chunked -> UpdateFraming.CHUNKED
                            total != null -> UpdateFraming.CONTENT_LENGTH
                            else -> UpdateFraming.UNSPECIFIED
                        }
                        val append = offset > 0 && status == 206
                        if (append) {
                            if (!matchesRange(connection.contentRange, offset, expectedTotal, total)) {
                                throw UpdateException(UpdateIssue.DOWNLOAD)
                            }
                        } else {
                            if (status != 200) throw UpdateException(UpdateIssue.DOWNLOAD)
                            count = 0
                            digest.reset()
                            if (total != null && total != expectedTotal) throw UpdateException(UpdateIssue.TRUNCATED)
                        }
                        FileOutputStream(target, append).use { output ->
                            if (attempt > 0 && !append) progress(0, expectedTotal)
                            connection.input.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    count += read
                                    if (count > expectedTotal) throw UpdateException(UpdateIssue.DOWNLOAD)
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    progress(count, expectedTotal)
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (count == 0L) throw UpdateException(UpdateIssue.EMPTY)
                        if (count != expectedTotal) throw UpdateException(UpdateIssue.TRUNCATED)
                        computedDigest = digest.digest().hex()
                        if (computedDigest != release.sha256) throw UpdateException(UpdateIssue.DIGEST_MISMATCH)
                    }
                    currentCoroutineContext().ensureActive()
                    return@withLock target
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    val expected = expectedTotal
                    val issue = if (e is UpdateException) e.issue
                        else if (count > 0 && count < expected) UpdateIssue.TRUNCATED
                        else UpdateIssue.DOWNLOAD
                    val record = UpdateDiagnostics(count, total, computedDigest ?: digest.digest().hex(), framing, status, expectedTotal)
                    val retryable = issue in setOf(UpdateIssue.DOWNLOAD, UpdateIssue.EMPTY, UpdateIssue.TRUNCATED, UpdateIssue.DIGEST_MISMATCH) &&
                        (receivedConnection || e !is UpdateException)
                    if (!retryable || attempt == ATTEMPTS - 1) throw UpdateException(issue, record)
                    resumeTotal = expectedTotal.takeIf {
                        issue == UpdateIssue.TRUNCATED && count in 1 until it && target.length() == count
                    }
                    if (resumeTotal == null) target.outputStream().close()
                    delay(500L * (attempt + 1))
                }
            }
            throw UpdateException(UpdateIssue.DOWNLOAD)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    internal suspend fun checksum(release: LatestRelease): String {
        if (release.sha256.isNotEmpty()) {
            if (!UpdatePolicy.isHash(release.sha256)) throw UpdateException(UpdateIssue.MANIFEST)
            return release.sha256
        }
        if (release.checksumUrl.isEmpty()) throw UpdateException(UpdateIssue.ASSETS)
        val name = "Trove-${release.tag}.apk"
        if (!UpdatePolicy.isAssetUrl(release.checksumUrl, release.tag, "$name.sha256")) {
            throw UpdateException(UpdateIssue.ASSETS)
        }
        var checksum: String? = null
        withResponse(release.checksumUrl, UPDATE_NO_CACHE_HEADERS) { connection ->
            if (connection.status in setOf(403, 429)) throw UpdateException(UpdateIssue.RATE_LIMIT)
            if (connection.status != 200) throw UpdateException(UpdateIssue.ASSETS)
            val expected = 67 + name.length
            if (connection.length?.let { it != expected.toLong() } == true) throw UpdateException(UpdateIssue.ASSETS)
            val bytes = ByteArray(expected)
            connection.input.use { input ->
                var offset = 0
                while (offset < expected) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes, offset, expected - offset)
                    if (count < 0) throw UpdateException(UpdateIssue.ASSETS)
                    offset += count
                }
                if (input.read() != -1) throw UpdateException(UpdateIssue.ASSETS)
            }
            val line = bytes.toString(Charsets.US_ASCII)
            val hash = line.take(64)
            if (!UpdatePolicy.isHash(hash) || line != "$hash  $name\n") throw UpdateException(UpdateIssue.ASSETS)
            checksum = hash
        }
        return requireNotNull(checksum)
    }

    private suspend fun withResponse(
        initialUrl: String,
        headers: Map<String, String>,
        read: suspend (UpdateConnection) -> Unit,
    ) {
        var url = initialUrl
        repeat(4) { hop ->
            currentCoroutineContext().ensureActive()
            if (hop > 0 && !UpdatePolicy.isReleaseRedirect(url)) throw UpdateException(UpdateIssue.MANIFEST)
            var complete = false
            stream.open(HttpRequest("GET", url, headers)).useCancellable { connection ->
                if (connection.status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3) throw UpdateException(UpdateIssue.DOWNLOAD)
                    url = try { redirectUrl(url, connection.location) }
                    catch (_: Exception) { throw UpdateException(UpdateIssue.MANIFEST) }
                } else {
                    read(connection)
                    complete = true
                }
            }
            if (complete) return
        }
        throw UpdateException(UpdateIssue.DOWNLOAD)
    }

    private fun matchesRange(raw: String?, offset: Long, total: Long, length: Long?): Boolean {
        // Bound parsing; raw text is never retained in diagnostics.
        if (raw == null || raw.length > 100) return false
        val match = contentRangePattern.matchEntire(raw) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val size = match.groupValues[3].toLongOrNull() ?: return false
        return start == offset && size == total && end == total - 1 &&
            (length == null || length == total - offset)
    }
}

/** Closing on IO also unblocks a blocking socket read on cancellation. Scope exit waits for
 * that closer, so the download gate cannot admit another request before this one closes.
 */
internal suspend fun <T> UpdateConnection.useCancellable(read: suspend (UpdateConnection) -> T): T = coroutineScope {
    val connection = this@useCancellable
    val closed = AtomicBoolean(false)
    fun closeOnce() { if (closed.compareAndSet(false, true)) connection.close() }
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { closeOnce() }
    }
    try { read(connection) }
    finally { closer.cancel(); closeOnce() }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun sha256(file: File, checkActive: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    checkActive()
    return digest.digest().hex()
}
