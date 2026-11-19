package app.trove.debrid

import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadRef
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.TorBoxProvider
import app.trove.debrid.data.BackupSignIn
import app.trove.debrid.data.DebridRepository
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.PreferencesStore
import app.trove.debrid.data.ProviderAccountRegistry
import app.trove.debrid.data.SavedConnection
import app.trove.debrid.data.StoredAccounts
import app.trove.debrid.data.ThemeAccent
import app.trove.debrid.data.ThemeMode
import app.trove.debrid.data.ThemePreference
import app.trove.debrid.ui.settings.providerSectionDescriptors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val SPLIT_VALID_ME = """{"success":true,"data":{"email":"user@example.com","plan":1}}"""
private const val SPLIT_VALID_AD_USER =
    """{"status":"success","data":{"user":{"username":"ad-user","isPremium":true}}}"""
private const val SPLIT_AD_HOSTS =
    """{"status":"success","data":{"hosts":["example.com"]}}"""

/** Split-boundary tests for unification item 5: the account/provider
 *  registry vs. the preferences store behind the [DebridRepository]
 *  facade. Each part is exercised in isolation, then the facade is
 *  shown to compose them over one shared state, and the two
 *  invariants — provider caching (rate budgets + hoster cache) and
 *  `loadConnection` atomicity — are pinned at the split boundary. */
class DebridRepositorySplitTest {

    private fun registry(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        transport: FakeHttpTransport = FakeHttpTransport(),
    ) = ProviderAccountRegistry(prefs, store, transport)

    private fun preferences(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
    ) = PreferencesStore(prefs, Dispatchers.Unconfined)

    private fun facade(
        reg: ProviderAccountRegistry,
        prefsStore: PreferencesStore,
        transport: FakeHttpTransport = FakeHttpTransport(),
    ) = DebridRepository(reg, prefsStore, transport, Dispatchers.Unconfined, null)

    /** Transport answering TorBox + AllDebrid account validation and
     *  the AllDebrid hoster list; anything else is an error. */
    private fun signInTransport(requests: MutableList<HttpRequest> = mutableListOf()) =
        FakeHttpTransport { request ->
            requests += request
            when (request.url.substringBefore('?')) {
                "${TorBoxProvider.BASE}/user/me" -> HttpResponse(200, SPLIT_VALID_ME)
                "https://api.alldebrid.com/v4/user" -> HttpResponse(200, SPLIT_VALID_AD_USER)
                "https://api.alldebrid.com/v4/hosts/domains" -> HttpResponse(200, SPLIT_AD_HOSTS)
                else -> error("Unexpected request: ${request.url}")
            }
        }

    private suspend fun ProviderAccountRegistry.save(
        service: DebridService,
        credential: String,
    ): Result<DebridAccount> {
        val descriptor = providerSectionDescriptors.getValue(service)
        return validateAndSave(service, credential, descriptor.factory, descriptor.accountLabel)
    }

    // ---- Registry in isolation ----

    @Test
    fun `registry validates saves and signs out one service without touching prefs keys`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val reg = registry(prefs, store, signInTransport())

        val account = reg.save(DebridService.TORBOX, "  torbox-key  ").getOrThrow()
        assertEquals(DebridRepository.TORBOX_ACCOUNT_ID, account.id)
        assertEquals("TorBox (Plan Essential)", account.label)
        assertEquals(listOf(account), reg.accounts())
        assertEquals(DebridService.TORBOX, reg.activeService())
        assertTrue(reg.provider() is TorBoxProvider)
        assertTrue(reg.hasCredential(DebridService.TORBOX))
        assertFalse(reg.hasCredential(DebridService.REAL_DEBRID))
        // Only the account keys exist in prefs: no preference key was written.
        assertEquals(setOf("accounts"), prefs.map.keys)
        assertFalse(prefs.map.containsKey("active_service"))

