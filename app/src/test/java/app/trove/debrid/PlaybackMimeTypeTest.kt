package app.trove.debrid

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMimeTypeTest {
    @Test
    fun `type comes from filename even for extensionless signed URLs`() {
        assertEquals("video/x-matroska", playbackMimeType("Folder/Movie.MKV"))
        assertEquals("video/mp4", playbackMimeType("episode.m4v"))
        assertEquals("video/mp2t", playbackMimeType("movie.m2ts"))
        assertEquals("video/webm", playbackMimeType("clip.webm"))
        assertEquals("video/*", playbackMimeType(null))
        assertEquals("video/*", playbackMimeType("unknown"))
    }
}
