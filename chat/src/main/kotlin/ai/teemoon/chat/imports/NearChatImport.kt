// ports: teemoon/Chat/Import/NearImportSource.swift @ 8d0b76c — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
//        teemoon/Chat/Import/ChatImportWriter.swift @ 8952827c (maxImagesPerMessage only: 10, the cloud send cap)
package ai.teemoon.chat.imports

import ai.teemoon.chat.imports.ImportedMessage.Part
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * near.ai Private Chat's export (IMPORT_NEAR.md draft 8, pinned to nearai/private-chat b5c5a97): the bulk "Export
 * Chats" file, an array of conversations, and the per-chat "Export chat (.json)" file, a bare array of one chat's
 * items. Section numbers below are the spec's; conformance/import/tools/reference_import.py `near_*` is the
 * definition every expected result in cases.json came from.
 *
 * Private Chat shuts down on 2026-11-15; after that the file is all a user has, so nothing here rejects a file for
 * being odd once its kind is known. An unreadable part is skipped and counted, and the rest still imports.
 */
object NearChatImport : ChatImportSource {
    override val prefix = "near"

    /** near's default title (lib/constants.ts DEFAULT_CONVERSATION_TITLE): the user never named the chat. */
    private const val DEFAULT_TITLE = "New Conversation"

    /**
     * §4: the per-chat file holds at most the first page of items, 100 by cloud-api's `ListItemsQuery.limit`
     * default (read in the code at 752e297, not verified on the deployed service). At that size the chat may be cut.
     */
    private const val PAGE_LIMIT = 100

    /** §4: the first element decides. An object with `data` is a conversation; one with `type` is an item. */
    override fun detect(firstElementKeys: Set<String>): Boolean = isConversation(firstElementKeys) || isItem(firstElementKeys)
    private fun isConversation(keys: Set<String>) = "data" in keys
    private fun isItem(keys: Set<String>) = "type" in keys

    override fun parse(overCap: List<ScannedElement>, first: ScannedElement.Value, rest: TopLevelArrayScanner): Sequence<ImportEvent> =
        if (isConversation(first.keys)) fullExport(overCap, first, rest) else singleChat(overCap, first, rest)

    /** The elements in file order, up to the end or the first broken one (which is yielded, then nothing more). */
    private fun elements(overCap: List<ScannedElement>, first: ScannedElement, rest: TopLevelArrayScanner): Sequence<ScannedElement> =
        overCap.asSequence() + sequenceOf(first) + generateSequence { rest.next() }

    // MARK: the bulk export

    private fun fullExport(overCap: List<ScannedElement>, first: ScannedElement, rest: TopLevelArrayScanner): Sequence<ImportEvent> = sequence {
        for (e in elements(overCap, first, rest)) {
            val spills = (e as? ScannedElement.Value)?.spills.orEmpty().associateBy { it.index }
            // §4: oversized, too deep, broken (the last one read), or not a conversation (an item in a bulk file):
            // skipped, counted once.
            val conv = (e as? ScannedElement.Value)?.json as? JsonObject
            val id = conv?.get("id")?.string()
            val data = conv?.get("data") as? JsonArray
            if (conv == null || id == null || data == null) { yield(ImportEvent.Skipped(1)); continue }
            // §3.1: metadata that is not an object is {}.
            val metadata = conv["metadata"] as? JsonObject
            yield(ImportEvent.Chat(chat(id, title(metadata), data.toList(), legacy(metadata), spills = spills)))
        }
    }

    /** §3.9: trimmed and whole; empty, not a string, or near's default means none. */
    private fun title(metadata: JsonObject?): String? =
        metadata?.get("title")?.string()?.trim()?.takeUnless { it.isEmpty() || it == DEFAULT_TITLE }

