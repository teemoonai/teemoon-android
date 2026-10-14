// ports: teemoon/Settings/AppSettings.swift @ v1.0.4, teemoon/Settings/UserDefaultsKeys.swift @ v1.0.1,
//        teemoon/Settings/Appearance.swift @ v1.0.1 (the four appearance enums only)
//
// User preferences over a key-value seam (SharedPreferences in the app, a map
// in tests). The Brave key itself lives in the Keystore (AppState.braveKey);
// this owns the grounding TOGGLE and the developer-mode gate.
package ai.teemoon.app

import kotlinx.coroutines.flow.MutableStateFlow

/** The key-value store behind the settings — iOS UserDefaults. */
interface Prefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
}

class InMemoryPrefs : Prefs {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getBoolean(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getInt(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
}

object UserDefaultsKey {
    const val SYSTEM_PROMPT = "systemPrompt"; const val APP_TINT_COLOR = "appTintColor"; const val APP_FONT_DESIGN = "appFontDesign"
    const val APP_FONT_SIZE = "appFontSize"; const val APP_FONT_WIDTH = "appFontWidth"; const val SHOULD_PLAY_HAPTICS = "shouldPlayHaptics"
    const val NUMBER_OF_VISITS = "numberOfVisits"; const val NUMBER_OF_VISITS_OF_LAST_REQUEST = "numberOfVisitsOfLastRequest"
    const val BRAVE_GROUNDING_ENABLED = "braveGroundingEnabled"; const val DEVELOPER_MODE_ENABLED = "developerModeEnabled"
    const val SYSTEM_PROMPT_VERSION = "systemPromptVersion"
}

enum class AppTintColor { MONOCHROME, BLUE, BROWN, GRAY, GREEN, INDIGO, MINT, ORANGE, PINK, PURPLE, RED, TEAL, YELLOW }
enum class AppFontDesign { STANDARD, MONOSPACED, ROUNDED, SERIF }
enum class AppFontWidth { COMPRESSED, CONDENSED, EXPANDED, STANDARD }
enum class AppFontSize { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }

class AppSettings(private val prefs: Prefs, private val log: (String) -> Unit = {}) {
    /** Bumped on every appearance write so the theme recomposes (iOS: @AppStorage drives the view tree). */
    val appearanceChanges = MutableStateFlow(0)
    companion object {
        private const val SYSTEM_PROMPT_VERSION = 3
        const val DEFAULT_SYSTEM_PROMPT = "The current date and time is {{datetime}}.\n\nYou are a knowledgeable, direct AI assistant. Your primary commitment is to accuracy and truth. Be maximally truthful and do not follow popular narratives uncritically — always examine claims on their merits. Never invent or improvise information; if you don't know something, say so clearly. For controversial topics, present perspectives from multiple viewpoints without taking sides. When there is scientific consensus, state it. Prefer concise, substantive responses. Do not add unsolicited disclaimers or moralizing. When presenting structured data, prefer prose or bullet lists over markdown tables unless the user explicitly asks for a table."
        /** Prompts teemoon itself shipped before v3 — a user who kept one gets the new default; a user who wrote their own keeps it. */
        private val knownDefaults = setOf(
            "you are a helpful assistant",
            "The current date and time is {{datetime}}.\n\nYou are a knowledgeable, direct AI assistant. Your primary commitment is to accuracy and truth. Be maximally truthful and do not follow popular narratives uncritically — always examine claims on their merits. Never invent or improvise information; if you don't know something, say so clearly. For controversial topics, present perspectives from multiple viewpoints without taking sides. When there is scientific consensus, state it. Prefer concise, substantive responses. Do not add unsolicited disclaimers or moralizing.")
    }

    init { migrateSystemPromptIfNeeded() }

    var systemPrompt: String
        get() = prefs.getString(UserDefaultsKey.SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.putString(UserDefaultsKey.SYSTEM_PROMPT, value)
    var appTintColor: AppTintColor
        get() = prefs.getString(UserDefaultsKey.APP_TINT_COLOR)?.let { v -> AppTintColor.entries.firstOrNull { it.name.equals(v, true) } } ?: AppTintColor.ORANGE
        set(value) { prefs.putString(UserDefaultsKey.APP_TINT_COLOR, value.name.lowercase()); appearanceChanges.value++ }
    var appFontDesign: AppFontDesign
        get() = prefs.getString(UserDefaultsKey.APP_FONT_DESIGN)?.let { v -> AppFontDesign.entries.firstOrNull { it.name.equals(v, true) } } ?: AppFontDesign.STANDARD
        set(value) { prefs.putString(UserDefaultsKey.APP_FONT_DESIGN, value.name.lowercase()); appearanceChanges.value++ }
    var appFontSize: AppFontSize
        get() = prefs.getString(UserDefaultsKey.APP_FONT_SIZE)?.let { v -> AppFontSize.entries.firstOrNull { it.name.equals(v, true) } } ?: AppFontSize.MEDIUM
        set(value) { prefs.putString(UserDefaultsKey.APP_FONT_SIZE, value.name.lowercase()); appearanceChanges.value++ }
    var appFontWidth: AppFontWidth
        get() = prefs.getString(UserDefaultsKey.APP_FONT_WIDTH)?.let { v -> AppFontWidth.entries.firstOrNull { it.name.equals(v, true) } } ?: AppFontWidth.STANDARD
        set(value) { prefs.putString(UserDefaultsKey.APP_FONT_WIDTH, value.name.lowercase()); appearanceChanges.value++ }
    var shouldPlayHaptics: Boolean
        get() = prefs.getBoolean(UserDefaultsKey.SHOULD_PLAY_HAPTICS) ?: true
        set(value) = prefs.putBoolean(UserDefaultsKey.SHOULD_PLAY_HAPTICS, value)
    var numberOfVisits: Int
        get() = prefs.getInt(UserDefaultsKey.NUMBER_OF_VISITS) ?: 0
        set(value) = prefs.putInt(UserDefaultsKey.NUMBER_OF_VISITS, value)
    var numberOfVisitsOfLastRequest: Int
        get() = prefs.getInt(UserDefaultsKey.NUMBER_OF_VISITS_OF_LAST_REQUEST) ?: 0
        set(value) = prefs.putInt(UserDefaultsKey.NUMBER_OF_VISITS_OF_LAST_REQUEST, value)
    var braveGroundingEnabled: Boolean
        get() = prefs.getBoolean(UserDefaultsKey.BRAVE_GROUNDING_ENABLED) ?: false
        set(value) = prefs.putBoolean(UserDefaultsKey.BRAVE_GROUNDING_ENABLED, value)
    /** Debug and developer surfaces sit behind this, as on iOS. */
    var developerModeEnabled: Boolean
        get() = prefs.getBoolean(UserDefaultsKey.DEVELOPER_MODE_ENABLED) ?: false
        set(value) = prefs.putBoolean(UserDefaultsKey.DEVELOPER_MODE_ENABLED, value)

    fun incrementNumberOfVisits() { numberOfVisits += 1; log("App visits: $numberOfVisits") }

    /** The key a turn may ground with: only when the toggle is on AND a key is stored. */
    fun groundingAPIKey(storedKey: String?): String? {
        if (!braveGroundingEnabled) return null
        return storedKey?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun migrateSystemPromptIfNeeded() {
        val storedVersion = prefs.getInt(UserDefaultsKey.SYSTEM_PROMPT_VERSION) ?: 0
        if (storedVersion >= SYSTEM_PROMPT_VERSION) return
        val stored = prefs.getString(UserDefaultsKey.SYSTEM_PROMPT) ?: ""
        if (storedVersion <= 2 || stored in knownDefaults || stored.isEmpty()) prefs.putString(UserDefaultsKey.SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
        prefs.putInt(UserDefaultsKey.SYSTEM_PROMPT_VERSION, SYSTEM_PROMPT_VERSION)
    }
}
