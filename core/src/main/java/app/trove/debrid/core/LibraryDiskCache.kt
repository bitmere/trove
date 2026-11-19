package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.File

data class LibrarySnapshot(
    val service: DebridService,
    val savedAt: Long,
    val tabs: Map<DownloadKind, Tab>,
) {
    data class Tab(val rows: List<DebridDownload>, val endReached: Boolean, val nextOffset: Int)

    companion object {
        const val MAX_ROWS_PER_TAB = 100
    }
}

/** Best-effort storage. Call on a background dispatcher. */
class LibraryDiskCache(private val directory: File) {
    private val file = File(directory, "library.json")

    @Synchronized
    fun load(): LibrarySnapshot? {
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
    fun save(snapshot: LibrarySnapshot) {
        runCatching {
            val textSize = snapshot.tabs.values.sumOf { tab ->
                tab.rows.take(LibrarySnapshot.MAX_ROWS_PER_TAB)
                    .sumOf { row -> row.id.length.toLong() + row.name.length }
            }
            if (textSize > MAX_BYTES) return
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

    private fun encode(snapshot: LibrarySnapshot) = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("service", snapshot.service.name)
        addProperty("savedAt", snapshot.savedAt)
        add("tabs", JsonObject().apply {
            snapshot.tabs.forEach { (kind, tab) ->
                add(kind.name, JsonObject().apply {
                    addProperty("endReached", tab.endReached)
                    addProperty("nextOffset", tab.nextOffset)
                    add("rows", JsonArray().apply {
                        tab.rows.take(LibrarySnapshot.MAX_ROWS_PER_TAB).forEach { add(encodeRow(it)) }
                    })
                })
            }
        })
    }

    // Use stable field names for minified builds. Provider error text and file details
    // are deliberately excluded along with the live transfer metrics.
    private fun encodeRow(row: DebridDownload) = JsonObject().apply {
        addProperty("id", row.id)
        addProperty("name", row.name)
        addProperty("sizeBytes", row.sizeBytes)
        addProperty("status", row.status.name)
        addProperty("progress", row.progress.takeIf { it.isFinite() } ?: 0f)
        addProperty("createdAtEpochMs", row.createdAtEpochMs)
        addProperty("active", row.active)
        addProperty("cached", row.cached)
        addProperty("expiresAtEpochMs", row.expiresAtEpochMs)
    }

    private fun decode(json: JsonObject): LibrarySnapshot? {
        if (json.get("schemaVersion").asString.toIntOrNull() != SCHEMA_VERSION) return null
        val service = DebridService.valueOf(json.get("service").asString)
        val savedAt = json.get("savedAt").asString.toLong()
        val tabs = json.getAsJsonObject("tabs").entrySet().associate { (name, value) ->
            val kind = DownloadKind.valueOf(name)
            val tab = value.asJsonObject
            val offset = tab.get("nextOffset").asString.toInt()
            require(offset >= 0)
            val rows = tab.getAsJsonArray("rows")
            require(rows.size() <= LibrarySnapshot.MAX_ROWS_PER_TAB)
            kind to LibrarySnapshot.Tab(
                rows.map { decodeRow(it.asJsonObject, service, kind) },
                tab.get("endReached").asString.toBooleanStrict(),
                offset,
            )
        }
        return LibrarySnapshot(service, savedAt, tabs)
    }

    private fun decodeRow(row: JsonObject, service: DebridService, kind: DownloadKind) = DebridDownload(
        id = row.get("id").asString,
        service = service,
        name = row.get("name").asString,
        sizeBytes = row.field("sizeBytes")?.toLong(),
        status = DownloadStatus.valueOf(row.get("status").asString),
        progress = row.get("progress").asFloat.also { require(it.isFinite()) },
        kind = kind,
        createdAtEpochMs = row.field("createdAtEpochMs")?.toLong(),
        active = row.field("active")?.toBooleanStrict(),
        cached = row.field("cached")?.toBooleanStrict(),
        expiresAtEpochMs = row.field("expiresAtEpochMs")?.toLong(),
    )

    private fun JsonObject.field(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
