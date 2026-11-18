package app.trove.debrid

import android.content.Context
import app.trove.debrid.data.DebridRepository
import app.trove.debrid.data.KeystoreCredentialStore

/** Input read by the dedicated share host or the add sheet file picker. */
data class PendingShare(
    val error: String? = null,
    val text: String? = null,
    val bytes: ByteArray? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
)

/** Manual wiring — no DI framework, per the project rules. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val credentialStore = KeystoreCredentialStore(appContext)
    val repository = DebridRepository(appContext, credentialStore)
    val playerDiscovery: PlayerDiscovery = AndroidPlayerDiscovery(appContext.packageManager)
    val updateBackend by lazy { app.trove.debrid.update.AndroidUpdateBackend(appContext) }

}
