// ports: teemoon/Views/Settings/PlacesKeysViews.swift @ v1.0.4 — REBUILD in Compose (PlacesKeysHubView, PlaceManageRow's title/subtitle rule,
//        MachinesPlaceManageView, CloudKeysManageView)
//        teemoon/Views/Settings/CloudKeyProviderPickerView.swift @ v1.0.4 (CloudKeyProviderPickerScreen)
package ai.teemoon.app

import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import ai.teemoon.providers.WhereProviderPresentation
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import ai.teemoon.providers.ProviderCapability
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import java.net.URI

// MARK: - Pure (PlaceManageRow)

fun placeManageTitle(provider: Provider): String = WhereProviderPresentation.canonicalName(provider)

/** "ollama · 2 models · key set" — kind (home) or host (cloud), the equipped count, the key state when a key is required. */
fun placeManageSubtitle(provider: Provider, storedKind: LocalServerKind?, hasKey: Boolean): String? {
    val modelCount = provider.equipped.size.let { n -> if (n == 0) "no models" else if (n == 1) "1 model" else "$n models" }
    val keyState = if (provider.requiresAPIKey) (if (hasKey) "key set" else "needs key") else null
    return when (WhereLocality.of(provider)) {
        WhereLocality.PHONE -> WhereProviderPresentation.placeCaption(provider)
        WhereLocality.HOME -> {
            val kind = when (storedKind) { LocalServerKind.OLLAMA -> "ollama"; LocalServerKind.LM_STUDIO -> "lm studio"; LocalServerKind.UNKNOWN -> "openai-compatible"; null -> null }
            listOfNotNull(kind, modelCount, keyState).joinToString(" · ")
        }
        WhereLocality.CLOUD -> {
            val host = provider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase()
            listOfNotNull(host, modelCount, keyState).joinToString(" · ")
        }
    }
}

fun placeCountBadge(n: Int): String = if (n == 0) "none" else "$n"

fun deleteMessage(provider: Provider): String =
    if (provider.requiresAPIKey) "this removes the setup and its api key. you'll have to paste the key again to use it." else "this removes the setup. you can add it again later."

// MARK: - Pure (CloudKeyProviderPicker)

/**
 * One offered row on "add cloud key". `presetCaption` rather than the add
 * form's longer `presetDescription`, and the e2ee tag straight off the
 * preset's capabilities — the UI never composes trust language (§9.5).
 */
data class CloudKeyPickerRow(val presetName: String, val title: String, val caption: String?, val showsE2EE: Boolean)

object CloudKeyPicker {
    fun rows(presetsWithoutKey: List<Provider>): List<CloudKeyPickerRow> = presetsWithoutKey.map {
        CloudKeyPickerRow(
            presetName = it.name,
            title = it.name.lowercase(),
            caption = WhereProviderPresentation.presetCaption(it),
            showsE2EE = ProviderCapability.END_TO_END_ENCRYPTION in it.capabilities,
        )
    }

    /** Shown in place of the list when every preset is already set up — it names where to go instead, because "nothing here" on its own reads as a failure. */
    const val NOTHING_LEFT = "every provider here already has a key — change one from its own row in places & keys."
    const val FOOTER = "the next screen asks for that provider's key."
    const val CUSTOM_CAPTION = "any endpoint serving /v1/chat/completions"

    /**
     * Whether picking a provider should take the picker off the stack too.
     *
     * The key form returns here on save AND on cancel, and the two have to end
     * differently: a saved setup is the end of this screen's job, so the user
     * lands back on the cloud-keys list with the new row on it, while a cancel
     * leaves the picker up to choose again. The form cannot say which happened
     * — it pops the same way both times — so the picker compares the provider
     * ids it saw before against the ones there now. Empty [before] is the first
     * appearance, when nothing has been added yet.
     */
    fun dismissAfterReturn(before: Set<String>, now: Set<String>): Boolean =
        before.isNotEmpty() && now.any { it !in before }
}

// MARK: - Screens

@Composable private fun Page(title: String, onBack: () -> Unit, topInset: Dp = 35.dp, content: @Composable ColumnScope.() -> Unit) =
    GroupedPage(header = { NavBar(title, leading = { BackButton(onBack) }) }, topInset = topInset) { content() }
@Composable private fun Footer(text: String) = GroupFooter(text)

@Composable
fun PlacesKeysHubScreen(machineCount: Int, cloudBadge: String, onOpenMachines: () -> Unit, onOpenCloud: () -> Unit, onBack: () -> Unit) = Page("places & keys", onBack) {
    InsetGroup(Modifier.padding(top = 8.dp), dividerInset = 52.dp) {
        InsetRow("home machines", onClick = onOpenMachines, chevron = true, leading = { LocalityGlyph(WhereLocality.HOME, tint = TeemoonColors.tint) }, trailing = { Text(placeCountBadge(machineCount), style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel) })
        InsetRow("cloud keys", onClick = onOpenCloud, chevron = true, valueFills = true, leading = { KeyGlyph() }, trailing = { Text(cloudBadge, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) })
        // The value gives way, never the title (LabeledContent), and it ends at the chevron. As a second weighted child
        // beside the title's column it got half the row and stopped mid-row (seen on the Pixel, 2026-10-04).
    }
    Footer("to switch model mid-chat, tap the model chip above the message field — not here.")
}

