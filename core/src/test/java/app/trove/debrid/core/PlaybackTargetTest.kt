package app.trove.debrid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTargetTest {
    private val saved = PlayerComponent("org.example.player", "org.example.player.VideoActivity")
    private val available = listOf(VideoPlayer(saved, "Video player"))

    @Test fun `no saved player uses chooser`() {
        assertEquals(PlaybackTarget.Chooser, playbackTarget(null, available))
    }

    @Test fun `installed saved component launches directly`() {
        assertEquals(PlaybackTarget.Direct(saved), playbackTarget(saved, available))
    }

    @Test fun `missing package uses chooser`() {
        assertEquals(PlaybackTarget.Chooser, playbackTarget(saved, emptyList()))
    }

    @Test fun `same package with a stale activity uses chooser`() {
        val renamed = VideoPlayer(saved.copy(activityName = "org.example.player.NewActivity"), "Video player")
        assertEquals(PlaybackTarget.Chooser, playbackTarget(saved, listOf(renamed)))
    }

    @Test fun `play with override uses chooser and leaves next ordinary play direct`() {
        assertEquals(PlaybackTarget.Chooser, playbackTarget(saved, available, askEveryTime = true))
        assertEquals(PlaybackTarget.Direct(saved), playbackTarget(saved, available))
    }
}
