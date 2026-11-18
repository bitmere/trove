package app.trove.debrid.ui.settings

import app.trove.debrid.core.VideoPlayer
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.PlayerDiscovery
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
import app.trove.debrid.data.TroveRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    override fun toString(): String = "SettingsUiState([redacted])"
}

class SettingsViewModel(
    private val repository: TroveRepository,
    private val playerDiscovery: PlayerDiscovery = PlayerDiscovery { emptyList() },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _accountChanged = MutableStateFlow(false)
    /** One-shot: true after a successful Test & Save or a sign-out,
     *  so the Downloads list refreshes only when the account actually
     *  changed — not on every return from Settings. */
    val accountChanged: StateFlow<Boolean> = _accountChanged.asStateFlow()

    private val accountReadError = "Couldn't read the saved account. Try again."
    private var refreshJob: Job? = null

    private var playersJob: Job? = null

    init {
        refresh()
        refreshPlayers()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val connection = repository.loadConnection()
                _state.update {
                    it.copy(
                        hasKey = connection.hasKey,
                        accountLabel = connection.account?.label,
                        maskedKey = if (connection.hasKey) "••••••••••••" else "",
                        message = it.message.takeUnless { message -> message == accountReadError },
                        messageIsError = it.messageIsError && it.message != accountReadError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(message = accountReadError, messageIsError = true)
                }
            }
        }
    }

    fun refreshPlayers() {
        if (_state.value.savingPlayer) return
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
        if (_state.value.loadingPlayers || _state.value.savingPlayer) return
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

    fun setKeyField(text: String) {
        _state.update { it.copy(keyField = text, message = null) }
    }

    fun toggleShowKey() {
        _state.update { it.copy(showKey = !it.showKey) }
    }

    fun testAndSave() {
        val key = _state.value.keyField.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(message = "Paste your TorBox API key first", messageIsError = true) }
            return
        }
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, message = null) }
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
                    it.copy(message = "Couldn't check that key", messageIsError = true)
                }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun signOut() {
        refreshJob?.cancel()
        repository.signOutTorbox()
        _state.update {
            it.copy(hasKey = false, accountLabel = null, maskedKey = "",
                keyField = "", showKey = false, message = "TorBox signed out", messageIsError = false)
        }
        refresh()
        _accountChanged.value = true
    }

    fun consumeAccountChanged() {
        _accountChanged.value = false
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

/** Providers after TorBox, in the build order from the research. */
val COMING_SOON_SERVICES: List<Pair<DebridService, String>> = listOf(
    DebridService.REAL_DEBRID to "Real-Debrid",
    DebridService.ALLDEBRID to "AllDebrid",
    DebridService.PREMIUMIZE to "Premiumize",
    DebridService.OFFCLOUD to "Offcloud",
    DebridService.DEBRID_LINK to "Debrid-Link",
)
