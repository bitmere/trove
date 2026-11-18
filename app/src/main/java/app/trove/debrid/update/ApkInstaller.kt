package app.trove.debrid.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.trove.debrid.core.update.UpdatePolicy
import java.io.File
import kotlinx.coroutines.CancellationException

sealed interface InstallOutcome {
    data class SessionStarted(val sessionId: Int) : InstallOutcome
    data object SystemInstallerLaunched : InstallOutcome
    data object NeedsUnknownSources : InstallOutcome
    data class Error(val issue: UpdateIssue, val diagnostics: InstallDiagnostics? = null) : InstallOutcome
}

enum class InstallCheck { PASS, FAIL, NOT_CHECKED }

/** One typed install-guard record: per-sub-check results, expected vs observed
 * measurements, and whether the file was deleted. In memory only; no URL, path,
 * key, response body, raw header or exception message is retained. `deleted`
 * is null when no deletion was attempted (the file was not owned). */
data class InstallDiagnostics(
    val ownership: InstallCheck,
    val size: InstallCheck,
    val sha256: InstallCheck,
    val expectedSize: Long,
    val observedSize: Long?,
    val expectedSha256: String,
    val observedSha256: String?,
    val deleted: Boolean?,
) {
    override fun toString(): String = buildString {
        appendLine("ownership: ${ownership.name.lowercase()}")
        appendLine("size: ${size.name.lowercase()} (expected $expectedSize, observed ${observedSize ?: "unavailable"})")
        appendLine("sha256: ${sha256.name.lowercase()} (expected $expectedSha256, observed ${observedSha256 ?: "unavailable"})")
        append("file deleted: ${deleted?.toString() ?: "not attempted"}")
    }
}

/** Test boundary preserving create/open/write+fsync/commit/abandon ordering. */
interface InstallSessionAdapter {
    fun create(): Int
    fun open(id: Int)
    fun writeAndSync()
    fun commit(id: Int)
    fun abandon(id: Int)
    fun close()
}
internal class UnsettledSession(cause: Throwable) : Exception(cause)
internal fun commitInstallSession(adapter: InstallSessionAdapter): Int {
    var id: Int? = null
    try {
        val created = adapter.create().also { id = it }
        adapter.open(created)
        adapter.writeAndSync()
        adapter.commit(created)
        return created
    } catch (failure: Exception) {
        id?.let {
            try { adapter.abandon(it) } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
                throw UnsettledSession(failure)
            }
        }
        throw failure
    } finally { runCatching { adapter.close() } }
}

internal fun installAttempt(granted: Boolean, abandonPrevious: () -> Unit, session: () -> Int, fallback: () -> InstallOutcome): InstallOutcome {
    if (!granted) return InstallOutcome.NeedsUnknownSources
    try { abandonPrevious() } catch (_: Exception) { return InstallOutcome.Error(UpdateIssue.PREVIOUS_SESSION) }
    return try { InstallOutcome.SessionStarted(session()) }
    catch (e: CancellationException) { throw e }
    catch (_: UnsettledSession) { InstallOutcome.Error(UpdateIssue.PREVIOUS_SESSION) }
    catch (_: Exception) { fallback() }
}

class ApkInstaller(private val context: Context, private val pending: PendingUpdateSession) {
    companion object {
        const val ACTION_INSTALL_STATUS = "app.trove.debrid.UPDATE_INSTALL_STATUS"
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_VERSION_CODE = "version_code"
        const val EXTRA_OPERATION = "operation"
    }
    fun install(file: File, version: Long, operation: String, checkActive: () -> Unit = {}): InstallOutcome = installAttempt(
        granted = context.packageManager.canRequestPackageInstalls(),
        abandonPrevious = {
            pending.abandon { id ->
                // A completed/removed session after process death is already settled.
                val installer = context.packageManager.packageInstaller
                if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
            }
        },
        session = { checkActive(); session(file, version, operation, checkActive) },
        fallback = { fallback(file) },
    )

    private fun session(file: File, version: Long, operation: String, checkActive: () -> Unit): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UpdatePolicy.PACKAGE_NAME)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        return commitInstallSession(object : InstallSessionAdapter {
            private var opened: PackageInstaller.Session? = null
            override fun create() = installer.createSession(params)
            override fun open(id: Int) {
                pending.save(id, version, file, operation)
                opened = installer.openSession(id)
            }
            override fun writeAndSync() {
                val session = checkNotNull(opened)
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    checkActive()
                    session.fsync(output)
                }
            }
            override fun commit(id: Int) {
                checkActive()
                val intent = Intent(context, UpdateInstallResultReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    data = "trove-update://session/$id/$operation".toUri()
                    putExtra(EXTRA_APK_PATH, file.absolutePath)
                    putExtra(EXTRA_VERSION_CODE, version)
                    putExtra(EXTRA_OPERATION, operation)
                }
                // PackageInstaller fills status and confirmation extras into this explicit intent.
                val status = PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                checkNotNull(opened).commit(status.intentSender)
            }
            override fun abandon(id: Int) {
                installer.abandonSession(id)
                pending.clear(operation)
            }
            override fun close() { opened?.close() }
        })
    }
    private fun fallback(file: File): InstallOutcome = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        InstallOutcome.SystemInstallerLaunched
    } catch (_: Exception) { InstallOutcome.Error(UpdateIssue.INSTALL) }
}
