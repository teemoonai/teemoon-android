// ports: teemoon/Views/Settings/SettingsView.swift @ v1.0.1, ChatsSettingsView.swift @ v1.0.1, AppearanceSettingsView.swift @ v1.0.1,
//        SearchSettingsView.swift @ v1.0.1 (SearchSetupSections, BuiltInGroundingSection, KeyField, KeyCheckCopy.icon/tint),
//        CreditsView.swift @ v1.0.1 (the list names Android's dependencies) — REBUILD in Compose
package ai.teemoon.app

import androidx.compose.ui.text.font.FontWeight

import ai.teemoon.inference.BraveWebSearchTool
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

// MARK: - Pure

/** SettingsView.placesSummary: the managed (non-phone) setups. */
fun placesSummary(managedCount: Int): String = when (managedCount) { 0 -> "none"; 1 -> "1 setup"; else -> "$managedCount setups" }

/** KeyCheckCopy.icon / tint, as glyph and semantic tone. */
enum class KeyCheckTone { GOOD, BAD, NEUTRAL }
fun keyCheckGlyph(check: BraveWebSearchTool.KeyCheck): String = when (check) {
    BraveWebSearchTool.KeyCheck.VALID -> "✓"; BraveWebSearchTool.KeyCheck.REJECTED -> "✕"
    BraveWebSearchTool.KeyCheck.OUT_OF_CREDIT -> "💳"; BraveWebSearchTool.KeyCheck.UNREACHABLE, BraveWebSearchTool.KeyCheck.BRAVE_UNAVAILABLE -> "⚠"
}
fun keyCheckTone(check: BraveWebSearchTool.KeyCheck): KeyCheckTone = when (check) {
    BraveWebSearchTool.KeyCheck.VALID -> KeyCheckTone.GOOD; BraveWebSearchTool.KeyCheck.REJECTED -> KeyCheckTone.BAD; else -> KeyCheckTone.NEUTRAL
}

/** What teemoon-android redistributes. Their licences travel with the app. */
data class Dependency(val name: String, val license: String, val role: String, val url: String)
val CREDITS: List<Dependency> = listOf(
    Dependency("OkHttp", "Apache-2.0", "http and tls", "https://github.com/square/okhttp"),
    Dependency("Jetpack Compose · Room", "Apache-2.0", "the ui and the chat store", "https://developer.android.com/jetpack"),
    Dependency("kotlinx.serialization · coroutines", "Apache-2.0", "json and concurrency", "https://github.com/Kotlin/kotlinx.serialization"),
    Dependency("Bouncy Castle", "MIT", "the crypto behind e2ee and attestation", "https://www.bouncycastle.org"),
    Dependency("secp256k1-kmp", "Apache-2.0", "signature checks on attested replies", "https://github.com/ACINQ/secp256k1-kmp"),
    Dependency("dcap-qvl-android", "MIT", "intel tdx quote verification", "https://github.com/Phala-Network/dcap-qvl"),
    Dependency("Coil", "Apache-2.0", "image loading", "https://github.com/coil-kt/coil"),
)

// MARK: - Screens

// The settings screens are insetGrouped lists (iOS Form): a back pill, section footnotes, raised groups.
@Composable private fun Header(title: String, onBack: () -> Unit) = NavBar(title, leading = { BackButton(onBack) })
@Composable private fun Section(title: String) = SectionLabel(title)
@Composable private fun Footer(text: String) = GroupFooter(text)
/** One nav row as its own group; rows that belong together use [Group]. */
@Composable private fun NavRow(label: String, trailing: String? = null, onClick: () -> Unit) = InsetGroup { NavItem(label, trailing, divider = false, onClick = onClick) }
@Composable private fun NavItem(label: String, trailing: String? = null, divider: Boolean = true, glyph: ImageVector? = null, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) =
    InsetRow(label, onClick = onClick, chevron = true, divider = divider, leading = leading ?: glyph?.let { { Icon(it, null, tint = TeemoonColors.tint, modifier = Modifier.size(20.dp)) } },
        trailing = { trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel) } })
