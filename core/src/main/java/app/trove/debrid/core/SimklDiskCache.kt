package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.File

/** Persisted SIMKL snapshot: the activities watermark, the entry
 *  list, and the last-checked time. Only the fields the badges need
 *  are stored — no per-episode season data, no posters. */
data class SimklSnapshot(
    val activitiesJson: String,
    val activities: SimklActivities,
    val username: String?,
    val entries: List<SimklEntry>,
    val lastCheckedMs: Long,
    /** Epoch ms until which syncs must not run (daily quota spent);
     *  0 means no block. */
    val quotaBlockedUntilMs: Long = 0L,
    val lastError: String? = null,
)

/** Best-effort storage. Call on a background dispatcher. Modelled
 *  on [LibraryDiskCache]: one JSON file, atomic temp+rename, size
 *  bound, corrupt file deleted on read. */
class SimklDiskCache(private val directory: File) {
    private val file = File(directory, "simkl.json")

    @Synchronized
    fun load(): SimklSnapshot? {
        val snapshot = runCatching {
            if (!file.isFile) return null
            val size = file.length()
            if (size !in 1..MAX_BYTES.toLong()) return@runCatching null
            val bytes = ByteArray(size.toInt())
            DataInputStream(file.inputStream()).use {
                it.readFully(bytes)
                if (it.read() != -1) return@runCatching null
            }
            decode(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
        }.getOrNull()
        if (snapshot == null) runCatching { file.delete() }
        return snapshot
    }

    @Synchronized
    fun save(snapshot: SimklSnapshot) {
        runCatching {
            val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            directory.listFiles()?.filter { it.name.startsWith(file.name) && it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
            val temp = File.createTempFile(file.name, ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                temp.renameTo(file)
            } finally {
                temp.delete()
            }
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun encode(snapshot: SimklSnapshot) = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("activitiesJson", snapshot.activitiesJson)
        addProperty("username", snapshot.username)
        addProperty("lastCheckedMs", snapshot.lastCheckedMs)
        addProperty("quotaBlockedUntilMs", snapshot.quotaBlockedUntilMs)
        addProperty("lastError", snapshot.lastError)
        add("entries", JsonArray().apply {
            snapshot.entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("simklId", entry.simklId)
                    addProperty("type", entry.type.name)
                    addProperty("status", entry.status.apiValue)
                    addProperty("title", entry.title)
                    addProperty("imdbId", entry.imdbId)
                    addProperty("tmdbId", entry.tmdbId)
                    addProperty("slug", entry.slug)
                    addProperty("watchedEpisodes", entry.watchedEpisodes)
                    addProperty("totalEpisodes", entry.totalEpisodes)
                    addProperty("nextSeason", entry.nextSeason)
                    addProperty("nextEpisode", entry.nextEpisode)
                    addProperty("nextTitle", entry.nextTitle)
                })
            }
        })
    }

    private fun decode(json: JsonObject): SimklSnapshot? {
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return null
        val activitiesJson = json.optString("activitiesJson")?.takeIf { it.isNotBlank() } ?: return null
        val activities = runCatching {
            SimklActivities.parse(JsonParser.parseString(activitiesJson).asJsonObject)
        }.getOrNull() ?: return null
        val lastChecked = json.optLong("lastCheckedMs")?.takeIf { it > 0 } ?: return null
        val entries = json.getAsJsonArray("entries")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val simklId = obj.optInt("simklId") ?: return@mapNotNull null
            val type = runCatching { SimklType.valueOf(obj.optString("type").orEmpty()) }.getOrNull()
                ?: return@mapNotNull null
            val status = SimklStatus.fromApi(obj.optString("status")) ?: return@mapNotNull null
            SimklEntry(
                simklId = simklId,
                type = type,
                status = status,
                title = obj.optString("title"),
                imdbId = obj.optString("imdbId"),
                tmdbId = obj.optInt("tmdbId"),
                slug = obj.optString("slug"),
                watchedEpisodes = obj.optInt("watchedEpisodes"),
                totalEpisodes = obj.optInt("totalEpisodes"),
                nextSeason = obj.optInt("nextSeason"),
                nextEpisode = obj.optInt("nextEpisode"),
                nextTitle = obj.optString("nextTitle"),
            )
        }.orEmpty()
        return SimklSnapshot(
            activitiesJson = activitiesJson,
            activities = activities,
            username = json.optString("username"),
            entries = entries,
            lastCheckedMs = lastChecked,
            quotaBlockedUntilMs = json.optLong("quotaBlockedUntilMs") ?: 0L,
            lastError = json.optString("lastError"),
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
