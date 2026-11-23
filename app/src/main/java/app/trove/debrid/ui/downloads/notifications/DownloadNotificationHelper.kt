package app.trove.debrid.ui.downloads.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.trove.debrid.MainActivity
import app.trove.debrid.R

const val DOWNLOAD_PROGRESS_CHANNEL_ID = "trove_download_progress"
const val DOWNLOAD_PROGRESS_GROUP_KEY = "trove_download_progress"
/** Fixed id for the group summary notification. */
val DOWNLOAD_PROGRESS_SUMMARY_ID: Int = "trove_download_progress_summary".hashCode() and Int.MAX_VALUE

/** Stable notification id for one download, keyed by kind:id so re-posts
 *  update the same row across service restarts. */
fun notificationIdFor(kind: app.trove.debrid.core.DownloadKind, id: String): Int =
    "${kind.name}:$id".hashCode() and Int.MAX_VALUE

/** Pure content of one progress notification, computed without any
 *  Android framework call so it is unit-testable. */
data class NotificationContent(
    val title: String,
    val text: String,
    val percent: Int,
    val indeterminate: Boolean,
    /** Speed/ETA part only, without the percent — used by the repost
     *  decider so a 1% move alone does not force a re-post. */
    val details: String,
)

/** Maps an active download to its notification content. Text matches the
 *  list row: "42% · 74 MB/s · 2m 3s left". */
fun notificationContentFor(download: ActiveDownload, queuedLabel: String): NotificationContent {
    if (download.queued) {
        return NotificationContent(
            title = download.name,
            text = queuedLabel,
            percent = 0,
            indeterminate = true,
            details = "",
        )
    }
    val percent = download.percent.coerceIn(0, 100)
    val details = listOfNotNull(download.speedText, download.etaText).joinToString(" · ")
    val text = listOfNotNull("$percent%", download.speedText, download.etaText).joinToString(" · ")
    return NotificationContent(
        title = download.name,
        text = text,
        percent = percent,
        indeterminate = false,
        details = details,
    )
}

/** Decides whether a notification is worth re-posting: only when its
 *  displayed values changed meaningfully (progress moved >= 2 points, or
 *  the title/details changed). Re-posting an identical notification every
 *  minute is noise and churns the shade. Pure and unit-testable. */
class NotificationRepostDecider(private val thresholdPercent: Int = 2) {
    private val lastPosted = mutableMapOf<Int, NotificationContent>()

    fun shouldPost(notificationId: Int, content: NotificationContent): Boolean {
        val last = lastPosted[notificationId] ?: return true
        if (content.title != last.title) return true
        if (content.indeterminate != last.indeterminate) return true
        if (content.details != last.details) return true
        return kotlin.math.abs(content.percent - last.percent) >= thresholdPercent
    }

    fun recordPosted(notificationId: Int, content: NotificationContent) {
        lastPosted[notificationId] = content
    }

    fun forget(notificationId: Int) {
        lastPosted.remove(notificationId)
    }

    fun forgetAll() {
        lastPosted.clear()
    }
}

/** Builds progress notifications and owns the channel. The service owns
 *  the posting decisions (which ids exist, the repost decider); this
 *  class only turns an ActiveDownload into a Notification. */
class DownloadNotificationHelper(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(DOWNLOAD_PROGRESS_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_PROGRESS_CHANNEL_ID,
                context.getString(R.string.download_progress_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.download_progress_channel_description)
            },
        )
    }

    fun notificationIdFor(download: ActiveDownload): Int =
        notificationIdFor(download.kind, download.id)

    private fun contentIntent(notificationId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun build(download: ActiveDownload): Notification {
        val id = notificationIdFor(download)
        val content = notificationContentFor(download, context.getString(R.string.queued))
        return NotificationCompat.Builder(context, DOWNLOAD_PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setProgress(100, content.percent, content.indeterminate)
            .setOngoing(true)
            .setGroup(DOWNLOAD_PROGRESS_GROUP_KEY)
            .setContentIntent(contentIntent(id))
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildSummary(count: Int): Notification {
        val text = context.getString(R.string.download_notifications_summary, count)
        return NotificationCompat.Builder(context, DOWNLOAD_PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text)
            .setGroup(DOWNLOAD_PROGRESS_GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(contentIntent(DOWNLOAD_PROGRESS_SUMMARY_ID))
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notify(notificationId: Int, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    fun cancel(notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }
}
