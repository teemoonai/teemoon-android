// ports: teemoonTests/DebugCopyRedactionTests.swift @ v1.0.1 (7 of 9 — the two card-copy tests need the iOS debug views),
//        teemoonTests/MarkdownStreamSplitterTests.swift @ v1.0.1 (both suites, 19), teemoonTests/TranscriptViewportTests.swift @ v1.0.1 (16),
//        teemoonTests/TranscriptApplyPolicyTests.swift @ v1.0.1 (8), teemoonTests/FreshStartTests.swift @ v1.0.1 (7),
//        teemoonTests/FollowGlideTests.swift @ v1.0.1 (3) + android-only: AppSettings migration and the grounding rule.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHeaderRedactionTest {
    private val liveKey = "sk-or-v1-fake00000000000000000000000000000000000000000000000000000-1f18"
    private val headers = mapOf("Authorization" to "Bearer $liveKey", "X-Api-Key" to liveKey, "x-subscription-token" to liveKey, "Content-Type" to "application/json", "User-Agent" to "teemoon/1.0")

    /**
     * A KEY UNDER A CUSTOM HEADER NAME IS MASKED TOO. A provider set up with its own header ("X-Gateway-Pass") had its
     * key copied verbatim — only four standard names were masked (the local audit, an INFO since 3910). The transport
     * that sends a key registers the header it used, and the copy masks every registered name.
     */
    @Test fun aKeyUnderACustomHeaderNameIsMaskedWhenCopied() {
        // what every key-sending site does (CredentialHeaders.attach). Not by building an OpenAiTransport: its OkHttp
        // client looks for Android's Conscrypt under the JVM and failed this test whenever another test ran first.
        ai.teemoon.providers.CredentialHeaders.attach(mutableMapOf(), liveKey, "X-Gateway-Pass")
        val block = DebugHeaderRedaction.copyHeaderBlock(mapOf("X-Gateway-Pass" to liveKey, "Content-Type" to "application/json"))
        assertFalse(block, block.contains(liveKey)); assertFalse(block.contains(liveKey.take(16)))
        assertTrue(block.contains("Content-Type: application/json"))
        assertFalse(DebugHeaderRedaction.copyValue(liveKey, "x-gateway-pass").contains(liveKey))   // header names are case-insensitive
    }

    /**
     * WHATEVER THE NAME, A STORED KEY IS MASKED. Registration covers the chat transport; the catalogue fetch, the
     * endpoint probes and Brave attach keys too, and any of them can reach an error card. So the copy also masks any
     * header whose value contains a key the app holds (the local audit's suggestion, 2026-10-13).
     */
    @Test fun aHeaderCarryingAStoredKeyIsMaskedWhateverItsName() {
        val previous = DebugHeaderRedaction.storedSecrets
        DebugHeaderRedaction.storedSecrets = { listOf(liveKey) }
        try {
            val block = DebugHeaderRedaction.copyHeaderBlock(mapOf("X-My-Token" to liveKey, "X-Wrapped" to "Token $liveKey", "Content-Type" to "application/json"))
            assertFalse(block, block.contains(liveKey)); assertFalse(block.contains(liveKey.take(16)))
            assertTrue(block.contains("Content-Type: application/json"))
        } finally { DebugHeaderRedaction.storedSecrets = previous }
    }

    @Test fun headerBlock_dropsEveryCredential_keepsEverythingElse() {
        val block = DebugHeaderRedaction.copyHeaderBlock(headers)
        assertFalse(block.contains(liveKey)); assertFalse(block.contains(liveKey.take(16)))
        assertTrue(block.contains("Content-Type: application/json")); assertTrue(block.contains("User-Agent: teemoon/1.0"))
        assertTrue(block.contains("Bearer ••••…1f18")); assertTrue(block.contains("X-Api-Key: ••••…1f18")); assertTrue(block.contains("x-subscription-token: ••••…1f18"))
    }
    @Test fun headerNameMatching_isCaseInsensitive() { for (n in listOf("AUTHORIZATION", "authorization", "Authorization")) assertFalse(n, DebugHeaderRedaction.copyValue("Bearer $liveKey", n).contains(liveKey)) }
    @Test fun nonSecretHeader_passesThroughUntouched() = assertEquals("application/json", DebugHeaderRedaction.copyValue("application/json", "Content-Type"))
    @Test fun shortSecret_isMaskedWhole() { assertEquals("Bearer ••••", DebugHeaderRedaction.copyValue("Bearer abc", "Authorization")); assertEquals("••••", DebugHeaderRedaction.copyValue("abc", "X-Api-Key")) }
    @Test fun schemelessSecret_keepsNoScheme() = assertEquals("••••…1f18", DebugHeaderRedaction.copyValue(liveKey, "api-key"))
    @Test fun urlQueryCredentials_areRedacted_othersSurvive() {
        val out = DebugHeaderRedaction.copyURLString("https://api.example.com/v1/chat?model=glm-5.2&api_key=QQQQWWWWEEEERRRRTTTT&stream=true")
        assertFalse(out.contains("QQQQWWWWEEEERRRRTTTT")); assertTrue(out.contains("api_key=••••…TTTT")); assertTrue(out.contains("model=glm-5.2")); assertTrue(out.contains("stream=true")); assertTrue(out.startsWith("https://api.example.com/v1/chat?"))
    }
    @Test fun urlWithoutSecrets_isByteIdentical() {
        val plain = "https://cloud-api.near.ai/v1/chat/completions"; assertEquals(plain, DebugHeaderRedaction.copyURLString(plain))
        val q = "https://api.example.com/search?q=hello&count=10"; assertEquals(q, DebugHeaderRedaction.copyURLString(q))
        assertEquals("Bearer $liveKey", DebugHeaderRedaction.value("Bearer $liveKey", "Authorization"))   // the display path is verbatim outside the UI-test host
    }
}

