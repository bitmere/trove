package app.trove.debrid.update

import java.io.File
import java.util.Properties

/** One bounded, durable operation; kept in noBackupFilesDir by production wiring. */
data class PendingSession(val id: Int, val version: Long, val path: String, val operation: String, val createdAt: Long)

class PendingUpdateSession(
    private val recordFile: File,
    private val updatesDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private val lock = Any()
    }
    fun load(): PendingSession? = synchronized(lock) {
        if (!recordFile.isFile || recordFile.length() > 4096) return@synchronized null
        runCatching {
            val p = Properties().apply { recordFile.inputStream().use { load(it) } }
            PendingSession(p.getProperty("id").toInt(), p.getProperty("version").toLong(), p.getProperty("path"), p.getProperty("operation"), p.getProperty("created").toLong()).also {
                require(it.id >= 0 && it.version > 0 && it.operation.matches(Regex("[A-Za-z0-9-]{1,64}")))
                require(isOwnedPath(it.path))
            }
        }.getOrNull()
    }
    fun isOwnedPath(path: String): Boolean = runCatching {
        val file = File(path)
        file.absolutePath == file.canonicalPath && file.parentFile?.canonicalFile == updatesDir.canonicalFile && file.extension == "apk"
    }.getOrDefault(false)

    fun save(id: Int, version: Long, file: File, operation: String) = synchronized(lock) {
        require(id >= 0 && version > 0 && isOwnedPath(file.absolutePath) && operation.matches(Regex("[A-Za-z0-9-]{1,64}")))
        recordFile.parentFile?.mkdirs()
        val p = Properties().apply {
            setProperty("id", id.toString()); setProperty("version", version.toString())
            setProperty("path", file.absolutePath); setProperty("operation", operation); setProperty("created", now().toString())
        }
        val temp = File(recordFile.parentFile, recordFile.name + ".tmp")
        try {
            temp.outputStream().use { p.store(it, null); it.fd.sync() }
            check(temp.length() <= 4096 && temp.renameTo(recordFile))
        } finally { temp.delete() }
    }
    fun matches(id: Int, version: Long, path: String?, operation: String?): Boolean = synchronized(lock) {
        val saved = load() ?: return@synchronized false
        saved.id == id && saved.version == version && saved.path == path && saved.operation == operation &&
            now() - saved.createdAt in 0..MAX_AGE_MS
    }
    fun clear(operation: String) = synchronized(lock) {
        if (load()?.operation == operation) check(!recordFile.exists() || recordFile.delete())
    }
    /** Recover even an expired record. Never forget an unsettled system session. */
    fun abandon(abandonSession: (Int) -> Unit) = synchronized(lock) {
        val previous = load() ?: run {
            check(!recordFile.exists()) { "Unreadable pending installation record" }
            return@synchronized
        }
        abandonSession(previous.id)
        clear(previous.operation)
    }
}