@Composable private fun Group(content: @Composable () -> Unit) = InsetGroup(content = content)
@Composable private fun ToggleRow(label: String, on: Boolean, onSet: (Boolean) -> Unit, enabled: Boolean = true) = InsetGroup { ToggleInsetRow(label, on, onSet, enabled = enabled, divider = false) }
@Composable private fun Page(header: (@Composable () -> Unit)? = null, topInset: Dp = 35.dp, content: @Composable ColumnScope.() -> Unit) = GroupedPage(header = header, topInset = topInset, content = content)
@Composable private fun Gap() = Spacer(Modifier.height(8.dp))

/** The settings root's words that a decision changed. */
object SettingsCopy {
    /** The maintainer 2026-11-13 (card settings-chip-footer): the model chip sits in the message box since the one-panel composer. */
    const val PLACES_FOOTER = "manage hosts and keys here. to switch model mid-chat, tap the model chip in the message box."
    /** The places & keys page's own footer, the same phrase (the same card, pm 2026-11-13). */
    const val PLACES_PAGE_FOOTER = "to switch model mid-chat, tap the model chip in the message box — not here."
}

/** SettingsView: you / places & keys / app. Its xmark is a plain toolbar button, so it takes the accent. */
@Composable
fun SettingsRootScreen(managedCount: Int, developerMode: Boolean, onSetDeveloperMode: (Boolean) -> Unit, onOpenAppearance: () -> Unit, onOpenChats: () -> Unit, onOpenSearch: () -> Unit, onOpenPlaces: () -> Unit, onOpenCredits: () -> Unit, onBack: () -> Unit) = Page(header = { NavBar("settings", leading = { CircleButton(Icons.Filled.Close, A11yLabels.CLOSE_SETTINGS, onBack) }) }, topInset = LABEL_FIRST_INSET) {
    // SettingsView is a sheet: xmark, then the groups with their glyphs.
    Section("you")
    InsetGroup(dividerInset = 52.dp) { NavItem("appearance", leading = { PaletteGlyph() }, onClick = onOpenAppearance); NavItem("chats", leading = { BubbleGlyph() }, onClick = onOpenChats); NavItem("search", glyph = Icons.Filled.Search, divider = false, onClick = onOpenSearch) }
    Section("places & keys")
    InsetGroup(dividerInset = 52.dp) { NavItem("places & keys", trailing = placesSummary(managedCount), leading = { KeyGlyph() }, divider = false, onClick = onOpenPlaces) }
    Footer(SettingsCopy.PLACES_FOOTER)
    Section("app")
    InsetGroup(dividerInset = 52.dp) {
        // the toggle row every switch uses (8e56151f): as tall as its siblings, the whole row its touch target; green as before
        ToggleInsetRow("developer mode", developerMode, onSetDeveloperMode, checkedTrack = TeemoonColors.verified,
            leading = { Icon(Icons.Filled.Build, null, tint = TeemoonColors.tint, modifier = Modifier.size(20.dp)) })
    }
    Gap()
    Group { NavItem("credits", divider = false, onClick = onOpenCredits) }
}

/** AppearanceSettingsView: tint, font design, width (standard design only), size. */
@Composable
fun AppearanceSettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    // AppearanceSettingsView: a "color" row to its own page, then the font group — design and width as inline
    // menus, size to its own page — each row with its glyph. Values are read through `tick` after every write.
    var tick by remember { mutableIntStateOf(0) }; @Suppress("UNUSED_VARIABLE") val v = tick
    var page by remember { mutableStateOf<String?>(null) }
    // The color and size pages are pushes INSIDE this route: system back pops them to appearance. Without this the
    // route's own back (MainActivity) took appearance away with them, straight to settings (the H1 journey, 2026-10-04).
    BackHandler(enabled = page != null) { page = null }
    when (page) {
        "color" -> OptionPage("color", AppTintColor.entries.sortedBy { it.name }, settings.appTintColor, { it.name.lowercase() }, onPick = { settings.appTintColor = it; tick++ }, onBack = { page = null },
            swatch = { TeemoonPalette.tintColor(it, TeemoonColors.palette.dark) })
        "size" -> OptionPage("size", AppFontSize.entries, settings.appFontSize, { it.name.lowercase() }, onPick = { settings.appFontSize = it; tick++ }, onBack = { page = null })
        else -> Page(header = { Header("appearance", onBack) }) {
                        InsetGroup(Modifier.padding(top = 8.dp), dividerInset = 52.dp) {
                InsetRow("color", onClick = { page = "color" }, chevron = true, leading = { PaintbrushGlyph() },
                    trailing = { Text(settings.appTintColor.name.lowercase(), style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel) })
            }
            Section("font")
            InsetGroup(dividerInset = 52.dp) {
                MenuRow("design", "Aa", AppFontDesign.entries.sortedBy { it.name }, settings.appFontDesign, { it.name.lowercase() }) { settings.appFontDesign = it; tick++ }
                MenuRow("width", "↔", AppFontWidth.entries.sortedBy { it.name }, settings.appFontWidth, { it.name.lowercase() }, enabled = settings.appFontDesign == AppFontDesign.STANDARD) { settings.appFontWidth = it; tick++ }
                InsetRow("size", onClick = { page = "size" }, chevron = true, leading = { ResizeGlyph() },
                    trailing = { Text(settings.appFontSize.name.lowercase(), style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel) })
            }
        }
    }
}