class MarkdownStreamSplitterTest {
    private val answer = "Here is the short version, and then the detail.\n\n## What changed\n\n- The parse moved off the hot path\n- The settled blocks stopped re-rendering\n\n1. First step\n2. Second step\n\n```swift\nfunc stream() {\n\n    print(\"blank line inside a fence\")\n}\n```\n\n| Approach | Cost |\n| --- | --- |\n| Whole document | O(n) |\n\n> Only the tail should cost anything per frame.\n\nThat is the whole idea."
    private fun rejoin(s: MarkdownStreamSplitter.Split) = (s.settled + listOf(s.tail)).joinToString("\n\n")

    @Test fun roundTrip() = assertEquals(answer, rejoin(MarkdownStreamSplitter.split(answer)))
    @Test fun settledIsAppendOnly() {
        var previous: List<String> = emptyList()
        for (end in 1..answer.length) {
            val settled = MarkdownStreamSplitter.split(answer.take(end)).settled
            assertTrue("settled shrank at $end", settled.size >= previous.size); assertEquals("rewritten at $end", previous, settled.take(previous.size)); previous = settled
        }
    }
    @Test fun fenceStaysWhole() { val s = MarkdownStreamSplitter.split(answer); for (b in s.settled + s.tail) assertEquals("unbalanced fence in:\n$b", 0, (b.split("```").size - 1) % 2) }
    @Test fun looseOrderedListStaysWhole() { val s = MarkdownStreamSplitter.split("1. First step\n\n2. Second step\n\n# Done"); assertEquals(1, (s.settled + s.tail).count { it.contains("step") }) }
    @Test fun looseUnorderedListStaysWhole() { val s = MarkdownStreamSplitter.split("- alpha\n\n- beta\n\n# End\n"); assertEquals(1, (s.settled + s.tail).count { it.contains("alpha") || it.contains("beta") }) }
    @Test fun tableStaysWhole() { val s = MarkdownStreamSplitter.split("Intro.\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nDone.\n"); val t = (s.settled + s.tail).filter { it.contains("|") }; assertEquals(1, t.size); assertTrue(t[0].contains("| 1 | 2 |")) }
    @Test fun thematicBreakSplits() = assertEquals("Before.", MarkdownStreamSplitter.split("Before.\n\n---\n\nAfter.").settled.first())
    @Test fun tailIsNeverSettled() { val s = MarkdownStreamSplitter.split("First.\n\n# Second\n"); assertEquals(listOf("First."), s.settled); assertEquals("# Second\n", s.tail) }
    @Test fun partialFinalLineIsNotTrusted() { assertTrue(MarkdownStreamSplitter.split("Intro.\n\n-").settled.isEmpty()); assertTrue(MarkdownStreamSplitter.split("Intro.\n\n- ").settled.isEmpty()) }
    @Test fun emptyInput() { assertEquals(MarkdownStreamSplitter.Split(emptyList(), ""), MarkdownStreamSplitter.split("")); assertTrue(MarkdownStreamSplitter.split("\n\n").settled.isEmpty()) }

