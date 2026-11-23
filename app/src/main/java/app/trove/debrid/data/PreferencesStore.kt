package app.trove.debrid.data

import android.content.SharedPreferences
import app.trove.debrid.core.ClipboardOffer
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.DownloadRef
import app.trove.debrid.core.PlayerComponent
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Preferences half of [DebridRepository] (unification item 5). Owns
 *  the user's own settings — theme, download sort, release words,
 *  clutter toggle, clipboard offers, default player — plus the
 *  metadata overrides, which are plain-preference user picks keyed by
 *  (kind, id), stored the same way.
 *
 *  Shares the SharedPreferences file with [ProviderAccountRegistry]
 *  but owns a disjoint key set and never touches the credential
 *  store, so a settings write can never move account state and an
 *  account change can never move a setting. The theme flow lives
 *  here: the in-memory theme is a preference, and the facade exposes
 *  this flow unchanged. Suspend functions dispatch their own I/O to
 *  [ioDispatcher]; [currentExcludeWords] is the synchronous read the
 *  download-listing path uses inside its own dispatched block. */
class PreferencesStore(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _theme = MutableStateFlow(ThemePreference())
    val theme = _theme.asStateFlow()

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_EXCLUDE_WORDS = "exclude_words"
        private const val KEY_PREFER_WORDS = "prefer_words"
        private const val KEY_DOWNLOAD_SORT = "download_sort"
        private const val KEY_SHOW_CLUTTER_FILES = "show_clutter_files"
        private const val KEY_CLIPBOARD_OFFERS_ENABLED = "clipboard_offers_enabled"
        private const val KEY_DOWNLOAD_PROGRESS_NOTIFICATIONS = "download_progress_notifications"
        private const val KEY_OFFERED_CLIPBOARD_HASHES = "offered_clipboard_hashes"
        private const val KEY_PLAYER_PACKAGE = "default_player_package"
        private const val KEY_PLAYER_ACTIVITY = "default_player_activity"
        private const val KEY_METADATA_OVERRIDE_PREFIX = "metadata_override_"
    }

    private fun readTheme(): ThemePreference {
        val saved = prefs.all
        return ThemePreference(
            ThemeMode.fromStored(saved[KEY_THEME_MODE] as? String),
            ThemeAccent.fromStored(saved[KEY_THEME_ACCENT] as? String),
        )
    }

    fun loadStartupTheme() {
        _theme.value = readTheme()
    }

    suspend fun loadTheme() = withContext(ioDispatcher) { readTheme() }

    suspend fun saveTheme(theme: ThemePreference) = withContext(ioDispatcher) {
        // Raw Editor API is intentional: the commit() result is checked.
        check(prefs.edit()
            .putString(KEY_THEME_MODE, theme.mode.name)
            .putString(KEY_THEME_ACCENT, theme.accent.name)
            .commit())
        _theme.value = theme
    }

    suspend fun loadDownloadSort() = withContext(ioDispatcher) {
        DownloadSort.fromStored(prefs.getString(KEY_DOWNLOAD_SORT, null))
    }

    suspend fun saveDownloadSort(sort: DownloadSort) = withContext(ioDispatcher) {
        prefs.edit { putString(KEY_DOWNLOAD_SORT, sort.name) }
    }

    suspend fun loadExcludeWords() = withContext(ioDispatcher) {
        readReleaseWords(KEY_EXCLUDE_WORDS, defaultExcludeWords)
    }

    suspend fun saveExcludeWords(words: List<String>) =
        writeReleaseWords(KEY_EXCLUDE_WORDS, words)

    suspend fun loadPreferWords() = withContext(ioDispatcher) {
        readReleaseWords(KEY_PREFER_WORDS, defaultPreferWords)
    }

    suspend fun savePreferWords(words: List<String>) =
        writeReleaseWords(KEY_PREFER_WORDS, words)

    /** Synchronous release-word read for the download-listing path,
     *  which already runs on IO. Same values as [loadExcludeWords]. */
    fun currentExcludeWords(): List<String> =
        readReleaseWords(KEY_EXCLUDE_WORDS, defaultExcludeWords)

    // An absent key uses the defaults; an empty string represents a cleared list.
    private fun readReleaseWords(key: String, defaults: List<String>): List<String> =
        prefs.getString(key, null)?.let { normalizeReleaseWords(listOf(it)) } ?: defaults

    private suspend fun writeReleaseWords(key: String, words: List<String>) = withContext(ioDispatcher) {
        prefs.edit { putString(key, normalizeReleaseWords(words).joinToString("\n")) }
    }

    suspend fun loadShowClutterFiles() = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_SHOW_CLUTTER_FILES, false)
    }

    suspend fun saveShowClutterFiles(show: Boolean) = withContext(ioDispatcher) {
        prefs.edit { putBoolean(KEY_SHOW_CLUTTER_FILES, show) }
    }

    suspend fun loadClipboardOffersEnabled() = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, true)
    }

    /** Download progress notifications, off by default: nothing is
     *  posted and no permission is requested until the user opts in. */
    suspend fun loadDownloadProgressNotificationsEnabled() = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_DOWNLOAD_PROGRESS_NOTIFICATIONS, false)
    }

    suspend fun saveDownloadProgressNotificationsEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        prefs.edit { putBoolean(KEY_DOWNLOAD_PROGRESS_NOTIFICATIONS, enabled) }
    }

    suspend fun saveClipboardOffersEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            synchronized(prefs) {
                // Raw Editor API is intentional: the commit() result is checked.
                check(prefs.edit().putBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, enabled).commit())
            }
        }
    }

    suspend fun claimClipboardOffer(text: String?): Boolean = withContext(ioDispatcher) {
        synchronized(prefs) {
            if (!prefs.getBoolean(KEY_CLIPBOARD_OFFERS_ENABLED, true)) return@synchronized false
            val offered = prefs.getStringSet(KEY_OFFERED_CLIPBOARD_HASHES, null).orEmpty()
            val hash = ClipboardOffer.newLinkHash(text, offered) ?: return@synchronized false
            // Raw Editor API is intentional: commit() is this function's Boolean result.
            prefs.edit().putStringSet(KEY_OFFERED_CLIPBOARD_HASHES, offered + hash).commit()
        }
    }

    // Acquire before dispatching: an immediate Play waits for an in-flight settings write.
    private val playerPreferencesMutex = Mutex()

    suspend fun defaultPlayer(): PlayerComponent? = playerPreferencesMutex.withLock {
        withContext(ioDispatcher) {
            val packageName = prefs.getString(KEY_PLAYER_PACKAGE, null)
            val activityName = prefs.getString(KEY_PLAYER_ACTIVITY, null)
            if (packageName.isNullOrBlank() || activityName.isNullOrBlank()) null
            else PlayerComponent(packageName, activityName)
        }
    }

    suspend fun setDefaultPlayer(player: PlayerComponent?) = playerPreferencesMutex.withLock {
        withContext(ioDispatcher) {
            prefs.edit {
                if (player == null) {
                    remove(KEY_PLAYER_PACKAGE)
                    remove(KEY_PLAYER_ACTIVITY)
                } else {
                    putString(KEY_PLAYER_PACKAGE, player.packageName)
                    putString(KEY_PLAYER_ACTIVITY, player.activityName)
                }
            }
        }
    }

    // One override is four plain-preference keys under
    // "metadata_override_<kind>:<id>:" — an id is not a secret, so
    // this stays out of the encrypted CredentialStore, and apart from
    // the metadata cache so clearing the cache never drops a pick.
    // A legacy override has no tmdb key and loads with tmdbId null.
    private fun metadataOverrideKey(ref: DownloadRef, field: String) =
        "$KEY_METADATA_OVERRIDE_PREFIX${ref.kind.name}:${ref.id}:$field"

    suspend fun loadMetadataOverride(ref: DownloadRef) = withContext(ioDispatcher) {
        val imdbId = prefs.getString(metadataOverrideKey(ref, "imdb"), null)?.takeIf { it.isNotBlank() }
        val tmdbId = prefs.getString(metadataOverrideKey(ref, "tmdb"), null)?.toIntOrNull()
        val type = prefs.getString(metadataOverrideKey(ref, "type"), null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        if (imdbId == null && tmdbId == null) return@withContext null
        MetadataOverride(
            imdbId, type,
            prefs.getString(metadataOverrideKey(ref, "title"), null) ?: imdbId ?: "tmdb:$tmdbId",
            tmdbId,
        )
    }

    suspend fun saveMetadataOverride(ref: DownloadRef, pick: MetadataOverride) =
        withContext(ioDispatcher) {
            prefs.edit {
                if (pick.imdbId != null) putString(metadataOverrideKey(ref, "imdb"), pick.imdbId)
                else remove(metadataOverrideKey(ref, "imdb"))
                if (pick.tmdbId != null) putString(metadataOverrideKey(ref, "tmdb"), pick.tmdbId.toString())
                else remove(metadataOverrideKey(ref, "tmdb"))
                putString(metadataOverrideKey(ref, "type"), pick.type)
                putString(metadataOverrideKey(ref, "title"), pick.title)
            }
        }

    suspend fun clearMetadataOverride(ref: DownloadRef) = withContext(ioDispatcher) {
        prefs.edit {
            remove(metadataOverrideKey(ref, "imdb"))
            remove(metadataOverrideKey(ref, "tmdb"))
            remove(metadataOverrideKey(ref, "type"))
            remove(metadataOverrideKey(ref, "title"))
        }
    }
}
