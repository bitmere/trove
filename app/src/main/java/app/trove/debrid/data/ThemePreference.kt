package app.trove.debrid.data

enum class ThemeMode {
    SYSTEM, LIGHT, DARK, OLED;

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class ThemeAccent {
    JADE, OCEAN, EMBER, ROSE;

    companion object {
        fun fromStored(value: String?): ThemeAccent = entries.firstOrNull { it.name == value } ?: JADE
    }
}

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: ThemeAccent = ThemeAccent.JADE,
)
