package app.trove.debrid

import android.content.SharedPreferences
import app.trove.debrid.core.AllDebridProvider
import app.trove.debrid.core.PremiumizeProvider
import app.trove.debrid.core.OffcloudProvider
import app.trove.debrid.core.DebridLinkProvider
import app.trove.debrid.core.CacheState
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.core.RealDebridProvider
import app.trove.debrid.core.ServiceCacheResult
import app.trove.debrid.core.TorBoxProvider
import app.trove.debrid.data.DebridRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

private const val VALID_REAL_DEBRID_USER = """{"username":"rd-user","type":"premium"}"""

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
    fun `provider lookup lists only signed in services in Settings order`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "torbox-key")
        val transport = FakeHttpTransport { error("Unexpected request") }
        val (repo, _) = repository(transport, store)
        repo.setActiveService(DebridService.REAL_DEBRID)

        assertNull(repo.providerFor(DebridService.PREMIUMIZE))
        assertTrue(repo.providerFor(DebridService.TORBOX) is TorBoxProvider)
        assertSame(repo.providerFor(DebridService.REAL_DEBRID), repo.provider())
        assertEquals(
            listOf(DebridService.TORBOX, DebridService.REAL_DEBRID),
            repo.signedInProviders().map { it.service },
        )
        assertEquals(0, transport.calls)
    }

    @Test
    fun `cross service cache check skips Real Debrid and returns TorBox hit`() = runTest {
        val hash = "aabbccddeeff00112233445566778899aabbccdd"
        assertCrossServiceCacheCheck(
            hash = hash,
            response = HttpResponse(200, """{"success":true,"data":{"$hash":{"name":"Cached torrent","size":100}}}"""),
            expectedTorbox = ServiceCacheResult(DebridService.TORBOX, CacheState.CACHED, CachedInfo(true, "Cached torrent", 100L)),
        )
    }

    @Test
    fun `TorBox transport failure leaves both service results unknown`() = runTest {
        assertCrossServiceCacheCheck(
            hash = "aabbccddeeff00112233445566778899aabbccdd",
            response = HttpResponse(500, "{}"),
            expectedTorbox = ServiceCacheResult(DebridService.TORBOX, CacheState.UNKNOWN),
        )
    }

    private suspend fun assertCrossServiceCacheCheck(
        hash: String,
        response: HttpResponse,
        expectedTorbox: ServiceCacheResult,
    ) {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "torbox-key")
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val requests = mutableListOf<HttpRequest>()
        val transport = FakeHttpTransport { request ->
            requests += request
            response
        }
        val (repo, _) = repository(transport, store)

        assertEquals(
            listOf(expectedTorbox, ServiceCacheResult(DebridService.REAL_DEBRID, CacheState.UNKNOWN)),
            repo.checkCachedEverywhere(hash),
        )
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("https://api.torbox.app/v1/api/torrents/checkcached", requests.single().url.substringBefore('?'))
    }

    @Test
    fun `listDownloads applies saved exclusions on each fetch including a cleared list`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "test-key")
        val transport = FakeHttpTransport { request ->
            val data = if (request.url.contains("/torrents/mylist")) {
                """[
                    {"id":1,"name":"Film.CAM"},
                    {"id":2,"name":"Camera.REMUX"},
                    {"id":3,"name":"Film.1080p"}
                ]"""
            } else {
                "[]"
            }
            HttpResponse(200, """{"success":true,"data":$data}""")
        }
        val (repo, _) = repository(transport, store)
        val provider = requireNotNull(repo.provider())

        assertEquals(listOf("CAM", "TS"), repo.loadExcludeWords())
        assertEquals(listOf("2", "3"), repo.listDownloads(provider).getOrThrow().map { it.id })

        repo.saveExcludeWords(listOf(" remux ", "REMUX", " "))
        assertEquals(listOf("remux"), repo.loadExcludeWords())
        assertEquals(listOf("1", "3"), repo.listDownloads(provider).getOrThrow().map { it.id })

        repo.saveExcludeWords(emptyList())
        assertEquals(emptyList<String>(), repo.loadExcludeWords())
        assertEquals(listOf("1", "2", "3"), repo.listDownloads(provider).getOrThrow().map { it.id })
    }

    @Test
    fun `preferred words persist first casing and clearing stays cleared`() = runTest {
        val prefs = FakeSharedPreferences()
        val transport = FakeHttpTransport()
        val (repo, _) = repository(transport, prefs = prefs)
        assertEquals(listOf("x265", "REMUX"), repo.loadPreferWords())

        repo.savePreferWords(listOf(" X265 ", "x265", "", "remux"))
        val (reopened, _) = repository(transport, prefs = prefs)
        assertEquals(listOf("X265", "remux"), reopened.loadPreferWords())

        reopened.savePreferWords(emptyList())
        assertEquals(emptyList<String>(), repo.loadPreferWords())
    }

    @Test
    fun `listDownloads returns the original provider failure`() = runTest {
        val failure = IOException("offline")
        val provider = object : DebridProvider by FakeProvider() {
            override suspend fun listDownloads(): Result<List<DebridDownload>> = Result.failure(failure)
        }
        val (repo, _) = repository(FakeHttpTransport())
        assertSame(failure, repo.listDownloads(provider).exceptionOrNull())
    }

    @Test
    fun `batch delete continues after failure and keeps failed and unrelated items`() = runTest {
        val first = sampleDownload(id = "1")
        val bad = sampleDownload(id = "2")
        val last = sampleDownload(id = "3")
        val unrelated = sampleDownload(id = "4")
        val provider = FakeProvider(mutableListOf(first, bad, last, unrelated))
        val failure = IOException("delete failed")
        provider.deleteFailures[bad.id] = failure
        val (repo, _) = repository(FakeHttpTransport())

        assertTrue(repo.deleteMany(provider, emptyList()).isEmpty())
        assertTrue(provider.deletedIds.isEmpty())

        val outcomes = repo.deleteMany(provider, listOf(first, bad, last, first, bad))
        val succeeded = outcomes.filter { it.result.isSuccess }.map { it.download.id }
        val failed = outcomes.filter { it.result.isFailure }.associate {
            it.download.id to it.result.exceptionOrNull()
        }
        assertEquals(listOf("1", "3"), succeeded)
        assertEquals(mapOf("2" to failure), failed)
        assertEquals(listOf("1", "2", "3"), provider.deletedIds)
        assertEquals(listOf(bad, unrelated), provider.downloads)

        repo.delete(provider, unrelated.id).getOrThrow()
        provider.deleteFailures.clear()
        repo.delete(provider, bad).getOrThrow()
        assertTrue(provider.downloads.isEmpty())
    }

    @Test
    fun `batch delete carries TorBox download kinds even when IDs match`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "test-key")
        val endpoints = mutableListOf<String>()
        val transport = FakeHttpTransport { request ->
            endpoints += request.url.substringAfterLast('/')
            HttpResponse(200, """{"success":true,"data":null}""")
        }
        val (repo, _) = repository(transport, store)
        val torrent = sampleDownload()
        val web = torrent.copy(kind = DownloadKind.WEB)

        val outcomes = repo.deleteMany(repo.provider()!!, listOf(torrent, web, torrent))

        assertEquals(listOf(torrent, web), outcomes.map { it.download })
        assertTrue(outcomes.all { it.result.isSuccess })
        assertEquals(listOf("controltorrent", "controlwebdownload"), endpoints)
    }

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
    fun `AllDebrid saves a separate key and remains selectable after switching`() = runTest {
        val transport = FakeHttpTransport { request ->
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "https://api.alldebrid.com/v4/user" -> {
                    assertEquals("Bearer ad-key", request.headers["Authorization"])
                    HttpResponse(200, """{"status":"success","data":{"user":{"username":"ad-user","isPremium":true}}}""")
                }
                else -> error("Unexpected request")
            }
        }
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(transport, prefs = prefs)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val account = repo.validateAndSaveAllDebridKey("  ad-key  ").getOrThrow()
        assertEquals(DebridRepository.ALLDEBRID_ACCOUNT_ID, account.id)
        assertEquals("AllDebrid (Premium)", account.label)
        assertEquals(account, repo.allDebridAccount())
        assertTrue(repo.hasAllDebridKey())
        assertEquals("ad-key", store.get(DebridRepository.ALLDEBRID_ACCOUNT_ID))
        assertEquals("torbox-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(DebridService.ALLDEBRID, repo.activeService())
        val provider = repo.provider()
        assertTrue(provider is AllDebridProvider)

        val afterSignIn = transport.calls
        repo.setActiveService(DebridService.TORBOX)
        assertTrue(repo.provider() is TorBoxProvider)
        repo.setActiveService(DebridService.ALLDEBRID)
        val connection = repo.loadConnection()
        assertEquals(account, connection.account)
        assertTrue(connection.hasKey)
        assertSame(provider, connection.provider)
        assertEquals(DebridService.ALLDEBRID, repository(transport, store, prefs).first.activeService())
        assertEquals(afterSignIn, transport.calls)
    }

    @Test
    fun `signing out of AllDebrid falls back to the remaining signed-in provider`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val transport = FakeHttpTransport { request ->
            check(request.url.substringBefore('?') == "https://api.alldebrid.com/v4/user")
            HttpResponse(200, """{"status":"success","data":{"user":{"username":"ad-user","isPremium":true}}}""")
        }
        val (repo, _) = repository(transport, store)
        repo.validateAndSaveAllDebridKey("ad-key").getOrThrow()

        repo.signOutAllDebrid()

        assertFalse(repo.hasAllDebridKey())
        assertNull(repo.allDebridAccount())
        assertNull(store.get(DebridRepository.ALLDEBRID_ACCOUNT_ID))
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertTrue(repo.provider() is RealDebridProvider)
    }

    @Test
    fun `share classification uses the AllDebrid hoster list`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.ALLDEBRID_ACCOUNT_ID, "ad-key")
        val transport = FakeHttpTransport { request ->
            check(request.url.substringBefore('?') == "https://api.alldebrid.com/v4/hosts/domains")
            HttpResponse(200, """{"status":"success","data":{"hosts":["example.com"]}}""")
        }
        val (repo, _) = repository(transport, store)
        val supported = "https://files.example.com/video"
        val unsupported = "https://example.org/video"

        assertEquals(ClassifiedShare.HosterLink(supported), repo.classifyShared(supported, null, null, null))
        assertEquals(ClassifiedShare.UnsupportedHoster(unsupported), repo.classifyShared(unsupported, null, null, null))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `Premiumize saves a separate key and remains selectable after switching`() = runTest {
        val transport = FakeHttpTransport { request ->
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "${PremiumizeProvider.BASE}/account/info" -> {
                    assertEquals("Bearer pm-key", request.headers["Authorization"])
                    HttpResponse(200, """{"status":"success","customer_id":"42","premium_until":4102444800,"booster_points":1234}""")
                }
                else -> error("Unexpected request")
            }
        }
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(transport, prefs = prefs)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val account = repo.validateAndSavePremiumizeKey("  pm-key  ").getOrThrow()
        assertEquals(DebridRepository.PREMIUMIZE_ACCOUNT_ID, account.id)
        assertEquals("Premiumize (Premium · 1234 booster points)", account.label)
        assertEquals(account, repo.premiumizeAccount())
        assertTrue(repo.hasPremiumizeKey())
        assertEquals("pm-key", store.get(DebridRepository.PREMIUMIZE_ACCOUNT_ID))
        assertEquals("torbox-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(DebridService.PREMIUMIZE, repo.activeService())
        val provider = repo.provider()
        assertTrue(provider is PremiumizeProvider)

        val afterSignIn = transport.calls
        repo.setActiveService(DebridService.TORBOX)
        assertTrue(repo.provider() is TorBoxProvider)
        repo.setActiveService(DebridService.PREMIUMIZE)
        val connection = repo.loadConnection()
        assertEquals(account, connection.account)
        assertTrue(connection.hasKey)
        assertSame(provider, connection.provider)
        assertEquals(DebridService.PREMIUMIZE, repository(transport, store, prefs).first.activeService())
        assertEquals(afterSignIn, transport.calls)
    }

    @Test
    fun `signing out of Premiumize falls back to the remaining signed-in provider`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val transport = FakeHttpTransport { request ->
            check(request.url.substringBefore('?') == "${PremiumizeProvider.BASE}/account/info")
            HttpResponse(200, """{"status":"success","customer_id":"42","premium_until":4102444800,"booster_points":1234}""")
        }
        val (repo, _) = repository(transport, store)
        repo.validateAndSavePremiumizeKey("pm-key").getOrThrow()

        repo.signOutPremiumize()

        assertFalse(repo.hasPremiumizeKey())
        assertNull(repo.premiumizeAccount())
        assertNull(store.get(DebridRepository.PREMIUMIZE_ACCOUNT_ID))
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertTrue(repo.provider() is RealDebridProvider)
    }

    @Test
    fun `Offcloud saves a separate key and remains selectable after switching`() = runTest {
        val transport = FakeHttpTransport { request ->
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "${OffcloudProvider.BASE}/api/remote/accounts" -> {
                    assertEquals("POST", request.method)
                    when (request.url.substringAfter('?')) {
                        "key=oc-key" -> HttpResponse(200, "[]")
                        "key=bad-key" -> HttpResponse(401, "")
                        else -> error("Unexpected key")
                    }
                }
                else -> error("Unexpected request")
            }
        }
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(transport, prefs = prefs)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val account = repo.validateAndSaveOffcloudKey("  oc-key  ").getOrThrow()
        assertEquals(DebridRepository.OFFCLOUD_ACCOUNT_ID, account.id)
        assertEquals("Offcloud", account.label)
        assertEquals(account, repo.offcloudAccount())
        assertTrue(repo.hasOffcloudKey())
        assertEquals("oc-key", store.get(DebridRepository.OFFCLOUD_ACCOUNT_ID))
        assertEquals("torbox-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(DebridService.OFFCLOUD, repo.activeService())
        val provider = repo.provider()
        assertTrue(provider is OffcloudProvider)

        assertTrue(repo.validateAndSaveOffcloudKey("bad-key").exceptionOrNull() is DebridError.AuthInvalid)
        assertEquals(account, repo.offcloudAccount())
        assertEquals("oc-key", store.get(DebridRepository.OFFCLOUD_ACCOUNT_ID))
        assertEquals(DebridService.OFFCLOUD, repo.activeService())

        val afterSignIn = transport.calls
        repo.setActiveService(DebridService.TORBOX)
        assertTrue(repo.provider() is TorBoxProvider)
        assertTrue(repo.hasOffcloudKey())
        repo.setActiveService(DebridService.OFFCLOUD)
        val connection = repo.loadConnection()
        assertEquals(account, connection.account)
        assertTrue(connection.hasKey)
        assertSame(provider, connection.provider)
        assertEquals(DebridService.OFFCLOUD, repository(transport, store, prefs).first.activeService())
        assertEquals(afterSignIn, transport.calls)
    }

    @Test
    fun `signing out of Offcloud falls back to the remaining signed-in provider`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val transport = FakeHttpTransport { request ->
            assertEquals("POST", request.method)
            assertEquals("${OffcloudProvider.BASE}/api/remote/accounts?key=oc-key", request.url)
            HttpResponse(200, "[]")
        }
        val (repo, _) = repository(transport, store)
        repo.validateAndSaveOffcloudKey("oc-key").getOrThrow()

        repo.signOutOffcloud()

        assertFalse(repo.hasOffcloudKey())
        assertNull(repo.offcloudAccount())
        assertNull(store.get(DebridRepository.OFFCLOUD_ACCOUNT_ID))
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertTrue(repo.provider() is RealDebridProvider)
    }

    @Test
    fun `Debrid-Link saves a separate key and remains selectable after switching`() = runTest {
        val transport = FakeHttpTransport { request ->
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "${DebridLinkProvider.BASE}/v2/account/infos" -> {
                    assertEquals("GET", request.method)
                    when (request.headers["Authorization"]) {
                        "Bearer dl-key" -> HttpResponse(200, """{"success":true,"value":{"username":"dl-user","accountType":1,"premiumLeft":3600,"pts":500}}""")
                        "Bearer bad-key" -> HttpResponse(401, """{"success":false,"error":"badToken","error_description":"Invalid token"}""")
                        else -> error("Unexpected key")
                    }
                }
                else -> error("Unexpected request")
            }
        }
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(transport, prefs = prefs)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val account = repo.validateAndSaveDebridLinkKey("  dl-key  ").getOrThrow()
        assertEquals(DebridRepository.DEBRID_LINK_ACCOUNT_ID, account.id)
        assertEquals("Debrid-Link (Premium · 500 pts)", account.label)
        assertEquals("debridlink-1", account.id)
        assertEquals(account, repo.debridLinkAccount())
        assertTrue(repo.hasDebridLinkKey())
        assertEquals("dl-key", store.get(DebridRepository.DEBRID_LINK_ACCOUNT_ID))
        assertEquals("torbox-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(DebridService.DEBRID_LINK, repo.activeService())
        val provider = repo.provider()
        assertTrue(provider is DebridLinkProvider)

        assertTrue(repo.validateAndSaveDebridLinkKey("bad-key").exceptionOrNull() is DebridError.AuthInvalid)
        assertEquals(account, repo.debridLinkAccount())
        assertEquals("dl-key", store.get(DebridRepository.DEBRID_LINK_ACCOUNT_ID))
        assertEquals(DebridService.DEBRID_LINK, repo.activeService())

        val afterSignIn = transport.calls
        repo.setActiveService(DebridService.TORBOX)
        assertTrue(repo.provider() is TorBoxProvider)
        assertTrue(repo.hasDebridLinkKey())
        repo.setActiveService(DebridService.DEBRID_LINK)
        val connection = repo.loadConnection()
        assertEquals(account, connection.account)
        assertTrue(connection.hasKey)
        assertSame(provider, connection.provider)
        assertEquals(DebridService.DEBRID_LINK, repository(transport, store, prefs).first.activeService())
        assertEquals(afterSignIn, transport.calls)
    }

    @Test
    fun `signing out of Debrid-Link falls back to the remaining signed-in provider`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val transport = FakeHttpTransport { request ->
            assertEquals("GET", request.method)
            assertEquals("${DebridLinkProvider.BASE}/v2/account/infos", request.url)
            HttpResponse(200, """{"success":true,"value":{"username":"dl-user","accountType":1,"premiumLeft":3600,"pts":500}}""")
        }
        val (repo, _) = repository(transport, store)
        repo.validateAndSaveDebridLinkKey("dl-key").getOrThrow()

        repo.signOutDebridLink()

        assertFalse(repo.hasDebridLinkKey())
        assertNull(repo.debridLinkAccount())
        assertNull(store.get(DebridRepository.DEBRID_LINK_ACCOUNT_ID))
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertTrue(repo.provider() is RealDebridProvider)
    }

    @Test
    fun `share classification uses the Premiumize hoster list`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.PREMIUMIZE_ACCOUNT_ID, "pm-key")
        val transport = FakeHttpTransport { request ->
            check(request.url.substringBefore('?') == "${PremiumizeProvider.BASE}/services/list")
            HttpResponse(200, """{"status":"success","directdl":["example.com"]}""")
        }
        val (repo, _) = repository(transport, store)
        val supported = "https://files.example.com/video"
        val unsupported = "https://example.org/video"

        assertEquals(ClassifiedShare.HosterLink(supported), repo.classifyShared(supported, null, null, null))
        assertEquals(ClassifiedShare.UnsupportedHoster(unsupported), repo.classifyShared(unsupported, null, null, null))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `switching selects the saved provider and keeps credentials separate`() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val transport = FakeHttpTransport { request ->
            requests += request
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "${TorBoxProvider.BASE}/torrents/mylist" -> HttpResponse(200,
                    """{"success":true,"data":[{"id":1,"name":"TorBox film"}]}""")
                "${TorBoxProvider.BASE}/webdl/mylist", "${TorBoxProvider.BASE}/usenet/mylist" ->
                    HttpResponse(200, """{"success":true,"data":[]}""")
                "${RealDebridProvider.BASE}/user" -> HttpResponse(200, VALID_REAL_DEBRID_USER)
                "${RealDebridProvider.BASE}/torrents" -> HttpResponse(200,
                    """[{"id":"RD1","filename":"Real-Debrid film","status":"downloaded"}]""")
                "${RealDebridProvider.BASE}/downloads" -> HttpResponse(200, "[]")
                else -> error("Unexpected request")
            }
        }
        val prefs = FakeSharedPreferences()
        val (repo, store) = repository(transport, prefs = prefs)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val torbox = requireNotNull(repo.provider())
        val account = repo.validateAndSaveRealDebridToken("  rd-token  ").getOrThrow()
        assertEquals("Real-Debrid (Premium)", account.label)
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertEquals("torbox-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))

        val afterSignIn = requests.size
        repo.setActiveService(DebridService.REAL_DEBRID)
        val realDebrid = requireNotNull(repo.provider())
        assertTrue(realDebrid is RealDebridProvider)
        val realDebridConnection = repo.loadConnection()
        assertEquals(DebridService.REAL_DEBRID, realDebridConnection.account?.service)
        assertTrue(realDebridConnection.hasKey)
        assertSame(realDebrid, realDebridConnection.provider)
        assertEquals(afterSignIn, requests.size)
        assertEquals(listOf("RD1"), repo.listDownloads(realDebrid).getOrThrow().map { it.id })

        repo.setActiveService(DebridService.TORBOX)
        val torboxConnection = repo.loadConnection()
        assertTrue(torboxConnection.provider is TorBoxProvider)
        assertSame(torbox, torboxConnection.provider)
        assertEquals(DebridService.TORBOX, torboxConnection.account?.service)
        assertTrue(torboxConnection.hasKey)
        assertEquals(listOf("1"), repo.listDownloads(torbox).getOrThrow().map { it.id })
        assertEquals(DebridService.TORBOX, repository(transport, store, prefs).first.activeService())

        val beforeSwitch = requests.size
        repo.setActiveService(DebridService.REAL_DEBRID)
        assertSame(realDebrid, repo.provider())
        assertEquals(beforeSwitch, requests.size)

        val rdRequests = requests.filter { it.url.startsWith(RealDebridProvider.BASE) }
        val torboxRequests = requests.filter { it.url.startsWith(TorBoxProvider.BASE) }
        assertTrue(rdRequests.isNotEmpty())
        assertTrue(torboxRequests.isNotEmpty())
        assertTrue(rdRequests.all { it.headers["Authorization"] == "Bearer rd-token" })
        assertTrue(torboxRequests.all { it.headers["Authorization"] == "Bearer torbox-key" })
    }

    @Test
    fun `switching to a signed-out service is ignored`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "torbox-key")
        val transport = FakeHttpTransport { error("Unexpected request") }
        val (repo, _) = repository(transport, store)

        repo.setActiveService(DebridService.REAL_DEBRID)

        assertEquals(DebridService.TORBOX, repo.activeService())
        assertTrue(repo.provider() is TorBoxProvider)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a missing selected credential falls back to the other provider`() = runTest {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "torbox-key")
        store.save(DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token")
        val transport = FakeHttpTransport { error("Unexpected request") }
        val (repo, _) = repository(transport, store)
        repo.setActiveService(DebridService.REAL_DEBRID)
        store.delete(DebridRepository.REAL_DEBRID_ACCOUNT_ID)

        assertEquals(DebridService.TORBOX, repo.activeService())
        assertTrue(repo.provider() is TorBoxProvider)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `signing out real debrid falls back to torbox and signing out torbox preserves active real debrid`() = runTest {
        val transport = FakeHttpTransport { request ->
            when (request.url) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, VALID_ME)
                "${RealDebridProvider.BASE}/user" -> HttpResponse(200, VALID_REAL_DEBRID_USER)
                else -> error("Unexpected request")
            }
        }
        val (repo, store) = repository(transport)
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        val torbox = repo.provider()
        repo.validateAndSaveRealDebridToken("rd-token").getOrThrow()
        val originalRealDebrid = repo.provider()

        repo.signOutRealDebrid()
        assertFalse(repo.hasRealDebridKey())
        assertNull(store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertNull(repo.realDebridAccount())
        assertEquals(listOf(DebridService.TORBOX), repo.accounts().map { it.service })
        assertEquals(DebridService.TORBOX, repo.activeService())
        assertSame(torbox, repo.provider())

        repo.validateAndSaveRealDebridToken("rd-token").getOrThrow()
        val realDebrid = repo.provider()
        assertTrue(realDebrid is RealDebridProvider)
        assertTrue(originalRealDebrid !== realDebrid)
        repo.signOutTorbox()
        assertNull(store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals(listOf(DebridService.REAL_DEBRID), repo.accounts().map { it.service })
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
        assertSame(realDebrid, repo.provider())

        repo.signOutRealDebrid()
        assertNull(repo.activeService())
        assertNull(repo.provider())
    }

    @Test
    fun `rejected real debrid token returns AuthInvalid and stores nothing`() = runTest {
        val prefs = FakeSharedPreferences()
        val transport = FakeHttpTransport { HttpResponse(401, """{"error":"bad_token"}""") }
        val (repo, store) = repository(transport, prefs = prefs)

        assertTrue(repo.validateAndSaveRealDebridToken("bad-token").exceptionOrNull() is DebridError.AuthInvalid)
        assertNull(store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertTrue(repo.accounts().isEmpty())
        assertTrue(prefs.map.isEmpty())

        transport.handler = { HttpResponse(200, VALID_REAL_DEBRID_USER) }
        val saved = repo.validateAndSaveRealDebridToken("rd-token").getOrThrow()
        transport.handler = { HttpResponse(401, """{"error":"bad_token"}""") }
        assertTrue(repo.validateAndSaveRealDebridToken("bad-token").exceptionOrNull() is DebridError.AuthInvalid)
        assertEquals("rd-token", store.get(DebridRepository.REAL_DEBRID_ACCOUNT_ID))
        assertEquals(saved, repo.realDebridAccount())
        assertEquals(DebridService.REAL_DEBRID, repo.activeService())
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