    // Streaming tail stabilizer
    private fun d(s: String) = MarkdownStreamSplitter.displayTail(s)
    @Test fun incompleteLinkShowsLabel() { assertEquals("docs", d("[docs](https://example.com/long")); assertEquals("See docs", d("See [docs](https://example.com/long")); assertEquals("See [docs](https://example.com/long)", d("See [docs](https://example.com/long)")) }
    @Test fun partialLinkLabel() { assertEquals("See do", d("See [do")); assertEquals("See [docs]", d("See [docs]")) }
    @Test fun completeLinkThenIncomplete() = assertEquals("See [one](https://a.com) and two", d("See [one](https://a.com) and [two](https://b.com/x"))
    @Test fun incompleteImageIsHeld() { assertEquals("Hi ", d("Hi ![alt](http://x.com/a")); assertEquals("Hi ", d("Hi ![al")) }
    @Test fun codeIsNotALink() { assertEquals("see `[docs](http://x", d("see `[docs](http://x")); assertEquals("```\n[docs](http://x.com\n", d("```\n[docs](http://x.com\n")) }
    @Test fun escapedBracket() = assertEquals("arr\\[0", d("arr\\[0"))
    @Test fun incompleteTableGetsDelimiter() { val out = d("| Name | Age |\n"); assertTrue(out.contains("| --- ")); assertTrue(out.contains("| Name | Age |")) }
    @Test fun completeTableUnchanged() { val t = "| Name | Age |\n| --- | --- |\n| Ada | 36 |\n"; assertEquals(t, d(t)) }
    @Test fun incompleteTableRowHeld() { assertEquals("", d("| Nam")); val out = d("| Name | Age |\n| Ad"); assertTrue(out.contains("| Name | Age |")); assertFalse(out.contains("| Ad")) }
    @Test fun completeTailIsIdentity() { val md = "Hello **world** and a [link](https://example.com).\n"; assertEquals(md, d(md)) }
}

class TranscriptTest {
    private val t = 1000.0
    private fun r(g: Boolean, i: Boolean, now: Double, settle: Double, holdY: Double?, holdUntil: Double) = TranscriptViewport.resolve(g, i, now, settle, holdY, holdUntil)
    @Test fun aLiveFollowIsThrottled() = assertEquals(TranscriptViewport.PinToEnd(PinMode.THROTTLED), r(true, false, t, t + 1, null, 0.0))
    @Test fun walkingAwayStopsTheFollow() = assertEquals(TranscriptViewport.Free, r(true, true, t, 0.0, null, 0.0))
    @Test fun aFollowingHandoffSettlesImmediately() = assertEquals(TranscriptViewport.PinToEnd(PinMode.IMMEDIATE), r(false, false, t, t + 1, null, 0.0))
    @Test fun aHoldOutranksSettleAndFollow() { assertEquals(TranscriptViewport.Hold(800.0), r(false, true, t, t + 1, 800.0, t + 1)); assertEquals(TranscriptViewport.Hold(800.0), r(true, true, t, t + 1, 800.0, t + 1)) }
    @Test fun anExpiredHoldIsFree() = assertEquals(TranscriptViewport.Free, r(false, true, t, 0.0, 800.0, t - 0.01))
    @Test fun aLiveFollowOutranksALeftoverSettle() = assertEquals(TranscriptViewport.PinToEnd(PinMode.THROTTLED), r(true, false, t, t + 1, 800.0, t - 1))
    @Test fun idleIsFree() = assertEquals(TranscriptViewport.Free, r(false, false, t, 0.0, null, 0.0))

