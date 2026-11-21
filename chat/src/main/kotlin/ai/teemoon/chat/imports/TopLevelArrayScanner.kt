// ports: teemoon/Chat/Import/ImportFileScanner.swift @ v1.1.0 — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
package ai.teemoon.chat.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One element of the file's top-level array, as the scanner found it. [keys]: an object's own top-level keys. */
sealed interface ScannedElement {
    val keys: Set<String>

    /**
     * Whole, valid UTF-8 and valid JSON, within the caps. [depth]: objects and arrays, the element itself 1 (0 for a
     * scalar). [spills]: image payloads set aside, each replaced in [json] by `"teemoon-spill:<index>"`.
     */
    class Value(val json: JsonElement, val size: Int, val depth: Int, val spills: List<Spill> = emptyList()) : ScannedElement {
        override val keys: Set<String> get() = (json as? JsonObject)?.keys.orEmpty()
    }
    /** Over [TopLevelArrayScanner.maxElementBytes]: read past, never held or parsed. */
    class TooBig(override val keys: Set<String>) : ScannedElement
    /** Nested deeper than [TopLevelArrayScanner.maxDepth]: read past, never parsed. */
    class TooDeep(override val keys: Set<String>) : ScannedElement
    /**
     * Cut off, not valid JSON or UTF-8, or followed by a bad separator: the read ENDS here (§4). Resynchronising inside
     * broken JSON is guesswork, so neither app tries. The first element broken rejects the file.
     */
    data object Broken : ScannedElement { override val keys: Set<String> = emptySet() }
}

/**
 * The input layer (IMPORT_NEAR.md §0, §4): a JSON file whose top level is an array, read ONE ELEMENT AT A TIME. No size
 * cap on the file: only the element being read is held, and only while it is within [maxElementBytes]. An element over
 * the byte cap or deeper than [maxDepth] is scanned past without being kept, so a hostile file cannot make the decoder
 * build it (iOS does the same with a memory map and a byte-range scanner). conformance/import/tools/reference_import.py
 * `scan` is the definition; this is it, streamed.
 *
 * Source-neutral: a zip reader (ChatGPT, another assistant's) would hand it the inner file's stream.
 */