    /**
     * §3.2: a chat Private Chat migrated from its legacy app (`imported_at`, ms, a string or a number that parses as
     * finite — a boolean is no marker). near relinks it by array index on load, ignoring the stored ids, so it is kept
     * in data order, with no walk.
     */
    private fun legacy(metadata: JsonObject?): Boolean {
        val v = metadata?.get("imported_at") as? JsonPrimitive ?: return false
        if (!v.isString && (v.content == "true" || v.content == "false" || v.content == "null")) return false
        return v.content.trim().toDoubleOrNull()?.isFinite() == true
    }

    // MARK: the per-chat file

    private fun singleChat(overCap: List<ScannedElement>, first: ScannedElement, rest: TopLevelArrayScanner): Sequence<ImportEvent> = sequence {
        // The file IS one conversation: its items can't be written one by one (the walk needs them all), and both
        // caps apply to the whole file, the top-level array being depth 1 (§4).
        val items = ArrayList<JsonElement>()
        val spills = HashMap<Int, Spill>()
        var broken = false
        var capped = false
        var depth = 1
        for (e in elements(overCap, first, rest)) when (e) {
            is ScannedElement.Value -> { if (!capped) { items += e.json; e.spills.forEach { spills[it.index] = it } }; depth = maxOf(depth, 1 + e.depth) }
            is ScannedElement.TooBig, is ScannedElement.TooDeep -> { capped = true; items.clear() }
            ScannedElement.Broken -> broken = true
        }
        val elementCount = rest.elementsRead
        if (capped || depth > rest.maxDepth || rest.fileBytes() > rest.maxElementBytes) { yield(ImportEvent.Skipped(1)); return@sequence }
        // §2: no conversation id, so the chat is named by its root response, which a re-export of the same chat shares.
        // No root at all (a loop): the first readable item's response.
        val readable = readable(items).map { it.second }
        val root = rootOf(readable) ?: readable.firstOrNull()?.responseId
        if (root == null) { yield(ImportEvent.Skipped(items.size + (if (broken) 1 else 0) + 1)); return@sequence }
        // §4: a file at the 100-item page, or cut off, may not hold the whole chat. A cut costs the item it cut.
        val incomplete = elementCount >= PAGE_LIMIT || broken
        yield(ImportEvent.Chat(chat("chat:$root", null, items, legacy = false, incomplete, skippedBefore = if (broken) 1 else 0, spills = spills)))
    }

    // MARK: one conversation (§3)

    private class Item(val json: JsonObject, val index: Int) {
        val id: String = json["id"]!!.string()!!
        val responseId: String = json["response_id"]!!.string()!!
        val createdAt: Long = (json["created_at"] as JsonPrimitive).content.toLong()
        val type: String? = json["type"]?.string()
        /** §3.1: not a non-empty string → absent. */
        val previous: String? = json["previous_response_id"]?.string()?.takeIf { it.isNotEmpty() }
        /** …not an array → none; its non-string entries skipped. */
        val next: List<String> = (json["next_response_ids"] as? JsonArray)?.mapNotNull { it.string() }.orEmpty()
    }

    private val integer = Regex("-?[0-9]+")

    /**
     * §3.1: what makes an item unreadable — and only this: `id`, `response_id`, `created_at`, and for a message its
     * `content`, `role` and `status`. Any other wrong type is treated as absent.
     */
    private fun isReadable(e: JsonElement): Boolean {
        val o = e as? JsonObject ?: return false
        if (o["id"]?.string() == null || o["response_id"]?.string() == null) return false
        val created = o["created_at"] as? JsonPrimitive ?: return false
        if (created.isString || !integer.matches(created.content) || created.content.toLongOrNull() == null) return false
        if (o["type"]?.string() == "message") {
            if (o["content"] !is JsonArray) return false
            if (o["role"]?.string() !in setOf("user", "assistant", "system")) return false
            if (o["status"]?.string() in setOf("failed", "pending")) return false
        }
        return true
    }

