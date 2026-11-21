package app.trove.debrid

import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.core.SimklActivities
import app.trove.debrid.core.SimklDiskCache
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.SimklSnapshot
import app.trove.debrid.core.SimklStatus
import app.trove.debrid.core.SimklTokens
import app.trove.debrid.core.SimklType
import app.trove.debrid.data.DebridRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SIMKL client ID field: a user-saved ID (encrypted) overrides
 *  the compile-time placeholder, configures the client, and a
 *  changed ID wipes the grant minted under the old one. Fake
 *  transport only — no live SIMKL app exists yet. */
class SimklClientIdTest {

    @get:Rule val folder = TemporaryFolder()

    private val deviceJson = """
        {"device_code":"dc-1","user_code":"ABCD-EFGH",
        "verification_uri":"https://simkl.com/pin",
        "verification_uri_complete":"https://simkl.com/pin/ABCD-EFGH",
        "expires_in":900,"interval":5}
    """.trimIndent()

    private fun repository(
        store: InMemoryCredentialStore,
        requests: MutableList<HttpRequest>,
        cache: SimklDiskCache,
    ) = DebridRepository(
        prefs = FakeSharedPreferences(),
        credentialStore = store,
        transport = FakeHttpTransport { request ->
            requests += request
            if (request.url.contains("/oauth2/device")) HttpResponse(200, deviceJson)
            else HttpResponse(200, "{}")
        },
        ioDispatcher = Dispatchers.Unconfined,
        simklDiskCache = cache,
        simklAppVersion = "test",
    )

    private fun seedTokens(store: InMemoryCredentialStore) {
        store.save(
            DebridRepository.SIMKL_ACCOUNT_ID,
            SimklTokens("at-1", "rt-1", 4_000_000_000_000L, 4_000_000_000_000L).encode(),
        )
    }

    private fun seedSnapshot(cache: SimklDiskCache) {
        val activitiesJson = """{"all":"2026-11-20T00:00:00Z"}"""
        val activities = SimklActivities.parse(JsonParser.parseString(activitiesJson).asJsonObject)
        cache.save(
            SimklSnapshot(
                activitiesJson = activitiesJson, activities = activities, username = "tester",
                entries = listOf(
                    SimklEntry(123, SimklType.SHOW, SimklStatus.WATCHING, "Example Show",
                        "tt1234567", 456, "example-show", 4, 20, 1, 5, null),
                ),
                lastCheckedMs = 1_700_000_000_000L, quotaBlockedUntilMs = 0L, lastError = null,
            ),
        )
    }

    @Test fun `empty field means not configured`() = runTest {
        val store = InMemoryCredentialStore()
        val repo = repository(store, mutableListOf(), SimklDiskCache(folder.newFolder()))

        assertFalse(repo.isSimklConfigured())
        assertNull(repo.simklClientId())
    }

    @Test fun `saving a client id configures the client and the device flow uses it`() = runTest {
        val store = InMemoryCredentialStore()
        val requests = mutableListOf<HttpRequest>()
        val repo = repository(store, requests, SimklDiskCache(folder.newFolder()))

        repo.saveSimklClientId("user-app-id")

        assertTrue(repo.isSimklConfigured())
        assertEquals("user-app-id", repo.simklClientId())
        assertEquals("user-app-id", store.get(DebridRepository.SIMKL_CLIENT_ID_ACCOUNT_ID))

        val result = repo.startSimklDeviceFlow()
        assertTrue(result.isSuccess)
        assertEquals("ABCD-EFGH", result.getOrThrow().userCode)
        assertTrue(requests.any { it.url.contains("/oauth2/device") && it.url.contains("client_id=user-app-id") })
    }

    @Test fun `changing the id wipes tokens and the disk cache`() = runTest {
        val store = InMemoryCredentialStore()
        val requests = mutableListOf<HttpRequest>()
        val cache = SimklDiskCache(folder.newFolder())
        val repo = repository(store, requests, cache)
        repo.saveSimklClientId("old-id")
        seedTokens(store)
        seedSnapshot(cache)
        assertTrue(repo.isSimklSignedIn())
        assertTrue(cache.load() != null)

        repo.saveSimklClientId("new-id")

        assertEquals("new-id", repo.simklClientId())
        assertTrue(repo.isSimklConfigured())
        assertFalse(repo.isSimklSignedIn())
        assertNull(store.get(DebridRepository.SIMKL_ACCOUNT_ID))
        assertNull(cache.load())
        assertNull(repo.simklSnapshotInfo())
        // The revoke went out under the OLD id, before the save.
        assertTrue(requests.any {
            it.url.contains("/oauth2/revoke") && it.url.contains("client_id=old-id")
        })
    }

    @Test fun `re-saving the same id keeps tokens and the cache`() = runTest {
        val store = InMemoryCredentialStore()
        val requests = mutableListOf<HttpRequest>()
        val cache = SimklDiskCache(folder.newFolder())
        val repo = repository(store, requests, cache)
        // Seed the ID in the store directly: going through save
        // would clear the engine's snapshot flag, and the engine
        // is the only writer of the disk snapshot in production.
        store.save(DebridRepository.SIMKL_CLIENT_ID_ACCOUNT_ID, "same-id")
        seedTokens(store)
        seedSnapshot(cache)
        assertTrue(repo.isSimklConfigured())

        repo.saveSimklClientId("same-id")

        assertTrue(repo.isSimklSignedIn())
        assertTrue(store.get(DebridRepository.SIMKL_ACCOUNT_ID) != null)
        assertTrue(cache.load() != null)
        assertTrue(repo.simklSnapshotInfo() != null)
        assertTrue(requests.isEmpty())
    }

    @Test fun `clearing the id unconfigures and wipes tokens and cache`() = runTest {
        val store = InMemoryCredentialStore()
        val cache = SimklDiskCache(folder.newFolder())
        val repo = repository(store, mutableListOf(), cache)
        repo.saveSimklClientId("doomed-id")
        seedTokens(store)
        seedSnapshot(cache)

        repo.clearSimklClientId()

        assertFalse(repo.isSimklConfigured())
        assertNull(repo.simklClientId())
        assertFalse(repo.isSimklSignedIn())
        assertNull(store.get(DebridRepository.SIMKL_ACCOUNT_ID))
        assertNull(cache.load())
    }

    @Test fun `sign-out keeps the id`() = runTest {
        val store = InMemoryCredentialStore()
        val cache = SimklDiskCache(folder.newFolder())
        val repo = repository(store, mutableListOf(), cache)
        repo.saveSimklClientId("kept-id")
        seedTokens(store)
        seedSnapshot(cache)
        assertTrue(repo.isSimklSignedIn())

        repo.signOutSimkl()

        assertFalse(repo.isSimklSignedIn())
        assertNull(store.get(DebridRepository.SIMKL_ACCOUNT_ID))
        assertNull(cache.load())
        assertEquals("kept-id", repo.simklClientId())
        assertTrue(repo.isSimklConfigured())
    }
}
