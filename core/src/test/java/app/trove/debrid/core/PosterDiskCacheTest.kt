package app.trove.debrid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PosterDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://images.example.org/poster.jpg"
    private val image = byteArrayOf(1, 2, 3)
    private fun decode(bytes: ByteArray): String? = if (bytes.contentEquals(image)) "poster" else null

    @Test fun `disk hit survives a new cache instance and skips network`() = runBlocking {
        PosterDiskCache(folder.root).put(url, image)
        val source = PosterSource(PosterDiskCache(folder.root), { error("network must not run") }, ::decode)
        assertEquals("poster", source.load(url))
        assertTrue(folder.root.listFiles()!!.single().name.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun `disk miss fetches and stores decoded poster`() = runBlocking {
        var calls = 0
        val source = PosterSource(PosterDiskCache(folder.root), { calls++; image }, ::decode)
        assertEquals("poster", source.load(url))
        assertEquals("poster", source.load(url))
        assertEquals(1, calls)
    }

    @Test fun `corrupt entry falls back to network and replaces bad bytes`() = runBlocking {
        val disk = PosterDiskCache(folder.root)
        disk.put(url, byteArrayOf(0))
        var calls = 0
        val source = PosterSource(disk, { calls++; image }, ::decode)
        assertEquals("poster", source.load(url))
        assertEquals(1, calls)
        assertArrayEquals(image, disk.get(url))
    }

    @Test fun `size cap evicts least recently used entry including after reopening`() {
        var time = 1000L
        val disk = PosterDiskCache(folder.root, maxBytes = 6, now = { time++ })
        val second = "https://images.example.org/second.jpg"
        val third = "https://images.example.org/third.jpg"
        disk.put(url, image)
        disk.put(second, image)
        assertNotNull(disk.get(url)) // First is now the most recently used.
        PosterDiskCache(folder.root, maxBytes = 6, now = { time++ }).put(third, image)
        assertNotNull(disk.get(url))
        assertNull(disk.get(second))
        assertNotNull(disk.get(third))
        assertTrue(folder.root.listFiles()!!.sumOf { it.length() } <= 6)
    }

    @Test fun `four megabyte entry is accepted and larger entries are rejected`() {
        val disk = PosterDiskCache(folder.root)
        val limit = 4 * 1024 * 1024
        disk.put(url, ByteArray(limit))
        assertEquals(limit, disk.get(url)!!.size)
        disk.put("https://images.example.org/huge.jpg", ByteArray(limit + 1))
        assertEquals(1, folder.root.listFiles()!!.size)
        folder.root.listFiles()!!.single().writeBytes(ByteArray(limit + 1))
        assertNull(disk.get(url))
    }

    @Test fun `disk failures still return network poster`() = runBlocking {
        val unusable = folder.newFile("not-a-directory")
        var calls = 0
        val source = PosterSource(PosterDiskCache(unusable), { calls++; image }, ::decode)
        assertEquals("poster", source.load(url))
        assertEquals(1, calls)
    }

    @Test fun `unsafe URLs never touch disk or network`() = runBlocking {
        var calls = 0
        val disk = PosterDiskCache(folder.root)
        val source = PosterSource(disk, { calls++; image }, ::decode)
        listOf("https://api.example.org/requestdl", "https://images.example.org/a?token=secret",
            "https://user:secret@images.example.org/a", "http://images.example.org/a").forEach {
            disk.put(it, image)
            assertNull(disk.get(it))
            assertNull(source.load(it))
        }
        assertEquals(0, calls)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun `invalid network image is never stored`() = runBlocking {
        val source = PosterSource(PosterDiskCache(folder.root), { byteArrayOf(0) }, ::decode)
        assertNull(source.load(url))
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
    @Test fun `throwing decoder on corrupt disk bytes still fetches network poster`() = runBlocking {
        val disk = PosterDiskCache(folder.root)
        disk.put(url, byteArrayOf(0))
        val source = PosterSource(disk, { image }) { bytes ->
            check(bytes.contentEquals(image))
            "poster"
        }
        assertEquals("poster", source.load(url))
    }

    @Test fun `oversized network poster is rejected before decoding and never stored`() = runBlocking {
        val source = PosterSource(PosterDiskCache(folder.root),
            { ByteArray(PosterDiskCache.MAX_ENTRY_BYTES + 1) },
            { _: ByteArray -> error("oversized bytes must not be decoded") })
        assertNull(source.load(url))
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun `known TMDB renditions reuse and migrate existing bytes without downloading again`() = runBlocking {
        val old = "https://image.tmdb.org/t/p/w342/example.jpg"
        val thumbnail = "https://image.tmdb.org/t/p/w185/example.jpg"
        val disk = PosterDiskCache(folder.root)
        disk.put(old, byteArrayOf(1))
        val source = PosterSource(disk, { error("Unexpected network call") }) { it.singleOrNull() }
        assertEquals(posterCacheKey(old), posterCacheKey(thumbnail))
        assertEquals(1.toByte(), source.load(thumbnail))
        assertEquals(1.toByte(), source.load(old))
        assertNull(disk.get(old))
        assertArrayEquals(byteArrayOf(1), disk.get(thumbnail))
        val other = "https://example.invalid/t/p/w342/example.jpg"
        assertEquals(other, posterCacheKey(other))
    }

}
