// ports: teemoon/Views/Chat/MarkdownStreamSplitter.swift @ v1.0.1
//
// Splits streaming markdown into SETTLED blocks (never re-rendered) and a
// TAIL that changes every frame, and stabilises the tail so half-typed links,
// images and tables paint as what they will become rather than as raw syntax.
// A block settles only when a blank line is followed by a hard block start
// that is not the last line — the last line is never trusted.
package ai.teemoon.app

object MarkdownStreamSplitter {
    data class Split(val settled: List<String>, val tail: String)

    private fun isHardBlockStart(line: String): Boolean {
        if (line.startsWith("    ")) return false
        val trimmed = line.trimStart(' ')
        if (trimmed.isEmpty()) return false
        return when (trimmed[0]) {
            '>', '|', '#', '`', '~' -> true
            '-', '*', '+' -> trimmed.getOrNull(1) != ' '   // a loose list item continues its list
            else -> {
                if (trimmed[0].isDigit()) {
                    val afterDigits = trimmed.dropWhile { it.isDigit() }
                    if (afterDigits.firstOrNull() == '.' || afterDigits.firstOrNull() == ')') return afterDigits.getOrNull(1) != ' '
                }
                true   // ordinary paragraph
            }
        }
    }

    /** Vertical spacing (em) each block kind carries, so settled and tail render with the same rhythm. */
    fun spacingEm(block: String): Pair<Double, Double> {
        val line = block.trimStart(' ', '\n')
        return when (line.firstOrNull()) {
            '#' -> 1.6 to 0.8
            '`', '~' -> 0.88 to 0.0
            '|' -> 1.6 to 1.6
            '-', '*', '_' -> if (line.getOrNull(1) == ' ') 0.8 to 0.0 else 1.6 to 1.6
            else -> 0.8 to 0.0
        }
    }
    fun collapsedSpacingEm(previous: String, next: String): Double = maxOf(spacingEm(previous).second, spacingEm(next).first)

    fun split(markdown: String): Split {
        if (markdown.isEmpty()) return Split(emptyList(), "")
        val lines = markdown.split("\n")
        val settled = ArrayList<String>(); val current = ArrayList<String>()
        var inFence = false; var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val marker = line.trimStart(' ').take(3)
            if (marker == "```" || marker == "~~~") inFence = !inFence
            if (line.trim(' ').isEmpty() && !inFence && current.isNotEmpty()) {
                var lookahead = index + 1
                while (lookahead < lines.size && lines[lookahead].trim(' ').isEmpty()) lookahead++
                if (lookahead < lines.size - 1 && isHardBlockStart(lines[lookahead])) {
                    settled.add(current.joinToString("\n")); current.clear(); index = lookahead; continue
                }
            }
            current.add(line); index++
        }
        return Split(settled, current.joinToString("\n"))
    }

    /** The tail as it should be painted this frame. */
    fun displayTail(tail: String): String {
        if (tail.isEmpty()) return tail
        if (isInsideUnclosedFence(tail)) return tail
        return stabilizeIncompleteLink(stabilizeIncompleteTable(tail))
    }

    private fun isInsideUnclosedFence(markdown: String): Boolean {
        var inFence = false
        for (line in markdown.split("\n")) { val m = line.trimStart(' ').take(3); if (m == "```" || m == "~~~") inFence = !inFence }
        return inFence
    }

    private fun isTableLine(line: String) = line.trimStart(' ').firstOrNull() == '|'
    private fun isCompleteTableRow(line: String) = isTableLine(line) && line.count { it == '|' } >= 2
    private fun isSeparatorRow(line: String): Boolean {
        if (!isTableLine(line)) return false
        val body = line.filter { it != '|' && it != ' ' && it != ':' }
        return body.isNotEmpty() && body.all { it == '-' }
    }
    private fun tableColumnCount(header: String): Int {
        val parts = header.split('|').toMutableList()
        if (parts.firstOrNull()?.trim(' ')?.isEmpty() == true) parts.removeAt(0)
        if (parts.lastOrNull()?.trim(' ')?.isEmpty() == true) parts.removeAt(parts.lastIndex)
        return maxOf(parts.size, 1)
    }
    private fun separatorRow(columns: Int) = "|" + List(columns) { " --- " }.joinToString("|") + "|"

    /** A header row with no delimiter yet paints as a table; a half-typed row is held. */
    private fun stabilizeIncompleteTable(markdown: String): String {
        val lines = markdown.split("\n")
        var end = lines.size
        while (end > 0 && lines[end - 1].trim(' ').isEmpty()) end--
        var start = end
        while (start > 0 && isTableLine(lines[start - 1])) start--
        val table = lines.subList(start, end)
        if (table.isEmpty()) return markdown
        if (table.any(::isSeparatorRow)) return markdown
        val working = table.toMutableList()
        if (end == lines.size && working.isNotEmpty() && !isCompleteTableRow(working.last())) working.removeAt(working.lastIndex)
        val headerIndex = working.indexOfFirst(::isCompleteTableRow)
        if (headerIndex < 0) return (lines.subList(0, start) + working + lines.subList(end, lines.size)).joinToString("\n")
        working.add(headerIndex + 1, separatorRow(tableColumnCount(working[headerIndex])))
        return (lines.subList(0, start) + working + lines.subList(end, lines.size)).joinToString("\n")
    }

    /** An incomplete link shows its label; an incomplete image is held (never alt text, never a fetch). */
    private fun stabilizeIncompleteLink(markdown: String): String {
        val opener = lastLinkOpener(markdown) ?: return markdown
        var from = opener; var isImage = false
        if (from > 0 && markdown[from - 1] == '!') { from -= 1; isImage = true }
        val afterOpen = markdown.substring(opener + 1)
        val closeLabel = afterOpen.indexOf(']')
        if (closeLabel < 0) return if (isImage) markdown.substring(0, from) else markdown.substring(0, from) + afterOpen
        val label = afterOpen.substring(0, closeLabel)
        val afterLabel = afterOpen.substring(closeLabel + 1)
        if (afterLabel.firstOrNull() != '(') return markdown
        if (linkDestinationClosed(afterLabel)) return markdown
        return if (isImage) markdown.substring(0, from) else markdown.substring(0, from) + label
    }

    private fun lastLinkOpener(s: String): Int? {
        var last: Int? = null; var i = 0; var codeRun = 0
        while (i < s.length) {
            if (s[i] == '\\' && codeRun == 0) { i += 2; continue }
            if (s[i] == '`') {
                var n = 0; while (i < s.length && s[i] == '`') { n++; i++ }
                if (codeRun == 0) codeRun = n else if (codeRun == n) codeRun = 0
                continue
            }
            if (codeRun == 0 && s[i] == '[') last = i
            i++
        }
        return if (codeRun != 0) null else last
    }

    private fun linkDestinationClosed(afterOpenParen: String): Boolean {
        var depth = 0; var inAngles = false
        for (ch in afterOpenParen) {
            if (inAngles) { if (ch == '>') inAngles = false; continue }
            when (ch) {
                '<' -> inAngles = true
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return true }
                '\n' -> return false
            }
        }
        return false
    }
}
