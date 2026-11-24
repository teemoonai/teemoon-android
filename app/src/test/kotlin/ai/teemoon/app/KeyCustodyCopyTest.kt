// ports: teemoonTests/KeyCustodyCopyTests.swift @ 37de10d9 (1.1.1 audit A9: the card's banned list over every string literal; a Kotlin lexer in place of iOS's Swift lexer)
// ports: teemoonTests/KeyCustodyCopyTests.swift @ 37de10d9
package ai.teemoon.app

// The key-custody copy (card key-custody-copy, the maintainer 2026-11-18): near.ai's key service derives the model key from
// the app id and decides which sealed chips get it (dstack kms derive_k256_key), and one listed holder serves other
// models too. So no shipping string may say the key "never comes out" / "never leaves" a chip, or that only one
// sealed chip holds it.
//
// Card trust-copy-sweep-111 v4 (audit 1.1.1 A9, the maintainer 2026-11-19) widened it: no string may claim only the model can
// open the chat, that no one else can read it, that the key was generated in or lives in an enclave, or that the
// gateway cannot decrypt.
//
// Reads the app's own source — every string literal (single-line, raw """, interpolated), never a comment. Mirrors
// iOS's Swift lexer as a Kotlin lexer: `//` and nested `/* */` comments skipped, `"…"` with `\` escapes and `$x` /
// `${…}` interpolation kept as source text, `"""…"""` raw strings (no escapes), char literals skipped so '"' opens
// nothing; `\uXXXX` decoded (Swift's `\u{…}`) and curly apostrophes normalised.

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCustodyCopyTest {
    /** Card trust-copy-sweep-111 v4, verbatim. */
    private val banned = listOf("only it can", "only you and", "no one else", "nobody else", "sealed so only", "so only",
        "can't decrypt", "cannot decrypt", "generated inside", "lives in the enclave",
        // 1.1.1 audit cut review: "can't read" is the same over-claim as "can't decrypt" (curly apostrophes are normalised first)
        "can't read", "cannot read")

    /** Card key-custody-copy (2026-11-18). */
    private val bannedCustody = listOf("never comes out", "never leaves", "sealed chip holds", "sealed chip can make",
        "no one else holds the key")

    /**
     * PERMANENT allow-list: the glossary primer's "a sealed region of the chip (a TEE) whose memory the machine's operator can't read
     * from outside." the maintainer approved it on 2026-11-21 (card tee-glossary-111). The line describes the TEE's memory isolation, a hardware
     * guarantee listed under "what you still trust" (Intel's and NVIDIA's promises), not key custody. Each entry is (file, the literal's
     * normalised text); [theAllowListHoldsOnlyLiveHits] fails on an entry that no longer hits, so a stale entry cannot stay.
     * The near.ai descriptions that once waited here (card nearai-descriptions-111) were replaced with the maintainer's approved wording
     * on 2026-11-21 and are under the tripwire like any other copy.
     */
    private val teeGlossaryHardwareGuarantee = listOf(
        "TrustPresentation.kt" to "operator can't read from outside.",   // the literal of the glossary primer's first beat that holds "can't read from outside"
    )

    /** Every module's main sources; Gradle runs a module's tests from that module's directory. */
    private fun appSources(): List<File> {
        val root = File("..").canonicalFile
        return root.listFiles().orEmpty().filter { File(it, "src/main/kotlin").isDirectory }
            .flatMap { File(it, "src/main/kotlin").walkTopDown().filter { f -> f.extension == "kt" }.toList() }
    }

    /** Every string literal in Kotlin source with the line it opens on. */
    private fun literals(source: String): List<Pair<Int, String>> {
        val c = source
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        var line = 1
        fun starts(s: String, k: Int) = c.startsWith(s, k)

        fun skipBlockComment() {
            var depth = 0
            do {
                if (starts("/*", i)) { depth++; i += 2 }
                else if (starts("*/", i)) { depth--; i += 2 }
                else { if (c[i] == '\n') line++; i++ }
            } while (depth > 0 && i < c.length)
        }
        /** At a string's opening quote, reads it and returns its raw body. */
        fun readString(): String {
            val raw = starts("\"\"\"", i)
            i += if (raw) 3 else 1
            val body = StringBuilder()
            while (i < c.length) {
                if (raw) {
                    // A raw string closes on the last quote of a run of three or more.
                    if (starts("\"\"\"", i)) {
                        while (starts("\"\"\"\"", i)) { body.append('"'); i++ }
                        i += 3; break
                    }
                } else if (c[i] == '"') { i++; break }
                if (!raw && c[i] == '\\' && i + 1 < c.length) { body.append(c[i]).append(c[i + 1]); i += 2; continue }
                if (c[i] == '$' && starts("\${", i)) {
                    // Interpolation: keep its source, skip nested strings and braces.
                    body.append("\${"); i += 2
                    var depth = 1
                    while (i < c.length && depth > 0) {
                        if (c[i] == '"') { val s = i; readString(); body.append(c, s, i); continue }
                        if (c[i] == '{') depth++ else if (c[i] == '}') depth--
                        if (c[i] == '\n') line++
                        body.append(c[i]); i++
                    }
                    continue
                }
                if (c[i] == '\n') line++
                body.append(c[i]); i++
            }
            return body.toString()
        }

        while (i < c.length) {
            when {
                starts("//", i) -> while (i < c.length && c[i] != '\n') i++
                starts("/*", i) -> skipBlockComment()
                c[i] == '\'' -> {   // a char literal: '"' must open no string
                    i++
                    while (i < c.length && c[i] != '\'') { if (c[i] == '\\') i++; i++ }
                    i++
                }
                c[i] == '"' -> { val opens = line; out.add(opens to readString()) }
                else -> { if (c[i] == '\n') line++; i++ }
            }
        }
        return out
    }

    /** A literal as a reader sees it: `\uXXXX` decoded, curly apostrophes straight, line breaks and indentation one
     *  space, lowercased. */
    private fun normalized(literal: String): String {
        var s = Regex("""\\u([0-9a-fA-F]{4})""").replace(literal) { it.groupValues[1].toInt(16).toChar().toString() }
        s = s.replace('’', '\'')
        s = s.replace(Regex("""\s+"""), " ")
        return s.lowercase()
    }

    private fun hits(files: List<File>, allowed: List<Pair<String, String>> = teeGlossaryHardwareGuarantee): List<String> {
        val hits = ArrayList<String>()
        for (f in files) {
            for ((line, literal) in literals(f.readText())) {
                val l = normalized(literal)
                if (allowed.any { (file, text) -> f.name == file && l.startsWith(text) }) continue
                val onlyHolds = l.contains("only ") && l.contains("holds the key")
                // iOS's "only \(" is Kotlin's "only $" (an interpolation straight after "only ").
                val onlySealedChip = l.contains("only $") && l.contains("sealed chip")
                if (onlyHolds || onlySealedChip || (banned + bannedCustody).any { l.contains(it) }) {
                    hits.add("${f.name}:$line: ${literal.take(110)}")
                }
            }
        }
        return hits.sorted()
    }

    @Test fun noShippingStringOverClaimsKeyCustody() {
        val files = appSources()
        assertTrue("no source found to read", files.isNotEmpty())
        val hits = hits(files)
        assertTrue("${hits.size} over-claims:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    /** The allow-list is not a place to park copy: every entry must still be a literal in that file that the tripwire flags. */
    @Test fun theAllowListHoldsOnlyLiveHits() {
        val files = appSources()
        for ((file, text) in teeGlossaryHardwareGuarantee) {
            val live = files.filter { it.name == file }.flatMap { f -> literals(f.readText()).map { normalized(it.second) } }
                .any { it.startsWith(text) && (banned + bannedCustody).any(it::contains) }
            assertTrue("$file: \"${text.take(50)}…\" no longer hits; remove it from teeGlossaryHardwareGuarantee", live)
        }
    }

    @Test fun theLexerReadsEveryLiteralShapeAndNoComment() {
        val q = "\""
        val src = listOf(
            "// ${q}so only a comment$q is not a literal",
            "/* ${q}no one else$q in a block /* nested ${q}nobody else$q */ still comment */",
            "val a = ${q}x — only it can read this$q",
            "val b = ${q}only you and \${model.name(${q}q$q)} can read$q",
            "val c = ${q}${q}${q}sealed so",
            "      only the model",
            "    ${q}${q}${q}",
            "val d = ${q}${q}${q}the gateway can't decrypt ${q}quoted$q${q}${q}${q}",
            "val e = ${q}it can\\u2019t decrypt$q",
            "val ch = '$q'",
            "val url = ${q}https://example.com$q // ${q}generated inside$q trailing comment",
        ).joinToString("\n")
        val found = literals(src).map { (line, text) -> line to normalized(text) }
        val flagged = found.filter { (_, t) -> banned.any { t.contains(it) } }.map { it.first }
        assertEquals(found.toString(), listOf(3, 4, 5, 8, 9), flagged)
        assertTrue(found.toString(), found.any { it.second == "https://example.com" })
    }
}
