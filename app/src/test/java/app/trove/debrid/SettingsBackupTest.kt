package app.trove.debrid

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.data.BackupSettings
import app.trove.debrid.data.BackupSignIn
import app.trove.debrid.data.BackupUnlockFailed
import app.trove.debrid.data.InvalidBackup
import app.trove.debrid.data.SettingsBackup
import app.trove.debrid.data.ThemeAccent
import app.trove.debrid.data.ThemeMode
import app.trove.debrid.data.ThemePreference
import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val settings = BackupSettings(
        PlayerComponent("example.player", "example.player.Main"),
        showClutterFiles = true,
        excludeWords = emptyList(),
        preferWords = listOf("REMUX", "x265"),
        clipboardOffersEnabled = false,
        updateChannel = UpdateChannel.BETA,
    )
    private val signIn = BackupSignIn("example-secret-api-key", "TorBox (Essential)", 123, null)
    private val password = "Café and 茶"

    @Test fun settingsOnlyRoundTrip() {
        val original = settings.copy(player = null)
        val restored = SettingsBackup.decode(SettingsBackup.encode(original))
        assertEquals(original, restored.settings)
        assertFalse(restored.hasSignIn)
        assertNull(restored.unlock(""))
    }

    @Test fun themeRoundTrip() {
        val theme = ThemePreference(ThemeMode.OLED, ThemeAccent.ROSE)
        val original = settings.copy(theme = theme)
        assertEquals(original, SettingsBackup.decode(SettingsBackup.encode(original)).settings)
    }

    @Test fun backupWithoutThemeUsesDefaults() {
        val original = settings.copy(theme = ThemePreference(ThemeMode.DARK, ThemeAccent.OCEAN))
        val json = JsonParser.parseString(SettingsBackup.encode(original)).asJsonObject
        val preferences = json.getAsJsonObject("settings")
        preferences.remove("theme_mode")
        preferences.remove("theme_accent")

        assertEquals(ThemePreference(), SettingsBackup.decode(json.toString()).settings.theme)
    }

    @Test fun encryptedSignInRoundTrip() {
        val json = SettingsBackup.encode(settings, signIn, password)
        assertFalse(json.contains(signIn.key))
        assertFalse(json.contains(signIn.label))
        val envelope = SettingsBackup.decode(json)
        assertEquals(settings, envelope.settings)
        assertTrue(envelope.hasSignIn)
        val restored = requireNotNull(envelope.unlock(password))
        assertEquals(signIn.key, restored.key)
        assertEquals(signIn.label, restored.label)
        assertEquals(signIn.addedAt, restored.addedAt)
        assertEquals(signIn.lastValidated, restored.lastValidated)
    }

    @Test fun wrongPasswordDoesNotProduceRestorableCredentials() {
        val envelope = SettingsBackup.decode(SettingsBackup.encode(settings, signIn, password))
        assertThrows(BackupUnlockFailed::class.java) { envelope.unlock("wrong password") }
        assertEquals(signIn.key, envelope.unlock(password)?.key)
    }

    @Test fun tamperedCiphertextFailsAuthentication() {
        val json = JsonParser.parseString(SettingsBackup.encode(settings, signIn, password)).asJsonObject
        val encrypted = json.getAsJsonObject("encrypted_sign_in")
        val bytes = Base64.getDecoder().decode(encrypted.get("ciphertext").asString)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        encrypted.addProperty("ciphertext", Base64.getEncoder().encodeToString(bytes))
        val envelope = SettingsBackup.decode(json.toString())
        assertThrows(BackupUnlockFailed::class.java) { envelope.unlock(password) }
    }

    @Test fun malformedJsonAndWrongFieldTypesFail() {
        val json = JsonParser.parseString(SettingsBackup.encode(settings)).asJsonObject
        assertThrows(InvalidBackup::class.java) { SettingsBackup.decode("{version:1}") }
        assertThrows(InvalidBackup::class.java) { SettingsBackup.decode("$json trailing") }
        json.getAsJsonObject("settings").addProperty("clipboard_offers", "false")
        assertThrows(InvalidBackup::class.java) { SettingsBackup.decode(json.toString()) }
    }

    @Test fun ignoresUnknownFields() {
        val json = JsonParser.parseString(SettingsBackup.encode(settings)).asJsonObject
        json.addProperty("future_envelope_field", 42)
        json.getAsJsonObject("settings").addProperty("future_theme", "oled")
        assertEquals(settings, SettingsBackup.decode(json.toString()).settings)
    }

    @Test fun rejectsEmptyPasswordForSignIn() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.encode(settings, signIn, "") }
        val envelope = SettingsBackup.decode(SettingsBackup.encode(settings, signIn, password))
        assertThrows(BackupUnlockFailed::class.java) { envelope.unlock("") }
    }
}
