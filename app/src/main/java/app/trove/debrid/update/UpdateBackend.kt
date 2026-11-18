package app.trove.debrid.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import app.trove.debrid.BuildConfig
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdatePolicy
import app.trove.debrid.core.update.UpdateChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CharacterCodingException

interface UpdateBackend {
    val installedName: String
    fun canInstall(): Boolean
    fun openSettings(): Boolean
    suspend fun check(channel: UpdateChannel): UpdateCheckResult
    suspend fun download(release: LatestRelease, progress: (Long, Long?) -> Unit): File
    suspend fun install(file: File, release: LatestRelease, operation: String): InstallOutcome
}

/** Dedicated public connection: no account client, cookies, auth headers or automatic redirects. */
internal class PublicUpdateStream(
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : UpdateStream {
    override fun open(request: HttpRequest): UpdateConnection {
        require(request.method == "GET" && UpdatePolicy.isHttps(request.url))
        require(request.headers.all { (name, value) ->
            UPDATE_NO_CACHE_HEADERS[name] == value || (name == "Range" && value.matches(Regex("bytes=[0-9]+-")))
        })
        val connection = connectionFactory(request.url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
            UPDATE_NO_CACHE_HEADERS.forEach { (name, value) -> setRequestProperty(name, value) }
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return object : UpdateConnection {
            override val status get() = connection.responseCode
            override val length get() = connection.contentLengthLong.takeIf { it >= 0 }
            override val link get() = connection.getHeaderField("Link")
            override val location get() = connection.getHeaderField("Location")
            override val contentRange get() = connection.getHeaderField("Content-Range")
            override val chunked get() = connection.getHeaderField("Transfer-Encoding")
                ?.split(',')?.any { it.trim().equals("chunked", true) } == true
            override val input get() = connection.inputStream
            override fun close() { connection.disconnect() }
        }
    }
}

/** Existing HttpTransport shape with bounded body reads, unlike the provider's whole-body client. */
internal class PublicManifestTransport(private val stream: UpdateStream) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        require(request.method == "GET" && request.headers.all { (name, value) -> UPDATE_NO_CACHE_HEADERS[name] == value })
        return stream.open(request.copy(headers = UPDATE_NO_CACHE_HEADERS)).useCancellable { connection ->
            val status = connection.status
            val headers = connection.link?.let { mapOf("Link" to it) }.orEmpty()
            if (status != 200) return@useCancellable HttpResponse(status, "", headers)
            if ((connection.length ?: 0) > UpdatePolicy.MAX_MANIFEST_BYTES) throw UpdateException(UpdateIssue.MANIFEST)
            val body = ByteArrayOutputStream()
            connection.input.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (body.size() + read > UpdatePolicy.MAX_MANIFEST_BYTES) throw UpdateException(UpdateIssue.MANIFEST)
                    body.write(buffer, 0, read)
                }
            }
            currentCoroutineContext().ensureActive()
            val decoder = Charsets.UTF_8.newDecoder()
            val text = try {
                decoder.decode(java.nio.ByteBuffer.wrap(body.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                throw UpdateException(UpdateIssue.MANIFEST)
            }
            HttpResponse(status, text, headers)
        }
    }
}

