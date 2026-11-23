package app.trove.debrid.ui.downloads.notifications

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.trove.debrid.R
import app.trove.debrid.core.DownloadKind

/** Foreground service that mirrors active download progress into the
 *  notification shade: one notification per download, grouped. It is
 *  started only while the app is in the foreground (from the Downloads
 *  ViewModel's poll path via [ServiceDownloadNotificationController]) and
 *  never outlives the downloads it reports: an empty SYNC, a STOP, or the
 *  last download finishing cancels everything and stops the service.
 *
 *  START_NOT_STICKY: if the process dies, the service does not restart
 *  itself; the app restarts it when foregrounded with active downloads.
 *  A recreated service with no SYNC data posts nothing — it never
 *  re-posts a cached progress value from a dead process. */
class DownloadProgressService : Service() {

    private lateinit var helper: DownloadNotificationHelper
    private val decider = NotificationRepostDecider()
    private val postedIds = linkedSetOf<Int>()
    private var foregroundId: Int? = null

    override fun onCreate() {
        super.onCreate()
        helper = DownloadNotificationHelper(applicationContext)
        helper.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_SYNC -> sync(intent.syncDownloads())
            // Unknown or redelivered intent with no data: post nothing.
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private fun sync(downloads: List<ActiveDownload>) {
        if (downloads.isEmpty()) {
            stopAll()
            return
        }
        val wantedIds = linkedSetOf<Int>()
        for (download in downloads) {
            val id = helper.notificationIdFor(download)
            wantedIds += id
            val content = notificationContentFor(download, getString(R.string.queued))
            if (!decider.shouldPost(id, content)) continue
            val notification = helper.build(download)
            if (foregroundId == null) {
                // First notification: become the foreground service now,
                // synchronously, before any network call.
                startForeground(id, notification)
                foregroundId = id
            } else {
                helper.notify(id, notification)
            }
            decider.recordPosted(id, content)
            postedIds += id
        }
        // If the foreground notification's download ended, promote another
        // child BEFORE cancelling it, so the service is never foregrounded
        // without a notification.
        val removed = postedIds - wantedIds
        if (foregroundId in removed && wantedIds.isNotEmpty()) {
            val promoteId = wantedIds.first()
            val download = downloads.firstOrNull { helper.notificationIdFor(it) == promoteId }
            if (download != null) {
                startForeground(promoteId, helper.build(download))
                foregroundId = promoteId
            }
        }
        for (id in removed) {
            helper.cancel(id)
            decider.forget(id)
            postedIds -= id
        }
        if (postedIds.size >= 2) {
            helper.notify(DOWNLOAD_PROGRESS_SUMMARY_ID, helper.buildSummary(postedIds.size))
        } else {
            helper.cancel(DOWNLOAD_PROGRESS_SUMMARY_ID)
        }
    }

    private fun stopAll() {
        for (id in postedIds) helper.cancel(id)
        helper.cancel(DOWNLOAD_PROGRESS_SUMMARY_ID)
        postedIds.clear()
        decider.forgetAll()
        foregroundId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        for (id in postedIds) helper.cancel(id)
        helper.cancel(DOWNLOAD_PROGRESS_SUMMARY_ID)
        postedIds.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SYNC = "app.trove.debrid.notifications.SYNC"
        const val ACTION_STOP = "app.trove.debrid.notifications.STOP"
        const val EXTRA_DOWNLOADS = "app.trove.debrid.notifications.DOWNLOADS"
    }
}

private const val KEY_KIND = "kind"
private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_QUEUED = "queued"
private const val KEY_PERCENT = "percent"
private const val KEY_SPEED = "speed"
private const val KEY_ETA = "eta"

internal fun ActiveDownload.toBundle(): Bundle = Bundle().apply {
    putString(KEY_KIND, kind.name)
    putString(KEY_ID, id)
    putString(KEY_NAME, name)
    putBoolean(KEY_QUEUED, queued)
    putInt(KEY_PERCENT, percent)
    putString(KEY_SPEED, speedText)
    putString(KEY_ETA, etaText)
}

internal fun Bundle.toActiveDownload(): ActiveDownload? {
    val kind = getString(KEY_KIND)?.let {
        runCatching { DownloadKind.valueOf(it) }.getOrNull()
    } ?: return null
    val id = getString(KEY_ID) ?: return null
    val name = getString(KEY_NAME) ?: return null
    return ActiveDownload(
        kind = kind,
        id = id,
        name = name,
        queued = getBoolean(KEY_QUEUED),
        percent = getInt(KEY_PERCENT),
        speedText = getString(KEY_SPEED),
        etaText = getString(KEY_ETA),
    )
}

private fun Intent.syncDownloads(): List<ActiveDownload> {
    val bundles: ArrayList<Bundle>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(DownloadProgressService.EXTRA_DOWNLOADS, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(DownloadProgressService.EXTRA_DOWNLOADS)
        }
    return bundles?.mapNotNull { it.toActiveDownload() }.orEmpty()
}

/** Context-backed [DownloadNotificationController]: SYNC intents carry the
 *  active downloads as primitive Bundles. The service is started with
 *  startForegroundService only when it is not already running (a plain
 *  startService delivers later SYNCs to the running service); if the
 *  system refuses a background foreground-service start, the start is
 *  skipped and the next foreground poll retries. */
class ServiceDownloadNotificationController(context: Context) : DownloadNotificationController {
    private val appContext = context.applicationContext
    private var serviceRunning = false

    // NewApi is safe here: ForegroundServiceStartNotAllowedException only
    // exists (and is only thrown) on API 31+, where background foreground-
    // service starts are restricted. Below 31 the catch clause never loads
    // the class, so it is unreachable rather than a crash.
    @SuppressLint("NewApi")
    override fun sync(active: List<ActiveDownload>) {
        if (active.isEmpty()) {
            stop()
            return
        }
        if (!hasNotificationPermission()) return
        DownloadNotificationHelper(appContext).ensureChannel()
        val intent = Intent(appContext, DownloadProgressService::class.java).apply {
            action = DownloadProgressService.ACTION_SYNC
            putParcelableArrayListExtra(
                DownloadProgressService.EXTRA_DOWNLOADS,
                ArrayList(active.map { it.toBundle() }),
            )
        }
        if (serviceRunning) {
            appContext.startService(intent)
        } else {
            try {
                ContextCompat.startForegroundService(appContext, intent)
                serviceRunning = true
            } catch (_: ForegroundServiceStartNotAllowedException) {
                // Background start refused: stay stopped; the next
                // foreground poll retries.
            }
        }
    }

    override fun stop() {
        serviceRunning = false
        appContext.stopService(Intent(appContext, DownloadProgressService::class.java))
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
