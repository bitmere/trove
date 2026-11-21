package app.trove.debrid.ui.settings

import android.content.ContentResolver
import android.net.Uri
import app.trove.debrid.R
import app.trove.debrid.core.VideoPlayer
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.PlayerDiscovery
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.AccountInfo
import app.trove.debrid.core.AllDebridProvider
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridLinkProvider
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.OffcloudProvider
import app.trove.debrid.core.PremiumizeProvider
import app.trove.debrid.core.RealDebridProvider
import app.trove.debrid.core.SimklPollResult
import app.trove.debrid.core.TorBoxProvider
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.data.BackupSettings
import app.trove.debrid.data.BackupUnlockFailed
import app.trove.debrid.data.DebridRepository
import app.trove.debrid.data.InvalidBackup
import app.trove.debrid.data.SettingsBackup
import app.trove.debrid.data.ThemePreference
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultExcludeWords
import app.trove.debrid.data.defaultPreferWords
import app.trove.debrid.data.normalizeReleaseWords
import app.trove.debrid.ui.theme.TroveSpace
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SAVED_CREDENTIAL_MASK = "••••••••••••"

/** One provider's account state: whether a credential is saved, the
 *  saved label, the credential field being typed, and whether a
 *  test/save is in flight. One record per service lives in the same
 *  [SettingsUiState] snapshot, so a state update touches one service
 *  without disturbing the others, and backup eligibility (which
 *  reads every record's `saving`) can never see a half-updated set. */
data class ProviderAccountState(
    val hasKey: Boolean = false,
    val accountLabel: String? = null,
    val maskedKey: String = "",
    val keyField: String = "",
    val showKey: Boolean = false,
    val saving: Boolean = false,
    val feedback: Boolean = false,
)

data class SettingsUiState(
    val defaultPlayer: PlayerComponent? = null,
    val players: List<VideoPlayer> = emptyList(),
    val loadingPlayers: Boolean = true,
    val savingPlayer: Boolean = false,
    val playerError: Boolean = false,
    val torbox: ProviderAccountState = ProviderAccountState(),
    val realDebrid: ProviderAccountState = ProviderAccountState(),
    val allDebrid: ProviderAccountState = ProviderAccountState(),
    val premiumize: ProviderAccountState = ProviderAccountState(),
    val offcloud: ProviderAccountState = ProviderAccountState(),
    val debridLink: ProviderAccountState = ProviderAccountState(),
    val accounts: List<DebridAccount> = emptyList(),
    val activeService: DebridService? = null,
    val messageResource: Int? = null,
    val messageArgument: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val showClutterFiles: Boolean = false,
    val loadingClutterFiles: Boolean = true,
    val savingClutterFiles: Boolean = false,
    val clutterFilesError: Boolean = false,
    val metadataCacheCleared: Boolean = false,
    val tmdbKeyField: String = "",
    val showTmdbKey: Boolean = false,
    val hasTmdbKey: Boolean = false,
    val maskedTmdbKey: String = "",
    val savingTmdbKey: Boolean = false,
    val tmdbKeyError: Boolean = false,
    val tmdbKeySaved: Boolean = false,
    val clipboardOffersEnabled: Boolean = true,
    val loadingClipboardOffers: Boolean = true,
    val savingClipboardOffers: Boolean = false,
    val clipboardOffersError: Boolean = false,
    val excludeWords: List<String> = defaultExcludeWords,
    val preferWords: List<String> = defaultPreferWords,
    val loadingReleaseWords: Boolean = true,
    val savingReleaseWords: Boolean = false,
    val releaseWordsError: Boolean = false,
    val theme: ThemePreference = ThemePreference(),
    val loadingTheme: Boolean = true,
    val savingTheme: Boolean = false,
    val themeError: Boolean = false,
    val backupBusy: Boolean = false,
    val chooseBackupDestination: Boolean = false,
    val restoreNeedsPassword: Boolean = false,
    val backupFeedback: Int? = null,
    val backupError: Boolean = false,
    // ---- SIMKL (read-only status badges) ----
    /** Whether the SIMKL V2 app is registered (client id minted). */
    val simklConfigured: Boolean = false,
    val simklSignedIn: Boolean = false,
    val simklUsername: String? = null,
    /** The saved client ID, shown in plain text — it is public. */
    val simklClientId: String? = null,
    val simklClientIdField: String = "",
    val savingSimklClientId: Boolean = false,
    val simklClientIdError: Boolean = false,
    val simklClientIdSaved: Boolean = false,
    val simklSigningIn: Boolean = false,
    /** The 8-char code the user types at simkl.com/pin. */
    val simklUserCode: String? = null,
    val simklVerificationUri: String? = null,
    val simklSignInExpired: Boolean = false,
    val simklSignInError: Boolean = false,
    val simklSyncing: Boolean = false,
    val simklEntryCount: Int = 0,
    val simklLastCheckedMs: Long? = null,
    val simklLastSyncError: String? = null,
) {
    val canStartBackup: Boolean
        get() = !backupBusy && !restoreNeedsPassword && !anyProviderSaving && !savingPlayer &&
            !savingClutterFiles && !savingClipboardOffers && !savingReleaseWords &&
            !loadingTheme && !savingTheme && !loadingPlayers && !loadingClutterFiles && !loadingClipboardOffers && !loadingReleaseWords

    /** True while any provider's test/save is in flight. */
    val anyProviderSaving: Boolean
        get() = torbox.saving || realDebrid.saving || allDebrid.saving ||
            premiumize.saving || offcloud.saving || debridLink.saving

    /** The account record for one service, for screens that render
     *  all six the same way. */
    fun providerAccount(service: DebridService): ProviderAccountState = when (service) {
        DebridService.TORBOX -> torbox
        DebridService.REAL_DEBRID -> realDebrid
        DebridService.ALLDEBRID -> allDebrid
        DebridService.PREMIUMIZE -> premiumize
        DebridService.OFFCLOUD -> offcloud
        DebridService.DEBRID_LINK -> debridLink
    }

    /** A copy with no provider section showing feedback. TorBox has
     *  no record flag of its own: its section shows the message
     *  exactly when no other section claims it. */
    fun clearFeedback(): SettingsUiState = copy(
        realDebrid = realDebrid.copy(feedback = false),
        allDebrid = allDebrid.copy(feedback = false),
        premiumize = premiumize.copy(feedback = false),
        offcloud = offcloud.copy(feedback = false),
        debridLink = debridLink.copy(feedback = false),
    )

    override fun toString(): String = "SettingsUiState([redacted])"
}

