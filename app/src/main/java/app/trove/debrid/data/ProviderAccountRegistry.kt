package app.trove.debrid.data

import android.content.SharedPreferences
import app.trove.debrid.core.AccountInfo
import app.trove.debrid.core.AllDebridProvider
import app.trove.debrid.core.CredentialStore
import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridLinkProvider
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.OffcloudProvider
import app.trove.debrid.core.PremiumizeProvider
import app.trove.debrid.core.RealDebridProvider
import app.trove.debrid.core.TmdbResolver
import app.trove.debrid.core.TorBoxProvider
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException

/** Account + provider-registry half of [DebridRepository] (unification
 *  item 5). Owns everything that identifies the user to a provider:
 *  account records, the active-service pointer, the encrypted
 *  credentials (including the TMDB key, which is a credential-store
 *  secret under its own id, not a debrid service), the per-service
 *  provider cache, and backup sign-in restore.
 *
 *  Storage is unchanged: account records and the active service live
 *  in the same SharedPreferences file as before, under the same keys
 *  — this split is in-memory ownership, not a migration, so existing
 *  installs keep their accounts. The preferences half
 *  ([PreferencesStore]) shares that file but owns a disjoint key set
 *  and never touches the credential store.
 *
 *  Threading: every account/provider read or write that must be
 *  atomic with the others is `@Synchronized` on this instance, so
 *  [loadConnection] takes one snapshot under one lock and a
 *  concurrent [signOut] cannot tear it (account from one service,
 *  provider from another). The provider cache map is the only place
 *  provider instances live: switching services or changing a
 *  preference never rebuilds one, so each provider's rate budgets
 *  and hoster cache survive both. Suspend functions here do no
 *  dispatching themselves — the facade dispatches them to IO. */
