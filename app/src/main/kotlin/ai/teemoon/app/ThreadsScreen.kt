// ports: teemoon/Views/Chat/ChatsListView.swift @ v1.0.1 — REBUILD in Compose (the list, search with the 150 ms debounce and the
//        remembered query, the index-unavailable fallback, the delete confirmation, swipe-to-delete, the deep link on open; no store review prompt)
package ai.teemoon.app

import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.search.ChatSearchHighlight
import ai.teemoon.chat.search.ChatSearchResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadsScreen(
    threads: List<ThreadEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    /** The xmark: back to the chat this sheet covers. */
    onClose: () -> Unit,
    hapticsEnabled: Boolean = false,
    /** Ranked hits, or null when the index cannot answer — then the content scan below stands in. */
    onSearch: suspend (String) -> List<ChatSearchResult>? = { null },
    /** The no-index fallback: thread ids whose messages contain the text. */
    onFallbackSearch: suspend (String) -> Set<String> = { emptySet() },
    onDelete: (String) -> Unit = {},
    /** The list row's preview line, by thread. */
    previews: Map<String, String> = emptyMap(),
    /** The search box survives leaving the list (ChatListSearchMemory). */
    initialQuery: String = "",
    onQueryChanged: (String) -> Unit = {},
    /** Opening a search hit: the hit rides along so the transcript can land on it. */
    onOpenHit: (String, ChatSearchResult) -> Unit = { id, _ -> onOpen(id) },
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var query by remember { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf<List<ChatSearchResult>?>(null) }
    var hitsQuery by remember { mutableStateOf("") }
    var indexUnavailable by remember { mutableStateOf(false) }
    var fallbackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeletion by remember { mutableStateOf<ThreadEntity?>(null) }
    LaunchedEffect(query) {
        onQueryChanged(query)
        if (query.isBlank()) { hits = null; indexUnavailable = false; hitsQuery = ""; return@LaunchedEffect }
        if (query != initialQuery) delay(150)   // fresh keystrokes debounce; a restored query answers at once
        val answer = onSearch(query)
        hits = answer; indexUnavailable = answer == null; hitsQuery = query
        if (answer == null) fallbackIds = onFallbackSearch(query)
    }
    val titles = threads.associate { it.id to (it.title ?: "untitled") }
    // NO INDEX MEANS NO SEARCH, NOT AN EMPTY SEARCH: a null answer falls back to a content scan, never to "no matches".
    val shown: List<ThreadEntity> = when {
        query.isBlank() -> threads
        hits != null -> hits!!.mapNotNull { h -> threads.firstOrNull { it.id == h.threadID } }
        indexUnavailable -> threads.filter { it.id in fallbackIds || (it.title ?: "").contains(query, ignoreCase = true) }
        else -> threads   // awaiting the index's first answer — never scan here
    }
    fun snippet(t: ThreadEntity): String? = if (hitsQuery == query) hits?.firstOrNull { it.threadID == t.id }?.snippet else null
    val sections = ChatListBuckets.sections(shown, searching = query.isNotBlank(), nowMs = nowMs())

    // ChatsListView: xmark / "chats" / plus, date-bucketed inset groups, the search pill at the foot. A sheet over the chat, as on iOS.
    GroupedPage(scroll = false) {
        NavBar(title = "chats",
            // ChatsListView ends in `.tint(settings.appTintColor.getColor())`: the xmark and plus are drawn in the accent
            leading = { CircleButton(Icons.Filled.Close, "close", onClose, tint = TeemoonColors.tint) },
            trailing = { CircleButton(Icons.Filled.Add, "new chat", onNew, tint = TeemoonColors.tint) })

        if (threads.isEmpty()) EmptyChatsState(Modifier.padding(top = 120.dp))
        else if (query.isNotBlank() && shown.isEmpty()) Text("no results", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 48.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            sections.forEach { section ->
                item(key = "h-" + section.title) { SectionLabel(section.title, uppercase = true) }
                itemsIndexed(section.threads, key = { _, t -> t.id }) { i, t ->
                    val bucket = DateBucket.of(t.timestamp, nowMs())
                    val hit = if (hitsQuery == query) hits?.firstOrNull { it.threadID == t.id } else null
                    val first = i == 0; val last = i == section.threads.lastIndex
                    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp, bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
                    // Swipe from the trailing edge to delete (the iOS swipe action, and Android's own gesture); long-press does the same.
                    val haptic = LocalHapticFeedback.current
                    TrailingSwipe("delete", { pendingDeletion = t }, modifier = Modifier.padding(horizontal = 16.dp).clip(shape)) {
                    Column(Modifier.fillMaxWidth().background(TeemoonColors.groupedCard)
                        .combinedClickable(onClick = { if (hit != null) onOpenHit(t.id, hit) else onOpen(t.id) }, onLongClick = { if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress); pendingDeletion = t })) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(titles[t.id] ?: "untitled", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(ChatListBuckets.compactTime(t.timestamp, bucket), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                            }
                            val sn = snippet(t)
                            if (sn != null) Text(highlighted(ChatSearchHighlight.centred(sn)), style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else Text(previews[t.id]?.takeIf { it.isNotEmpty() } ?: " ", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!last) InsetDivider()
                    }
                    }
                }
            }
        }
        SearchPill(query, { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    pendingDeletion?.let { t ->
        AlertDialog(onDismissRequest = { pendingDeletion = null }, title = { Text("delete this chat?") },
            text = { Text("this conversation is removed from this device. it cannot be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(t.id); pendingDeletion = null }) { Text("delete", color = TeemoonColors.red) } },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("cancel") } })
    }
}

/** The `attributed` half of ChatSearchHighlight: matched runs in bold, everything else inherited. */
private fun highlighted(snippet: String) = buildAnnotatedString {
    for (seg in ChatSearchHighlight.segments(snippet)) {
        if (seg.isMatch) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg.text) } else append(seg.text)
    }
}
