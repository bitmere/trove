package app.trove.debrid.ui.downloads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.trove.debrid.core.PosterDiskCache
import app.trove.debrid.core.PosterSource
import java.io.File
import androidx.compose.ui.unit.dp
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.OkHttpTransport
import app.trove.debrid.core.publicPosterUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Small session cache with sampled decoding and one in-flight load per URL. */
private object PosterLoader {
    private val transport = OkHttpTransport()
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Bitmap?>>()
    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val misses = object : LinkedHashMap<String, Long>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }

    private val sourceLock = Any()
    private var source: PosterSource<Bitmap>? = null

    private fun posterSource(cacheDirectory: () -> File): PosterSource<Bitmap> = synchronized(sourceLock) {
        source ?: PosterSource(
            runCatching { PosterDiskCache(File(cacheDirectory(), "posters")) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull(),
            { posterUrl ->
                transport.executeBytes(HttpRequest("GET", posterUrl), PosterDiskCache.MAX_ENTRY_BYTES)
            },
            ::decode,
        ).also { source = it }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 384) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    suspend fun load(url: String, cacheDirectory: () -> File): Bitmap? = withContext(Dispatchers.IO) {
        loadOnIo(url, cacheDirectory)
    }

    private suspend fun loadOnIo(url: String, cacheDirectory: () -> File): Bitmap? {
        while (true) {
            var ownsLoad = false
            val pending = mutex.withLock {
                bitmaps.get(url)?.let { return it }
                if ((misses[url] ?: 0) > System.currentTimeMillis()) return null
                inFlight[url] ?: CompletableDeferred<Bitmap?>().also {
                    inFlight[url] = it
                    ownsLoad = true
                }
            }
            if (ownsLoad) return fetchPoster(url, cacheDirectory, pending)

            try {
                return pending.await()
            } catch (_: CancellationException) {
                // A visible card can take over if the original caller scrolled away.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun fetchPoster(
        url: String,
        cacheDirectory: () -> File,
        pending: CompletableDeferred<Bitmap?>,
    ): Bitmap? {
        val result = runCatching { posterSource(cacheDirectory).load(url) }.recoverCatching {
            if (it is CancellationException || it !is Exception) throw it
            null
        }
        withContext(NonCancellable) {
            mutex.withLock {
                if (result.isSuccess) {
                    val bitmap = result.getOrNull()
                    if (bitmap != null) bitmaps.put(url, bitmap)
                    else misses[url] = System.currentTimeMillis() + 60_000
                }
                inFlight.remove(url)
            }
            result.fold(pending::complete, pending::completeExceptionally)
        }
        return result.getOrThrow()
    }
}

@Composable
internal fun MetadataPoster(url: String?) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        publicPosterUrl(url)?.let { bitmap = PosterLoader.load(it) { context.cacheDir } }
    }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(width = 64.dp, height = 96.dp)) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
    }
}
