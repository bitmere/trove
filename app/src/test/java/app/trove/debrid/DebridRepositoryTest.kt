package app.trove.debrid

import android.content.SharedPreferences
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.data.DebridRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** In-memory SharedPreferences for repository tests (no Robolectric). */
class FakeSharedPreferences : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun contains(key: String) = map.containsKey(key)
    override fun getAll(): Map<String, *> = map.toMap()
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        map[key] as? MutableSet<String> ?: defValues
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun edit(): SharedPreferences.Editor = FakeEditor()

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            applyChanges()
            return true
        }
        override fun apply() = applyChanges()
        private fun applyChanges() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}

class FakeHttpTransport(
    var handler: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, "{}") },
) : HttpTransport {
    var calls: Int = 0
    override suspend fun execute(request: HttpRequest): HttpResponse {
        calls++
        return handler(request)
    }
}

private const val VALID_ME = """{"success":true,"data":{"email":"user@example.com","plan":1}}"""

class DebridRepositoryTest {

    private fun repository(
        transport: HttpTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
    ) = DebridRepository(
        prefs = prefs,
        credentialStore = store,
        transport = transport,
        ioDispatcher = Dispatchers.Unconfined,
    ) to store

    @Test
    fun `valid key stores the trimmed key and account`() = runTest {
        val (repo, store) = repository(FakeHttpTransport { HttpResponse(200, VALID_ME) })
        val result = repo.validateAndSaveTorboxKey("  my-key  ")
        assertTrue(result.isSuccess)
        assertEquals("TorBox (Plan Essential)", result.getOrThrow().label)
        assertEquals("my-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(1, repo.accounts().size)
    }

    @Test
    fun `linked torrent upload uses the file path name without the query or fragment`() = runTest {
        val url = "https://example.com/My%20File.torrent?return=/other#source"
        val (repo, _) = repository(FakeHttpTransport { request ->
            assertEquals(url, request.url)
            HttpResponse(200, "file bytes")
        })
        val provider = FakeProvider()
        assertTrue(repo.addClassified(provider, app.trove.debrid.core.ClassifiedShare.TorrentUrl(url)).isSuccess)
        val input = provider.addedInputs.single() as app.trove.debrid.core.AddInput.TorrentFile
        assertEquals("My File.torrent", input.fileName)
    }

    @Test
    fun `rejected key returns failure and writes nothing`() = runTest {
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(
            FakeHttpTransport { HttpResponse(401, """{"success":false,"error":"unauthorized"}""") },
            prefs = prefs,
        )
        val result = repo.validateAndSaveTorboxKey("bad-key")
        assertTrue(result.isFailure)
        assertNull(store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertTrue(repo.accounts().isEmpty())
        assertFalse(prefs.map.containsKey("accounts"))
    }

    @Test
    fun `rate limit and malformed responses return failure without throwing`() = runTest {
        val rateLimited = repository(FakeHttpTransport { HttpResponse(429, "{}") }).first
        assertTrue(rateLimited.validateAndSaveTorboxKey("k").isFailure)

        val malformed = repository(FakeHttpTransport { HttpResponse(200, "not json") }).first
        assertTrue(malformed.validateAndSaveTorboxKey("k").isFailure)

        val missingData = repository(FakeHttpTransport { HttpResponse(200, """{"success":true}""") }).first
        assertTrue(missingData.validateAndSaveTorboxKey("k").isFailure)
    }

    @Test
    fun `a throwing transport returns failure, never a crash`() = runTest {
        val (repo, store) = repository(FakeHttpTransport { throw IOException("offline") })
        val result = repo.validateAndSaveTorboxKey("k")
        assertTrue(result.isFailure)
        assertNull(store.get(DebridRepository.TORBOX_ACCOUNT_ID))
    }

    @Test
    fun `a throwing credential store is reported as failure`() = runTest {
        val throwingStore = object : app.trove.debrid.core.CredentialStore {
            override fun save(accountId: String, secret: String): Nothing =
                throw IllegalStateException("keystore locked")
            override fun get(accountId: String): String? = null
            override fun delete(accountId: String) = Unit
            override fun has(accountId: String) = false
        }
        val repo = DebridRepository(
            prefs = FakeSharedPreferences(),
            credentialStore = throwingStore,
            transport = FakeHttpTransport { HttpResponse(200, VALID_ME) },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = repo.validateAndSaveTorboxKey("k")
        assertTrue(result.isFailure)
        assertTrue(repo.accounts().isEmpty())
    }

    @Test
    fun `cancellation still cancels instead of becoming a failure`() = runTest {
        val (repo, _) = repository(FakeHttpTransport {
            delay(Long.MAX_VALUE)
            HttpResponse(200, VALID_ME)
        })
        val deferred = async { repo.validateAndSaveTorboxKey("k") }
        delay(10)
        deferred.cancel()
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Expected: cancellation propagates, it is not wrapped.
        }
    }
    @Test
    fun `repository play carries the selected download kind to TorBox`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "test-key")
        val transport = FakeHttpTransport { request ->
            assertTrue(request.url.contains("/webdl/requestdl"))
            assertTrue(request.url.contains("web_id=11"))
            assertTrue(request.url.contains("file_id=1"))
            HttpResponse(200, """{"success":true,"data":"https://example.com/play"}""")
        }
        val (repo, _) = repository(transport, store)
        val result = repo.play(repo.provider()!!, sampleDownload(kind = app.trove.debrid.core.DownloadKind.WEB), "1")
        assertEquals("https://example.com/play", result.getOrThrow().url)
        assertEquals(1, transport.calls)
    }

}
