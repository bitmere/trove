package app.trove.debrid

import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.data.DebridRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test

class DefaultPlayerRepositoryTest {
    @Test fun `empty preferences means ask every time`() = runTest {
        val repo = DebridRepository(FakeSharedPreferences(), InMemoryCredentialStore(),
            FakeHttpTransport(), StandardTestDispatcher(testScheduler))
        assertNull(repo.defaultPlayer())
    }

    @Test fun `only identifiers persist across repository recreation and clearing restores ask`() = runTest {
        val prefs = FakeSharedPreferences()
        val store = InMemoryCredentialStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun repository() = DebridRepository(prefs, store, FakeHttpTransport(), dispatcher)
        val player = PlayerComponent("org.example.player", "org.example.player.VideoActivity")
        repository().setDefaultPlayer(player)
        assertEquals(player, repository().defaultPlayer())
        assertEquals(mapOf("default_player_package" to player.packageName,
            "default_player_activity" to player.activityName), prefs.map)
        assertFalse(store.has(DebridRepository.TORBOX_ACCOUNT_ID))
        repository().setDefaultPlayer(null)
        assertNull(repository().defaultPlayer())
        assertTrue(prefs.map.isEmpty())
    }

    @Test fun `incomplete or blank identifiers safely use ask every time`() = runTest {
        val prefs = FakeSharedPreferences()
        val repo = DebridRepository(prefs, InMemoryCredentialStore(), FakeHttpTransport(),
            StandardTestDispatcher(testScheduler))
        prefs.map["default_player_package"] = "org.example.player"
        assertNull(repo.defaultPlayer())
        prefs.map["default_player_activity"] = " "
        assertNull(repo.defaultPlayer())
    }
}
