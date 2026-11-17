// ports: teemoon/Views/Chat/WebSearchOffer.swift @ v1.0.1,
//        teemoon/Views/Settings/SearchSettingsView.swift @ v1.0.1 (KeyCheckCopy only)
package ai.teemoon.app

import ai.teemoon.inference.BraveWebSearchTool
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What each key-check outcome says. Every non-valid outcome except REJECTED says the key is SAVED: an unreachable Brave is not a bad key. */
object KeyCheckCopy {
    fun message(check: BraveWebSearchTool.KeyCheckResult): String = when (check.kind) {
        BraveWebSearchTool.KeyCheck.VALID -> "brave accepted this key"
        BraveWebSearchTool.KeyCheck.REJECTED -> "brave didn't recognise this key. check for a missing character or a stray space — and that it's a search api key, not one from another brave product."
        BraveWebSearchTool.KeyCheck.OUT_OF_CREDIT -> "this key is real but has no credit left this month. it's saved; searches will work again when brave resets, or sooner if you raise the limit in their dashboard."
        BraveWebSearchTool.KeyCheck.UNREACHABLE -> "couldn't reach brave, so the key hasn't been checked. it's saved — this usually means no connection."
        BraveWebSearchTool.KeyCheck.BRAVE_UNAVAILABLE -> "brave's api returned an error (${check.status ?: "?"}), so the key hasn't been checked. it's saved; this is on their side."
    }
}

const val BRAVE_SIGNUP_URL = "https://brave.com/search/api/"

/**
 * The card under an answer the model wanted the web for. The answer stands;
 * the card explains it. Paste-only, as on iOS: a key is never typed into a chat.
 */
@Composable
fun WebSearchOffer(
    query: String,
    onDismiss: () -> Unit = {},
    onKeyAccepted: (String) -> Unit = {},
    check: suspend (String) -> BraveWebSearchTool.KeyCheckResult = { BraveWebSearchTool.checkKey(it) },
    modifier: Modifier = Modifier,
) {
    val latestAccepted = rememberUpdatedState(onKeyAccepted)
    val run = remember { KeyOfferRun(verify = { check(it) }, onAccepted = { latestAccepted.value(it) }) }
    val key = run.key; val result = run.result; val checking = run.checking; val accepted = run.accepted
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current

    Card(modifier.fillMaxWidth().testTag("chat.webOffer")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("TO ANSWER THIS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat.webOffer.dismiss")) { Text("dismiss") }
            }
            Text("turn on web search", style = MaterialTheme.typography.titleMedium)
            Text(
                "the model searches when it needs to (e.g. “$query”). needs a brave account with a card on file. you get \$5 of free credit per month (about 1,000 searches); extra queries bill your card. setup takes a few minutes on their site.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { uriHandler.openUri(BRAVE_SIGNUP_URL) }, modifier = Modifier.fillMaxWidth().testTag("chat.webOffer.getKey")) { Text("get a key ↗") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (key.isEmpty()) "paste key" else maskedKey(key),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (key.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val pasted = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return@launch
                        run.check(pasted.trim())
                    }
                }, modifier = Modifier.testTag("chat.webOffer.paste")) { Text("paste") }
            }
            when {
                accepted -> Text("✓ brave accepted this key — asking again now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text("checking with brave…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result != null && result.kind != BraveWebSearchTool.KeyCheck.VALID -> Text(
                    KeyCheckCopy.message(result), style = MaterialTheme.typography.bodySmall,
                    color = if (result.kind == BraveWebSearchTool.KeyCheck.REJECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("privacy: brave sees only the search terms, never your conversation.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Enough of a key to recognise it, never enough to reuse it. */
fun maskedKey(key: String): String = if (key.length > 8) "${key.take(4)}…${key.takeLast(4)}" else "••••"

/**
 * One key check at a time, each answering for its own key (pm sweep 2026-11-06). The card's state read the key field
 * 1.7 s after key A's VALID — by then key B, pasted meanwhile and never accepted by Brave, was the one saved — and a
 * second VALID handed a key over twice. A check that a newer one superseded writes nothing; an accepted key is handed
 * over once, and only if it is still the one on the card after the pause.
 */
internal class KeyOfferRun(
    private val verify: suspend (String) -> BraveWebSearchTool.KeyCheckResult,
    private val onAccepted: (String) -> Unit,
    /** Long enough to read "accepted", short enough that the re-ask feels like a consequence. */
    private val pause: suspend () -> Unit = { delay(1700) },
) {
    var key by mutableStateOf(""); private set
    var result by mutableStateOf<BraveWebSearchTool.KeyCheckResult?>(null); private set
    var checking by mutableStateOf(false); private set
    var accepted by mutableStateOf(false); private set
    private var run = 0
    private var handedOver: String? = null

    suspend fun check(candidate: String) {
        val mine = ++run
        key = candidate; result = null; accepted = false; checking = true
        val outcome = verify(candidate)
        if (mine != run) return
        result = outcome; checking = false
        if (outcome.kind != BraveWebSearchTool.KeyCheck.VALID) return
        accepted = true
        pause()
        if (mine != run || handedOver == candidate) return
        handedOver = candidate
        onAccepted(candidate)
    }
}
