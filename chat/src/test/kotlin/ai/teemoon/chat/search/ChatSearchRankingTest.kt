// ports: teemoonTests/ChatSearchRankingTests.swift @ v1.0.1
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.THREADS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The "iran bug" (2026-08-30): a term buried deep in one LONG reply of an old
 * thread stopped appearing once the list trusted the index's ranked answer
 * exclusively — bm25's length normalization ranks the long-document match
 * below dozens of weak short matches, and the thread-granularity limit then
 * cut it entirely.
 */
class ChatSearchRankingTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun makeIndex() = SearchTestSupport.index(File(tmp.root, "ranking-${UUID.randomUUID()}.sqlite"))
    private val filler = (0 until 160).joinToString(" ") { "paragraph $it of dense market commentary with citations." }
    private fun gold(thread: String) = ChatMessageRecord(UUID.randomUUID().toString(), thread, "assistant", System.currentTimeMillis() - 86_400_000L * 30, "$filler sanctions on zubrovka moved the price. $filler")

    @Test fun aLongBuriedMatchSurvivesManyShortWeakMatches() = runTest {
        val index = makeIndex()
        val records = (0 until 120).map { n -> ChatMessageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "assistant", System.currentTimeMillis() - n * 60_000L, "note $n: zubrovka came up briefly in the meeting today.") }
        index.index(records + gold("gold"))
        // At the list's OLD parameters (limit 100, overfetch cap 512) this exact corpus dropped the gold thread entirely.
        val hits = index.search("zubrovka", THREADS, 500)
        assertNotNull("a buried long-document match must never vanish from the ranked answer", hits.firstOrNull { it.threadID == "gold" })
    }

    @Test fun fewMatches_theBuriedMatchIsStillPresent() = runTest {
        val index = makeIndex()
        val records = (0 until 5).map { n -> ChatMessageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "assistant", System.currentTimeMillis(), "note $n: zubrovka mentioned.") }
        index.index(records + gold("gold"))
        assertNotNull(index.search("zubrovka", THREADS, 100).firstOrNull { it.threadID == "gold" })
    }
}