    /** Readable items in data order, the first of each id; the caller counts the rest. */
    private fun readable(data: List<JsonElement>): List<Pair<Int, Item>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Int, Item>>()
        data.forEachIndexed { i, e ->
            if (isReadable(e)) { val item = Item(e as JsonObject, i); if (seen.add(item.id)) out += i to item }
        }
        return out
    }

    /** §3.3 / §2: the first item, in data order, whose parent is absent, empty or not in the file. The real export's roots dangle. */
    private fun rootOf(items: List<Item>): String? {
        val responses = items.mapTo(HashSet()) { it.responseId }
        return items.firstOrNull { it.previous == null || it.previous !in responses }?.responseId
    }

    private class Node { val next = ArrayList<String>(); var parent: String? = null; var has = false }

    /**
     * §3.3: the branch Private Chat itself shows when the chat is reopened (`combineMessagesById` +
     * `findLastResponseId`): from the root, depth first through each response's children in recorded order; the
     * deepest response wins and a tie goes to the first reached. `last_id` is not used. Regenerating re-sends the
     * user message as a new sibling response, so the kept path has its own copy of the question.
     * Null when no item can be a root (a loop, a self-parent): the caller keeps data order.
     */
    private fun path(items: List<Item>): Set<String>? {
        val root = rootOf(items) ?: return null
        // A response named only as some item's parent gets a node (it records that item as a child), but the walk
        // never enters it: a response with no readable items is skipped, even when listed in next_response_ids.
        val nodes = LinkedHashMap<String, Node>()
        fun node(r: String) = nodes.getOrPut(r) { Node() }
        for (i in items) {
            val n = node(i.responseId).also { it.has = true }
            i.previous?.let { p -> n.parent = p; node(p).next.let { if (i.responseId !in it) it += i.responseId } }
            for (x in i.next) if (x !in n.next) n.next += x
        }
        var best = root
        var bestDepth = 0
        val seen = HashSet<String>()
        // Iterative, children in recorded order: a hostile chain can't overflow the stack.
        val stack = ArrayDeque<Pair<String, Int>>().apply { addLast(root to 0) }
        while (stack.isNotEmpty()) {
            val (r, d) = stack.removeLast()
            val n = nodes[r]?.takeIf { it.has } ?: continue
            if (!seen.add(r)) continue
            if (d > bestDepth) { best = r; bestDepth = d }
            for (x in n.next.asReversed()) stack.addLast(x to d + 1)
        }
        val path = LinkedHashSet<String>()
        var cur: String? = best
        while (!cur.isNullOrEmpty()) {
            val n = nodes[cur] ?: break
            if (cur in path || !n.has) break
            path += cur
            cur = n.parent
        }
        return path
    }

    private fun chat(sourceId: String, title: String?, data: List<JsonElement>, legacy: Boolean,
                     mayBeIncomplete: Boolean = false, skippedBefore: Int = 0, spills: Map<Int, Spill> = emptyMap()): ImportedChat {
        val readable = readable(data)
        val skipped = skippedBefore + data.size - readable.size
        val items = readable.map { it.second }
        val ordered = if (legacy) items else when (val keep = path(items)) {
            null -> items   // §3.3: no root → data order, as for a legacy chat
            else -> items.filter { it.responseId in keep }.sortedWith(compareBy<Item> { it.createdAt }.thenBy { it.index })
        }
        val messages = ArrayList<ImportedMessage>()
        for (item in ordered) {
            // §3.4: reasoning, web searches (read below for their sources), system messages and unknown types are
            // dropped by design and not counted.
            if (item.type != "message") continue
            val role = when (item.json["role"]?.string()) {
                "user" -> ImportedMessage.Role.USER
                "assistant" -> ImportedMessage.Role.ASSISTANT
                else -> continue
            }
            val content = item.json["content"] as JsonArray
            val sources = if (role == ImportedMessage.Role.ASSISTANT) sources(content, ordered.filter { it.responseId == item.responseId }) else emptyList()
            // §3.6: author id and name, and the model, are never read into a message.
            // An image part decodes only below position 10 among the message's image parts: as many as a sent message carries
            // (the maintainer 2026-11-07; iOS 8952827c, docs/IMAGES.md — was 8 in IMPORT_NEAR.md draft 9).
            var position = 0
            val parts = content.mapNotNull { part(it, spills) }.map { p ->
                if (p is Part.Placeholder && p.kind == Part.Kind.IMAGE && position++ >= IMAGES_PER_MESSAGE) p.copy(image = null) else p
            }
            messages += ImportedMessage(item.id, role, parts, item.createdAt * 1000, sources)
        }
        return ImportedChat(prefix, sourceId, title, messages, skipped, mayBeIncomplete)
    }

    /**
     * §3.5: a text part gives its non-empty text; any other part a placeholder. An image is NEVER fetched or decoded —
     * its `image_url` is not even read. A file is only a reference in the export (no bytes, no name); a reader stays
     * tolerant of a name all the same. A part that is not an object is ignored.
     */
    private fun part(e: JsonElement, spills: Map<Int, Spill>): Part? {
        val p = e as? JsonObject ?: return null
        return when (val type = p["type"]?.string()) {
            "input_text", "output_text" -> p["text"]?.string()?.takeIf { it.isNotEmpty() }?.let(Part::Text)
            else -> Part.Placeholder(
                if (type == "input_image") Part.Kind.IMAGE else Part.Kind.FILE,
                p["name"]?.string()?.takeIf { it.isNotEmpty() } ?: p["filename"]?.string()?.takeIf { it.isNotEmpty() },
                // image_url is a string, or {url} (tolerated, IMPORT_NEAR.md draft 9).
                if (type == "input_image") image(p["image_url"]?.string() ?: (p["image_url"] as? JsonObject)?.get("url")?.string(), spills) else null,
            )
        }
    }

    /**
     * IMAGES.md §3: an `input_image`'s payload, if it is one the writer may decode — a `data:` URL, inline or set aside by
     * the scanner. A remote URL is never fetched: no payload, so a placeholder.
     */
    private fun image(url: String?, spills: Map<Int, Spill>): ImportedImage? {
        if (url == null) return null
        if (url.startsWith(TopLevelArrayScanner.SPILL_PREFIX)) {
            val spill = url.removePrefix(TopLevelArrayScanner.SPILL_PREFIX).toIntOrNull()?.let(spills::get) ?: return null
            // A remote URL set aside like any image_url value: no payload, so it is never handed to the decoder.
            return if (spill.prefix.startsWith("data:")) ImportedImage { spill.readOnce() } else null
        }
        return if (url.startsWith("data:")) ImportedImage { url } else null
    }

    /**
     * IMAGES.md §2: images per imported message. Equal to the cloud send cap (app ImageCaps.CLOUD_PER_SEND); a test pins
     * it (ImageGateTest), as iOS's does — chat cannot see the app's caps.
     */
    const val IMAGES_PER_MESSAGE = 10

    /**
     * §3.8: an answer's `url_citation` annotations in order, then the `action.sources` of the web searches in the same
     * response (optional; the real export had none). The writer dedupes and fills in domains and missing titles.
     */
    private fun sources(content: JsonArray, sameResponse: List<Item>): List<ImportedSource> {
        val out = ArrayList<ImportedSource>()
        for (p in content) {
            val annotations = (p as? JsonObject)?.get("annotations") as? JsonArray ?: continue
            for (a in annotations) {
                val o = a as? JsonObject ?: continue
                if (o["type"]?.string() != "url_citation") continue
                val url = o["url"]?.string()?.takeIf { it.isNotEmpty() } ?: continue
                out += ImportedSource(url, o["title"]?.string()?.takeIf { it.isNotEmpty() })
            }
        }
        for (w in sameResponse) {
            if (w.type != "web_search_call") continue
            val found = (w.json["action"] as? JsonObject)?.get("sources") as? JsonArray ?: continue
            for (s in found) {
                val url = (s as? JsonObject)?.get("url")?.string()?.takeIf { it.isNotEmpty() } ?: continue
                out += ImportedSource(url, null)
            }
        }
        return out
    }

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
