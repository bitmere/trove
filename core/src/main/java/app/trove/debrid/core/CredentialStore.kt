package app.trove.debrid.core

/** Storage for per-account secrets. The interface is the only thing
 *  `:core` knows; `:app` provides the Keystore-backed implementation.
 *  A secret must never be recoverable from logs or from anything this
 *  interface returns besides [get]. */
interface CredentialStore {
    fun save(accountId: String, secret: String)
    fun get(accountId: String): String?
    fun delete(accountId: String)
    fun has(accountId: String): Boolean

    /** Masked display, e.g. "••••••••ab12" — the only form a key may
     *  ever take in UI or logs. */
    fun masked(accountId: String): String {
        val secret = get(accountId) ?: return ""
        return if (secret.length <= 4) "••••" else "•".repeat(8) + secret.takeLast(4)
    }
}

/** Test/stand-in store. Not for production use. */
class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun save(accountId: String, secret: String) { map[accountId] = secret }
    override fun get(accountId: String): String? = map[accountId]
    override fun delete(accountId: String) { map.remove(accountId) }
    override fun has(accountId: String): Boolean = map.containsKey(accountId)
}
