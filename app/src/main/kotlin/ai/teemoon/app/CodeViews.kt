// ports: teemoon/Views/CodeBlock.swift @ v1.0.1, teemoon/Views/Chat/SelfVerifyScriptCard.swift @ v1.0.1 (the card and its
//        comment/string highlighter and line-number gutter), teemoon/Views/MoonPhase.swift @ v1.0.1, teemoon/Views/Haptics.swift @ v1.0.1
package ai.teemoon.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.compose.ui.text.style.TextOverflow

// MARK: - Pure

/** SelfVerifyScriptCard.highlighted: a shell/python script split into base, comment and string runs — enough to read, never a parser. */
object ScriptHighlighter {
    enum class Tok { BASE, COMMENT, STRING }
    data class Run(val text: String, val tok: Tok)

    fun runs(code: String): List<Run> {
        val out = ArrayList<Run>(); val buf = StringBuilder(); var bufTok = Tok.BASE
        var inString: Char? = null; var inComment = false
        fun flush() { if (buf.isNotEmpty()) { out += Run(buf.toString(), bufTok); buf.setLength(0) } }
        fun emit(ch: Char, tok: Tok) { if (tok != bufTok) { flush(); bufTok = tok }; buf.append(ch) }
        for (ch in code) {
            when {
                inComment -> { emit(ch, Tok.COMMENT); if (ch == '\n') inComment = false }
                inString != null -> { emit(ch, Tok.STRING); if (ch == inString || ch == '\n') inString = null }
                ch == '#' -> { inComment = true; emit(ch, Tok.COMMENT) }
                ch == '"' || ch == '\'' -> { inString = ch; emit(ch, Tok.STRING) }
                else -> emit(ch, Tok.BASE)
            }
        }
        flush(); return out
    }

    fun lineNumbers(code: String): String { val n = maxOf(1, 1 + code.count { it == '\n' }); return (1..n).joinToString("\n") }

    /** CodeBlock.dimmedCode: whole lines that start with `#` read as comments. */
    fun commentLines(code: String): List<Pair<String, Boolean>> = code.split("\n").map { line -> line to (line.trimStart(' ', '\t').startsWith("#")) }
}

/** MoonPhase.currentSymbolName — the phase from days since the 2000-01-06 new moon over a 29.53-day cycle. */
object MoonPhase {
    private const val CYCLE = 29.53
    private val BASE: LocalDate = LocalDate.of(2000, 1, 6)
    val names = listOf("new moon", "waxing crescent", "first quarter", "waxing gibbous", "full moon", "waning gibbous", "last quarter", "waning crescent")
    val glyphs = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")

    fun index(on: LocalDate = LocalDate.now()): Int {
        val days = ChronoUnit.DAYS.between(BASE, on).toDouble()
        val into = ((days % CYCLE) + CYCLE) % CYCLE
        return when {
            into < 1.8457 -> 0; into < 5.536 -> 1; into < 9.228 -> 2; into < 12.919 -> 3
            into < 16.610 -> 4; into < 20.302 -> 5; into < 23.993 -> 6; into < 27.684 -> 7; else -> 0
        }
    }
    fun currentName(on: LocalDate = LocalDate.now()) = names[index(on)]
    fun currentGlyph(on: LocalDate = LocalDate.now()) = glyphs[index(on)]
}

/** Haptics.play: a soft tap, gated by the setting. */
object Haptics {
    fun play(haptic: HapticFeedback, enabled: Boolean) { if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

// MARK: - Views

private val codeBG = Color(0xFF1A1C21); private val codeBarBG = Color(0xFF24262E); private val codeFG = Color(0xFFD9DEE8)
private val commentFG = Color(0xFF708090); private val stringFG = Color(0xFF8FCA87); private val gutterFG = Color(0xFF5C6675)

fun highlightedScript(code: String): AnnotatedString = buildAnnotatedString {
    for (r in ScriptHighlighter.runs(code)) withStyle(SpanStyle(color = when (r.tok) { ScriptHighlighter.Tok.BASE -> codeFG; ScriptHighlighter.Tok.COMMENT -> commentFG; ScriptHighlighter.Tok.STRING -> stringFG })) { append(r.text) }
}

/** SelfVerifyScriptCard: a dark code surface with the file name, a copy button, a line gutter and the highlighted source. */
@Composable
fun SelfVerifyScriptCard(script: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Column(modifier.fillMaxWidth().background(codeBG, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().background(codeBarBG, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Filename · language tag · filled copy pill (green when copied), as SelfVerifyScriptCard.header draws them.
            TerminalGlyph(Color(0xFFADB5C4))
            Text("teemoon_verify.py", style = mono, color = Color(0xFFADB5C4), modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text("shell · python", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Color(0xFF99A3B5), maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.weight(0.01f))   // the filename outranks the spacer (iOS layoutPriority 1 over Spacer(minLength: 8))
            val pill = if (copied) TrustTone.OK.color() else Color(0xFF8CB8FF)
            Row(Modifier.clip(CircleShape).background(if (copied) pill.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f)).clickable { onCopy(script); copied = true }.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (copied) Text("✓", style = MaterialTheme.typography.labelSmall, color = pill) else CopyGlyph(pill, 12.dp)
                Text(if (copied) "copied" else "copy", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = pill, maxLines = 1)
            }
        }
        HorizontalDivider(color = Color.Black.copy(alpha = 0.5f))
        Row(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ScriptHighlighter.lineNumbers(script), style = mono, color = gutterFG)
            Text(highlightedScript(script), style = mono, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

/** CodeBlock: a labelled, copyable code viewport where comment lines read dimmed. */
@Composable
fun CodeBlock(label: String, code: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⌘ $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { onCopy(code); copied = true }) { Text(if (copied) "✓" else "⧉", color = if (copied) TrustTone.OK.color() else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        val dimmed = buildAnnotatedString { ScriptHighlighter.commentLines(code).forEachIndexed { i, (line, comment) -> withStyle(SpanStyle(color = if (comment) Color.Gray else Color.Unspecified)) { append(line); if (i < code.count { it == '\n' }) append("\n") } } }
        Text(dimmed, style = mono, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}
