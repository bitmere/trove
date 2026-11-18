package app.trove.debrid.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Encoded public poster bytes only. Call from a background dispatcher, including construction. */
class PosterDiskCache(
    private val directory: File,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        private val keyPattern = Regex("[0-9a-f]{64}")
    }

    private var lastAccess = runCatching {
        directory.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
    }.getOrDefault(0L)

    private fun file(url: String): File? = publicPosterUrl(url)?.let {
        val key = MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        File(directory, key)
    }

    private fun touch(file: File) {
        lastAccess = maxOf(now(), lastAccess + 1, file.lastModified() + 1)
        file.setLastModified(lastAccess)
    }

    @Synchronized
    fun get(url: String): ByteArray? = runCatching {
        val file = file(url) ?: return null
        if (!file.isFile) return null
        val size = file.length()
        if (size !in 1..minOf(MAX_ENTRY_BYTES.toLong(), maxBytes)) {
            file.delete()
            return null
        }
        // One bounded buffer; reject truncation/growth rather than allocating unbounded bytes.
        val bytes = ByteArray(size.toInt())
        val complete = file.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
            offset == bytes.size && input.read() == -1
        }
        if (!complete) {
            file.delete()
            return null
        }
        touch(file)
        bytes
    }.getOrNull()

    @Synchronized
    fun put(url: String, bytes: ByteArray) {
        runCatching {
            val target = file(url) ?: return
            if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES || bytes.size > maxBytes) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            // Remove abandoned writes and evict before allocating the temporary file.
            directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (target.exists() && !target.delete()) return
            val entries = directory.listFiles()?.filter { it.isFile && keyPattern.matches(it.name) }.orEmpty()
            var total = entries.sumOf { it.length() }
            for (entry in entries.sortedBy { it.lastModified() }) {
                if (total + bytes.size > maxBytes) {
                    val size = entry.length()
                    if (entry.delete()) total -= size
                }
            }
            if (total + bytes.size > maxBytes) return
            val temp = File.createTempFile(target.name, ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                if (temp.renameTo(target)) touch(target)
            } finally {
                temp.delete()
            }
        }
    }

    @Synchronized
    fun remove(url: String) { runCatching { file(url)?.delete() } }
}

/** Decode is injected so corrupt-image fallback is tested on the JVM without Android. */
class PosterSource<T : Any>(
    private val disk: PosterDiskCache?,
    private val fetch: suspend (String) -> ByteArray,
    private val decode: (ByteArray) -> T?,
) {
    suspend fun load(url: String): T? {
        if (publicPosterUrl(url) == null) return null
        disk?.get(url)?.let { bytes ->
            val value = try { decode(bytes) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (value != null) return value
            disk?.remove(url)
        }
        val bytes = fetch(url)
        if (bytes.size > PosterDiskCache.MAX_ENTRY_BYTES) return null
        val value = decode(bytes) ?: return null
        disk?.put(url, bytes)
        return value
    }
}
