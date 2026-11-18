package app.trove.debrid.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardOfferTest {
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    private val torrent = "https://example.com/movie.TORRENT?token=123"

    @Test
    fun `offers magnets and torrent links`() {
        assertNotNull(ClipboardOffer.newLinkHash(magnet, emptySet()))
        assertNotNull(ClipboardOffer.newLinkHash(torrent, emptySet()))
    }

    @Test
    fun `ignores clipboard content outside magnet and torrent links`() {
        val ineligibleText = listOf(
            null,
            "",
            "  ",
            "plain text",
            magnet.substringAfter("btih:"),
            "https://example.com/movie.nzb",
            "https://example.com/watch?name=movie.torrent",
            "movie.torrent",
        )
        for (text in ineligibleText) {
            assertNull(ClipboardOffer.newLinkHash(text, emptySet()))
        }
    }

    @Test
    fun `previously recorded hashes suppress offers even after another link`() {
        val magnetHash = checkNotNull(ClipboardOffer.newLinkHash(magnet, emptySet()))
        val torrentHash = checkNotNull(ClipboardOffer.newLinkHash(torrent, setOf(magnetHash)))
        val restoredHashes = setOf(magnetHash, torrentHash)
        assertNull(ClipboardOffer.newLinkHash("  $magnet  ", restoredHashes))
        assertNull(ClipboardOffer.newLinkHash(torrent, restoredHashes))
    }
}