@Composable
fun PlacesManageScreen(locality: WhereLocality, providers: List<Provider>, subtitle: (Provider) -> String?, onAdd: () -> Unit, onEdit: (String) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    var pendingDelete by remember { mutableStateOf<Provider?>(null) }
    val home = locality == WhereLocality.HOME
    Page(if (home) "home machines" else "cloud keys", onBack, topInset = LABEL_FIRST_INSET) {
        SectionLabel(if (home) "computers" else "saved")
        InsetGroup(dividerInset = 52.dp) {
            if (providers.isEmpty()) InsetRow(if (home) "no computers connected." else "no cloud keys yet.", titleColor = TeemoonColors.secondaryLabel)
            // PlacesKeysViews: the row reads "edit" in secondary; delete is the trailing swipe, as on iOS, and the row's
            // long-press menu as well. CONFIRMED either way — the Swift's note: this destroyed a setup and its Keychain
            // entry on one careless thumb, while the same action in Where sat behind a long press AND an alert.
            providers.forEach { p ->
                var open by remember(p.id) { mutableStateOf(false) }
                TrailingSwipe("delete", { pendingDelete = p }) { Box {
                    InsetRow(placeManageTitle(p).lowercase(), subtitle = subtitle(p)?.lowercase(), onClick = { onEdit(p.id) }, onLongClick = { open = true }, leading = { LocalityGlyph(locality) },
                        trailing = { Text("edit", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel) })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("delete", color = TeemoonColors.red) }, onClick = { open = false; pendingDelete = p })
                    }
                } }
            }
        }
        Footer(if (home) "edit host and models here. pick which one runs from the where chip in chat." else "add or rotate keys; to revoke one, delete its setup. current model per setup is chosen in chat → where.")
        InsetGroup(Modifier.padding(top = 8.dp)) {
            InsetRow(if (home) "connect a computer" else "add cloud key", onClick = onAdd, titleColor = TeemoonColors.tint, leading = { Icon(Icons.Filled.Add, null, tint = TeemoonColors.tint, modifier = Modifier.size(20.dp)) })
        }
    }
    pendingDelete?.let { p ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("delete ${placeManageTitle(p)}?") }, text = { Text(deleteMessage(p)) },
            confirmButton = { TextButton(onClick = { onDelete(p.id); pendingDelete = null }) { Text("delete", color = TeemoonColors.red) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("keep") } })
    }
}

/**
 * Step one of "add cloud key": pick the provider. Step two is that provider's own key form — the same
 * AddEditProviderScreen the where sheet opens from its `get` rows.
 *
 * The two never share a screen. What this replaces was one form already filled in for a provider nobody had
 * chosen yet, so the first thing the user did on it was undo a choice they had not made.
 *
 * PUSHED, not presented as a sheet: the key form is the next step of this flow, and on iOS a sheet rising from
 * the bottom after a push from the side read as a different task. The host does the navigating; this screen only
 * says which was picked.
 */
@Composable
fun CloudKeyProviderPickerScreen(
    presetsWithoutKey: List<Provider>,
    onPickPreset: (String) -> Unit,
    onPickCustom: () -> Unit,
    onBack: () -> Unit,
) = Page("add cloud key", onBack) {
    val rows = CloudKeyPicker.rows(presetsWithoutKey)
    InsetGroup(Modifier.padding(top = 8.dp), dividerInset = 52.dp) {
        if (rows.isEmpty()) InsetRow(CloudKeyPicker.NOTHING_LEFT, titleColor = TeemoonColors.secondaryLabel, titleMaxLines = 3)
        rows.forEach { row ->
            InsetRow(
                title = row.title, subtitle = row.caption, titleWeight = FontWeight.SemiBold, titleMaxLines = 2,
                onClick = { onPickPreset(row.presetName) },
                leading = { LocalityGlyph(WhereLocality.CLOUD, tint = TeemoonColors.tint) },
                titleTags = if (row.showsE2EE) ({ E2EETag() }) else null,
                modifier = Modifier.testTag("cloudKey.preset.${row.presetName.lowercase()}"),
            )
        }
    }
    Footer(CloudKeyPicker.FOOTER)
    InsetGroup(Modifier.padding(top = 8.dp), dividerInset = 52.dp) {
        InsetRow(
            title = "custom", subtitle = CloudKeyPicker.CUSTOM_CAPTION, titleWeight = FontWeight.SemiBold,
            onClick = onPickCustom, leading = { LocalityGlyph(WhereLocality.HOME, tint = TeemoonColors.tint) },
            modifier = Modifier.testTag("cloudKey.custom"),
        )
    }
}
