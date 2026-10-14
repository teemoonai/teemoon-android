// ports: teemoonTests/ChatSearchCorrectnessTests.swift @ v1.0.1
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.ChatSearchGranularity.THREADS
import ai.teemoon.chat.search.SearchTestSupport.OPEN
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** The property a search box actually promises: every result contains what you typed, and the snippet shows you where. Synthetic corpus, deliberately. */
class ChatSearchCorrectnessTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun makeIndex() = SearchTestSupport.index(File(tmp.root, "correctness-${UUID.randomUUID()}.sqlite"))

    private fun corpus(): List<Pair<String, List<Pair<String, String>>>> = listOf(
        "a" to listOf("user" to "how do I descale the kettle",
            "assistant" to "Run a cycle of white vinegar and water through the kettle, then rinse twice. Limescale builds up fastest in hard water areas and the element is what suffers first."),
        "b" to listOf("user" to "what appliances need servicing this year",
            "assistant" to "The boiler is due in March. Your appliances under warranty are the dishwasher and the washing machine; the extractor fan is not covered.",
            "user" to "book the boiler one"),
        "c" to listOf("user" to "recipe for sourdough",
            "assistant" to "Feed the starter twelve hours ahead. Autolyse the flour and water for an hour before adding salt. Bulk ferment until it has risen by half."),
        "d" to listOf("user" to "cat vaccination schedule",
            "assistant" to "Kittens need a first course at nine weeks and a booster at twelve. After that it is annual. The catalogue from the vet lists which ones are legally required."))

    private fun records() = corpus().flatMap { (thread, msgs) -> msgs.mapIndexed { i, (role, text) ->
        ChatMessageRecord(UUID.randomUUID().toString(), thread, role, 1_700_000_000_000L + i, text) } }

    /** Prefix semantics, because the last query token gets `*`. */
    private fun threadContainsPrefix(term: String, threadID: String, all: List<ChatMessageRecord>) = all.filter { it.threadID == threadID }.any { r ->
        r.content.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(term.lowercase()) } }

    @Test fun everyThreadResultActuallyContainsTheTerm() = runTest {
        val index = makeIndex(); val all = records(); index.index(all)
        for (term in listOf("kettle", "boiler", "sourdough", "cat", "applianc", "vinegar", "limescale"))
            for (hit in index.search(term, THREADS, 50)) assertTrue("\"$term\" returned a thread that does not contain it", threadContainsPrefix(term, hit.threadID, all))
    }

    /** The reported symptom: a row with no highlight at all. If a result is real, snippet() must mark the term inside it. */
    @Test fun everyResultSnippetShowsWhereItMatched() = runTest {
        val index = makeIndex(); index.index(records())
        for (term in listOf("kettle", "boiler", "sourdough", "cat", "applianc")) {
            val hits = index.search(term, THREADS, 50)
            assertTrue("\"$term\" should match something", hits.isNotEmpty())
            for (hit in hits) assertTrue("\"$term\" produced an unhighlighted snippet: ${hit.snippet}", hit.snippet.contains(OPEN))
        }
    }

    @Test fun prefixMatchesAreRealMatchesNotAccidents() = runTest {
        val index = makeIndex(); index.index(records())
        val hits = index.search("cat", MESSAGES, 50)   // "cat" prefixes "catalogue"; both live in thread d
        assertTrue(hits.isNotEmpty())
        for (hit in hits) assertTrue(ChatSearchHighlight.plainText(hit.snippet).lowercase().contains("cat"))
    }

    @Test fun aTermInNoThreadReturnsNothing() = runTest {
        val index = makeIndex(); index.index(records()); assertTrue(index.search("helicopter", THREADS, 50).isEmpty())
    }

    /** Multi-word queries are implicit AND: this is where an OR-by-accident would show up as "keyword not in chat". */
    @Test fun multiWordQueriesRequireEveryTerm() = runTest {
        val index = makeIndex(); val all = records(); index.index(all)
        assertTrue(index.search("kettle sourdough", THREADS, 50).isEmpty())
        val both = index.search("kettle vinegar", THREADS, 50)
        assertEquals(1, both.size)
        for (hit in both) { assertTrue(threadContainsPrefix("kettle", hit.threadID, all)); assertTrue(threadContainsPrefix("vinegar", hit.threadID, all)) }
    }

    @Test fun anEditedMessageDoesNotKeepMatchingItsOldText() = runTest {
        val index = makeIndex(); val original = ChatMessageRecord("m", "t", "user", 1L, "the kettle is broken")
        index.index(listOf(original)); index.index(listOf(original.copy(content = "the toaster is broken")))
        assertTrue(index.search("kettle", THREADS, 50).isEmpty()); assertEquals(1, index.search("toaster", THREADS, 50).size)
    }

    // MARK: - Long documents: the corpus above is short, clean prose, so snippet() never had to CHOOSE a window. These do.

    private fun longDocument(position: String, term: String = "kettle"): String {
        val filler = (1..200).map { "paragraph $it discusses unrelated household maintenance topics at length" }
        return when (position) {
            "start" -> (listOf("$term appears first here") + filler).joinToString(". ")
            "middle" -> (filler.take(100) + "$term appears in the middle" + filler.takeLast(100)).joinToString(". ")
            else -> (filler + "$term appears last of all").joinToString(". ")
        }
    }

    @Test fun snippetHighlightsAMatchWhereverItSitsInALongDocument() = runTest {
        for (position in listOf("start", "middle", "end")) {
            val index = makeIndex(); index.index(listOf(ChatMessageRecord(UUID.randomUUID().toString(), "t", "assistant", 1L, longDocument(position))))
            val hits = index.search("kettle", THREADS, 10)
            assertEquals("no hit for a term at the $position", 1, hits.size)
            assertTrue("term at the $position of a long document produced NO highlight", hits.first().snippet.contains(OPEN))
        }
    }

    /** Implicit AND matches the ROW; the 18-token snippet window can only cover one region. It must still mark something. */
    @Test fun multiWordMatchesFarApartStillShowAHighlight() = runTest {
        val index = makeIndex(); val filler = (1..200).joinToString(". ") { "filler line $it about nothing in particular" }
        index.index(listOf(ChatMessageRecord(UUID.randomUUID().toString(), "t", "assistant", 1L, "kettle at the very beginning. $filler. vinegar at the very end")))
        val hits = index.search("kettle vinegar", THREADS, 10)
        assertEquals(1, hits.size); assertTrue(hits.first().snippet.contains(OPEN))
    }
}