    private fun geometry(ink: Double) = TranscriptAnchorGeometry(userHeight = 120.0, visibleHeight = 900.0, topFade = 60.0, chrome = 190.0, inkBelow = ink)
    private fun gap(ink: Double): Double { val g = geometry(ink); return g.userHeight + ink + g.placeholder - g.usableHeight }
    @Test fun theReserveMakesTheEndLandWhereThePromptAtTopWouldBe() { var ink = 0.0; while (ink <= 530) { assertEquals(0.0, gap(ink), 0.0); ink += 10 }; assertEquals(0.0, gap(529.0), 0.0); assertEquals(0.0, gap(530.0), 0.0); assertEquals(1.0, gap(531.0), 0.0); assertEquals(30.0, gap(560.0), 0.0) }
    @Test fun aPointOfJitterAtTheBoundaryDoesNotMoveTheViewport() { var ink = 500.0; while (ink <= 560) { assertTrue(Math.abs(gap(ink) - gap(ink - 1)) <= 1); ink += 1 } }
    @Test fun aShortReplyReservesTheRestOfTheBand() = assertEquals(330.0, geometry(200.0).placeholder, 0.0)
    @Test fun theReserveGivesBackExactlyWhatTheReplyTakes() { val s = geometry(100.0); val b = geometry(400.0); assertEquals(300.0, s.placeholder - b.placeholder, 0.0); assertEquals(s.placeholder + 100, b.placeholder + 400, 0.0) }
    @Test fun aReplyTallerThanTheBandReservesNothing() { assertEquals(0.0, geometry(600.0).placeholder, 0.0); assertEquals(0.0, geometry(530.0).placeholder, 0.0); assertEquals(1.0, geometry(529.0).placeholder, 0.0) }
    @Test fun turnStartReservesSoTheEndIsNotTheComposer() = assertEquals(530.0, geometry(0.0).placeholder, 0.0)
    @Test fun slackIsBoundedByOneViewportAndNeverTheGapBetweenTwoEnds() {
        val empty = geometry(0.0); assertEquals(empty.usableHeight - 120, empty.placeholder, 0.0); assertTrue(empty.placeholder <= empty.visibleHeight)
        var previous = empty.placeholder; var ink = 50.0; while (ink <= 700) { val p = geometry(ink).placeholder; assertTrue(p <= previous); previous = p; ink += 50 }; assertEquals(0.0, previous, 0.0)
    }
    @Test fun aTallPromptLeavesNoReserveOfItsOwn() = assertEquals(0.0, geometry(0.0).copy(userHeight = 800.0).placeholder, 0.0)
    @Test fun handoffFloorKeepsTheSeedWhenFittedIsUnfitted() {
        assertEquals(6480.0, TranscriptHandoffSizing.floor(6480.0, 400.0), 0.0); assertEquals(6480.0, TranscriptHandoffSizing.floor(6480.0, 83.0), 0.0); assertEquals(6447.0, TranscriptHandoffSizing.floor(6480.0, 6447.0), 0.0)
        assertEquals(500.0, TranscriptHandoffSizing.floor(0.0, 500.0), 0.0); assertEquals(276.0, TranscriptHandoffSizing.floor(876.0, 276.0), 0.0)
    }
    @Test fun aFirstPaintMustNotShrinkAHandoffSeed() {
        val item = TranscriptItem.Message("m1")
        TranscriptRowHeightCache.store(3000.0, item); TranscriptRowHeightCache.store(83.0, item); assertEquals(3000.0, TranscriptRowHeightCache.height(item)!!, 0.0)
        TranscriptRowHeightCache.forget(item); TranscriptRowHeightCache.store(83.0, item); assertEquals(83.0, TranscriptRowHeightCache.height(item)!!, 0.0); TranscriptRowHeightCache.forget(item)
    }