class ProviderAccountRegistry(
    private val prefs: SharedPreferences,
    private val credentialStore: CredentialStore,
    private val transport: HttpTransport,
) {

    companion object {
        const val TORBOX_ACCOUNT_ID = "torbox-1"
        const val REAL_DEBRID_ACCOUNT_ID = "realdebrid-1"
        const val ALLDEBRID_ACCOUNT_ID = "alldebrid-1"
        const val PREMIUMIZE_ACCOUNT_ID = "premiumize-1"
        const val OFFCLOUD_ACCOUNT_ID = "offcloud-1"
        const val DEBRID_LINK_ACCOUNT_ID = "debridlink-1"
        /** TMDB is not a debrid service: its per-user API Read Access
         *  Token lives in the same encrypted store under its own id,
         *  and is never backed up or shipped. */
        const val TMDB_ACCOUNT_ID = "tmdb-1"
        private const val KEY_ACTIVE_SERVICE = "active_service"
        private const val KEY_ACCOUNTS = "accounts"
    }

    /** The TMDB resolver, keyed by the stored TMDB key. The
     *  facade's metadata wiring consumes this; the key itself never
     *  leaves the registry. */
    internal val tmdbResolver = TmdbResolver(transport) { tmdbKey() }

    fun accounts(): List<DebridAccount> = readStored()

    fun accountFor(service: DebridService): DebridAccount? =
        accounts().firstOrNull { it.service == service }

    fun hasCredential(service: DebridService): Boolean = credentialStore.has(accountId(service))

    /** The stored TMDB key, or null when absent/blank. Read on every
     *  resolve, so saving or clearing it in Settings takes effect
     *  without rebuilding anything. */
    fun tmdbKey(): String? = credentialStore.get(TMDB_ACCOUNT_ID)?.takeIf { it.isNotBlank() }

    fun hasTmdbKey(): Boolean = credentialStore.has(TMDB_ACCOUNT_ID)

    fun maskedTmdbKey(): String = credentialStore.masked(TMDB_ACCOUNT_ID)

    fun saveTmdbKey(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "TMDB key is empty" }
        credentialStore.save(TMDB_ACCOUNT_ID, trimmed)
    }

    fun deleteTmdbKey() {
        credentialStore.delete(TMDB_ACCOUNT_ID)
    }

    /** Validates against /3/configuration with the given key, before
     *  it is stored. A failure means the key is wrong or TMDB is
     *  unreachable — either way nothing is saved. */
    suspend fun validateTmdbKey(key: String): Result<Unit> {
        return try {
            val trimmed = key.trim()
            require(trimmed.isNotEmpty()) { "TMDB key is empty" }
            if (tmdbResolver.validateKey(trimmed)) Result.success(Unit)
            else Result.failure(IllegalStateException("TMDB key was not accepted"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** One atomic snapshot: account, key presence, and provider are
     *  read under the registry lock, so a concurrent sign-out cannot
     *  produce an account from one service with a provider from
     *  another. */
    fun loadConnection(): SavedConnection = synchronized(this) {
        val activeProvider = provider()
        SavedConnection(
            accounts().firstOrNull { it.service == activeProvider?.service },
            activeProvider != null,
            activeProvider,
        )
    }

    fun loadBackupSignIn(): BackupSignIn? = synchronized(this) {
        if (!credentialStore.has(TORBOX_ACCOUNT_ID)) return@synchronized null
        val account = accountFor(DebridService.TORBOX) ?: return@synchronized null
        val key = credentialStore.get(TORBOX_ACCOUNT_ID) ?: return@synchronized null
        BackupSignIn(key, account.label, account.addedAtEpochMs, account.lastValidatedEpochMs)
    }

    fun restoreBackupSignIn(signIn: BackupSignIn) {
        val account = DebridAccount(
            id = TORBOX_ACCOUNT_ID,
            service = DebridService.TORBOX,
            label = signIn.label,
            addedAtEpochMs = signIn.addedAt,
            lastValidatedEpochMs = signIn.lastValidated,
        )
        storeTorboxAccount(signIn.key, account)
    }

    @Synchronized
    private fun storeTorboxAccount(key: String, account: DebridAccount) {
        val accounts = readStored().filter { it.id != TORBOX_ACCOUNT_ID } + account
        credentialStore.save(TORBOX_ACCOUNT_ID, key)
        writeStored(accounts)
    }

    private fun accountId(service: DebridService): String = when (service) {
        DebridService.TORBOX -> TORBOX_ACCOUNT_ID
        DebridService.REAL_DEBRID -> REAL_DEBRID_ACCOUNT_ID
        DebridService.ALLDEBRID -> ALLDEBRID_ACCOUNT_ID
        DebridService.PREMIUMIZE -> PREMIUMIZE_ACCOUNT_ID
        DebridService.OFFCLOUD -> OFFCLOUD_ACCOUNT_ID
        DebridService.DEBRID_LINK -> DEBRID_LINK_ACCOUNT_ID
    }

    private fun credential(service: DebridService): String? = credentialStore.get(accountId(service))

    private fun preferredService(): DebridService {
        val storedService = prefs.getString(KEY_ACTIVE_SERVICE, null)
        return DebridService.entries.firstOrNull { it.name == storedService } ?: DebridService.TORBOX
    }

    private fun activeCredential(): Pair<DebridService, String>? {
        val preferred = preferredService()
        credential(preferred)?.let { return preferred to it }
        return debridServiceOrder.firstNotNullOfOrNull { service ->
            if (service == preferred) null else credential(service)?.let { service to it }
        }
    }

    @Synchronized
    fun activeService(): DebridService? = activeCredential()?.first

    @Synchronized
    fun setActiveService(service: DebridService) {
        if (credential(service) == null) return
        prefs.edit { putString(KEY_ACTIVE_SERVICE, service.name) }
    }

    private class CachedProvider(val credential: String, val provider: DebridProvider)
    private val providers = mutableMapOf<DebridService, CachedProvider>()

    /** Retain each service's rate budgets and hoster cache across switches. */
    @Synchronized
    fun provider(): DebridProvider? {
        val preferred = preferredService()
        return providerFor(preferred) ?: debridServiceOrder.firstNotNullOfOrNull { service ->
            if (service == preferred) null else providerFor(service)
        }
    }

    @Synchronized
    fun providerFor(service: DebridService): DebridProvider? {
        val key = credential(service) ?: return null
        providers[service]?.takeIf { it.credential == key }?.let { return it.provider }
        val provider = when (service) {
            DebridService.TORBOX -> TorBoxProvider(
                key, transport,
                diagnosticLog = { android.util.Log.d("TorBox", it) },
            )
            DebridService.REAL_DEBRID -> RealDebridProvider(key, transport)
            DebridService.ALLDEBRID -> AllDebridProvider(key, transport)
            DebridService.PREMIUMIZE -> PremiumizeProvider(key, transport)
            DebridService.OFFCLOUD -> OffcloudProvider(key, transport)
            DebridService.DEBRID_LINK -> DebridLinkProvider(key, transport)
            else -> return null
        }
        providers[service] = CachedProvider(key, provider)
        return provider
    }

    /** A rejected credential leaves the current account untouched. */
    suspend fun validateAndSave(
        service: DebridService,
        credential: String,
        factory: (String, HttpTransport) -> DebridProvider,
        label: (AccountInfo?) -> String,
    ): Result<DebridAccount> {
        return try {
            val trimmed = credential.trim()
            require(trimmed.isNotEmpty()) {
                if (service == DebridService.REAL_DEBRID) "Token is empty" else "Key is empty"
            }
            val info = factory(trimmed, transport).validateAccount().getOrThrow()
            val now = System.currentTimeMillis()
            val account = DebridAccount(
                id = accountId(service),
                service = service,
                label = label(info.takeUnless { service == DebridService.OFFCLOUD }),
                addedAtEpochMs = now,
                lastValidatedEpochMs = if (service == DebridService.TORBOX) System.currentTimeMillis() else now,
            )
            if (service == DebridService.TORBOX) storeTorboxAccount(trimmed, account)
            else storeActiveAccount(trimmed, account)
            Result.success(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    private fun storeActiveAccount(credential: String, account: DebridAccount) {
        val accounts = readStored().filter { it.id != account.id } + account
        credentialStore.save(account.id, credential)
        prefs.edit {
            putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts))
            putString(KEY_ACTIVE_SERVICE, account.service.name)
        }
    }

    @Synchronized
    fun signOut(service: DebridService) {
        val accountId = accountId(service)
        val wasPreferred = preferredService() == service
        providers.remove(service)
        credentialStore.delete(accountId)
        val remainingAccounts = readStored().filter { it.id != accountId }
        val fallback = if (wasPreferred) activeService() else null
        prefs.edit {
            putString(KEY_ACCOUNTS, StoredAccounts.encode(remainingAccounts))
            if (wasPreferred) {
                if (fallback == null) remove(KEY_ACTIVE_SERVICE)
                else putString(KEY_ACTIVE_SERVICE, fallback.name)
            }
        }
    }

    private fun readStored(): List<DebridAccount> =
        StoredAccounts.decode(prefs.getString(KEY_ACCOUNTS, null))

    private fun writeStored(accounts: List<DebridAccount>) {
        prefs.edit { putString(KEY_ACCOUNTS, StoredAccounts.encode(accounts)) }
    }
}
