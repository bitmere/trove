package app.trove.debrid.data

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.update.UpdateChannel
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class BackupSettings(
    val player: PlayerComponent?,
    val showClutterFiles: Boolean,
    val excludeWords: List<String>,
    val preferWords: List<String>,
    val clipboardOffersEnabled: Boolean,
    val updateChannel: UpdateChannel?,
    val theme: ThemePreference = ThemePreference(),
)

class BackupSignIn(
    val key: String,
    val label: String,
    val addedAt: Long,
    val lastValidated: Long?,
) {
    init {
        require(key.isNotBlank() && label.isNotBlank())
        require(addedAt >= 0 && (lastValidated == null || lastValidated >= 0))
    }
}

internal class InvalidBackup : Exception()
internal class BackupUnlockFailed : Exception()

/** Version 1 uses fixed cryptographic parameters and explicit JSON keys. */
internal object SettingsBackup {
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    internal const val MAX_BYTES = 1024 * 1024
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val random = SecureRandom()

    class Envelope(val settings: BackupSettings, private val encrypted: EncryptedSignIn?) {
        val hasSignIn: Boolean get() = encrypted != null

        fun unlock(password: String): BackupSignIn? {
            val blob = encrypted ?: return null
            if (password.isEmpty()) throw BackupUnlockFailed()
            val cleartext = try {
                cipher(Cipher.DECRYPT_MODE, password, blob.salt, blob.iv).doFinal(blob.ciphertext)
            } catch (_: GeneralSecurityException) {
                throw BackupUnlockFailed()
            }
            return try {
                parse {
                    val payload = readObject(cleartext.decodeToString(throwOnInvalidSequence = true))
                    BackupSignIn(
                        key = payload.string("api_key"),
                        label = payload.string("label"),
                        addedAt = payload.long("added_at"),
                        lastValidated = payload.get("last_validated")
                            ?.takeUnless { it.isJsonNull }?.let { payload.long("last_validated") },
                    )
                }
            } finally {
                cleartext.fill(0)
            }
        }
    }

    class EncryptedSignIn(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(settings: BackupSettings, signIn: BackupSignIn? = null, password: String = ""): String {
        val preferences = JsonObject().apply {
            add("default_player", settings.player?.let { player ->
                JsonObject().apply {
                    addProperty("package", player.packageName)
                    addProperty("activity", player.activityName)
                }
            } ?: JsonNull.INSTANCE)
            addProperty("show_clutter_files", settings.showClutterFiles)
            add("exclude_words", JsonArray().apply { settings.excludeWords.forEach { add(it) } })
            add("prefer_words", JsonArray().apply { settings.preferWords.forEach { add(it) } })
            addProperty("clipboard_offers", settings.clipboardOffersEnabled)
            addProperty("theme_mode", settings.theme.mode.name)
            addProperty("theme_accent", settings.theme.accent.name)
            settings.updateChannel?.let { addProperty("update_channel", it.name) }
        }
        return JsonObject().apply {
            addProperty("version", VERSION)
            add("settings", preferences)
            if (signIn != null) add("encrypted_sign_in", encrypt(signIn, password))
        }.toString()
    }

    fun decode(json: String): Envelope = parse {
        val root = readObject(json)
        require(root.long("version") == VERSION.toLong())
        val preferences = root.get("settings").asJsonObject
        val player = preferences.get("default_player").takeUnless { it.isJsonNull }?.asJsonObject
        val settings = BackupSettings(
            player = player?.let { PlayerComponent(it.string("package"), it.string("activity")) },
            showClutterFiles = preferences.boolean("show_clutter_files"),
            excludeWords = preferences.words("exclude_words"),
            preferWords = preferences.words("prefer_words"),
            clipboardOffersEnabled = preferences.boolean("clipboard_offers"),
            theme = ThemePreference(
                ThemeMode.fromStored(preferences.get("theme_mode")?.takeUnless { it.isJsonNull }?.asString),
                ThemeAccent.fromStored(preferences.get("theme_accent")?.takeUnless { it.isJsonNull }?.asString),
            ),
            updateChannel = preferences.get("update_channel")?.let {
                val channel = preferences.string("update_channel")
                UpdateChannel.entries.firstOrNull { it.name == channel }
            },
        )
        val encrypted = root.get("encrypted_sign_in")?.asJsonObject?.let {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(it.string("salt"))
            val iv = decoder.decode(it.string("iv"))
            val ciphertext = decoder.decode(it.string("ciphertext"))
            require(salt.size == SALT_BYTES && iv.size == IV_BYTES && ciphertext.size > TAG_BITS / 8)
            EncryptedSignIn(salt, iv, ciphertext)
        }
        Envelope(settings, encrypted)
    }

    private fun encrypt(signIn: BackupSignIn, password: String): JsonObject {
        require(password.isNotEmpty())
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cleartext = JsonObject().apply {
            addProperty("api_key", signIn.key)
            addProperty("label", signIn.label)
            addProperty("added_at", signIn.addedAt)
            addProperty("last_validated", signIn.lastValidated)
        }.toString().encodeToByteArray()
        val ciphertext = try {
            cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(cleartext)
        } finally {
            cleartext.fill(0)
        }
        val encoder = Base64.getEncoder()
        return JsonObject().apply {
            addProperty("salt", encoder.encodeToString(salt))
            addProperty("iv", encoder.encodeToString(iv))
            addProperty("ciphertext", encoder.encodeToString(ciphertext))
        }
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        chars.fill('\u0000')
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
        } finally {
            key.fill(0)
        }
    }

    private fun readObject(json: String): JsonObject = requireNotNull(gson.fromJson(json, JsonObject::class.java))

    private fun JsonObject.string(name: String): String =
        getAsJsonPrimitive(name).also { require(it.isString) }.asString.also { require(it.isNotBlank()) }

    private fun JsonObject.long(name: String): Long =
        getAsJsonPrimitive(name).also { require(it.isNumber) }.asString.toLong()

    private fun JsonObject.boolean(name: String): Boolean =
        getAsJsonPrimitive(name).also { require(it.isBoolean) }.asBoolean

    private fun JsonObject.words(name: String): List<String> =
        getAsJsonArray(name).map { it.asJsonPrimitive.also { word -> require(word.isString) }.asString }

    private inline fun <T> parse(read: () -> T): T = try {
        read()
    } catch (_: Exception) {
        throw InvalidBackup()
    }
}
