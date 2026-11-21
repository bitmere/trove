package app.trove.debrid.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimklDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun snapshot(entries: List<SimklEntry> = emptyList()): SimklSnapshot {
        val activitiesJson = """
            {"all":"2026-11-21T00:00:00Z","settings":{"all":"2026-11-21T00:00:00Z"},
            "tv_shows":{"all":"2026-11-21T00:00:00Z","watching":"2026-11-21T00:00:00Z"}}
        """.trimIndent()
        val activities = SimklActivities.parse(
            com.google.gson.JsonParser.parseString(activitiesJson).asJsonObject)
        return SimklSnapshot(
            activitiesJson = activitiesJson, activities = activities, username = "tester",
            entries = entries, lastCheckedMs = 1_700_000_000_000L,
            quotaBlockedUntilMs = 0L, lastError = null,
        )
    }

    private fun entry(simklId: Int = 123) = SimklEntry(
        simklId = simklId, type = SimklType.SHOW, status = SimklStatus.WATCHING,
        title = "Example Show", imdbId = "tt1234567", tmdbId = 456, slug = "example-show",
        watchedEpisodes = 4, totalEpisodes = 20, nextSeason = 1, nextEpisode = 5, nextTitle = "Chapter Five",
    )

    @Test fun roundTripsSnapshotAndEntries() {
        val directory = temporary.newFolder()
        val cache = SimklDiskCache(directory)
        val original = snapshot(listOf(entry()))
        cache.save(original)
        assertEquals(original, SimklDiskCache(directory).load())
    }

    @Test fun storesOnlyBadgeFields() {
        val directory = temporary.newFolder()
        val cache = SimklDiskCache(directory)
        cache.save(snapshot(listOf(entry())))
        val encoded = directory.listFiles()!!.single().readText()
        // No per-episode season data or posters are persisted; only
        // the badge counts (watched/total/next) are stored.
        assertFalse(encoded.contains("seasons"))
        assertFalse(encoded.contains("poster"))
    }

    @Test fun deletesCorruptUnsupportedAndOversizedFiles() {
        val directory = temporary.newFolder()
        val cache = SimklDiskCache(directory)
        val file = File(directory, "simkl.json")
        for (contents in listOf("{broken", """{"schemaVersion":2}""", "x".repeat(2 * 1024 * 1024 + 1))) {
            file.writeText(contents)
            assertNull(cache.load())
            assertFalse(file.exists())
        }
    }

    @Test fun clearDeletesTheFile() {
        val directory = temporary.newFolder()
        val cache = SimklDiskCache(directory)
        cache.save(snapshot())
        assertTrue(File(directory, "simkl.json").exists())
        cache.clear()
        assertFalse(File(directory, "simkl.json").exists())
        assertNull(cache.load())
    }
}
