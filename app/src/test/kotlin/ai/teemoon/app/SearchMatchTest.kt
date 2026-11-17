package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The maintainer 2026-11-12 ("yes to the search match change"): a search match in the chat list — a snippet's matched
 * words and a title match — is bold in the primary label color, not the tint; the rest of a snippet stays secondary.
 */
class SearchMatchTest {
    @Test fun aMatchIsTheLabelColorNotTheTintInBothAppearances() {
        for (p in listOf(TeemoonPalette.dark(AppTintColor.ORANGE), TeemoonPalette.light(AppTintColor.ORANGE), TeemoonPalette.dark(AppTintColor.BLUE))) {
            assertEquals(p.label, SearchMatch.color(p))
        }
    }

    /**
     * The maintainer 2026-11-12 ("Regular titles in search"): while searching, a result row's title is regular, so its
     * matched words — bold, as a snippet's — show; outside search titles stay semibold, as today.
     */
    @Test fun whileSearchingTitlesAreRegularSoTheBoldMatchShows() {
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, SearchMatch.titleWeight(searching = true))
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, SearchMatch.titleWeight(searching = false))
    }
}