class SettingsViewModel(
    private val repository: TroveRepository,
    private val playerDiscovery: PlayerDiscovery = PlayerDiscovery { emptyList() },
    // The Android repository has synchronous Keystore and preference methods.
    private val accountDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        if (repository is DebridRepository) Dispatchers.IO else Dispatchers.Main.immediate,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _accountChanged = MutableStateFlow(false)
    /** One-shot refresh after account changes or restoring library preferences. */
    val accountChanged: StateFlow<Boolean> = _accountChanged.asStateFlow()

    private val accountReadError = "Couldn't read the saved account. Try again."
    private var refreshJob: Job? = null

    private var playersJob: Job? = null
    private var simklSignInJob: Job? = null
    private var pendingExport: ByteArray? = null
    private var pendingRestore: SettingsBackup.Envelope? = null

    init {
        refresh()
        refreshPlayers()
        viewModelScope.launch {
            try {
                val theme = repository.loadTheme()
                _state.update { it.copy(theme = theme) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(themeError = true) }
            } finally {
                _state.update { it.copy(loadingTheme = false) }
            }
        }
        viewModelScope.launch {
            try {
                val excludeWords = repository.loadExcludeWords()
                val preferWords = repository.loadPreferWords()
                _state.update { it.copy(excludeWords = excludeWords, preferWords = preferWords) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(releaseWordsError = true) }
            } finally {
                _state.update { it.copy(loadingReleaseWords = false) }
            }
        }
        viewModelScope.launch {
            try {
                val show = repository.loadShowClutterFiles()
                _state.update { it.copy(showClutterFiles = show) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(clutterFilesError = true) }
            } finally {
                _state.update { it.copy(loadingClutterFiles = false) }
            }
        }
        viewModelScope.launch {
            try {
                val enabled = repository.loadClipboardOffersEnabled()
                _state.update { it.copy(clipboardOffersEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(clipboardOffersError = true) }
            } finally {
                _state.update { it.copy(loadingClipboardOffers = false) }
            }
        }
    }

    fun prepareBackup(includeSignIn: Boolean, password: String, updateChannel: UpdateChannel) {
        if (!_state.value.canStartBackup) return
        runBackupOperation(R.string.backup_failed) {
            val encoded = withContext(Dispatchers.IO) {
                val settings = BackupSettings(
                    repository.defaultPlayer(),
                    repository.loadShowClutterFiles(),
                    repository.loadExcludeWords(),
                    repository.loadPreferWords(),
                    repository.loadClipboardOffersEnabled(),
                    updateChannel,
                    repository.loadTheme(),
                )
                val signIn = if (includeSignIn) {
                    repository.loadBackupSignIn() ?: throw MissingBackupSignIn()
                } else null
                SettingsBackup.encode(settings, signIn, password).encodeToByteArray().also {
                    check(it.size <= SettingsBackup.MAX_BYTES)
                }
            }
            pendingExport = encoded
            _state.update { it.copy(chooseBackupDestination = true) }
        }
    }

    fun backupDestinationRequested() {
        _state.update { it.copy(chooseBackupDestination = false) }
    }

    fun writeBackup(resolver: ContentResolver, uri: Uri?) {
        val encoded = pendingExport ?: return
        pendingExport = null
        if (uri == null) {
            _state.update { it.copy(backupBusy = false) }
            return
        }
        runBackupOperation(R.string.backup_failed) {
            withContext(Dispatchers.IO) {
                checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(encoded) }
            }
            _state.update { it.copy(backupFeedback = R.string.backup_saved) }
        }
    }

    fun chooseRestoreSource(): Boolean {
        if (!_state.value.canStartBackup) return false
        _state.update { it.copy(backupBusy = true, backupFeedback = null, backupError = false) }
        return true
    }

    fun backupPickerUnavailable() {
        pendingExport = null
        _state.update {
            it.copy(backupBusy = false, backupFeedback = R.string.backup_picker_unavailable, backupError = true)
        }
    }

    fun readBackup(resolver: ContentResolver, uri: Uri?, updateViewModel: UpdateViewModel) {
        if (uri == null) {
            _state.update { it.copy(backupBusy = false) }
            return
        }
        runBackupOperation(R.string.restore_read_failed) {
            val envelope = withContext(Dispatchers.IO) {
                val bytes = checkNotNull(resolver.openInputStream(uri)).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > SettingsBackup.MAX_BYTES) throw InvalidBackup()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val json = try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (_: java.nio.charset.CharacterCodingException) {
                    throw InvalidBackup()
                }
                SettingsBackup.decode(json)
            }
            if (envelope.hasSignIn) {
                pendingRestore = envelope
                _state.update { it.copy(restoreNeedsPassword = true) }
            } else {
                applyBackup(envelope, "", updateViewModel)
            }
        }
    }

    fun restoreWithPassword(password: String, updateViewModel: UpdateViewModel) {
        val envelope = pendingRestore ?: return
        if (_state.value.backupBusy) return
        runBackupOperation(R.string.restore_failed) { applyBackup(envelope, password, updateViewModel) }
    }

    fun dismissRestorePassword() {
        if (_state.value.backupBusy) return
        pendingRestore = null
        _state.update { it.copy(restoreNeedsPassword = false, backupFeedback = null, backupError = false) }
    }

    private suspend fun applyBackup(
        envelope: SettingsBackup.Envelope,
        password: String,
        updateViewModel: UpdateViewModel,
    ) {
        val signIn = withContext(Dispatchers.IO) { envelope.unlock(password) }
        val settings = envelope.settings
        val excludeWords = normalizeReleaseWords(settings.excludeWords)
        val preferWords = normalizeReleaseWords(settings.preferWords)
        withContext(NonCancellable) {
            settings.updateChannel?.let { updateViewModel.restoreChannel(it) }
            withContext(Dispatchers.IO) {
                repository.saveClipboardOffersEnabled(settings.clipboardOffersEnabled)
                repository.setDefaultPlayer(settings.player)
                repository.saveShowClutterFiles(settings.showClutterFiles)
                repository.saveExcludeWords(excludeWords)
                repository.savePreferWords(preferWords)
                if (signIn != null) repository.restoreBackupSignIn(signIn)
                repository.saveTheme(settings.theme)
            }
        }
        pendingRestore = null
        // Restoring a sign-in clears every typed credential field, so
        // no stale secret stays visible; without a sign-in the typed
        // fields are left exactly as they were.
        fun ProviderAccountState.clearTypedField() =
            if (signIn != null) copy(keyField = "", showKey = false) else this
        _state.update {
            it.copy(
                theme = settings.theme,
                themeError = false,
                defaultPlayer = settings.player,
                playerError = false,
                showClutterFiles = settings.showClutterFiles,
                clutterFilesError = false,
                excludeWords = excludeWords,
                preferWords = preferWords,
                releaseWordsError = false,
                clipboardOffersEnabled = settings.clipboardOffersEnabled,
                clipboardOffersError = false,
                torbox = it.torbox.clearTypedField(),
                realDebrid = it.realDebrid.clearTypedField(),
                allDebrid = it.allDebrid.clearTypedField(),
                premiumize = it.premiumize.clearTypedField(),
                offcloud = it.offcloud.clearTypedField(),
                debridLink = it.debridLink.clearTypedField(),
                restoreNeedsPassword = false,
                backupFeedback = R.string.restore_done,
            )
        }
        if (signIn != null) refresh()
        _accountChanged.value = true
    }

    private fun runBackupOperation(failureMessage: Int, operation: suspend () -> Unit) {
        _state.update { it.copy(backupBusy = true, backupFeedback = null, backupError = false) }
        viewModelScope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is InvalidBackup -> R.string.restore_invalid
                    is BackupUnlockFailed -> R.string.restore_unlock_failed
                    is MissingBackupSignIn -> R.string.backup_sign_in_missing
                    else -> failureMessage
                }
                if (e !is BackupUnlockFailed) pendingRestore = null
                _state.update { it.copy(
                    restoreNeedsPassword = pendingRestore != null,
                    backupFeedback = message,
                    backupError = true,
                ) }
            } finally {
                _state.update { it.copy(backupBusy = pendingExport != null) }
            }
        }
    }

    private class MissingBackupSignIn : Exception()

    fun setExcludeWords(words: List<String>) = saveReleaseWords(words) { normalized ->
        repository.saveExcludeWords(normalized)
        _state.update { it.copy(excludeWords = normalized) }
    }

    fun setPreferWords(words: List<String>) = saveReleaseWords(words) { normalized ->
        repository.savePreferWords(normalized)
        _state.update { it.copy(preferWords = normalized) }
    }

    private fun saveReleaseWords(words: List<String>, save: suspend (List<String>) -> Unit) {
        if (backupBlocksEdits() || _state.value.loadingReleaseWords || _state.value.savingReleaseWords) return
        val normalized = normalizeReleaseWords(words)
        _state.update { it.copy(savingReleaseWords = true, releaseWordsError = false) }
        viewModelScope.launch {
            try {
                save(normalized)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(releaseWordsError = true) }
            } finally {
                _state.update { it.copy(savingReleaseWords = false) }
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val connection = repository.loadConnection()
                val (accounts, active, servicesWithKeys) = withContext(accountDispatcher) {
                    Triple(
                        repository.accounts(),
                        repository.activeService(),
                        buildSet {
                            if (repository.hasTorboxKey()) add(DebridService.TORBOX)
                            if (repository.hasRealDebridKey()) add(DebridService.REAL_DEBRID)
                            if (repository.hasAllDebridKey()) add(DebridService.ALLDEBRID)
                            if (repository.hasPremiumizeKey()) add(DebridService.PREMIUMIZE)
                            if (repository.hasOffcloudKey()) add(DebridService.OFFCLOUD)
                            if (repository.hasDebridLinkKey()) add(DebridService.DEBRID_LINK)
                        },
                    )
                }
                val torboxConnection = listOfNotNull(active, connection.provider?.service, connection.account?.service)
                    .all { it == DebridService.TORBOX }
                val hasTorboxKey = DebridService.TORBOX in servicesWithKeys || (torboxConnection && connection.hasKey)
                val tmdbKey = withContext(accountDispatcher) {
                    repository.hasTmdbKey() to repository.maskedTmdbKey()
                }
                val simklInfo = withContext(accountDispatcher) {
                    repository.simklSnapshotInfo()
                }
                val simklClientId = withContext(accountDispatcher) {
                    repository.simklClientId()
                }
                val torbox = accounts.firstOrNull { it.service == DebridService.TORBOX }
                val realDebrid = accounts.firstOrNull { it.service == DebridService.REAL_DEBRID }
                _state.update {
                    it.copy(
                        // One snapshot rewrites all six records: a
                        // refresh never reports a half-updated set, and
                        // typed fields and in-flight saves survive it.
                        torbox = it.torbox.copy(
                            hasKey = hasTorboxKey,
                            accountLabel = torbox?.label ?: connection.account?.label.takeIf { torboxConnection },
                            maskedKey = if (hasTorboxKey) SAVED_CREDENTIAL_MASK else "",
                        ),
                        realDebrid = it.realDebrid.copy(
                            hasKey = DebridService.REAL_DEBRID in servicesWithKeys,
                            accountLabel = realDebrid?.label,
                        ),
                        allDebrid = it.allDebrid.copy(
                            hasKey = DebridService.ALLDEBRID in servicesWithKeys,
                            accountLabel = accounts.firstOrNull { it.service == DebridService.ALLDEBRID }?.label,
                        ),
                        premiumize = it.premiumize.copy(
                            hasKey = DebridService.PREMIUMIZE in servicesWithKeys,
                            accountLabel = accounts.firstOrNull { it.service == DebridService.PREMIUMIZE }?.label,
                        ),
                        offcloud = it.offcloud.copy(
                            hasKey = DebridService.OFFCLOUD in servicesWithKeys,
                            accountLabel = accounts.firstOrNull { it.service == DebridService.OFFCLOUD }?.label,
                        ),
                        debridLink = it.debridLink.copy(
                            hasKey = DebridService.DEBRID_LINK in servicesWithKeys,
                            accountLabel = accounts.firstOrNull { it.service == DebridService.DEBRID_LINK }?.label,
                        ),
                        accounts = accounts,
                        activeService = active,
                        hasTmdbKey = tmdbKey.first,
                        maskedTmdbKey = tmdbKey.second,
                        simklConfigured = repository.isSimklConfigured(),
                        simklSignedIn = repository.isSimklSignedIn(),
                        simklUsername = simklInfo?.username,
                        simklClientId = simklClientId,
                        simklEntryCount = simklInfo?.entryCount ?: 0,
                        simklLastCheckedMs = simklInfo?.lastCheckedMs,
                        simklLastSyncError = simklInfo?.lastFailure?.name,
                        message = it.message.takeUnless { message -> message == accountReadError },
                        messageIsError = it.messageIsError && it.message != accountReadError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.clearFeedback().copy(message = accountReadError, messageIsError = true,
                        messageResource = null, messageArgument = null)
                }
            }
        }
    }

    fun refreshPlayers() {
        if (backupBlocksEdits() || _state.value.savingPlayer) return
        playersJob?.cancel()
        _state.update { it.copy(loadingPlayers = true, playerError = false) }
        playersJob = viewModelScope.launch {
            try {
                val saved = repository.defaultPlayer()
                val players = playerDiscovery.players("video/mp4")
                ensureActive()
                _state.update { it.copy(defaultPlayer = saved, players = players) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureActive()
                _state.update { it.copy(playerError = true) }
            } finally {
                if (coroutineContext[Job]?.isActive == true) {
                    _state.update { it.copy(loadingPlayers = false) }
                }
            }
        }
    }

    fun setDefaultPlayer(player: PlayerComponent?) {
        if (backupBlocksEdits() || _state.value.loadingPlayers || _state.value.savingPlayer) return
        _state.update { it.copy(savingPlayer = true, playerError = false) }
        viewModelScope.launch {
            try {
                repository.setDefaultPlayer(player)
                _state.update { it.copy(defaultPlayer = player) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(playerError = true) }
            } finally {
                _state.update { it.copy(savingPlayer = false) }
            }
        }
    }

    fun setShowClutterFiles(show: Boolean) {
        if (backupBlocksEdits() || _state.value.loadingClutterFiles || _state.value.savingClutterFiles) return
        _state.update { it.copy(savingClutterFiles = true, clutterFilesError = false) }
        viewModelScope.launch {
            try {
                repository.saveShowClutterFiles(show)
                _state.update { it.copy(showClutterFiles = show) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(clutterFilesError = true) }
            } finally {
                _state.update { it.copy(savingClutterFiles = false) }
            }
        }
    }

    /** Best-effort: the disk cache itself never throws, so a failure here
     *  just leaves the cache in place with no banner. */
    fun clearCachedMetadata() {
        _state.update { it.copy(metadataCacheCleared = false) }
        viewModelScope.launch {
            try {
                repository.clearCachedMetadata()
                _state.update { it.copy(metadataCacheCleared = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave the flag false; the cached entries stay.
            }
        }
    }

    fun setTmdbKeyField(text: String) {
        _state.update { it.copy(tmdbKeyField = text, tmdbKeySaved = false, tmdbKeyError = false) }
    }

    fun toggleShowTmdbKey() {
        _state.update { it.copy(showTmdbKey = !it.showTmdbKey) }
    }

    /** Checks the typed key against TMDB before storing it, so a
     *  mistyped key is caught here instead of silently disabling the
     *  fallback. The key itself is never copied into state or a
     *  message; only its masked form is ever shown. */
    fun saveTmdbKey() {
        if (backupBlocksEdits() || _state.value.savingTmdbKey) return
        val key = _state.value.tmdbKeyField.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(tmdbKeyError = true) }
            return
        }
        _state.update { it.copy(savingTmdbKey = true, tmdbKeyError = false, tmdbKeySaved = false) }
        viewModelScope.launch {
            try {
                // Result.fold's lambdas are not suspending, so branch
                // on the outcome instead of folding over it.
                if (repository.validateTmdbKey(key).isSuccess) {
                    repository.saveTmdbKey(key)
                    val masked = withContext(accountDispatcher) { repository.maskedTmdbKey() }
                    _state.update {
                        it.copy(
                            hasTmdbKey = true, maskedTmdbKey = masked,
                            tmdbKeyField = "", showTmdbKey = false, tmdbKeySaved = true,
                        )
                    }
                } else {
                    _state.update { it.copy(tmdbKeyError = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(tmdbKeyError = true) }
            } finally {
                _state.update { it.copy(savingTmdbKey = false) }
            }
        }
    }

    fun clearTmdbKey() {
        if (backupBlocksEdits() || _state.value.savingTmdbKey) return
        viewModelScope.launch {
            try {
                repository.clearTmdbKey()
                _state.update {
                    it.copy(
                        hasTmdbKey = false, maskedTmdbKey = "",
                        tmdbKeyField = "", showTmdbKey = false, tmdbKeySaved = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(tmdbKeyError = true) }
            }
        }
    }

    fun setTheme(theme: ThemePreference) {
        val state = _state.value
        if (backupBlocksEdits() || state.loadingTheme || state.savingTheme || state.theme == theme) return
        _state.update { it.copy(savingTheme = true, themeError = false) }
        viewModelScope.launch {
            try {
                repository.saveTheme(theme)
                _state.update { it.copy(theme = theme) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(themeError = true) }
            } finally {
                _state.update { it.copy(savingTheme = false) }
            }
        }
    }

    fun setClipboardOffersEnabled(enabled: Boolean) {
        if (backupBlocksEdits() || _state.value.loadingClipboardOffers || _state.value.savingClipboardOffers) return
        _state.update { it.copy(savingClipboardOffers = true, clipboardOffersError = false) }
        viewModelScope.launch {
            try {
                repository.saveClipboardOffersEnabled(enabled)
                _state.update { it.copy(clipboardOffersEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(clipboardOffersError = true) }
            } finally {
                _state.update { it.copy(savingClipboardOffers = false) }
            }
        }
    }

    fun setKeyField(text: String) {
        _state.update {
            it.clearFeedback().copy(
                torbox = it.torbox.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowKey() {
        _state.update { it.copy(torbox = it.torbox.copy(showKey = !it.torbox.showKey)) }
    }

    fun setRdTokenField(text: String) {
        _state.update {
            it.copy(
                realDebrid = it.realDebrid.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowRdToken() {
        _state.update { it.copy(realDebrid = it.realDebrid.copy(showKey = !it.realDebrid.showKey)) }
    }

    fun setAdKeyField(text: String) {
        _state.update {
            it.copy(
                allDebrid = it.allDebrid.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowAdKey() {
        _state.update { it.copy(allDebrid = it.allDebrid.copy(showKey = !it.allDebrid.showKey)) }
    }

    fun setPmKeyField(text: String) {
        _state.update {
            it.copy(
                premiumize = it.premiumize.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowPmKey() {
        _state.update { it.copy(premiumize = it.premiumize.copy(showKey = !it.premiumize.showKey)) }
    }

    fun setOcKeyField(text: String) {
        _state.update {
            it.copy(
                offcloud = it.offcloud.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowOcKey() {
        _state.update { it.copy(offcloud = it.offcloud.copy(showKey = !it.offcloud.showKey)) }
    }

    fun setDlKeyField(text: String) {
        _state.update {
            it.clearFeedback().copy(
                debridLink = it.debridLink.copy(keyField = text),
                message = null, messageResource = null, messageArgument = null,
            )
        }
    }

    fun toggleShowDlKey() {
        _state.update { it.copy(debridLink = it.debridLink.copy(showKey = !it.debridLink.showKey)) }
    }

    fun testAndSave(service: DebridService) {
        val descriptor = providerSectionDescriptors.getValue(service)
        if (backupBlocksEdits()) return
        val state = _state.value
        val plain = descriptor.plainFeedback
        if (!plain && descriptor.saving(state)) return
        val credential = descriptor.field(state).trim()
        if (credential.isEmpty()) {
            _state.update {
                descriptor.feedback(it, text = if (plain) "Paste your TorBox API key first" else null,
                    resource = descriptor.emptyCredential, error = true)
            }
            return
        }
        if (descriptor.saving(state)) return
        _state.update {
            descriptor.setSaving(descriptor.feedback(it, error = plain && it.messageIsError), true)
        }
        viewModelScope.launch {
            try {
                repository.validateAndSave(service, credential, descriptor.factory, descriptor.accountLabel).fold(
                    onSuccess = { account ->
                        _state.update {
                            descriptor.feedback(
                                descriptor.clearField(it),
                                text = if (plain) "TorBox connected — ${account.label}" else null,
                                resource = descriptor.connected,
                                argument = if (plain) null else account.label,
                            )
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (!plain && error is CancellationException) throw error
                        showSaveFailure(descriptor, error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSaveFailure(descriptor, e, thrown = true)
            } finally {
                _state.update { descriptor.setSaving(it, false) }
            }
        }
    }

    fun signOut(service: DebridService) {
        val descriptor = providerSectionDescriptors.getValue(service)
        if (backupBlocksEdits() || descriptor.saving(_state.value)) return
        refreshJob?.cancel()
        val plain = descriptor.plainFeedback
        _state.update {
            descriptor.setSaving(if (plain) it else descriptor.feedback(it), true)
        }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOut(service) }
                _state.update {
                    descriptor.feedback(
                        descriptor.clearAccount(descriptor.clearField(it)),
                        text = if (plain) "TorBox signed out" else null,
                        resource = descriptor.signedOut,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    descriptor.feedback(it, text = if (plain) "Couldn't sign out of TorBox" else null,
                        resource = descriptor.signOutFailed, error = true)
                }
            } finally {
                _state.update { descriptor.setSaving(it, false) }
            }
        }
    }

    // ---- SIMKL (read-only status badges) ----

    /** Starts the SIMKL device/PIN flow: shows the 8-char code and
     *  polls until the user authorises, the code expires, or the
     *  user cancels. The code itself is never logged. */
    fun setSimklClientIdField(text: String) {
        _state.update { it.copy(simklClientIdField = text, simklClientIdSaved = false, simklClientIdError = false) }
    }

    /** Stores the user's own SIMKL client id. No live validation:
     *  there is no endpoint that validates a client id without
     *  starting a device flow, and the flow itself reports an
     *  invalid client at sign-in. A changed ID wipes the tokens
     *  and the cached library in the repository, so the state is
     *  refreshed after the save. */
    fun saveSimklClientId() {
        val state = _state.value
        if (backupBlocksEdits() || state.savingSimklClientId || state.simklSigningIn || state.simklSyncing) return
        val id = state.simklClientIdField.trim()
        if (id.isEmpty()) {
            _state.update { it.copy(simklClientIdError = true) }
            return
        }
        _state.update { it.copy(savingSimklClientId = true, simklClientIdError = false, simklClientIdSaved = false) }
        viewModelScope.launch {
            try {
                repository.saveSimklClientId(id)
                refresh()
                _state.update { it.copy(simklClientIdField = "", simklClientIdSaved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(simklClientIdError = true) }
            } finally {
                _state.update { it.copy(savingSimklClientId = false) }
            }
        }
    }

    fun clearSimklClientId() {
        val state = _state.value
        if (backupBlocksEdits() || state.savingSimklClientId || state.simklSigningIn || state.simklSyncing) return
        _state.update { it.copy(savingSimklClientId = true, simklClientIdError = false, simklClientIdSaved = false) }
        viewModelScope.launch {
            try {
                repository.clearSimklClientId()
                refresh()
                _state.update { it.copy(simklClientIdField = "", simklClientIdSaved = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(simklClientIdError = true) }
            } finally {
                _state.update { it.copy(savingSimklClientId = false) }
            }
        }
    }

    fun startSimklSignIn() {
        if (!repository.isSimklConfigured() || _state.value.simklSigningIn) return
        simklSignInJob?.cancel()
        _state.update {
            it.copy(simklSigningIn = true, simklUserCode = null, simklVerificationUri = null,
                simklSignInExpired = false, simklSignInError = false)
        }
        simklSignInJob = viewModelScope.launch {
            val deviceResult = try {
                repository.startSimklDeviceFlow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(simklSigningIn = false, simklSignInError = true) }
                return@launch
            }
            if (deviceResult.isFailure) {
                _state.update { it.copy(simklSigningIn = false, simklSignInError = true) }
                return@launch
            }
            val device = deviceResult.getOrThrow()
            _state.update {
                it.copy(simklUserCode = device.userCode, simklVerificationUri = device.verificationUriComplete)
            }
            val deadline = System.currentTimeMillis() + device.expiresInSecs * 1000L
            var intervalMs = device.intervalSecs * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                val result = try {
                    repository.pollSimklDeviceCode(device)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SimklPollResult.Failed("poll_failed")
                }
                when (result) {
                    is SimklPollResult.Authorized -> {
                        _state.update { it.copy(simklSigningIn = false, simklUserCode = null, simklVerificationUri = null) }
                        refresh()
                        return@launch
                    }
                    is SimklPollResult.SlowDown -> intervalMs += 5_000L
                    is SimklPollResult.Expired -> {
                        _state.update { it.copy(simklSigningIn = false, simklUserCode = null, simklVerificationUri = null, simklSignInExpired = true) }
                        return@launch
                    }
                    is SimklPollResult.InvalidClient, is SimklPollResult.Failed -> {
                        _state.update { it.copy(simklSigningIn = false, simklUserCode = null, simklVerificationUri = null, simklSignInError = true) }
                        return@launch
                    }
                    is SimklPollResult.Pending -> Unit
                }
            }
            _state.update { it.copy(simklSigningIn = false, simklUserCode = null, simklVerificationUri = null, simklSignInExpired = true) }
        }
    }

    fun cancelSimklSignIn() {
        simklSignInJob?.cancel()
        _state.update { it.copy(simklSigningIn = false, simklUserCode = null, simklVerificationUri = null) }
    }

    fun signOutSimkl() {
        if (_state.value.simklSyncing) return
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutSimkl() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local state is cleared by the repository regardless.
            }
            refresh()
        }
    }

    fun syncSimklNow() {
        if (!repository.isSimklSignedIn() || _state.value.simklSyncing) return
        _state.update { it.copy(simklSyncing = true, simklLastSyncError = null) }
        viewModelScope.launch {
            try {
                repository.syncSimkl(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The failure is recorded on the snapshot; refresh surfaces it.
            } finally {
                _state.update { it.copy(simklSyncing = false) }
                refresh()
            }
        }
    }

    fun setActiveProvider(service: DebridService) {        if (backupBlocksEdits()) return
        viewModelScope.launch {
            try {
                val changed = withContext(accountDispatcher) {
                    val previous = repository.activeService()
                    repository.setActiveService(service)
                    repository.activeService() != previous
                }
                refresh()
                if (changed) _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.clearFeedback().copy(message = accountReadError, messageIsError = true,
                        messageResource = null, messageArgument = null)
                }
            }
        }
    }

    private fun showSaveFailure(descriptor: ProviderSectionDescriptor, error: Throwable, thrown: Boolean = false) {
        val plain = descriptor.plainFeedback
        val detail = when {
            plain && thrown -> "Couldn't check that key"
            else -> (error as? DebridError)?.message ?: if (plain) "Key didn't work" else null
        }
        _state.update {
            descriptor.feedback(it, text = detail, resource = descriptor.saveFailed.takeIf { detail == null },
                error = true)
        }
    }

    private fun backupBlocksEdits(): Boolean =
        _state.value.backupBusy || _state.value.restoreNeedsPassword

    fun consumeAccountChanged() {
        _accountChanged.value = false
    }
}

internal class ProviderSectionDescriptor(
    val service: DebridService,
    val factory: (String, HttpTransport) -> DebridProvider,
    val accountLabel: (AccountInfo?) -> String,
    val title: Int,
    val credentialSaved: Int,
    val savedCredential: Int,
    val replaceCredential: Int,
    val credentialLabel: Int,
    val checkingCredential: Int,
    val helpLines: List<Int>,
    val hasKey: (SettingsUiState) -> Boolean,
    val savedLabel: (SettingsUiState) -> String?,
    val field: (SettingsUiState) -> String,
    val showCredential: (SettingsUiState) -> Boolean,
    val saving: (SettingsUiState) -> Boolean,
    val showFeedback: (SettingsUiState) -> Boolean,
    val setField: (SettingsViewModel, String) -> Unit,
    val toggleShow: (SettingsViewModel) -> Unit,
    val setSaving: (SettingsUiState, Boolean) -> SettingsUiState,
    val clearField: (SettingsUiState) -> SettingsUiState,
    val clearAccount: (SettingsUiState) -> SettingsUiState,
    val emptyCredential: Int? = null,
    val connected: Int? = null,
    val saveFailed: Int? = null,
    val signedOut: Int? = null,
    val signOutFailed: Int? = null,
    val maskedCredential: (SettingsUiState) -> String = { SAVED_CREDENTIAL_MASK },
    val buttonPadding: Dp = TroveSpace.medium,
    val progressStroke: Dp? = null,
) {
    val plainFeedback: Boolean get() = service == DebridService.TORBOX

    fun feedback(
        state: SettingsUiState,
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ): SettingsUiState = state.copy(
        message = text,
        messageResource = resource,
        messageArgument = argument,
        messageIsError = error,
        // Exactly one section claims the message. TorBox has no
        // record flag of its own: it shows the message when no other
        // section claims it, so all five stay false for TorBox.
        realDebrid = state.realDebrid.copy(feedback = service == DebridService.REAL_DEBRID),
        allDebrid = state.allDebrid.copy(feedback = service == DebridService.ALLDEBRID),
        premiumize = state.premiumize.copy(feedback = service == DebridService.PREMIUMIZE),
        offcloud = state.offcloud.copy(feedback = service == DebridService.OFFCLOUD),
        debridLink = state.debridLink.copy(feedback = service == DebridService.DEBRID_LINK),
    )
}

internal val providerSectionDescriptors = listOf(
    ProviderSectionDescriptor(
        service = DebridService.TORBOX,
        factory = { credential, transport -> TorBoxProvider(
            credential, transport,
            diagnosticLog = { android.util.Log.d("TorBox", it) },
        ) },
        accountLabel = { "TorBox (${it?.detail ?: "Essential"})" },
        title = R.string.torbox,
        credentialSaved = R.string.key_saved,
        savedCredential = R.string.saved_key,
        replaceCredential = R.string.replace_key,
        credentialLabel = R.string.api_key,
        checkingCredential = R.string.checking_key,
        helpLines = listOf(R.string.key_help),
        hasKey = { it.torbox.hasKey },
        savedLabel = { it.torbox.accountLabel },
        field = { it.torbox.keyField },
        showCredential = { it.torbox.showKey },
        saving = { it.torbox.saving },
        showFeedback = {
            !it.realDebrid.feedback && !it.allDebrid.feedback && !it.premiumize.feedback &&
                !it.offcloud.feedback && !it.debridLink.feedback
        },
        setField = SettingsViewModel::setKeyField,
        toggleShow = SettingsViewModel::toggleShowKey,
        setSaving = { state, saving -> state.copy(torbox = state.torbox.copy(saving = saving)) },
        clearField = { it.copy(torbox = it.torbox.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(torbox = it.torbox.copy(hasKey = false, accountLabel = null, maskedKey = "")) },
        maskedCredential = { it.torbox.maskedKey },
        buttonPadding = 18.dp,
        progressStroke = 2.dp,
    ),
    ProviderSectionDescriptor(
        service = DebridService.REAL_DEBRID,
        factory = { credential, transport -> RealDebridProvider(credential, transport) },
        accountLabel = { "Real-Debrid (${it?.detail})" },
        title = R.string.real_debrid,
        credentialSaved = R.string.rd_token_saved,
        savedCredential = R.string.rd_saved_token,
        replaceCredential = R.string.rd_replace_token,
        credentialLabel = R.string.real_debrid_api_token,
        checkingCredential = R.string.rd_checking_token,
        helpLines = listOf(R.string.rd_token_help, R.string.rd_token_note),
        hasKey = { it.realDebrid.hasKey },
        savedLabel = { it.realDebrid.accountLabel },
        field = { it.realDebrid.keyField },
        showCredential = { it.realDebrid.showKey },
        saving = { it.realDebrid.saving },
        showFeedback = { it.realDebrid.feedback },
        setField = SettingsViewModel::setRdTokenField,
        toggleShow = SettingsViewModel::toggleShowRdToken,
        setSaving = { state, saving -> state.copy(realDebrid = state.realDebrid.copy(saving = saving)) },
        clearField = { it.copy(realDebrid = it.realDebrid.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(realDebrid = it.realDebrid.copy(hasKey = false, accountLabel = null)) },
        emptyCredential = R.string.rd_token_empty,
        connected = R.string.rd_connected,
        saveFailed = R.string.rd_token_failed,
        signedOut = R.string.rd_signed_out,
        signOutFailed = R.string.rd_sign_out_failed,
    ),
    ProviderSectionDescriptor(
        service = DebridService.ALLDEBRID,
        factory = { credential, transport -> AllDebridProvider(credential, transport) },
        accountLabel = { "AllDebrid (${it?.detail})" },
        title = R.string.all_debrid,
        credentialSaved = R.string.ad_key_saved,
        savedCredential = R.string.ad_saved_key,
        replaceCredential = R.string.ad_replace_key,
        credentialLabel = R.string.all_debrid_api_key,
        checkingCredential = R.string.ad_checking_key,
        helpLines = listOf(R.string.ad_key_help, R.string.ad_key_note),
        hasKey = { it.allDebrid.hasKey },
        savedLabel = { it.allDebrid.accountLabel },
        field = { it.allDebrid.keyField },
        showCredential = { it.allDebrid.showKey },
        saving = { it.allDebrid.saving },
        showFeedback = { it.allDebrid.feedback },
        setField = SettingsViewModel::setAdKeyField,
        toggleShow = SettingsViewModel::toggleShowAdKey,
        setSaving = { state, saving -> state.copy(allDebrid = state.allDebrid.copy(saving = saving)) },
        clearField = { it.copy(allDebrid = it.allDebrid.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(allDebrid = it.allDebrid.copy(hasKey = false, accountLabel = null)) },
        emptyCredential = R.string.ad_key_empty,
        connected = R.string.ad_connected,
        saveFailed = R.string.ad_key_failed,
        signedOut = R.string.ad_signed_out,
        signOutFailed = R.string.ad_sign_out_failed,
    ),
    ProviderSectionDescriptor(
        service = DebridService.PREMIUMIZE,
        factory = { credential, transport -> PremiumizeProvider(credential, transport) },
        accountLabel = { "Premiumize (${it?.detail})" },
        title = R.string.premiumize,
        credentialSaved = R.string.pm_key_saved,
        savedCredential = R.string.pm_saved_key,
        replaceCredential = R.string.pm_replace_key,
        credentialLabel = R.string.premiumize_api_key,
        checkingCredential = R.string.pm_checking_key,
        helpLines = listOf(R.string.pm_key_help),
        hasKey = { it.premiumize.hasKey },
        savedLabel = { it.premiumize.accountLabel },
        field = { it.premiumize.keyField },
        showCredential = { it.premiumize.showKey },
        saving = { it.premiumize.saving },
        showFeedback = { it.premiumize.feedback },
        setField = SettingsViewModel::setPmKeyField,
        toggleShow = SettingsViewModel::toggleShowPmKey,
        setSaving = { state, saving -> state.copy(premiumize = state.premiumize.copy(saving = saving)) },
        clearField = { it.copy(premiumize = it.premiumize.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(premiumize = it.premiumize.copy(hasKey = false, accountLabel = null)) },
        emptyCredential = R.string.pm_key_empty,
        connected = R.string.pm_connected,
        saveFailed = R.string.pm_key_failed,
        signedOut = R.string.pm_signed_out,
        signOutFailed = R.string.pm_sign_out_failed,
    ),
    ProviderSectionDescriptor(
        service = DebridService.OFFCLOUD,
        factory = { credential, transport -> OffcloudProvider(credential, transport) },
        accountLabel = { "Offcloud" },
        title = R.string.offcloud,
        credentialSaved = R.string.oc_key_saved,
        savedCredential = R.string.oc_saved_key,
        replaceCredential = R.string.oc_replace_key,
        credentialLabel = R.string.offcloud_api_key,
        checkingCredential = R.string.oc_checking_key,
        helpLines = listOf(R.string.oc_key_help),
        hasKey = { it.offcloud.hasKey },
        savedLabel = { it.offcloud.accountLabel },
        field = { it.offcloud.keyField },
        showCredential = { it.offcloud.showKey },
        saving = { it.offcloud.saving },
        showFeedback = { it.offcloud.feedback },
        setField = SettingsViewModel::setOcKeyField,
        toggleShow = SettingsViewModel::toggleShowOcKey,
        setSaving = { state, saving -> state.copy(offcloud = state.offcloud.copy(saving = saving)) },
        clearField = { it.copy(offcloud = it.offcloud.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(offcloud = it.offcloud.copy(hasKey = false, accountLabel = null)) },
        emptyCredential = R.string.oc_key_empty,
        connected = R.string.oc_connected,
        saveFailed = R.string.oc_key_failed,
        signedOut = R.string.oc_signed_out,
        signOutFailed = R.string.oc_sign_out_failed,
    ),
    ProviderSectionDescriptor(
        service = DebridService.DEBRID_LINK,
        factory = { credential, transport -> DebridLinkProvider(credential, transport) },
        accountLabel = { "Debrid-Link (${it?.detail})" },
        title = R.string.debrid_link,
        credentialSaved = R.string.dl_key_saved,
        savedCredential = R.string.dl_saved_key,
        replaceCredential = R.string.dl_replace_key,
        credentialLabel = R.string.debridlink_api_key,
        checkingCredential = R.string.dl_checking_key,
        helpLines = listOf(R.string.dl_key_help),
        hasKey = { it.debridLink.hasKey },
        savedLabel = { it.debridLink.accountLabel },
        field = { it.debridLink.keyField },
        showCredential = { it.debridLink.showKey },
        saving = { it.debridLink.saving },
        showFeedback = { it.debridLink.feedback },
        setField = SettingsViewModel::setDlKeyField,
        toggleShow = SettingsViewModel::toggleShowDlKey,
        setSaving = { state, saving -> state.copy(debridLink = state.debridLink.copy(saving = saving)) },
        clearField = { it.copy(debridLink = it.debridLink.copy(keyField = "", showKey = false)) },
        clearAccount = { it.copy(debridLink = it.debridLink.copy(hasKey = false, accountLabel = null)) },
        emptyCredential = R.string.dl_key_empty,
        connected = R.string.dl_connected,
        saveFailed = R.string.dl_key_failed,
        signedOut = R.string.dl_signed_out,
        signOutFailed = R.string.dl_sign_out_failed,
    ),
).associateBy(ProviderSectionDescriptor::service)

/** Providers after TorBox, in the build order from the research. */
val COMING_SOON_SERVICES: List<Pair<DebridService, String>> = emptyList()
