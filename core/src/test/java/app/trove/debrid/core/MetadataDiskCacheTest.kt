package app.trove.debrid.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val query = MediaQuery("Example Show", 2026, "series")
    private val metadata = MediaMetadata(
        "Example Show", 2026, "series", "https://images.example.invalid/poster.jpg",
        imdbId = "tt0000001", overview = "A synopsis.", genres = listOf("Drama", "Mystery"),
        cast = listOf("Actor One", "Actor Two"), director = "Director One", imdbRating = 8.2,
        runtime = "45m", episodes = listOf(MediaEpisode(1, 2, "Second", "2026-01-02")),
        provenance = EnrichmentProvenance.CINEMETA,
    )

    @Test fun `round trips typed provenance and lookup order across cache instances`() {
        for (source in listOf(EnrichmentProvenance.CINEMETA, EnrichmentProvenance.TMDB,
            EnrichmentProvenance.CINEMETA_TMDB_POSTER, EnrichmentProvenance.TMDB_CINEMETA_BACKFILL)
        ) {
            val enriched = metadata.copy(provenance = source)
            MetadataDiskCache(folder.root, now = { 1000 }).put(query, enriched, tmdbFirst = true)
            val entry = MetadataDiskCache(folder.root).get(query)!!
            assertEquals(enriched, entry.metadata)
            assertTrue(entry.tmdbFirst)
            assertEquals(1000L, entry.fetchedAt)
        }
    }

    @Test fun `legacy sources keep their original meaning and lookup order`() {
        val disk = MetadataDiskCache(folder.root, now = { 1000 })
        disk.put(query, metadata)
        val file = folder.root.listFiles()!!.single()
        for ((source, provenance) in mapOf("cinemeta" to EnrichmentProvenance.CINEMETA,
            "tmdb" to EnrichmentProvenance.LEGACY_TMDB)
        ) {
            file.writeText(
                """{"schemaVersion":1,"source":"$source","title":"Example Show","fetchedAt":1000,
                   "posterUrl":"https://image.tmdb.org/t/p/w342/example.jpg"}""",
            )
            val entry = disk.get(query)!!
            assertEquals(provenance, entry.metadata.provenance)
            assertEquals("https://image.tmdb.org/t/p/w342/example.jpg", entry.metadata.posterUrl)
            assertFalse(entry.tmdbFirst)
        }
    }

    @Test fun `posterless writes preserve poster order provenance and timestamp`() {
        var time = 1000L
        val disk = MetadataDiskCache(folder.root, now = { time })
        val posterless = metadata.copy(posterUrl = null, provenance = EnrichmentProvenance.TMDB)
        disk.put(query, posterless, tmdbFirst = true)
        assertEquals(posterless, disk.get(query)?.metadata)
        time++
        disk.put(query, posterless.copy(overview = "Updated"), tmdbFirst = true)
        assertEquals("Updated", disk.get(query)?.metadata?.overview)
        time++
        disk.put(query, metadata)
        assertEquals(MetadataDiskCache.DiskEntry(metadata, time, false), disk.get(query))
        time++
        disk.put(query, posterless, tmdbFirst = true)
        assertEquals(MetadataDiskCache.DiskEntry(metadata, time - 1, false), disk.get(query))
    }

    @Test fun `normalizes titles and distinguishes years and types`() {
        val disk = MetadataDiskCache(folder.root)
        disk.put(query, metadata)
        val equivalent = query.copy(title = "ÉXAMPLE.show!")
        assertEquals(metadata, disk.get(equivalent)?.metadata)
        disk.put(equivalent, metadata)
        val otherYear = query.copy(year = null)
        val otherType = query.copy(type = null)
        assertNull(disk.get(otherYear))
        assertNull(disk.get(otherType))
        disk.put(otherYear, metadata)
        disk.put(otherType, metadata)
        val files = folder.root.listFiles()!!
        assertEquals(3, files.size)
        assertTrue(files.all { it.name.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun `count and byte limits evict the least recently used entry after reopening`() {
        val clock = { 1000L }
        for (byCount in listOf(true, false)) {
            val directory = folder.newFolder()
            val seed = MetadataDiskCache(directory, now = clock)
            val queries = List(4) { query.copy(year = 2020 + it) }
            queries.take(3).forEach { seed.put(it, metadata) }
            val maxBytes = if (byCount) Long.MAX_VALUE else directory.listFiles()!!.sumOf { it.length() }
            if (byCount) assertNotNull(seed.get(queries[0]))
            val disk = MetadataDiskCache(directory, maxEntries = if (byCount) 3 else 512,
                maxBytes = maxBytes, now = clock)
            if (!byCount) assertNotNull(disk.get(queries[0]))
            disk.put(queries[3], metadata)
            assertNull(disk.get(queries[1]))
            listOf(0, 2, 3).forEach { assertNotNull(disk.get(queries[it])) }
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= maxBytes)
        }
    }

    @Test fun `freshness expires after 180 days and rejects clock skew`() {
        var time = 1000L
        val disk = MetadataDiskCache(folder.root, now = { time })
        disk.put(query, metadata)
        val ttl = 180L * 24 * 60 * 60 * 1000
        time += ttl - 1
        assertTrue(disk.get(query)!!.isFresh(time))
        time++
        assertFalse(disk.get(query)!!.isFresh(time))
        time++
        assertFalse(disk.get(query)!!.isFresh(time))
        assertFalse(disk.get(query)!!.isFresh(999))
    }

    @Test fun `corrupt files are deleted and can be replaced`() {
        val disk = MetadataDiskCache(folder.root, now = { 1000 })
        for (broken in listOf(
            "garbage",
            """{"schemaVersion":2,"source":"cinemeta","title":"Example Show","fetchedAt":1000}""",
            """{"schemaVersion":1,"title":"Example Show","fetchedAt":1000""",
            """{"schemaVersion":1,"title":" ","fetchedAt":1000}""",
            """{"schemaVersion":1,"title":"Example Show","fetchedAt":0}""",
            " ".repeat(128 * 1024 + 1),
        )) {
            disk.put(query, metadata)
            val file = folder.root.listFiles()!!.single()
            file.writeText(broken)
            assertNull(disk.get(query))
            assertFalse(file.exists())
        }
        disk.put(query, metadata)
        assertEquals(metadata, disk.get(query)?.metadata)
    }

    @Test fun `caps names and handles missing optional fields`() {
        val disk = MetadataDiskCache(folder.root)
        val names = List(6) { "Name $it" }
        disk.put(query, metadata.copy(genres = names, cast = names))
        assertEquals(metadata.copy(genres = names.take(5), cast = names.take(5)), disk.get(query)?.metadata)
        folder.root.listFiles()!!.single().writeText(
            """{"schemaVersion":1,"source":"cinemeta","title":"Example Show","fetchedAt":1000,
                "year":{},"genres":false,"episodes":[null,{"season":1},{"season":1,"episode":2}]}""",
        )
        assertEquals(MediaMetadata("Example Show", null, null, episodes = listOf(MediaEpisode(1, 2, null, null)),
            provenance = EnrichmentProvenance.CINEMETA),
            disk.get(query)?.metadata)
    }

    @Test fun `rejects oversized entries and cleans temporary files`() {
        val disk = MetadataDiskCache(folder.root)
        folder.newFile("abandoned.tmp")
        disk.put(query, metadata)
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".tmp") })
        val episodes = List(4000) { MediaEpisode(1, it, "Episode $it", "2026-01-02") }
        disk.put(query, metadata.copy(episodes = episodes))
        assertEquals(metadata, disk.get(query)?.metadata)
        disk.put(query.copy(year = null), metadata.copy(title = " "))
        assertEquals(1, folder.root.listFiles()!!.size)
        disk.remove(query)
        assertNull(disk.get(query))
        disk.put(query, metadata)
        disk.put(query.copy(year = null), metadata)
        folder.newFile("abandoned.tmp")
        disk.clear()
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun `a plain file as the cache directory is harmless`() {
        val disk = MetadataDiskCache(folder.newFile())
        disk.put(query, metadata)
        assertNull(disk.get(query))
        disk.remove(query)
        disk.clear()
    }
}