        reg.signOut(DebridService.TORBOX)
        assertTrue(reg.accounts().isEmpty())
        assertNull(reg.activeService())
        assertNull(reg.provider())
        assertFalse(reg.hasCredential(DebridService.TORBOX))
        assertFalse(prefs.map.containsKey("active_service"))
    }

    @Test
    fun `registry rejects a blank or refused credential and stores nothing`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val refusing = FakeHttpTransport { HttpResponse(401, """{"success":false,"error":"unauthorized"}""") }
        val reg = registry(prefs, store, refusing)

        assertTrue(reg.save(DebridService.TORBOX, "   ").isFailure)
        assertTrue(reg.save(DebridService.TORBOX, "bad-key").isFailure)
        assertTrue(reg.accounts().isEmpty())
        assertNull(store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertFalse(prefs.map.containsKey("accounts"))
    }

    @Test
    fun `registry sign-out falls back to the remaining provider keeping its instance`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val reg = registry(prefs, store, signInTransport())
        reg.save(DebridService.TORBOX, "torbox-key").getOrThrow()
        val torbox = reg.provider()
        reg.save(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        assertEquals(DebridService.ALLDEBRID, reg.activeService())

        reg.signOut(DebridService.ALLDEBRID)

        assertEquals(DebridService.TORBOX, reg.activeService())
        assertSame(torbox, reg.provider())
        assertEquals(listOf(DebridService.TORBOX), reg.accounts().map { it.service })
    }

    @Test
    fun `registry rebuilds a provider only when its credential changes`() {
        val store = InMemoryCredentialStore()
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "key-one")
        val reg = registry(store = store)
        val first = reg.providerFor(DebridService.TORBOX)
        assertSame(first, reg.providerFor(DebridService.TORBOX))
        assertNull(reg.providerFor(DebridService.PREMIUMIZE))

        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "key-two")
        val second = reg.providerFor(DebridService.TORBOX)
        assertTrue(first !== second)
        assertSame(second, reg.providerFor(DebridService.TORBOX))
    }

    @Test
    fun `registry backup sign-in round-trips without becoming the active service`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val reg = registry(prefs, store, signInTransport())
        restoreAccount(reg, prefs, store, DebridService.REAL_DEBRID)
        reg.setActiveService(DebridService.REAL_DEBRID)
        assertEquals(DebridService.REAL_DEBRID, reg.activeService())

        // A backup restore replaces the TorBox account (its distinct
        // helper) but must not move the active service.
        val restored = BackupSignIn("restored-key", "TorBox (Restored)", 123L, 456L)
        reg.restoreBackupSignIn(restored)
        assertEquals(DebridService.REAL_DEBRID, reg.activeService())
        assertEquals(restored.key, reg.loadBackupSignIn()?.key)
        assertEquals(restored.label, reg.accountFor(DebridService.TORBOX)?.label)
        assertEquals(restored.addedAt, reg.accountFor(DebridService.TORBOX)?.addedAtEpochMs)
        assertEquals(restored.lastValidated, reg.accountFor(DebridService.TORBOX)?.lastValidatedEpochMs)
        assertEquals(restored.key, store.get(DebridRepository.TORBOX_ACCOUNT_ID))
    }

    // ---- Preferences store in isolation ----

    @Test
    fun `prefs store round-trips theme sort words clutter clipboard and player`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = preferences(prefs)

        assertEquals(ThemePreference(), store.loadTheme())
        store.saveTheme(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER))
        assertEquals(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER), store.loadTheme())
        assertEquals(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER), store.theme.value)
        store.loadStartupTheme()
        assertEquals(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER), store.theme.value)

        assertEquals(DownloadSort.NEWEST, store.loadDownloadSort())
        store.saveDownloadSort(DownloadSort.OLDEST)
        assertEquals(DownloadSort.OLDEST, store.loadDownloadSort())

        assertEquals(listOf("CAM", "TS"), store.loadExcludeWords())
        store.saveExcludeWords(listOf(" remux ", "REMUX", " "))
        assertEquals(listOf("remux"), store.loadExcludeWords())
        store.saveExcludeWords(emptyList())
        assertEquals(emptyList<String>(), store.loadExcludeWords())
        assertEquals(listOf("x265", "REMUX"), store.loadPreferWords())
        store.savePreferWords(listOf(" X265 ", "x265"))
        assertEquals(listOf("X265"), store.loadPreferWords())

        assertFalse(store.loadShowClutterFiles())
        store.saveShowClutterFiles(true)
        assertTrue(store.loadShowClutterFiles())

        assertTrue(store.loadClipboardOffersEnabled())
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        assertTrue(store.claimClipboardOffer(magnet))
        assertFalse(store.claimClipboardOffer(magnet))
        assertTrue(store.claimClipboardOffer("magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98"))
        store.saveClipboardOffersEnabled(false)
        assertFalse(store.claimClipboardOffer(magnet))
        assertFalse(store.loadClipboardOffersEnabled())

        assertNull(store.defaultPlayer())
        val player = PlayerComponent("com.example.player", "com.example.player.Main")
        store.setDefaultPlayer(player)
        assertEquals(player, store.defaultPlayer())
        store.setDefaultPlayer(null)
        assertNull(store.defaultPlayer())

        // Preferences writes never create account keys.
        assertFalse(prefs.map.containsKey("accounts"))
        assertFalse(prefs.map.containsKey("active_service"))
    }

    @Test
    fun `prefs store metadata overrides are independent per kind`() = runTest {
        val store = preferences()
        val torrent = DownloadRef(DownloadKind.TORRENT, "42")
        val web = DownloadRef(DownloadKind.WEB, "42")
        store.saveMetadataOverride(torrent, MetadataOverride("tt0000001", "movie", "Torrent Film"))
        store.saveMetadataOverride(web, MetadataOverride("tt0000002", "movie", "Web Film"))
        assertEquals("Torrent Film", store.loadMetadataOverride(torrent)?.title)
        assertEquals("Web Film", store.loadMetadataOverride(web)?.title)
        store.clearMetadataOverride(torrent)
        assertNull(store.loadMetadataOverride(torrent))
        assertEquals("Web Film", store.loadMetadataOverride(web)?.title)
    }

    // ---- Independence across the split ----

    @Test
    fun `changing preferences never touches account state or the cached provider`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val transport = signInTransport()
        val reg = registry(prefs, store, transport)
        val prefsStore = preferences(prefs)
        val repo = facade(reg, prefsStore, transport)
        reg.save(DebridService.TORBOX, "torbox-key").getOrThrow()
        reg.save(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        val provider = reg.provider()
        val accountsBefore = reg.accounts()
        val accountsJsonBefore = prefs.map["accounts"]

        prefsStore.saveTheme(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER))
        prefsStore.saveDownloadSort(DownloadSort.OLDEST)
        prefsStore.saveExcludeWords(listOf("CAM"))
        prefsStore.savePreferWords(listOf("x265"))
        prefsStore.saveShowClutterFiles(true)
        prefsStore.saveClipboardOffersEnabled(false)
        prefsStore.setDefaultPlayer(PlayerComponent("com.example.player", "com.example.player.Main"))
        prefsStore.saveMetadataOverride(
            DownloadRef(DownloadKind.TORRENT, "1"),
            MetadataOverride("tt0000001", "movie", "Film"),
        )

        assertEquals(accountsBefore, reg.accounts())
        assertEquals(accountsJsonBefore, prefs.map["accounts"])
        assertEquals(DebridService.ALLDEBRID, reg.activeService())
        assertSame(provider, reg.provider())
        assertSame(provider, repo.provider())
    }

    @Test
    fun `changing accounts never touches preference values`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val transport = signInTransport()
        val reg = registry(prefs, store, transport)
        val prefsStore = preferences(prefs)
        prefsStore.saveTheme(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER))
        prefsStore.saveDownloadSort(DownloadSort.OLDEST)
        prefsStore.saveExcludeWords(listOf("CAM"))
        prefsStore.savePreferWords(listOf("x265"))
        prefsStore.saveShowClutterFiles(true)
        prefsStore.saveClipboardOffersEnabled(false)
        prefsStore.setDefaultPlayer(PlayerComponent("com.example.player", "com.example.player.Main"))
        val prefsSnapshot = prefs.map.toMap()

        reg.save(DebridService.TORBOX, "torbox-key").getOrThrow()
        reg.save(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        reg.setActiveService(DebridService.TORBOX)
        reg.signOut(DebridService.ALLDEBRID)

        assertEquals(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER), prefsStore.loadTheme())
        assertEquals(DownloadSort.OLDEST, prefsStore.loadDownloadSort())
        assertEquals(listOf("CAM"), prefsStore.loadExcludeWords())
        assertEquals(listOf("x265"), prefsStore.loadPreferWords())
        assertTrue(prefsStore.loadShowClutterFiles())
        assertFalse(prefsStore.loadClipboardOffersEnabled())
        assertEquals(
            PlayerComponent("com.example.player", "com.example.player.Main"),
            prefsStore.defaultPlayer(),
        )
        prefsSnapshot.forEach { (key, value) -> assertEquals("pref key $key moved", value, prefs.map[key]) }
    }

    // ---- Provider caching: budgets + hoster cache survive the split ----

    @Test
    fun `hoster cache and provider identity survive switching and preference writes`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val requests = mutableListOf<HttpRequest>()
        val transport = signInTransport(requests)
        val reg = registry(prefs, store, transport)
        val prefsStore = preferences(prefs)
        val repo = facade(reg, prefsStore, transport)
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        val allDebrid = requireNotNull(reg.providerFor(DebridService.ALLDEBRID))

        // First hoster fetch hits the network; the second is served
        // from the provider instance's own 1h cache.
        assertEquals(setOf("example.com"), allDebrid.hosterDomains())
        assertEquals(setOf("example.com"), allDebrid.hosterDomains())
        assertEquals(1, requests.count { it.url.contains("/v4/hosts/domains") })
        val totalCalls = transport.calls

        // Switching away and back, then writing preferences, must not
        // rebuild the provider (which would reset its rate budgets
        // and drop the hoster cache).
        repo.setActiveService(DebridService.TORBOX)
        assertTrue(repo.provider() is TorBoxProvider)
        repo.setActiveService(DebridService.ALLDEBRID)
        assertSame(allDebrid, repo.provider())
        prefsStore.saveTheme(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER))
        prefsStore.saveExcludeWords(listOf("CAM"))
        prefsStore.setDefaultPlayer(PlayerComponent("com.example.player", "com.example.player.Main"))
        assertSame(allDebrid, repo.provider())
        assertEquals(setOf("example.com"), allDebrid.hosterDomains())
        assertEquals(totalCalls, transport.calls)
        assertEquals(1, requests.count { it.url.contains("/v4/hosts/domains") })
    }

    // ---- loadConnection atomicity ----

    @Test
    fun `concurrent loadConnection and sign-out never yields a torn connection`() {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val reg = registry(prefs, store)
        reseed(reg, prefs, store, DebridService.TORBOX, DebridService.REAL_DEBRID)
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(6)

        fun check(connection: SavedConnection) {
            val provider = connection.provider
            val account = connection.account
            if ((provider == null) != (account == null)) {
                violations += "provider=$provider account=$account"
            }
            if (provider != null && account?.service != provider.service) {
                violations += "account service ${account?.service} vs provider ${provider.service}"
            }
            if (connection.hasKey != (provider != null)) {
                violations += "hasKey=${connection.hasKey} provider=$provider"
            }
        }

        repeat(4) {
            thread {
                start.await()
                repeat(300) { check(reg.loadConnection()) }
                done.countDown()
            }
        }
        repeat(2) { writer ->
            thread {
                start.await()
                val service = if (writer == 0) DebridService.TORBOX else DebridService.REAL_DEBRID
                repeat(150) {
                    reg.signOut(service)
                    restoreAccount(reg, prefs, store, service)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))
        assertTrue("torn connections: ${violations.take(5)}", violations.isEmpty())
    }

    @Test
    fun `facade loadConnection stays consistent while sign-out races it`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val transport = signInTransport()
        val reg = registry(prefs, store, transport)
        val repo = facade(reg, preferences(prefs), transport)
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.ALLDEBRID, "ad-key").getOrThrow()

        val seen = Collections.synchronizedList(mutableListOf<SavedConnection>())
        val loader = async { repeat(50) { seen += repo.loadConnection() } }
        val signer = async {
            repeat(10) {
                repo.signOut(DebridService.TORBOX)
                repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
            }
        }
        loader.await()
        signer.await()
        assertEquals(50, seen.size)
        seen.forEach { connection ->
            val provider = connection.provider
            assertEquals(provider != null, connection.hasKey)
            if (provider != null) assertEquals(provider.service, connection.account?.service)
            else assertNull(connection.account)
        }
    }

    // ---- Facade composes the two parts over one shared state ----

    @Test
    fun `facade and parts share one state in both directions`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val transport = signInTransport()
        val reg = registry(prefs, store, transport)
        val prefsStore = preferences(prefs)
        val repo = facade(reg, prefsStore, transport)

        // Registry write -> facade read.
        reg.save(DebridService.TORBOX, "torbox-key").getOrThrow()
        assertEquals(DebridService.TORBOX, repo.activeService())
        assertTrue(repo.provider() is TorBoxProvider)
        // Facade write -> registry read.
        repo.saveTestCredential(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        assertEquals(DebridService.ALLDEBRID, reg.activeService())
        // Prefs-store write -> facade read, and back.
        prefsStore.saveTheme(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER))
        assertEquals(ThemePreference(ThemeMode.DARK, ThemeAccent.EMBER), repo.loadTheme())
        repo.saveDownloadSort(DownloadSort.OLDEST)
        assertEquals(DownloadSort.OLDEST, prefsStore.loadDownloadSort())
        // TMDB key lives with the registry (credential-store secret);
        // metadata overrides live with the prefs store (plain prefs).
        repo.saveTmdbKey("tmdb-key")
        assertTrue(reg.hasTmdbKey())
        assertEquals("tmdb-key", reg.tmdbKey())
        repo.saveMetadataOverride(
            DownloadRef(DownloadKind.TORRENT, "7"),
            MetadataOverride("tt0000007", "movie", "Picked"),
        )
        assertEquals("Picked", prefsStore.loadMetadataOverride(DownloadRef(DownloadKind.TORRENT, "7"))?.title)
        repo.clearTmdbKey()
        assertFalse(reg.hasTmdbKey())
    }

    // ---- Test-only seeding helpers (direct prefs + store writes) ----

    /** Restores one account's credential + stored record atomically
     *  with respect to the registry lock, so the race test's writers
     *  never publish a half-written account themselves. */
    private fun restoreAccount(
        reg: ProviderAccountRegistry,
        prefs: FakeSharedPreferences,
        store: InMemoryCredentialStore,
        service: DebridService,
    ) {
        val (id, key, label) = when (service) {
            DebridService.TORBOX -> Triple(DebridRepository.TORBOX_ACCOUNT_ID, "torbox-key", "TorBox (Plan Essential)")
            DebridService.REAL_DEBRID -> Triple(
                DebridRepository.REAL_DEBRID_ACCOUNT_ID, "rd-token", "Real-Debrid (Premium)",
            )
            else -> error("race test only seeds TorBox / Real-Debrid")
        }
        synchronized(reg) {
            store.save(id, key)
            val accounts = reg.accounts().filter { it.service != service } +
                DebridAccount(id, service, label, 1L, 1L)
            prefs.edit().putString("accounts", StoredAccounts.encode(accounts)).apply()
        }
    }

    private fun reseed(
        reg: ProviderAccountRegistry,
        prefs: FakeSharedPreferences,
        store: InMemoryCredentialStore,
        vararg services: DebridService,
    ) {
        synchronized(reg) {
            services.forEach { restoreAccount(reg, prefs, store, it) }
            prefs.edit().putString("active_service", services.first().name).apply()
        }
    }
}