class TopLevelArrayScanner(
    private val input: InputStream,
    val maxElementBytes: Int = MAX_ELEMENT_BYTES,
    val maxDepth: Int = MAX_DEPTH,
    /**
     * Where an `image_url` string's payload is set aside while it streams (IMAGES.md §3: a chat is never skipped for
     * its images — they are outside the element's byte cap and decoded one at a time). Null: payloads stay inline.
     */
    private val spillDir: java.io.File? = null,
) {
    private val spills = ArrayList<Spill>()
    private var nextSpill = 0
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var end = 0
    private var consumed = 0L
    private var started = false
    private var finished = false
    private var count = 0

    /** Top-level elements met so far, a broken one included (near's per-chat file: ≥ 100 may be a cut-off page). */
    val elementsRead: Int get() = count

    private fun read(): Int {
        if (pos == end) {
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) return -1
            end = n; pos = 0
        }
        consumed++
        return buf[pos++].toInt() and 0xFF
    }

    private fun unread() { pos--; consumed-- }

    private fun skipWhitespace(): Int {
        while (true) {
            val b = read()
            if (b != ' '.code && b != '\n'.code && b != '\r'.code && b != '\t'.code) return b
        }
    }

    /** Reads up to the opening bracket. Anything else first — not JSON, an object, a number, nothing — is not an export. */
    fun open() {
        var b = read()
        // A UTF-8 byte order mark, which some editors add on save.
        if (b == 0xEF) { if (read() != 0xBB || read() != 0xBF) throw NotAnExportException("not a JSON array"); b = read() }
        if (b == -1) throw NotAnExportException("empty file")
        unread()
        b = skipWhitespace()
        if (b != '['.code) throw NotAnExportException("not a JSON array")
        started = true
    }

    /**
     * The next element, or null at the end: the closing bracket (bytes after it are ignored), or the file ending
     * cleanly between elements without one — whole elements, nothing partial, nothing counted.
     */
    fun next(): ScannedElement? {
        check(started) { "open() first" }
        if (finished) return null
        var b = skipWhitespace()
        if (count > 0) {
            when (b) {
                ']'.code, -1 -> { finished = true; return null }
                ','.code -> b = skipWhitespace()
                else -> { finished = true; count++; return ScannedElement.Broken }
            }
            if (b == -1) { finished = true; return null }
        } else if (b == ']'.code) { finished = true; return null }
        // `[` and then nothing: cut off before its first element, which must be whole.
        else if (b == -1) { finished = true; count++; return ScannedElement.Broken }
        count++
        return element(b).also { if (it === ScannedElement.Broken) finished = true }
    }

    /** The bytes read so far, reading nothing more (unlike [fileBytes]): for a cap checked as the file is read. */
    val bytesRead: Long get() = consumed

    /** Every byte of the file: the per-chat file's 64 MiB cap is on the whole file (§4). Reads to the end. */
    fun fileBytes(): Long {
        do { val b = read() } while (b != -1)
        return consumed
    }

    private fun element(first: Int): ScannedElement {
        var out: ByteArrayOutputStream? = ByteArrayOutputStream(1024)
        var size = 0L
        var tooBig = false
        var tooDeep = false
        fun keep(b: Int) {
            size++
            val o = out ?: return
            if (size > maxElementBytes) { out = null; tooBig = true } else o.write(b)
        }

        val keys = LinkedHashSet<String>()
        var depth = 0
        if (first != '{'.code && first != '['.code) {
            // A scalar: a string, or a number or literal up to the next separator. One that runs into the end is cut off.
            keep(first)
            if (first == '"'.code) {
                var escape = false
                while (true) {
                    val b = read()
                    if (b == -1) return ScannedElement.Broken
                    keep(b)
                    if (escape) escape = false else if (b == '\\'.code) escape = true else if (b == '"'.code) break
                }
            } else {
                while (true) {
                    val b = read()
                    if (b == -1) return ScannedElement.Broken
                    if (b == ','.code || b == ']'.code || b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) { unread(); break }
                    keep(b)
                }
            }
        } else {
            val isObject = first == '{'.code
            var level = 1
            depth = 1
            keep(first)
            var inString = false
            var escape = false
            // Per level: is it an object, does a key come next, and was the last key `image_url`? An object's own keys
            // at depth 1 are kept so a source can tell an oversized element's kind.
            val obj = BooleanArray(maxDepth + 2); val expect = BooleanArray(maxDepth + 2); val imageKey = BooleanArray(maxDepth + 2)
            // An object that is itself an image_url's value ({url: …}, tolerated): its `url` key is the payload's.
            val urlObject = BooleanArray(maxDepth + 2)
            obj[1] = isObject; expect[1] = isObject
            var key: ByteArrayOutputStream? = null        // a depth-1 key, for [keys]
            var anyKey: ByteArrayOutputStream? = null     // any key, compared with "image_url"
            var spill: Spill? = null                      // the image payload being set aside, if this string is one
            while (level > 0) {
                val b = read()
                if (b == -1) { spill?.discard(); return ScannedElement.Broken.also { discardSpills() } }
                if (inString && spill != null) {
                    // An image payload: written to its own file, never to the element, never counted against its cap.
                    val sp = spill
                    if (escape) { escape = false; if (b == '/'.code) sp.write(b) else sp.invalid = true; continue }
                    when (b) {
                        '\\'.code -> escape = true
                        '"'.code -> {
                            inString = false
                            sp.close()
                            for (c in "$SPILL_PREFIX${sp.index}\"".toByteArray()) keep(c.toInt() and 0xFF)
                            spill = null
                        }
                        else -> sp.write(b)
                    }
                    continue
                }
                keep(b)
                if (inString) {
                    if (escape) { escape = false; key?.write(b); anyKey?.write(b); continue }
                    when (b) {
                        '\\'.code -> { escape = true; key?.write(b); anyKey?.write(b) }
                        '"'.code -> {
                            inString = false
                            key?.let { if (keys.size < MAX_KEYS) keys += it.toByteArray().decodeToString() }
                            anyKey?.let { if (level < obj.size) imageKey[level] = it.toByteArray().contentEquals(if (urlObject[level]) URL_KEY else IMAGE_URL_KEY) }
                            key = null; anyKey = null
                        }
                        else -> {
                            key?.let { if (it.size() < MAX_KEY_BYTES) it.write(b) }
                            anyKey?.let { if (it.size() <= IMAGE_URL_KEY.size) it.write(b) }   // long enough for either key
                        }
                    }
                    continue
                }
                val inObject = level < obj.size && obj[level]
                when (b) {
                    '"'.code -> {
                        inString = true
                        if (inObject && expect[level]) {
                            if (level == 1 && isObject) key = ByteArrayOutputStream(16)
                            anyKey = ByteArrayOutputStream(16)
                            expect[level] = false
                        } else if (inObject && imageKey[level] && spillDir != null && !tooDeep) {
                            spill = Spill.open(spillDir, nextSpill++, MAX_SPILL_BYTES).also { spills += it }
                        }
                    }
                    '{'.code, '['.code -> {
                        level++
                        if (level > depth) depth = level
                        if (depth > maxDepth && !tooDeep) { tooDeep = true; out = null }
                        if (level < obj.size) {
                            val parentImage = level - 1 < obj.size && obj[level - 1] && imageKey[level - 1] && !urlObject[level - 1]
                            obj[level] = b == '{'.code; expect[level] = b == '{'.code; imageKey[level] = false
                            urlObject[level] = b == '{'.code && parentImage
                        }
                    }
                    '}'.code, ']'.code -> level--
                    ','.code -> if (inObject) { expect[level] = true; imageKey[level] = false }
                }
            }
        }
        if (tooDeep) { discardSpills(); return ScannedElement.TooDeep(keys) }
        if (tooBig) { discardSpills(); return ScannedElement.TooBig(keys) }
        val bytes = out!!.toByteArray()
        // Strict UTF-8 first: the JSON parser would quietly turn a bad byte into U+FFFD and store it.
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return ScannedElement.Broken.also { discardSpills() }
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return ScannedElement.Broken.also { discardSpills() }
        return ScannedElement.Value(json, bytes.size, depth, spills.toList()).also { spills.clear() }
    }

    private fun discardSpills() { spills.forEach { it.discard() }; spills.clear() }

    companion object {
        /** §4: a conversation over 64 MiB (67,108,864 bytes of its JSON text) is skipped and counted; the read goes on. */
        const val MAX_ELEMENT_BYTES = 64 * 1024 * 1024
        /** …and one nested deeper than 64, the conversation object itself being 1. */
        const val MAX_DEPTH = 64
        private const val MAX_KEYS = 64
        private const val MAX_KEY_BYTES = 128
        private val IMAGE_URL_KEY = "image_url".toByteArray()
        private val URL_KEY = "url".toByteArray()
        /** What an `image_url` value reads as once its payload is set aside. */
        const val SPILL_PREFIX = "teemoon-spill:"
        /** A 20 MiB image as base64 in a `data:` URL, and a little room for its header; past it the payload overflows. */
        const val MAX_SPILL_BYTES = 20L * 1024 * 1024 * 4 / 3 + 256
    }
}