/**
 * A row whose value is picked from a menu — the inline Picker of a SwiftUI Form. Its value and chevrons are plain
 * secondary, like a pushed row's value (review item appearance-plain-values, the maintainer 2026-11-07); tertiary when
 * the row is off.
 */
@Composable
private fun <T> MenuRow(label: String, glyph: String, options: List<T>, selected: T, text: (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        // AppearanceSettingsView's Labels: `textformat` reads as "Aa"; width is arrow.left.and.line.vertical.and.arrow.right.
        InsetRow(label, onClick = if (enabled) ({ open = true }) else null, leading = { if (glyph == "↔") WidthGlyph() else Text(glyph, color = TeemoonColors.tint, style = MaterialTheme.typography.titleMedium) },
            trailing = { Text(text(selected), style = MaterialTheme.typography.bodyLarge, color = if (enabled) TeemoonColors.secondaryLabel else TeemoonColors.tertiaryLabel); UpDownChevrons(if (enabled) TeemoonColors.secondaryLabel else TeemoonColors.tertiaryLabel) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text((if (o == selected) "✓ " else "   ") + text(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** A page of options with a check on the chosen one — the pushed Picker of a SwiftUI Form. */
@Composable
private fun <T> OptionPage(title: String, options: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit, onBack: () -> Unit, swatch: (@Composable (T) -> Color)? = null) = Page(header = { Header(title, onBack) }) {
        InsetGroup(Modifier.padding(top = 8.dp), dividerInset = if (swatch != null) 52.dp else 16.dp) {
        options.forEach { o ->
            InsetRow(text(o), onClick = { onPick(o) },
                leading = swatch?.let { { Box(Modifier.size(20.dp).clip(CircleShape).background(it(o))) } },
                trailing = { if (o == selected) SelectedCheck() })
        }
    }
}

/**
 * ChatsSettingsView: the system prompt, haptics, importing chats (IMPORT_NEAR.md §5: the row opens the system picker,
 * the summary comes back here), delete all chats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsSettingsScreen(
    settings: AppSettings, onDeleteAllChats: () -> Unit, onBack: () -> Unit,
    /** Opens the import page (iOS ChatsSettingsView's NavigationLink to NearImportView, cdea210c). */
    onImport: () -> Unit = {},
    onOpenSystemPrompt: () -> Unit = {},
) = Page(header = { Header("chats", onBack) }) {   // the first row has no label above it: Form's ~35pt inset, not the label-first 0 (the maintainer's Pixel, 2026-11-07)
    var haptics by remember { mutableStateOf(settings.shouldPlayHaptics) }
    var confirm by remember { mutableStateOf(false) }
    // UI audit #10: a row with its value that opens its own screen — the whole prompt filled this screen and pushed
    // "import chats" to the bottom.
    NavRow("system prompt", trailing = SystemPromptCopy.rowValue(settings.systemPrompt), onClick = onOpenSystemPrompt)
    Gap()
    ToggleRow("haptics", haptics, onSet = { haptics = it; settings.shouldPlayHaptics = it })
    Gap()
    // iOS ChatsSettingsView @ cdea210c: a row that pushes the import page, where the steps, the file and its progress are.
    Group { InsetRow(ImportCopy.PAGE_TITLE, onClick = onImport, chevron = true, modifier = Modifier.testTag("chats.import")) }
    // the footer alone: "how to export" and its sheet went with import-page-strings v5 (pm default, the maintainer approved
    // 2026-11-01) — the import page itself names the export path
    Footer(ImportCopy.FOOTER)
    Gap()
    Group { InsetRow("delete all chats", onClick = { confirm = true }, titleColor = TeemoonColors.red) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("are you sure?") },
        confirmButton = { TextButton(onClick = { confirm = false; onDeleteAllChats() }) { Text("delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("cancel") } })
}

/** The system prompt row's words (UI audit #10, the same on iOS). */

object SystemPromptCopy {
    const val RESET = "reset to default"
    const val FOOTER = "{{date}} is replaced with today's date. The current time is added to each message you send."
    fun rowValue(prompt: String) = if (prompt == AppSettings.DEFAULT_SYSTEM_PROMPT) "default" else "custom"
    fun canReset(prompt: String) = prompt != AppSettings.DEFAULT_SYSTEM_PROMPT
}

/** UI audit #10: the system prompt's own screen — the editor, what {{date}} does, and "reset to default". */
@Composable
fun SystemPromptScreen(settings: AppSettings, onBack: () -> Unit) = Page(header = { Header("system prompt", onBack) }) {   // starts with its field, no label: Form's inset
    var prompt by remember { mutableStateOf(settings.systemPrompt) }
    Group { InsetField(prompt, { prompt = it; settings.systemPrompt = it }, "system prompt", singleLine = false, minLines = 8) }
    Footer(SystemPromptCopy.FOOTER)
    Gap()
    val canReset = SystemPromptCopy.canReset(prompt)
    Group { InsetRow(SystemPromptCopy.RESET, onClick = if (canReset) ({ prompt = AppSettings.DEFAULT_SYSTEM_PROMPT; settings.systemPrompt = prompt }) else null,
        titleColor = if (canReset) TeemoonColors.tint else TeemoonColors.secondaryLabel) }
}

/**
 * SearchSettingsView. A key is checked with Brave when the field loses focus
 * (here: on save); a valid key turns grounding on. Every non-valid outcome
 * still saves the key — an unreachable Brave is not a bad key.
 */
@Composable
fun SearchSettingsScreen(
    activeProviderHasBuiltInGrounding: Boolean,
    storedKey: String?,
    groundingEnabled: Boolean,
    onSaveKey: (String) -> String?,
    onSetEnabled: (Boolean) -> Unit,
    onCopySensitive: (String) -> Unit,
    check: suspend (String) -> BraveWebSearchTool.KeyCheckResult = { BraveWebSearchTool.checkKey(it) },
    onBack: () -> Unit,
) = Page(header = { Header("web search", onBack) }, topInset = if (storedKey != null && !activeProviderHasBuiltInGrounding) LABEL_FIRST_INSET else 35.dp) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(storedKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    val latestEnable = rememberUpdatedState(onSetEnabled)
    val keyCheck = remember { SearchKeyCheck(verify = { check(it) }, onValid = { latestEnable.value(true) }) }
    val checking = keyCheck.checking; val result = keyCheck.result
    var problem by remember { mutableStateOf<String?>(null) }
    val showsGetKeyPath = remember { (storedKey ?: "").isEmpty() }
    if (activeProviderHasBuiltInGrounding) {
        Group { InsetRow("search is built in here", titleColor = TeemoonColors.secondaryLabel, trailing = { SelectedCheck() }) }
        Footer("brave answers searches the web itself. switch to another place to set up search for it.")
        return@Page
    }
    if (showsGetKeyPath) {
        Text("the model looks things up on its own initiative", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))   // 20pt semibold, primary
        // SearchSettingsView: 14pt secondary, flush with the headline (its VStack, leading 16), 8pt under it
        Text("when it needs more information, it calls brave's llm context api and receives the extracted page content — not just links. nothing is fetched unless the model decides it needs it.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
        // the link row: 16pt semibold in the accent, the arrow.up.right at the trailing edge
        Group { InsetRow("brave.com/search/api", subtitle = "get a key · account and a card · a few minutes", onClick = { uri.openUri(BRAVE_SIGNUP_URL) }, titleColor = TeemoonColors.tint, titleWeight = FontWeight.SemiBold, trailing = { ArrowUpRightGlyph() }) }
        Footer("\$5 of free credit a month — about 1,000 searches. billed per search after that.")
    }
    // SearchSettingsView's Form renders its headers prominent — 17pt semibold, secondary — unlike the settings lists' footnote headers
    SectionLabel(if (showsGetKeyPath) "already have a key?" else "brave api key", style = MaterialTheme.typography.titleMedium)
    Group {
        // SearchSetupSections: the toggle carries the magnifier; the key row ends in square.on.square and eye; the key
        // is committed when the field is left (`onSubmit` / focus loss) — there is no save row.
        if (key.isNotEmpty() && storedKey != null) ToggleInsetRow("enabled", groundingEnabled, onSetEnabled, leading = { Icon(Icons.Filled.Search, null, tint = TeemoonColors.tint, modifier = Modifier.size(20.dp)) })
        val commit = {
            problem = onSaveKey(key)
            if (problem == null) { val k = key; scope.launch { keyCheck.commit(k) } }
        }
        InsetField(key, { if (it != key) keyCheck.edited(); key = it }, "api key", secret = !showKey, sensitive = true, singleLine = !showKey, onFocusLost = { commit() }, trailing = {
            if (key.isNotEmpty()) Box(Modifier.clickable { onCopySensitive(key) }.semantics { contentDescription = A11yLabels.COPY_KEY }) { CopyGlyph() }
            Box(Modifier.clickable { showKey = !showKey }.semantics { contentDescription = if (showKey) A11yLabels.HIDE_KEY else A11yLabels.SHOW_KEY }) { EyeGlyph(open = !showKey) }
        })
    }
    problem?.let { GroupFooter(it, color = TeemoonColors.red) }
    when {
        checking -> Footer("checking with brave…")
        result != null -> GroupFooter("${keyCheckGlyph(result.kind)} ${KeyCheckCopy.message(result)}",
            color = when (keyCheckTone(result.kind)) { KeyCheckTone.BAD -> TeemoonColors.red; KeyCheckTone.GOOD -> TeemoonColors.verified; KeyCheckTone.NEUTRAL -> TeemoonColors.secondaryLabel })
    }
    Footer("stored in the keystore on this device — not synced, so a new phone means doing this again. brave sees the search terms, never your conversation.")
}

/**
 * The search settings' key check (pm sweep 2026-11-06): each commit is one run; an edit ends it. The result was kept
 * while the key was edited or emptied, and a VALID that arrived after an edit turned search on for a key Brave had not
 * accepted. Only the current run's answer shows, and only it enables search.
 */
internal class SearchKeyCheck(
    private val verify: suspend (String) -> BraveWebSearchTool.KeyCheckResult,
    private val onValid: () -> Unit,
) {
    var checking by mutableStateOf(false); private set
    var result by mutableStateOf<BraveWebSearchTool.KeyCheckResult?>(null); private set
    private var run = 0

    /** The key field changed: whatever was being checked no longer answers for it. */
    fun edited() { run++; checking = false; result = null }

    suspend fun commit(key: String) {
        val mine = ++run
        result = null
        if (key.isEmpty()) { checking = false; return }
        checking = true
        val r = verify(key)
        if (mine != run) return
        result = r; checking = false
        if (r.kind == BraveWebSearchTool.KeyCheck.VALID) onValid()
    }
}

/** CreditsView. */
@Composable
fun CreditsScreen(onBack: () -> Unit) = Page(header = { Header("credits", onBack) }) {
    val uri = LocalUriHandler.current
    Section("open source")
    Group { CREDITS.forEach { d -> InsetRow(d.name, subtitle = "${d.role} · ${d.license}", onClick = { uri.openUri(d.url) }, trailing = { Text("↗", color = TeemoonColors.tint) }) } }
    Footer("teemoon redistributes these. their licences travel with the app; the full texts are at the links above.")
    Section("models")
    Footer("models you download run under their publisher's own licence — gemma under the gemma terms of use, and so on. teemoon ships no model weights.")
}
