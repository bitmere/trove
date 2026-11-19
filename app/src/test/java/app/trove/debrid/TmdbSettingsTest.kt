package app.trove.debrid

import app.trove.debrid.ui.settings.SettingsViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TMDB key field in Settings → Library, plus the attribution guardrail. */
@OptIn(ExperimentalCoroutinesApi::class)
class TmdbSettingsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saving a valid TMDB key stores it masked and clears the field`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setTmdbKeyField("test-read-access-token")
        assertEquals("test-read-access-token", vm.state.value.tmdbKeyField)
        vm.saveTmdbKey()
        advanceUntilIdle()

        assertTrue(vm.state.value.hasTmdbKey)
        assertTrue(vm.state.value.tmdbKeySaved)
        assertFalse(vm.state.value.tmdbKeyError)
        assertEquals("", vm.state.value.tmdbKeyField)
        assertEquals("test-read-access-token", repo.savedTmdbKey)
        assertTrue(vm.state.value.maskedTmdbKey.endsWith("oken"))
        assertFalse(vm.state.value.maskedTmdbKey.contains("test-read-access-token"))
    }

    @Test
    fun `a rejected TMDB key errors and keeps the typed value for correction`() = runTest {
        val repo = FakeRepository().apply { tmdbKeyValid = false }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setTmdbKeyField("bad-key")
        vm.saveTmdbKey()
        advanceUntilIdle()

        assertTrue(vm.state.value.tmdbKeyError)
        assertFalse(vm.state.value.tmdbKeySaved)
        assertFalse(vm.state.value.hasTmdbKey)
        assertNull(repo.savedTmdbKey)
        assertEquals("bad-key", vm.state.value.tmdbKeyField)
    }

    @Test
    fun `an empty TMDB key is rejected without a validation call`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setTmdbKeyField("   ")
        vm.saveTmdbKey()
        advanceUntilIdle()

        assertTrue(vm.state.value.tmdbKeyError)
        assertFalse(vm.state.value.hasTmdbKey)
        assertNull(repo.savedTmdbKey)
    }

    @Test
    fun `clearing the TMDB key removes it and the eye toggle flips`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        vm.setTmdbKeyField("test-read-access-token")
        vm.saveTmdbKey()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasTmdbKey)

        assertFalse(vm.state.value.showTmdbKey)
        vm.toggleShowTmdbKey()
        assertTrue(vm.state.value.showTmdbKey)
        vm.toggleShowTmdbKey()
        assertFalse(vm.state.value.showTmdbKey)

        vm.clearTmdbKey()
        advanceUntilIdle()
        assertFalse(vm.state.value.hasTmdbKey)
        assertEquals("", vm.state.value.maskedTmdbKey)
        assertNull(repo.savedTmdbKey)
    }

    @Test
    fun `refresh loads a previously saved TMDB key as masked`() = runTest {
        val repo = FakeRepository()
        repo.saveTmdbKey("test-read-access-token")
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.hasTmdbKey)
        assertTrue(vm.state.value.maskedTmdbKey.endsWith("oken"))
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/app/trove/debrid/MainActivity.kt").isFile }

    @Test
    fun `attribution notice and key handling are present in source`() {
        val root = repoRoot()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        // Exact notice from TMDB API Terms of Use §3 (verified
        // 2026-11-19): the [product] slot is filled with "product".
        assertTrue(
            strings.contains(
                "This product uses TMDB and the TMDB APIs but is not endorsed, " +
                    "certified, or otherwise approved by TMDB.",
            ),
        )
        val screen = File(
            root,
            "app/src/main/java/app/trove/debrid/ui/settings/SettingsScreen.kt",
        ).readText()
        val library = screen.substringAfter("private fun LibraryContent")
        assertTrue(library.contains("R.string.tmdb_attribution"))
        assertTrue(library.contains("R.string.tmdb_api_key"))
        assertTrue(library.contains("PasswordVisualTransformation"))
        assertTrue(library.contains("testTag(\"tmdb_key_field\")"))

        val resolver = File(
            root,
            "core/src/main/java/app/trove/debrid/core/TmdbResolver.kt",
        ).readText()
        // The image host comes from /3/configuration, never hardcoded;
        // the token travels as a Bearer header, never in a URL, and
        // nothing in the resolver logs.
        assertTrue(resolver.contains("/3/configuration"))
        assertTrue(resolver.contains("Bearer "))
        for (forbidden in listOf("image.tmdb.org", "api_key=", "Log.", "println")) {
            assertFalse("TMDB resolver must not contain $forbidden", resolver.contains(forbidden))
        }
    }
}