class AndroidUpdateBackend(context: Context) : UpdateBackend {
    private val context = context.applicationContext
    private val stream = PublicUpdateStream()
    private val downloader = ApkDownload(stream, ::prepareDownload)
    private val checker = UpdateCheck(PublicManifestTransport(stream), downloader)
    private val pending by lazy { pendingSessions(this.context) }
    private val installer by lazy { ApkInstaller(this.context, pending) }
    override val installedName: String = BuildConfig.VERSION_NAME
    override fun canInstall() = context.packageManager.canRequestPackageInstalls()
    @Suppress("DEPRECATION")
    override suspend fun check(channel: UpdateChannel): UpdateCheckResult {
        val result = checker.check(BuildConfig.VERSION_CODE.toLong(), channel)
        if (result is UpdateCheckResult.Available && !BuildConfig.DEBUG) {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            if (!hasReleaseSigner(pm.getPackageInfo(context.packageName, flags))) {
                return UpdateCheckResult.Error(UpdateIssue.SIGNER)
            }
        }
        return result
    }
    private fun prepareDownload() {
        try {
            pending.abandon { id ->
                val installer = context.packageManager.packageInstaller
                if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
            }
        } catch (_: Exception) { throw UpdateException(UpdateIssue.PREVIOUS_SESSION) }
        // Bound stale verified files left by a cleared UI or an earlier failed attempt.
        File(context.filesDir, "updates").listFiles()?.forEach {
            if (pending.isOwnedPath(it.absolutePath) && it.isFile) it.delete()
        }
    }
    override suspend fun download(release: LatestRelease, progress: (Long, Long?) -> Unit): File {
        val file = downloader.download(context.filesDir, release, progress = progress)
        try {
            currentCoroutineContext().ensureActive()
            verifyArchive(file, release)
            currentCoroutineContext().ensureActive()
            return file
        }
        catch (failure: Throwable) { file.delete(); throw failure }
    }
    override suspend fun install(file: File, release: LatestRelease, operation: String): InstallOutcome {
        if (!canInstall()) return InstallOutcome.NeedsUnknownSources
        val caller = currentCoroutineContext()
        return try {
            // Reverify at the install boundary, including retries and permission returns.
            if (!pending.isOwnedPath(file.absolutePath) || file.length() != release.size || sha256(file) { caller.ensureActive() } != release.sha256) throw UpdateException(UpdateIssue.VERIFICATION)
            verifyArchive(file, release)
            caller.ensureActive()
            installer.install(file, release.versionCode, operation) { caller.ensureActive() }
        } catch (e: CancellationException) { throw e }
        catch (failure: UpdateException) {
            if (pending.isOwnedPath(file.absolutePath)) file.delete()
            InstallOutcome.Error(failure.issue)
        }
        catch (_: Exception) { InstallOutcome.Error(UpdateIssue.INSTALL) }
    }
    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: LatestRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateException(UpdateIssue.PACKAGE)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (context.packageName != UpdatePolicy.PACKAGE_NAME || !UpdatePolicy.isEligible(archive.packageName, version, release.versionCode, BuildConfig.VERSION_CODE.toLong())) throw UpdateException(UpdateIssue.PACKAGE)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val flagsInArchive = archive.applicationInfo?.flags ?: throw UpdateException(UpdateIssue.PACKAGE)
        if (archive.versionName != release.versionName ||
            flagsInArchive and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            throw UpdateException(UpdateIssue.PACKAGE)
        }
        if (!hasReleaseSigner(archive) || !hasReleaseSigner(installed) || !compatibleSigners(installed, archive)) throw UpdateException(UpdateIssue.SIGNER)
    }
    @Suppress("DEPRECATION")
    private fun hasReleaseSigner(info: PackageInfo): Boolean {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val fingerprints = signers?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
        return fingerprints == listOf(UpdatePolicy.RELEASE_CERT)
    }
    @Suppress("DEPRECATION")
    private fun compatibleSigners(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val current = installed.signingInfo ?: return false
            val candidate = archive.signingInfo ?: return false
            val old = current.apkContentsSigners.map { it.toCharsString() }.toSet()
            return compatibleSignerSets(old, candidate.apkContentsSigners.map { it.toCharsString() }.toSet(),
                candidate.signingCertificateHistory?.map { it.toCharsString() }?.toSet().orEmpty(),
                current.hasMultipleSigners() || candidate.hasMultipleSigners())
        }
        val old = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        return old.isNotEmpty() && old == archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }
    override fun openSettings(): Boolean {
        for (intent in listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { /* Try the general security screen on vendor builds. */ }
        }
        return false
    }
}

internal fun pendingSessions(context: Context) = PendingUpdateSession(
    File(context.noBackupFilesDir, "trove-update-session.properties"), File(context.filesDir, "updates"),
)

/** Single signers may rotate through a verified lineage; multiple signers require exact identity. */
internal fun compatibleSignerSets(current: Set<String>, candidate: Set<String>, history: Set<String>, multiple: Boolean): Boolean =
    current.isNotEmpty() && candidate.isNotEmpty() && if (multiple) current == candidate else history.containsAll(current)