    // Debug card reveal
    private val msg = TranscriptItem.Message("a"); private val debug = TranscriptItem.DebugInfo(1); private val pending = TranscriptItem.Pending
    @Test fun aTailOnlyDebugInsertIsAReveal() = assertTrue(TranscriptApplyPolicy.isDebugCardReveal(listOf(msg), emptyList(), listOf(msg), listOf(debug)))
    @Test fun aHandoffThatAlsoAddsDebugIsNotAReveal() = assertFalse(TranscriptApplyPolicy.isDebugCardReveal(listOf(msg), emptyList(), listOf(msg, TranscriptItem.Message("b")), listOf(debug)))
    @Test fun removingDebugIsNotAReveal() = assertFalse(TranscriptApplyPolicy.isDebugCardReveal(listOf(msg), listOf(debug), listOf(msg), emptyList()))
    @Test fun anUnchangedTailIsNotAReveal() = assertFalse(TranscriptApplyPolicy.isDebugCardReveal(listOf(msg), listOf(debug), listOf(msg), listOf(debug)))
    @Test fun handoffTailStripsTheDebugCard() { assertEquals(emptyList<TranscriptItem>(), TranscriptApplyPolicy.handoffTail(listOf(debug))); assertEquals(listOf(pending), TranscriptApplyPolicy.handoffTail(listOf(pending, debug))); assertTrue(TranscriptApplyPolicy.hasDebugCard(listOf(debug))); assertFalse(TranscriptApplyPolicy.hasDebugCard(listOf(pending))) }
    @Test fun aHandoffThenDebugInsertIsAReveal() { val after = listOf(msg, TranscriptItem.Message("r")); assertTrue(TranscriptApplyPolicy.isDebugCardReveal(after, emptyList(), after, listOf(debug))) }
    @Test fun aNewDebugCardIsFlooredAboveTheLayoutEstimate() {
        assertNull(TranscriptRowHeightCache.height(TranscriptItem.DebugInfo(7))); TranscriptRowHeightCache.store(900.0, TranscriptItem.DebugInfo(7)); assertEquals(900.0, TranscriptRowHeightCache.height(TranscriptItem.DebugInfo(7))!!, 0.0)
        assertNull(TranscriptRowHeightCache.height(TranscriptItem.DebugInfo(8))); TranscriptRowHeightCache.forget(TranscriptItem.DebugInfo(7)); assertNull(TranscriptRowHeightCache.height(TranscriptItem.DebugInfo(7)))
    }

    // Fresh-start rule placement
    @Test fun noRulesForAProviderThatSeesTheConversation() = assertTrue(FreshStart.indices(listOf("user", "assistant", "user", "assistant"), singleTurn = false).isEmpty())
    @Test fun theFirstQuestionIsNotAFreshStart() { assertTrue(FreshStart.indices(listOf("user"), true).isEmpty()); assertTrue(FreshStart.indices(listOf("user", "assistant"), true).isEmpty()) }
    @Test fun everyFollowUpQuestionIsMarked() = assertEquals(setOf(2, 4), FreshStart.indices(listOf("user", "assistant", "user", "assistant", "user"), true))
    @Test fun consecutiveQuestionsEachStartFresh() = assertEquals(setOf(1, 2), FreshStart.indices(listOf("user", "user", "user"), true))
    @Test fun aLeadingAssistantTurnDoesNotCountAsAQuestion() = assertEquals(setOf(3), FreshStart.indices(listOf("assistant", "user", "assistant", "user"), true))
    @Test fun emptyTranscript() = assertTrue(FreshStart.indices(emptyList(), true).isEmpty())

