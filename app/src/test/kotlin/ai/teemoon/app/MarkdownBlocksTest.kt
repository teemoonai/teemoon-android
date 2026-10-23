// Android-only (no Swift source): MarkdownBlocks — a streamed reply's unchanged blocks keep their node, and a kept node
// is the node a fresh parse would have produced.
package ai.teemoon.app

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    /** What MarkdownBody parses for a partial: the settled blocks and the stabilised tail, joined. */
    private fun painted(markdown: String): String {
        val split = MarkdownStreamSplitter.split(markdown)
        val tail = MarkdownStreamSplitter.displayTail(split.tail)
        return (split.settled + listOfNotNull(tail.takeIf { it.isNotEmpty() })).joinToString("\n\n")
    }

    /** A subtree as its types and texts — everything the renderer reads from it. */
    private fun shape(node: ASTNode, content: String): String =
        "${node.type}[${content.substring(node.startOffset, node.endOffset)}](" + node.children.joinToString(",") { shape(it, content) } + ")"

    private fun stream(markdown: String, check: (fresh: List<Pair<ASTNode, String>>, kept: List<MarkdownBlocks.Block>) -> Unit) {
        val blocks = MarkdownBlocks()
        for (end in ScriptedReplyTransport.partialEnds(markdown.length)) {
            val text = painted(markdown.substring(0, end))
            val viaCache = parseMarkdown(content = text, referenceLinkHandler = blocks.handlerFor(text)) as State.Success
            val fresh = parseMarkdown(content = text) as State.Success
            check(fresh.node.children.map { it to fresh.content }, blocks.resolve(viaCache))
        }
    }

    /** Over every partial of the benchmark's reply, each block — kept or new — has the shape a fresh parse gives it. */
    @Test fun everyKeptBlockIsTheBlockAFreshParseWouldGive() {
        var kept = 0; var total = 0
        var last: List<MarkdownBlocks.Block> = emptyList()
        stream(StreamingReplyFixture.MARKDOWN) { fresh, blocks ->
            assertEquals(fresh.size, blocks.size)
            fresh.zip(blocks).forEachIndexed { i, (f, b) ->
                assertEquals("block $i", shape(f.first, f.second), shape(b.node, b.content))
                total++; if (last.getOrNull(i) === b) kept++
            }
            last = blocks
        }
        // most blocks are above the tail: the point of the cache
        assertTrue("kept $kept of $total", kept > total * 0.8)
    }

    @Test fun anUnchangedBlockKeepsItsInstanceAndTheGrowingOneDoesNot() {
        val blocks = MarkdownBlocks()
        fun parse(t: String) = blocks.resolve(parseMarkdown(content = t, referenceLinkHandler = blocks.handlerFor(t)) as State.Success)
        val a = parse("# title\n\nfirst para\n\nsecond")
        val b = parse("# title\n\nfirst para\n\nsecond, longer")
        assertSame(a[0], b[0]); assertSame(a[2], b[2]); assertNotSame(a.last(), b.last())
    }

    /** A reply that defines a reference link resolves `[x]` anywhere in it: nothing is kept, as before the cache. */
    @Test fun aReferenceDefinitionTurnsKeepingOff() {
        val blocks = MarkdownBlocks()
        fun parse(t: String) = blocks.resolve(parseMarkdown(content = t, referenceLinkHandler = blocks.handlerFor(t)) as State.Success)
        val a = parse("see [docs]\n\n[docs]: https://example.org/a")
        val b = parse("see [docs]\n\n[docs]: https://example.org/ab")
        assertNotSame(a[0], b[0])
    }
}
