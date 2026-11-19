package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** Query-keyed metadata storage. Call from a background dispatcher, including construction. */
class MetadataDiskCache(
    private val directory: File,
    private val maxEntries: Int = 512,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val TTL_MS = 180L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRY_BYTES = 128 * 1024
        private const val SCHEMA_VERSION = 1
        private val keyPattern = Regex("[0-9a-f]{64}")
    }

    data class DiskEntry(val metadata: MediaMetadata, val source: String, val fetchedAt: Long) {
        fun isFresh(now: Long): Boolean = fetchedAt > 0 && now >= fetchedAt && now - fetchedAt < TTL_MS
    }

    private var lastAccess = runCatching {
        entryFiles().maxOfOrNull { it.lastModified() } ?: 0L
    }.getOrDefault(0L)

    @Synchronized
    fun get(query: MediaQuery): DiskEntry? = runCatching {
        val file = file(query)
        if (!file.isFile) return null
        val entry = runCatching read@{
            val size = file.length()
            if (size !in 1..MAX_ENTRY_BYTES.toLong()) return@read null
            val bytes = ByteArray(size.toInt())
            file.inputStream().use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset != bytes.size || input.read() != -1) return@use null
                decode(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
            }
        }.getOrNull()
        if (entry == null) file.delete() else touch(file)
        entry
    }.getOrNull()

    @Synchronized
    fun put(query: MediaQuery, metadata: MediaMetadata, source: String = "cinemeta") {
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (metadata.title.isBlank() || maxEntries <= 0) return
            val fetchedAt = now()
            if (fetchedAt <= 0) return
            val limit = minOf(MAX_ENTRY_BYTES.toLong(), maxBytes)
            if (minimumSize(metadata, source) > limit) return
            val bytes = encode(metadata, source, fetchedAt).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > limit) return

            val target = file(query)
            val temp = File.createTempFile(target.name, ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                if (temp.renameTo(target)) {
                    touch(target)
                    evict()
                }
            } finally {
                temp.delete()
            }
        }
    }

    @Synchronized
    fun remove(query: MediaQuery) {
        runCatching { file(query).delete() }
    }

    @Synchronized
    fun clear() {
        runCatching {
            directory.listFiles()?.filter { it.isFile && (keyPattern.matches(it.name) || it.name.endsWith(".tmp")) }
                ?.forEach { it.delete() }
        }
    }

    private fun file(query: MediaQuery): File {
        val identity = "${normalizedTitle(query.title)}|${query.year ?: ""}|${query.type.orEmpty()}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, key)
    }

    private fun entryFiles(): List<File> =
        directory.listFiles()?.filter { it.isFile && keyPattern.matches(it.name) }.orEmpty()

    private fun touch(file: File) {
        lastAccess = maxOf(now(), lastAccess + 1, file.lastModified() + 1)
        file.setLastModified(lastAccess)
    }

    private fun evict() {
        val entries = entryFiles()
        var count = entries.size
        var bytes = entries.sumOf { it.length() }
        if (count <= maxEntries && bytes <= maxBytes) return
        for (entry in entries.sortedBy { it.lastModified() }) {
            val size = entry.length()
            if (entry.delete()) {
                count--
                bytes -= size
            }
            if (count <= maxEntries && bytes <= maxBytes) break
        }
    }

    // Reject large payloads before allocating their JSON tree or UTF-8 buffer.
    private fun minimumSize(metadata: MediaMetadata, source: String): Long = with(metadata) {
        listOf(title, type, posterUrl, imdbId, overview, director, runtime, source)
            .sumOf { it.orEmpty().length.toLong() } +
            genres.sumOf { it.length + 2L } + cast.sumOf { it.length + 2L } +
            episodes.sumOf { 24L + it.title.orEmpty().length + it.released.orEmpty().length }
    }

    private fun encode(metadata: MediaMetadata, source: String, fetchedAt: Long): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("source", source)
        addProperty("fetchedAt", fetchedAt)
        addProperty("title", metadata.title)
        addProperty("year", metadata.year)
        addProperty("type", metadata.type)
        addProperty("posterUrl", metadata.posterUrl)
        addProperty("imdbId", metadata.imdbId)
        addProperty("overview", metadata.overview)
        add("genres", JsonArray().apply { metadata.genres.forEach { add(it) } })
        add("cast", JsonArray().apply { metadata.cast.forEach { add(it) } })
        addProperty("director", metadata.director)
        addProperty("imdbRating", metadata.imdbRating?.takeIf { it.isFinite() })
        addProperty("runtime", metadata.runtime)
        add("episodes", JsonArray().apply {
            metadata.episodes.forEach { episode ->
                add(JsonObject().apply {
                    addProperty("season", episode.season)
                    addProperty("episode", episode.episode)
                    addProperty("title", episode.title)
                    addProperty("released", episode.released)
                })
            }
        })
    }

    private fun decode(json: JsonObject): DiskEntry? = with(json) {
        if (primitiveField("schemaVersion")?.toIntOrNull() != SCHEMA_VERSION) return null
        val title = stringField("title")?.takeIf { it.isNotBlank() } ?: return null
        val source = stringField("source") ?: return null
        val fetchedAt = primitiveField("fetchedAt")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val metadata = MediaMetadata(
            title = title,
            year = primitiveField("year")?.toIntOrNull(),
            type = stringField("type"),
            posterUrl = stringField("posterUrl"),
            imdbId = stringField("imdbId"),
            overview = stringField("overview"),
            genres = names("genres"),
            cast = names("cast"),
            director = stringField("director"),
            imdbRating = primitiveField("imdbRating")?.toDoubleOrNull()?.takeIf { it.isFinite() },
            runtime = stringField("runtime"),
            episodes = elements("episodes").mapNotNull { element ->
                val episode = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val season = episode.primitiveField("season")?.toIntOrNull() ?: return@mapNotNull null
                val number = episode.primitiveField("episode")?.toIntOrNull() ?: return@mapNotNull null
                MediaEpisode(season, number, episode.stringField("title"), episode.stringField("released"))
            }.toList(),
        )
        DiskEntry(metadata, source, fetchedAt)
    }

    private fun JsonObject.elements(name: String): Sequence<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.asSequence() ?: emptySequence()

    private fun JsonObject.primitiveField(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.stringField(name: String): String? = get(name)?.stringValue()

    private fun JsonObject.names(name: String): List<String> =
        elements(name).mapNotNull { it.stringValue() }.take(5).toList()

    private fun JsonElement.stringValue(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