    // Follow glide
    @Test fun aWrapSizedDeltaStartsAGlide_settleDoesNot() { assertTrue(FollowGlide.shouldStart(23.0, PinMode.THROTTLED)); assertFalse(FollowGlide.shouldStart(23.0, PinMode.IMMEDIATE)); assertFalse(FollowGlide.shouldStart(23.0, PinMode.OFF)); assertFalse(FollowGlide.shouldStart(4.0, PinMode.THROTTLED)) }
    @Test fun midGlideNoiseDoesNotRetarget_aBurstDoes() { assertFalse(FollowGlide.shouldRetarget(23.0)); assertTrue(FollowGlide.shouldRetarget(46.0)) }
    @Test fun aWrapIsMidTravelAtHalfwayAndLandedAtTheDuration() {
        val g = FollowGlide(100.0, 123.0, 1000.0, FollowGlide.FOLLOW_GLIDE_DURATION)
        assertEquals(100.0, g.offset(1000.0), 0.01); val mid = g.offset(1000.06); assertTrue(mid > 108 && mid < 115); assertFalse(g.isFinished(1000.06)); assertEquals(123.0, g.offset(1000.12), 0.01); assertTrue(g.isFinished(1000.12))
    }
}

class AppSettingsTest {
    @Test fun aKeptDefaultPromptMigratesAndAUsersOwnDoesNot() {
        val kept = InMemoryPrefs().also { it.putString(UserDefaultsKey.SYSTEM_PROMPT, "you are a helpful assistant"); it.putInt(UserDefaultsKey.SYSTEM_PROMPT_VERSION, 2) }
        assertEquals(AppSettings.DEFAULT_SYSTEM_PROMPT, AppSettings(kept).systemPrompt); assertEquals(3, kept.getInt(UserDefaultsKey.SYSTEM_PROMPT_VERSION))
        val mine = InMemoryPrefs().also { it.putString(UserDefaultsKey.SYSTEM_PROMPT, "talk like a pirate"); it.putInt(UserDefaultsKey.SYSTEM_PROMPT_VERSION, 3) }
        assertEquals("talk like a pirate", AppSettings(mine).systemPrompt)
        val fresh = AppSettings(InMemoryPrefs()); assertEquals(AppSettings.DEFAULT_SYSTEM_PROMPT, fresh.systemPrompt); assertEquals(AppTintColor.ORANGE, fresh.appTintColor); assertFalse(fresh.developerModeEnabled); assertTrue(fresh.shouldPlayHaptics)
    }
    @Test fun groundingNeedsTheToggleAndAKey() {
        val s = AppSettings(InMemoryPrefs())
        assertNull(s.groundingAPIKey("brave-key")); s.braveGroundingEnabled = true; assertEquals("brave-key", s.groundingAPIKey(" brave-key ")); assertNull(s.groundingAPIKey("  ")); assertNull(s.groundingAPIKey(null))
        s.appFontSize = AppFontSize.LARGE; assertEquals(AppFontSize.LARGE, AppSettings(InMemoryPrefs().also { it.putString(UserDefaultsKey.APP_FONT_SIZE, "large") }).appFontSize)
    }

    /**
     * A CELLULAR PATH IS MOBILE DATA EVEN WHEN IT IS NOT METERED, and a metered
     * path is mobile data even when it is not cellular.
     *
     * iOS found this on a phone whose unlimited plan reported cellular as
     * `expensive=false`, so neither the prompt nor the park fired. Android has
     * the same hole from both sides: a carrier can mark an unlimited plan
     * NOT_METERED, and a personal hotspot is metered without being cellular.
     * Either signal alone misses a real case (ports: 3b1c494).
     */
    @Test fun cellularIsMobileDataEvenWhenNotMetered() {
        assertTrue(NetworkPathObserver.simulating(satisfied = true, cellular = true, metered = false).isMobileData)
        assertTrue(NetworkPathObserver.simulating(satisfied = true, cellular = false, metered = true).isMobileData)
        assertFalse(NetworkPathObserver.simulating(satisfied = true).isMobileData)
        assertTrue(NetworkPathObserver.simulating(satisfied = true, constrained = true).isConstrained)
    }
}
