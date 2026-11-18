package app.trove.debrid.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.trove.debrid.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateInstallResult {
    data object Installed : UpdateInstallResult
    data object WaitingForConfirmation : UpdateInstallResult
    data class Failed(val issue: UpdateIssue = UpdateIssue.INSTALL, val message: String? = null) : UpdateInstallResult
}
data class UpdateInstallEvent(val operation: String, val result: UpdateInstallResult)
object UpdateInstallEvents {
    private val mutable = MutableSharedFlow<UpdateInstallEvent>(extraBufferCapacity = 8)
    val events = mutable.asSharedFlow()
    fun publish(operation: String, result: UpdateInstallResult) { mutable.tryEmit(UpdateInstallEvent(operation, result)) }
}

/** JVM-testable callback boundary; validation precedes every action, including confirmation launch. */
internal suspend fun handleInstallResult(
    pending: PendingUpdateSession,
    id: Int,
    version: Long,
    path: String?,
    operation: String?,
    status: Int,
    message: String?,
    confirm: suspend () -> Boolean,
    abandon: (Int) -> Unit,
    publish: suspend (String, UpdateInstallResult) -> Unit,
    reopen: suspend () -> Unit,
) {
    if (!pending.matches(id, version, path, operation)) return
    val op = requireNotNull(operation)
    when (status) {
        PackageInstaller.STATUS_SUCCESS -> {
            try {
                File(requireNotNull(path)).delete()
                pending.clear(op)
            } finally {
                // A dead process or missing UI observer must never suppress relaunch.
                try { publish(op, UpdateInstallResult.Installed) } finally { reopen() }
            }
        }
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            if (confirm()) publish(op, UpdateInstallResult.WaitingForConfirmation)
            else {
                // Keep durable correlation when the previous session cannot be settled.
                if (runCatching { abandon(id) }.isSuccess) pending.clear(op)
                publish(op, UpdateInstallResult.Failed())
            }
        }
        else -> {
            pending.clear(op)
            val signerMismatch = status == PackageInstaller.STATUS_FAILURE_CONFLICT &&
                message.orEmpty().contains("signatur", ignoreCase = true)
            publish(op, UpdateInstallResult.Failed(if (signerMismatch) UpdateIssue.SIGNER else UpdateIssue.INSTALL, message?.take(500)))
        }
    }
}

/** Framework entry point is public, explicit and non-exported. All disk cleanup uses goAsync + IO. */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_STATUS) return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val version = intent.getLongExtra(ApkInstaller.EXTRA_VERSION_CODE, -1)
        val path = intent.getStringExtra(ApkInstaller.EXTRA_APK_PATH)
        val operation = intent.getStringExtra(ApkInstaller.EXTRA_OPERATION)
        if (intent.dataString != "trove-update://session/$id/$operation") return
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleInstallResult(
                    pendingSessions(appContext), id, version, path, operation,
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    confirm = {
                        withContext(Dispatchers.Main) {
                            runCatching {
                                val confirmation = confirmation(intent) ?: return@runCatching false
                                appContext.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                true
                            }.getOrDefault(false)
                        }
                    },
                    abandon = { appContext.packageManager.packageInstaller.abandonSession(it) },
                    publish = { op, event -> UpdateInstallEvents.publish(op, event) },
                    reopen = {
                        withContext(Dispatchers.Main) {
                            runCatching {
                                appContext.startActivity(Intent(appContext, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                })
                            }
                        }
                    },
                )
            } catch (_: Exception) {
                // A malformed callback or failed disk operation must not crash the app.
                // The saved record remains available to settle before the next attempt.
            } finally { result.finish() }
        }
    }
    @Suppress("DEPRECATION")
    private fun confirmation(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
