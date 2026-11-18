package app.trove.debrid.ui.settings

import android.content.ContentResolver
import android.net.Uri
import app.trove.debrid.R
import app.trove.debrid.core.VideoPlayer
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.PlayerDiscovery
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SAVED_CREDENTIAL_MASK = "••••••••••••"

data class SettingsUiState(
    val defaultPlayer: PlayerComponent? = null,
    val players: List<VideoPlayer> = emptyList(),
    val loadingPlayers: Boolean = true,
    val savingPlayer: Boolean = false,
    val playerError: Boolean = false,
    val hasKey: Boolean = false,
    val accountLabel: String? = null,
    val maskedKey: String = "",
    val keyField: String = "",
    val showKey: Boolean = false,
    val saving: Boolean = false,
    val rdHasKey: Boolean = false,
    val rdAccountLabel: String? = null,
    val rdTokenField: String = "",
    val rdShowToken: Boolean = false,
    val rdSaving: Boolean = false,
    val adHasKey: Boolean = false,
    val adAccountLabel: String? = null,
    val adKeyField: String = "",
    val adShowKey: Boolean = false,
    val adSaving: Boolean = false,
    val pmHasKey: Boolean = false,
    val pmAccountLabel: String? = null,
    val pmKeyField: String = "",
    val pmShowKey: Boolean = false,
    val pmSaving: Boolean = false,
    val ocHasKey: Boolean = false,
    val dlHasKey: Boolean = false,
    val ocAccountLabel: String? = null,
    val dlAccountLabel: String? = null,
    val ocKeyField: String = "",
    val dlKeyField: String = "",
    val ocShowKey: Boolean = false,
    val dlShowKey: Boolean = false,
    val ocSaving: Boolean = false,
    val dlSaving: Boolean = false,
    val accounts: List<DebridAccount> = emptyList(),
    val activeService: DebridService? = null,
    val rdFeedback: Boolean = false,
    val adFeedback: Boolean = false,
    val pmFeedback: Boolean = false,
    val ocFeedback: Boolean = false,
    val dlFeedback: Boolean = false,
    val messageResource: Int? = null,
    val messageArgument: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val showClutterFiles: Boolean = false,
    val loadingClutterFiles: Boolean = true,
    val savingClutterFiles: Boolean = false,
    val clutterFilesError: Boolean = false,
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
) {
    val canStartBackup: Boolean
        get() = !backupBusy && !restoreNeedsPassword && !saving && !rdSaving && !adSaving && !pmSaving && !ocSaving && !dlSaving && !savingPlayer &&
            !savingClutterFiles && !savingClipboardOffers && !savingReleaseWords &&
            !loadingTheme && !savingTheme && !loadingPlayers && !loadingClutterFiles && !loadingClipboardOffers && !loadingReleaseWords

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
                keyField = if (signIn != null) "" else it.keyField,
                showKey = if (signIn != null) false else it.showKey,
                rdTokenField = if (signIn != null) "" else it.rdTokenField,
                rdShowToken = if (signIn != null) false else it.rdShowToken,
                adKeyField = if (signIn != null) "" else it.adKeyField,
                pmKeyField = if (signIn != null) "" else it.pmKeyField,
                ocKeyField = if (signIn != null) "" else it.ocKeyField,
                dlKeyField = if (signIn != null) "" else it.dlKeyField,
                adShowKey = if (signIn != null) false else it.adShowKey,
                pmShowKey = if (signIn != null) false else it.pmShowKey,
                ocShowKey = if (signIn != null) false else it.ocShowKey,
                dlShowKey = if (signIn != null) false else it.dlShowKey,
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
                val torbox = accounts.firstOrNull { it.service == DebridService.TORBOX }
                val realDebrid = accounts.firstOrNull { it.service == DebridService.REAL_DEBRID }
                _state.update {
                    it.copy(
                        hasKey = hasTorboxKey,
                        accountLabel = torbox?.label ?: connection.account?.label.takeIf { torboxConnection },
                        maskedKey = if (hasTorboxKey) SAVED_CREDENTIAL_MASK else "",
                        rdHasKey = DebridService.REAL_DEBRID in servicesWithKeys,
                        rdAccountLabel = realDebrid?.label,
                        adHasKey = DebridService.ALLDEBRID in servicesWithKeys,
                        adAccountLabel = accounts.firstOrNull { it.service == DebridService.ALLDEBRID }?.label,
                        pmHasKey = DebridService.PREMIUMIZE in servicesWithKeys,
                        ocHasKey = DebridService.OFFCLOUD in servicesWithKeys,
                        dlHasKey = DebridService.DEBRID_LINK in servicesWithKeys,
                        pmAccountLabel = accounts.firstOrNull { it.service == DebridService.PREMIUMIZE }?.label,
                        ocAccountLabel = accounts.firstOrNull { it.service == DebridService.OFFCLOUD }?.label,
                        dlAccountLabel = accounts.firstOrNull { it.service == DebridService.DEBRID_LINK }?.label,
                        accounts = accounts,
                        activeService = active,
                        message = it.message.takeUnless { message -> message == accountReadError },
                        messageIsError = it.messageIsError && it.message != accountReadError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(message = accountReadError, messageIsError = true,
                        rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null)
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
        _state.update { it.copy(keyField = text, message = null, rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null) }
    }

    fun toggleShowKey() {
        _state.update { it.copy(showKey = !it.showKey) }
    }

    fun testAndSave() {
        if (backupBlocksEdits()) return
        val key = _state.value.keyField.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(message = "Paste your TorBox API key first", messageIsError = true,
                rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null) }
            return
        }
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, message = null, rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null) }
        viewModelScope.launch {
            try {
                repository.validateAndSaveTorboxKey(key).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.copy(
                                keyField = "",
                                showKey = false,
                                message = "TorBox connected — ${account.label}",
                                messageIsError = false,
                                rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false,
                                messageResource = null,
                                messageArgument = null,
                            )
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { e ->
                        _state.update {
                            it.copy(
                                message = (e as? DebridError)?.message ?: "Key didn't work",
                                messageIsError = true,
                                rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false,
                                messageResource = null,
                                messageArgument = null,
                            )
                        }
                    },
                )
            } catch (e: CancellationException) {
                // The coroutine was cancelled; saving must still reset.
                throw e
            } catch (e: Exception) {
                // Defensive: the repository promises a Result, but a
                // thrown exception must never close the app.
                _state.update {
                    it.copy(message = "Couldn't check that key", messageIsError = true,
                        rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null)
                }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun signOut() {
        if (backupBlocksEdits()) return
        refreshJob?.cancel()
        repository.signOutTorbox()
        _state.update {
            it.copy(hasKey = false, accountLabel = null, maskedKey = "",
                keyField = "", showKey = false, message = "TorBox signed out", messageIsError = false,
                rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null)
        }
        refresh()
        _accountChanged.value = true
    }

    fun setRdTokenField(text: String) {
        _state.update {
            it.copy(rdTokenField = text, message = null, messageResource = null, messageArgument = null)
        }
    }

    fun toggleShowRdToken() {
        _state.update { it.copy(rdShowToken = !it.rdShowToken) }
    }

    fun testAndSaveRealDebrid() {
        if (backupBlocksEdits() || _state.value.rdSaving) return
        val token = _state.value.rdTokenField.trim()
        if (token.isEmpty()) {
            _state.update { it.withRdFeedback(resource = R.string.rd_token_empty, error = true) }
            return
        }
        _state.update { it.withRdFeedback().copy(rdSaving = true) }
        viewModelScope.launch {
            try {
                repository.validateAndSaveRealDebridToken(token).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.withRdFeedback(resource = R.string.rd_connected, argument = account.label)
                                .copy(rdTokenField = "", rdShowToken = false)
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        showRdFailure(error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showRdFailure(e)
            } finally {
                _state.update { it.copy(rdSaving = false) }
            }
        }
    }

    fun signOutRealDebrid() {
        if (backupBlocksEdits() || _state.value.rdSaving) return
        refreshJob?.cancel()
        _state.update { it.withRdFeedback().copy(rdSaving = true) }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutRealDebrid() }
                _state.update {
                    it.withRdFeedback(resource = R.string.rd_signed_out).copy(
                        rdHasKey = false, rdAccountLabel = null, rdTokenField = "", rdShowToken = false,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.withRdFeedback(resource = R.string.rd_sign_out_failed, error = true) }
            } finally {
                _state.update { it.copy(rdSaving = false) }
            }
        }
    }

    fun setAdKeyField(text: String) {
        _state.update {
            it.copy(adKeyField = text, message = null, messageResource = null, messageArgument = null)
        }
    }

    fun toggleShowAdKey() {
        _state.update { it.copy(adShowKey = !it.adShowKey) }
    }

    fun testAndSaveAllDebrid() {
        if (backupBlocksEdits() || _state.value.adSaving) return
        val key = _state.value.adKeyField.trim()
        if (key.isEmpty()) {
            _state.update { it.withAdFeedback(resource = R.string.ad_key_empty, error = true) }
            return
        }
        _state.update { it.withAdFeedback().copy(adSaving = true) }
        viewModelScope.launch {
            try {
                repository.validateAndSaveAllDebridKey(key).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.withAdFeedback(resource = R.string.ad_connected, argument = account.label)
                                .copy(adKeyField = "", adShowKey = false)
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        showAdFailure(error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAdFailure(e)
            } finally {
                _state.update { it.copy(adSaving = false) }
            }
        }
    }

    fun signOutAllDebrid() {
        if (backupBlocksEdits() || _state.value.adSaving) return
        refreshJob?.cancel()
        _state.update { it.withAdFeedback().copy(adSaving = true) }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutAllDebrid() }
                _state.update {
                    it.withAdFeedback(resource = R.string.ad_signed_out).copy(
                        adHasKey = false, adAccountLabel = null, adKeyField = "", adShowKey = false,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.withAdFeedback(resource = R.string.ad_sign_out_failed, error = true) }
            } finally {
                _state.update { it.copy(adSaving = false) }
            }
        }
    }

    fun setPmKeyField(text: String) {
        _state.update {
            it.copy(pmKeyField = text, message = null, messageResource = null, messageArgument = null)
        }
    }

    fun toggleShowPmKey() {
        _state.update { it.copy(pmShowKey = !it.pmShowKey) }
    }

    fun testAndSavePremiumize() {
        if (backupBlocksEdits() || _state.value.pmSaving) return
        val key = _state.value.pmKeyField.trim()
        if (key.isEmpty()) {
            _state.update { it.withPmFeedback(resource = R.string.pm_key_empty, error = true) }
            return
        }
        _state.update { it.withPmFeedback().copy(pmSaving = true) }
        viewModelScope.launch {
            try {
                repository.validateAndSavePremiumizeKey(key).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.withPmFeedback(resource = R.string.pm_connected, argument = account.label)
                                .copy(pmKeyField = "", pmShowKey = false)
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        showPmFailure(error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showPmFailure(e)
            } finally {
                _state.update { it.copy(pmSaving = false) }
            }
        }
    }

    fun signOutPremiumize() {
        if (backupBlocksEdits() || _state.value.pmSaving) return
        refreshJob?.cancel()
        _state.update { it.withPmFeedback().copy(pmSaving = true) }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutPremiumize() }
                _state.update {
                    it.withPmFeedback(resource = R.string.pm_signed_out).copy(
                        pmHasKey = false, pmAccountLabel = null, pmKeyField = "", pmShowKey = false,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.withPmFeedback(resource = R.string.pm_sign_out_failed, error = true) }
            } finally {
                _state.update { it.copy(pmSaving = false) }
            }
        }
    }

    fun setOcKeyField(text: String) {
        _state.update {
            it.copy(ocKeyField = text, message = null, messageResource = null, messageArgument = null)
        }
    }

    fun toggleShowOcKey() {
        _state.update { it.copy(ocShowKey = !it.ocShowKey) }
    }

    fun testAndSaveOffcloud() {
        if (backupBlocksEdits() || _state.value.ocSaving) return
        val key = _state.value.ocKeyField.trim()
        if (key.isEmpty()) {
            _state.update { it.withOcFeedback(resource = R.string.oc_key_empty, error = true) }
            return
        }
        _state.update { it.withOcFeedback().copy(ocSaving = true) }
        viewModelScope.launch {
            try {
                repository.validateAndSaveOffcloudKey(key).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.withOcFeedback(resource = R.string.oc_connected, argument = account.label)
                                .copy(ocKeyField = "", ocShowKey = false)
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        showOcFailure(error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showOcFailure(e)
            } finally {
                _state.update { it.copy(ocSaving = false) }
            }
        }
    }

    fun signOutOffcloud() {
        if (backupBlocksEdits() || _state.value.ocSaving) return
        refreshJob?.cancel()
        _state.update { it.withOcFeedback().copy(ocSaving = true) }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutOffcloud() }
                _state.update {
                    it.withOcFeedback(resource = R.string.oc_signed_out).copy(
                        ocHasKey = false, ocAccountLabel = null, ocKeyField = "", ocShowKey = false,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.withOcFeedback(resource = R.string.oc_sign_out_failed, error = true) }
            } finally {
                _state.update { it.copy(ocSaving = false) }
            }
        }
    }

    fun setDlKeyField(text: String) {
        _state.update {
            it.copy(
                dlKeyField = text, message = null, messageResource = null, messageArgument = null,
                rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false,
            )
        }
    }

    fun toggleShowDlKey() {
        _state.update { it.copy(dlShowKey = !it.dlShowKey) }
    }

    fun testAndSaveDebridLink() {
        if (backupBlocksEdits() || _state.value.dlSaving) return
        val key = _state.value.dlKeyField.trim()
        if (key.isEmpty()) {
            _state.update { it.withDlFeedback(resource = R.string.dl_key_empty, error = true) }
            return
        }
        _state.update { it.withDlFeedback().copy(dlSaving = true) }
        viewModelScope.launch {
            try {
                repository.validateAndSaveDebridLinkKey(key).fold(
                    onSuccess = { account ->
                        _state.update {
                            it.withDlFeedback(resource = R.string.dl_connected, argument = account.label)
                                .copy(dlKeyField = "", dlShowKey = false)
                        }
                        refresh()
                        _accountChanged.value = true
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        showDlFailure(error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showDlFailure(e)
            } finally {
                _state.update { it.copy(dlSaving = false) }
            }
        }
    }

    fun signOutDebridLink() {
        if (backupBlocksEdits() || _state.value.dlSaving) return
        refreshJob?.cancel()
        _state.update { it.withDlFeedback().copy(dlSaving = true) }
        viewModelScope.launch {
            try {
                withContext(accountDispatcher) { repository.signOutDebridLink() }
                _state.update {
                    it.withDlFeedback(resource = R.string.dl_signed_out).copy(
                        dlHasKey = false, dlAccountLabel = null, dlKeyField = "", dlShowKey = false,
                    )
                }
                refresh()
                _accountChanged.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.withDlFeedback(resource = R.string.dl_sign_out_failed, error = true) }
            } finally {
                _state.update { it.copy(dlSaving = false) }
            }
        }
    }

    fun setActiveProvider(service: DebridService) {
        if (backupBlocksEdits()) return
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
                    it.copy(message = accountReadError, messageIsError = true,
                        rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false, messageResource = null, messageArgument = null)
                }
            }
        }
    }

    private fun showRdFailure(error: Throwable) {
        val detail = (error as? DebridError)?.message
        _state.update {
            it.withRdFeedback(
                text = detail,
                resource = R.string.rd_token_failed.takeIf { detail == null },
                error = true,
            )
        }
    }

    private fun SettingsUiState.withRdFeedback(
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ) = copy(message = text, messageIsError = error, rdFeedback = true, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = false,
        messageResource = resource, messageArgument = argument)

    private fun showAdFailure(error: Throwable) {
        val detail = (error as? DebridError)?.message
        _state.update {
            it.withAdFeedback(
                text = detail,
                resource = R.string.ad_key_failed.takeIf { detail == null },
                error = true,
            )
        }
    }

    private fun SettingsUiState.withAdFeedback(
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ) = copy(message = text, messageIsError = error, rdFeedback = false, adFeedback = true, pmFeedback = false, ocFeedback = false, dlFeedback = false,
        messageResource = resource, messageArgument = argument)

    private fun showPmFailure(error: Throwable) {
        val detail = (error as? DebridError)?.message
        _state.update {
            it.withPmFeedback(
                text = detail,
                resource = R.string.pm_key_failed.takeIf { detail == null },
                error = true,
            )
        }
    }

    private fun SettingsUiState.withPmFeedback(
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ) = copy(message = text, messageIsError = error, rdFeedback = false, adFeedback = false, pmFeedback = true, ocFeedback = false, dlFeedback = false,
        messageResource = resource, messageArgument = argument)

    private fun showOcFailure(error: Throwable) {
        val detail = (error as? DebridError)?.message
        _state.update {
            it.withOcFeedback(
                text = detail,
                resource = R.string.oc_key_failed.takeIf { detail == null },
                error = true,
            )
        }
    }

    private fun SettingsUiState.withOcFeedback(
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ) = copy(message = text, messageIsError = error, rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = true, dlFeedback = false,
        messageResource = resource, messageArgument = argument)

    private fun showDlFailure(error: Throwable) {
        val detail = (error as? DebridError)?.message
        _state.update {
            it.withDlFeedback(
                text = detail,
                resource = R.string.dl_key_failed.takeIf { detail == null },
                error = true,
            )
        }
    }

    private fun SettingsUiState.withDlFeedback(
        text: String? = null,
        resource: Int? = null,
        argument: String? = null,
        error: Boolean = false,
    ) = copy(message = text, messageIsError = error, rdFeedback = false, adFeedback = false, pmFeedback = false, ocFeedback = false, dlFeedback = true,
        messageResource = resource, messageArgument = argument)

    private fun backupBlocksEdits(): Boolean =
        _state.value.backupBusy || _state.value.restoreNeedsPassword

    fun consumeAccountChanged() {
        _accountChanged.value = false
    }
}

/** Providers after TorBox, in the build order from the research. */
val COMING_SOON_SERVICES: List<Pair<DebridService, String>> = emptyList()
