package app.trove.debrid.ui.downloads.notifications

import app.trove.debrid.core.DownloadKind

/** One active download, as primitives only. This crosses into the
 *  foreground service via intent extras, so it carries no DebridDownload
 *  (not Parcelable; core is Android-free), no URL, no file path, and
 *  never the API key. */
data class ActiveDownload(
    val kind: DownloadKind,
    val id: String,
    val name: String,
    val queued: Boolean,
    /** 0..100; 0 for queued rows. */
    val percent: Int,
    val speedText: String?,
    val etaText: String?,
)

/** Starts, updates, and stops the download-progress foreground service.
 *  The ViewModel talks to this interface only, so it stays Context-free
 *  and unit tests can substitute a fake. The real implementation lives
 *  next to the service and checks POST_NOTIFICATIONS before starting
 *  anything. */
interface DownloadNotificationController {
    fun sync(active: List<ActiveDownload>)
    fun stop()

    object NoOp : DownloadNotificationController {
        override fun sync(active: List<ActiveDownload>) = Unit
        override fun stop() = Unit
    }
}
