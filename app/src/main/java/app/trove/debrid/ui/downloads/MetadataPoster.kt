package app.trove.debrid.ui.downloads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import app.trove.debrid.R
import app.trove.debrid.core.PosterDiskCache
import app.trove.debrid.core.PosterSource
import app.trove.debrid.core.publicPosterUrl
import app.trove.debrid.core.posterCacheKey
import java.io.File
import androidx.compose.ui.unit.dp
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.OkHttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Small session cache with sampled decoding and one in-flight load per URL. */
internal object PosterLoader {
    private val transport = OkHttpTransport()
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<PosterLoad<Bitmap>>>()
    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val misses = PosterMissCache(SystemClock::elapsedRealtime)
    private val _loadGeneration = MutableStateFlow(0L)
    val loadGeneration = _loadGeneration.asStateFlow()

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

    private var prefetchJob: Job? = null
    private val visibleLoads = MutableStateFlow(0)

    suspend fun loadVisible(url: String, force: Boolean, cacheDirectory: () -> File): Bitmap? {
        mutex.withLock {
            val key = posterCacheKey(url)
            bitmaps.get(key)?.let { return it }
            if (force) misses.clear(key)
            else if (misses.get(key)?.transient == false) return null
            visibleLoads.value++
            prefetchJob?.cancel()
        }
        try {
            return loadPosterWithRetry(url, SystemClock::elapsedRealtime, { delay(it) }) {
                withContext(Dispatchers.IO) { loadOnIo(it, cacheDirectory) }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { visibleLoads.value-- } }
        }
    }

    suspend fun prefetch(visible: List<String>, next: List<String>, cacheDirectory: () -> File) {
        if (next.isEmpty()) return
        visibleLoads.first { it == 0 }
        val job = currentCoroutineContext().job
        val admitted = mutex.withLock {
            val ready = visible.all {
                val key = posterCacheKey(it)
                bitmaps.get(key) != null || misses.get(key) != null
            }
            if (ready && visibleLoads.value == 0 && prefetchJob == null) {
                prefetchJob = job
                true
            } else false
        }
        if (!admitted) return
        try {
            for (url in next) {
                withContext(Dispatchers.IO) { loadOnIo(url, cacheDirectory) }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (prefetchJob === job) prefetchJob = null }
            }
        }
    }

    private suspend fun loadOnIo(url: String, cacheDirectory: () -> File): PosterLoad<Bitmap> {
        val key = posterCacheKey(url)
        while (true) {
            currentCoroutineContext().ensureActive()
            var ownsLoad = false
            val pending = mutex.withLock {
                bitmaps.get(key)?.let { return PosterLoad.Ready(it) }
                misses.get(key)?.let { return it }
                inFlight[key] ?: CompletableDeferred<PosterLoad<Bitmap>>().also {
                    inFlight[key] = it
                    ownsLoad = true
                }
            }
            if (ownsLoad) return fetchPoster(url, key, cacheDirectory, pending)

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
        key: String,
        cacheDirectory: () -> File,
        pending: CompletableDeferred<PosterLoad<Bitmap>>,
    ): PosterLoad<Bitmap> {
        val decoded = runCatching { posterSource(cacheDirectory).load(url) }
        return withContext(NonCancellable) {
            val result: Result<PosterLoad<Bitmap>> = mutex.withLock {
                val load = decoded.fold(
                    onSuccess = { bitmap ->
                        if (bitmap == null) Result.success(misses.record(key, transient = false))
                        else {
                            bitmaps.put(key, bitmap)
                            misses.clear(key)
                            _loadGeneration.value++
                            Result.success(PosterLoad.Ready(bitmap))
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException || error !is Exception) Result.failure(error)
                        else Result.success(misses.record(key, isTransientPosterFailure(error)))
                    },
                )
                inFlight.remove(key)
                load
            }
            result.fold(pending::complete, pending::completeExceptionally)
            result.getOrThrow()
        }
    }
}

internal val RowPosterSize = DpSize(48.dp, 72.dp)

internal data class PosterSlot<T>(val bitmap: T? = null, val decodedUrl: String? = null) {
    fun afterLoad(url: String, decoded: T?): PosterSlot<T> =
        if (decoded == null) this else PosterSlot(decoded, url)

    fun hasPoster(url: String?): Boolean = url != null || bitmap != null
}

@Composable
internal fun MetadataPoster(
    url: String?,
    size: DpSize = RowPosterSize,
    onFailure: suspend (String) -> Unit = {},
) {
    MetadataPoster(rememberPoster(url, onFailure), size)
}

internal data class PosterPresentation(
    val bitmap: Bitmap?,
    val hasPoster: Boolean,
    val onRetry: (() -> Unit)?,
)

@Composable
internal fun rememberPoster(
    url: String?,
    onFailure: suspend (String) -> Unit,
): PosterPresentation {
    val context = LocalContext.current.applicationContext
    val validUrl = remember(url) { publicPosterUrl(url) }
    var slot by remember { mutableStateOf(PosterSlot<Bitmap>()) }
    var failed by remember(validUrl) { mutableStateOf(false) }
    var retry by remember(validUrl) { mutableIntStateOf(0) }
    val reportFailure by rememberUpdatedState(onFailure)
    LaunchedEffect(validUrl, retry) {
        if (validUrl != null && slot.decodedUrl != validUrl) {
            val bitmap = awaitPoster(
                PosterLoader.loadGeneration,
                force = retry > 0,
                load = { force -> PosterLoader.loadVisible(validUrl, force) { context.cacheDir } },
                onFailure = { failed = true },
            )
            ensureActive()
            slot = slot.afterLoad(validUrl, bitmap)
            failed = false
        }
    }
    LaunchedEffect(validUrl, retry, failed) {
        if (validUrl != null && failed) reportFailure(validUrl)
    }
    val onRetry: (() -> Unit)? = if (failed) {
        {
            failed = false
            retry++
        }
    } else null
    return PosterPresentation(slot.bitmap, slot.hasPoster(validUrl), onRetry)
}

@Composable
internal fun MetadataPoster(poster: PosterPresentation, size: DpSize) {
    val retryLabel = stringResource(R.string.retry_poster)
    val retryModifier = poster.onRetry?.let { onRetry ->
        Modifier.clickable(role = Role.Button, onClickLabel = retryLabel, onClick = onRetry)
    } ?: Modifier
    val failed = poster.onRetry != null
    val bitmap = poster.bitmap
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(size).then(retryModifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (failed) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { Icon(Icons.Filled.Refresh, retryLabel, tint = tint) }
                }
            } else if (failed) {
                Icon(Icons.Filled.Refresh, retryLabel, tint = tint)
            } else {
                Icon(painterResource(R.drawable.ic_poster_placeholder), null, tint = tint)
            }
        }
    }
}
