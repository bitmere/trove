package app.trove.debrid.core

/** Stable identifiers only. Labels are discovered afresh and never persisted. */
data class PlayerComponent(val packageName: String, val activityName: String)
data class VideoPlayer(val component: PlayerComponent, val label: String)

sealed interface PlaybackTarget {
    data object Chooser : PlaybackTarget
    data class Direct(val component: PlayerComponent) : PlaybackTarget
}

/** Availability is for the selected file's MIME type, queried without its bearer URL. */
fun playbackTarget(
    saved: PlayerComponent?,
    available: List<VideoPlayer>,
    askEveryTime: Boolean = false,
): PlaybackTarget = if (!askEveryTime && saved != null && available.any { it.component == saved }) {
    PlaybackTarget.Direct(saved)
} else {
    PlaybackTarget.Chooser
}
