package app.trove.debrid

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.VideoPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlayerDiscovery(
    private val packageManager: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlayerDiscovery {
    private val discovery = SyntheticPlayerDiscovery { query -> queryPlayers(query) }

    override suspend fun players(mimeType: String): List<VideoPlayer> = discovery.players(mimeType)

    private suspend fun queryPlayers(query: VideoPlayerQuery): List<VideoPlayer> = withContext(ioDispatcher) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(query.url.toUri(), query.mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            @Suppress("DEPRECATION") // int overload also supports API 26–32.
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { resolved ->
                    val activity = resolved.activityInfo ?: return@mapNotNull null
                    if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) {
                        return@mapNotNull null
                    }
                    VideoPlayer(
                        PlayerComponent(activity.packageName, activity.name),
                        resolved.loadLabel(packageManager).toString(),
                    )
                }.distinctBy { it.component }
                .sortedWith(compareBy({ it.label.lowercase() }, { it.component.packageName }, { it.component.activityName }))
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
