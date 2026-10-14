// The assistant body: settled markdown blocks plus the streaming tail stabilised per frame (MarkdownStreamSplitter),
// rendered by the markdown renderer (mikepenz multiplatform-markdown-renderer, Textual's role on iOS): headings, lists,
// code, quotes, tables, links. Images are NEVER fetched — an image shows its alt text (iOS: NoRemoteAttachmentLoader /
// withoutRemoteImages, an exfiltration guard: assistant markdown must not be able to trigger a request).
package ai.teemoon.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun MarkdownBody(markdown: String, streaming: Boolean) {
    if (markdown.isEmpty()) { Text(if (streaming) "…" else "", style = MaterialTheme.typography.bodyLarge); return }
    val split = remember(markdown) { MarkdownStreamSplitter.split(markdown) }
    // The settled blocks are final; the tail is painted as the splitter says it should look this frame (a half-typed
    // link or an unclosed fence is held back), so the transcript never flickers between two parses.
    val tail = if (streaming) MarkdownStreamSplitter.displayTail(split.tail) else split.tail
    val text = (split.settled + listOfNotNull(tail.takeIf { it.isNotEmpty() })).joinToString("\n\n")
    val t = MaterialTheme.typography
    // TEXTUAL'S DEFAULT STYLE, the one iOS renders replies with (StructuredText, no custom style): headings at the body
    // size × [2.353, 1.882, 1.647, 1.412, 1.294, 1], semibold; code at 0.882×, monospaced; links in its own blue, not the
    // tint; a code block at a 4pt radius. (Its 1px grid stroke is not drawn: the renderer's fence carries an outer
    // margin, and a border around it stood clear of the block.) Measured against the iOS chat capture
    // (2026-10-05): this drew a 20sp h2 under iOS's 32pt and an orange link under a blue one.
    val body = t.bodyLarge
    fun heading(scale: Float) = body.copy(fontSize = body.fontSize * scale, lineHeight = body.fontSize * scale * 1.2f, fontWeight = FontWeight.SemiBold)
    val mono = body.copy(fontFamily = FontFamily.Monospace, fontSize = body.fontSize * 0.882f, lineHeight = body.fontSize * 0.882f * 1.39f)
    val dark = TeemoonColors.label.luminance() > 0.5f
    val link = if (dark) Color(0xFF0099FF) else Color(0xFF3366FF)
    // UIKIT_TRANSCRIPT constraint 10: nothing in the render path may resolve after the first measure. The renderer's
    // String entry parses in a coroutine and paints an EMPTY document first, so every streamed chunk measured the reply
    // at 0pt for a frame — the list re-anchored to the top, the anchor geometry re-sized its slack, and the follow
    // chased its own tail (traced 2026-09-07 on the AVD: `first` bouncing 0 ↔ 3 every chunk). Parse synchronously,
    // in composition, so the row's first measure is its real height.
    val parsed = remember(text) { com.mikepenz.markdown.model.parseMarkdown(content = text) }
    Column {
        Markdown(
            state = parsed,
            // WRAP, not the renderer's default fill: its default filled the remaining height, harmless in the transcript's
            // lazy list (unbounded) but eating everything under the reply in any bounded column — the trailing chip and
            // the sources chip vanished in a snapshot that way (2026-09-23).
            modifier = Modifier.fillMaxWidth(),
            colors = markdownColor(text = TeemoonColors.label, codeBackground = TeemoonColors.secondaryBackground, inlineCodeBackground = TeemoonColors.secondaryBackground, dividerColor = TeemoonColors.separator),
            typography = markdownTypography(
                h1 = heading(2.353f), h2 = heading(1.882f), h3 = heading(1.647f), h4 = heading(1.412f), h5 = heading(1.294f), h6 = heading(1f),
                text = body, paragraph = body, code = mono, inlineCode = body.copy(fontFamily = FontFamily.Monospace), quote = body, bullet = body, list = body, textLink = TextLinkStyles(SpanStyle(color = link)),
            ),
            dimens = com.mikepenz.markdown.model.markdownDimens(codeBackgroundCornerSize = 4.dp),
            padding = com.mikepenz.markdown.model.markdownPadding(codeBlock = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)),
            imageTransformer = NoOpImageTransformerImpl(),
            // NO SIZE ANIMATION ON THE TEXT. The renderer's default wraps every text block in animateContentSize, so
            // a streamed word grew its paragraph's width and height over ~300 ms — several lines "typing" left to right
            // at once, the whole block clipped to the animating bounds (the user saw it on 2026-09-09). iOS paints the
            // paced text plainly; the only motion in a streaming reply is the tail growing and the follow.
            animations = com.mikepenz.markdown.model.markdownAnimations(animateTextSize = { this }),
            components = markdownComponents(
                image = { model ->
                // Alt text only. The renderer would otherwise resolve the url; the guard above is why it never does.
                Text(MarkdownImageAlt.altText(model.content.substring(model.node.startOffset, model.node.endOffset)),
                    style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
            }),
        )
    }
}

/**
 * What an image node in assistant markdown renders as: its alt text, never its url.
 *
 * The exfiltration guard's visible half (iOS: NoRemoteAttachmentLoader, and `CachedMarkdownParser.withoutRemoteImages`
 * before it). A hostile or prompt-injected model can emit an image node whose target it controls and whose query
 * string carries whatever it wants out. That must not make the client fetch the url, and must not make the url
 * appear on screen either — a rendered url is a link the user can be talked into tapping, which is the same leak
 * with one more step. So the target is dropped here rather than shown.
 *
 * (No example url in this comment: the ledger's networkDestinations rule reads string literals AND comments, and a
 * fake host in an explanation trips it. It was right to — a host is a host.)
 *
 * `[image]` when there is no alt text, because a row that renders as nothing reads as a rendering bug.
 */
object MarkdownImageAlt {
    private val PATTERN = Regex("""!\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    fun altText(rawImageNode: String): String =
        PATTERN.find(rawImageNode)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: "[image]"
}

/** Inline markdown → AnnotatedString for the places that render a single line themselves (chips, previews). Pure; unit-tested. */
object MarkdownInline {
    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0; val n = text.length
        fun until(close: String, from: Int): Int = text.indexOf(close, from).takeIf { it >= 0 } ?: -1
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> { val end = until("`", i + 1); if (end < 0) { append(text.substring(i)); i = n } else { withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }; i = end + 1 } }
                text.startsWith("**", i) -> { val end = until("**", i + 2); if (end < 0) { append(text.substring(i)); i = n } else { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }; i = end + 2 } }
                c == '*' && i + 1 < n && text[i + 1] != ' ' -> { val end = until("*", i + 1); if (end < 0) { append(text.substring(i)); i = n } else { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }; i = end + 1 } }
                c == '!' && text.startsWith("![", i) -> { val alt = until("]", i + 2); val paren = if (alt >= 0 && text.getOrNull(alt + 1) == '(') until(")", alt + 2) else -1; if (paren < 0) { append(text.substring(i)); i = n } else { append(text.substring(i + 2, alt)); i = paren + 1 } }
                c == '[' -> { val label = until("]", i + 1); val paren = if (label >= 0 && text.getOrNull(label + 1) == '(') until(")", label + 2) else -1; if (paren < 0) { append(c); i++ } else { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, label)) }; i = paren + 1 } }
                else -> { append(c); i++ }
            }
        }
    }
}