/**
 * One image payload set aside by the scanner: the string's bytes as they were (a `\/` escape undone), in its own file.
 * [overflowed]: longer than the cap, stopped there; [invalid]: carried an escape no base64 payload has. Either way the
 * image is a placeholder, and the chat still imports.
 */
class Spill private constructor(val index: Int, val file: java.io.File, private val limit: Long) {
    private var out: java.io.OutputStream? = java.io.BufferedOutputStream(java.io.FileOutputStream(file), 64 * 1024)
    var length = 0L; private set
    var overflowed = false; private set
    var invalid = false
    private val head = StringBuilder()
    /** The payload's first bytes, so a source can tell a `data:` URL from a remote one without reading the file. */
    val prefix: String get() = head.toString()

    fun write(b: Int) {
        if (head.length < 32) head.append(b.toChar())
        if (overflowed || invalid) return
        if (length >= limit) { overflowed = true; close(); return }
        out?.write(b); length++
    }
    fun close() { runCatching { out?.close() }; out = null }
    fun discard() { close(); file.delete() }

    /** The payload as text, or null when it overflowed or was invalid; read once, when the image is decoded. */
    fun readOnce(): String? = if (overflowed || invalid) null else runCatching { file.readText(Charsets.ISO_8859_1) }.getOrNull()

    companion object {
        fun open(dir: java.io.File, index: Int, limit: Long): Spill { dir.mkdirs(); return Spill(index, java.io.File(dir, "spill-$index"), limit) }
    }
}
