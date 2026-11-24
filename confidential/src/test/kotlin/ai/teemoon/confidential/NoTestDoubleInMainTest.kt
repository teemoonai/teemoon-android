// 1.1.1 audit integ read, item 7 (Android-only tripwire): a test double never ships in main. InMemorySealedModelStore forgets on exit; in main
// it was one import away from the app's own wiring. It is a Gradle test fixture (src/testFixtures) now.
package ai.teemoon.confidential

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoTestDoubleInMainTest {
    @Test fun noInMemorySealedStoreInMain() {
        val main = File("src/main")
        assertTrue("run from the module dir: ${File(".").absolutePath}", main.isDirectory)
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" && Regex("""class\s+InMemorySealedModelStore""").containsMatchIn(it.readText()) }.toList()
        assertTrue("a test double in main: $offenders", offenders.isEmpty())
    }

    /**
     * 1.1.1 audit (41abe266 read): the trust gate reads no test seam. listedNearScopesProducer and listedNearScopesOverride were two, read
     * before the real listed scopes; their tests now reach the scopes through real records (the live list, edited). A val/var named
     * *Override, *Producer or *ForTest, or any @VisibleForTesting var, in the session or a file the gate reads fails here.
     */
    private val gateFiles = listOf("ConfidentialSession.kt", "ModelCandidates.kt", "NearTrustScope.kt", "AttestationService.kt", "DcapService.kt",
        "TrustVerdict.kt", "KeyCustodyCopy.kt")

    /**
     * Named, with the reason, never by weakening the pattern; [everyKnownSeamIsStillLive] fails on an entry that no longer matches.
     * nearScopeOverride is NOT a production name: it is the read enclave's scope as the send-gate kit's vectors state it (iOS sets trustScope),
     * set by SendGateRuleTest for every vector and by six other suites. Removing it means building the read scope from real reports for each
     * vector; that was left out of the listed-scopes change and is flagged to pm as an open audit item. Remove this entry with it.
     */
    private val knownSeams = mapOf("ConfidentialSession.kt" to setOf("nearScopeOverride"))

    private fun seamsIn(text: String): List<String> {
        val named = Regex("""\b(?:val|var)\s+(\w*(?:Override|Producer|ForTest))\b""").findAll(text).map { it.groupValues[1] }.toList()
        val visible = Regex("""@(?:\w+:)?VisibleForTesting(?:\([^)]*\))?\s+(?:(?:private|internal|protected|public|lateinit|override|open)\s+)*var\s+(\w+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        return named + visible
    }

    private fun gateFile(name: String) = File("src/main/kotlin/ai/teemoon/confidential/$name").also { assertTrue("run from the module dir; no $it", it.isFile) }

    @Test fun noTestSeamInTheGate() {
        val found = gateFiles.flatMap { f -> seamsIn(gateFile(f).readText()).filter { it !in knownSeams[f].orEmpty() }.map { "$f: $it" } }
        assertTrue("test seams in the gate: $found", found.isEmpty())
    }

    @Test fun everyKnownSeamIsStillLive() {
        for ((f, names) in knownSeams) for (n in names) assertTrue("$f: $n no longer matches; remove it from knownSeams", n in seamsIn(gateFile(f).readText()))
    }

    @Test fun thePatternCatchesEachSeamShape() {
        val shapes = listOf("internal var listedNearScopesOverride: List<X>? = null", "internal var listedNearScopesProducer: (() -> X)? = null",
            "val clockForTest = 1", "@VisibleForTesting internal var x = 1", "@get:VisibleForTesting var y = 2")
        for (shape in shapes) assertTrue(shape, seamsIn(shape).isNotEmpty())
        assertTrue(seamsIn("override val state: Int = 1\nval overrideReason = 1").isEmpty())
    }

    /**
     * The one named exception, nearScopeOverride, is only ever SET by tests (pm 2026-11-21; its removal is on the 1.1.2 list:
     * rebuilding every SendGateRuleTest vector's read scope from real reports). No main source in any module assigns it; the
     * declaration's own "= null" initialiser is the only allowed match.
     */
    @Test fun noMainCodeSetsTheNamedException() {
        val roots = listOf("app", "chat", "confidential", "inference", "ondevice", "providers").map { File("../$it/src/main/kotlin") }
        assertTrue("run from the module dir: ${File(".").absolutePath}", roots.all { it.isDirectory })
        val writes = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (assignsNearScopeOverride(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList() }
        assertTrue("main code sets nearScopeOverride: $writes", writes.isEmpty())
    }

    @Test fun theWriteCheckCatchesAnAssignment() {
        assertTrue(assignsNearScopeOverride("        session.nearScopeOverride = scope"))
        assertTrue(assignsNearScopeOverride("nearScopeOverride=null"))
        assertTrue(!assignsNearScopeOverride("    internal var nearScopeOverride: NearTrustScope? = null"))
        assertTrue(!assignsNearScopeOverride("            nearScopeOverride?.let { return it }"))
    }

    private fun assignsNearScopeOverride(line: String): Boolean =
        Regex("""\bnearScopeOverride\s*=(?!=)""").containsMatchIn(line) && !Regex("""\b(?:var|val)\s+nearScopeOverride\b""").containsMatchIn(line)
}
