package app.trove.debrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareClassifierTest {

    private val hosters = setOf("example.com", "example.net")

    @Test
    fun `magnet link classified with hash`() {
        val out = ShareClassifier.classify(
            ShareInput(text = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Dune"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.Magnet)
        assertEquals("0123456789abcdef0123456789abcdef01234567", (out as ClassifiedShare.Magnet).infoHashHex)
    }

    @Test
    fun `bare info hash becomes a magnet`() {
        val out = ShareClassifier.classify(
            ShareInput(text = "0123456789ABCDEF0123456789abcdef01234567"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.Magnet)
        assertEquals("0123456789abcdef0123456789abcdef01234567", (out as ClassifiedShare.Magnet).infoHashHex)
    }

    @Test
    fun `torrent file picked by extension`() {
        val out = ShareClassifier.classify(
            ShareInput(fileBytes = byteArrayOf(1, 2), fileName = "movie.torrent"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.TorrentFile)
    }

    @Test
    fun `torrent url classified as torrent url`() {
        val out = ShareClassifier.classify(
            ShareInput(text = "https://example.com/movie.torrent"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.TorrentUrl)
    }

    @Test
    fun `file type comes from the URL path and share text can use a newline`() {
        val torrent = "https://example.com/movie.torrent#source"
        assertEquals(ClassifiedShare.TorrentUrl(torrent), ShareClassifier.classify(ShareInput(text = "$torrent\nShared from browser"), hosters))
        val web = "https://example.com/watch#movie.nzb"
        assertEquals(ClassifiedShare.HosterLink(web), ShareClassifier.classify(ShareInput(text = web), hosters))
        assertEquals(ClassifiedShare.NotShareable, ShareClassifier.classify(ShareInput(text = "https://"), emptySet()))
    }

    @Test
    fun `hash is read only from a complete v1 exact topic parameter`() {
        val hash = "a".repeat(40)
        assertNull(ShareClassifier.extractInfoHash("magnet:?dn=xt=urn:btih:$hash"))
        assertNull(ShareClassifier.extractInfoHash("magnet:?dn=Name?xt=urn:btih:$hash"))
        assertNull(ShareClassifier.extractInfoHash("magnet:?xt=urn:btih:${hash}a"))
        assertEquals(hash, ShareClassifier.extractInfoHash("magnet:?dn=Movie&xt=urn:btih:$hash"))
    }

    @Test
    fun `nzb file and url`() {
        assertTrue(
            ShareClassifier.classify(
                ShareInput(fileBytes = byteArrayOf(1), fileName = "x.nzb"),
                hosters,
            ) is ClassifiedShare.Nzb,
        )
        assertTrue(
            ShareClassifier.classify(
                ShareInput(text = "https://example.com/x.nzb"),
                hosters,
            ) is ClassifiedShare.NzbUrl,
        )
    }

    @Test
    fun `supported hoster link`() {
        val out = ShareClassifier.classify(
            ShareInput(text = "https://www.example.com/file/abc123"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.HosterLink)
    }

    @Test
    fun `unsupported hoster warned`() {
        val out = ShareClassifier.classify(
            ShareInput(text = "https://example.org/file/1"),
            hosters,
        )
        assertTrue(out is ClassifiedShare.UnsupportedHoster)
    }

    @Test
    fun `empty and malformed input not shareable`() {
        assertTrue(ShareClassifier.classify(ShareInput(text = ""), hosters) is ClassifiedShare.NotShareable)
        assertTrue(ShareClassifier.classify(ShareInput(text = "hello world"), hosters) is ClassifiedShare.NotShareable)
        assertTrue(ShareClassifier.classify(ShareInput(), hosters) is ClassifiedShare.NotShareable)
    }

    @Test
    fun `base32 magnet hash yields null info hash`() {
        assertNull(ShareClassifier.extractInfoHash("magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"))
    }

    @Test
    fun `browser prose whitespace and stripped prefix produce the clean magnet`() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie%20Name"
        for (input in listOf("  $magnet  ", "A movie\n<$magnet>\nShared from browser", "Watch ($magnet)", magnet.removePrefix("magnet:"), magnet.removePrefix("magnet:?"))) {
            val share = ShareClassifier.classify(ShareInput(text = input), hosters) as ClassifiedShare.Magnet
            assertEquals(magnet, share.magnetUri)
        }
    }

    @Test
    fun `escaped parameters stay escaped and HTML ampersands normalize`() {
        val magnet = "magnet:?xt=urn:btih:aabbcc&dn=A%2BB&tr=https%3A%2F%2Ft.example%2Fa%3Fx%3D1%26y%3D2"
        assertEquals(magnet, ShareClassifier.cleanMagnet("\"${magnet.replace("&", "&amp;")}\""))
    }

}
