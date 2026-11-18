package app.trove.debrid.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.trove.debrid.core.CredentialStore

/** Keystore-backed [CredentialStore]. The MasterKey lives in the
 *  Android Keystore; values are AES-256-GCM encrypted in
 *  `trove_credentials.xml`, which is excluded from backup. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    // Construction must be safe during composition. First access happens in
    // DebridRepository.loadConnection on IO; a failed initialization can retry.
    private val prefs by lazy { EncryptedSharedPreferences.create(
        context,
        "trove_credentials",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ) }

    override fun save(accountId: String, secret: String) {
        prefs.edit { putString(accountId, secret) }
    }

    override fun get(accountId: String): String? = prefs.getString(accountId, null)

    override fun delete(accountId: String) {
        prefs.edit { remove(accountId) }
    }

    override fun has(accountId: String): Boolean = prefs.contains(accountId)
}
