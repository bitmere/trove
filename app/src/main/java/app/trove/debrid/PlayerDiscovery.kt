package app.trove.debrid

import app.trove.debrid.core.VideoPlayer

/** No playback URL parameter: discovery can only query a harmless synthetic resource. */
fun interface PlayerDiscovery {
    suspend fun players(mimeType: String): List<VideoPlayer>
}

/** Shared query specification, executable in JVM tests without Android. */
internal data class VideoPlayerQuery(val mimeType: String) {
    val url: String get() = "https://example.invalid/sample.mp4"
}

internal class SyntheticPlayerDiscovery(
    private val queryPlayers: suspend (VideoPlayerQuery) -> List<VideoPlayer>,
) : PlayerDiscovery {
    override suspend fun players(mimeType: String): List<VideoPlayer> = queryPlayers(VideoPlayerQuery(mimeType))
}
