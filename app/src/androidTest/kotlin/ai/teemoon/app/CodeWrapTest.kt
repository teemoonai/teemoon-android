package ai.teemoon.app

// the maintainer 2026-10-25 (via pm, the same on iOS): a long line in a reply's code block WRAPS. The renderer's default laid
// the line out at its full width inside a sideways scroller, so on a phone the end of
// `val counts = wordCounts("the cat and the hat")` was cut at the screen's edge with nothing to say so.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeWrapTest {
    @get:Rule val compose = createComposeRule()

    private val line = "    val counts = wordCounts(\"the cat and the hat sat on the mat with a bat\") // {the=2, cat=1, and=1, hat=1}"

    @Test fun aLongCodeLineWrapsInsideTheColumn() {
        compose.setContent { TeemoonTheme { Column(Modifier.width(320.dp).testTag("column")) { MarkdownBody("Here:\n\n```kotlin\n$line\n```\n", streaming = false) } } }
        // The text's own layout: in a sideways scroller it is laid out at unbounded width, one line; wrapped, several.
        // (Its bounds cannot tell: the scroller clips them to the column, and the block's padding makes one line tall.)
        val node = compose.onNode(hasText(line.trim(), substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val lines = layouts.single().lineCount
        val width = layouts.single().size.width
        val column = compose.onNodeWithTag("column").fetchSemanticsNode().size.width
        assertTrue("the line was laid out on $lines line(s), $width px wide in a $column px column", lines >= 2 && width <= column)
    }
}
