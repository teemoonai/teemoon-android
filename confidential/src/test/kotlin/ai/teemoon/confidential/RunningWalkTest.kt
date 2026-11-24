// ports: teemoonTests/ActionLogBindingTests.swift @ 28c43414 (the (c) walk and liveness tests: theRunningPairsAreAuditsUnderTheWholeFileRule,
//        theCapNeverChangesWhichPairsAreListed, aWholeFileUpRetiresServicesItsRevisionDropped, aNewerEntryDecidesBeforeAnOlderWholeFile,
//        aWholeFileDownTakesTheFile, anUnreadableWholeFileStillSupersedesItsFile, onlyTheNewestWholeFileOfEachFileIsFetched,
//        aDownIsHashCheckedAgainstAnUpOfTheSameRevision)
//        and @ 37784653 (rule 3: anyUpRetiresServicesItsRevisionLacks, everyUpRevisionIsFetchedOnce),
//        @ 4cee6e7d (aMiddleRevisionThatDroppedAServiceRetiresIt), @ 4d2a41e3 (per-service liveness:
//        livenessIsAuditsPerService, livenessMutations, aNameTheLaterFileAlsoDefinesIsNotAnOrphanAndDownsNeverCount,
//        theAnsweringProxyRefutesAnEviction)
//
// Trust scope (c), audit's canonical rule A (2026-11-06) over fixtures/nearai-trust and rule 3, over fixtures/nearai-trust
// (expected.json sha256 6e8f1e1e… since iOS ce5cada8, prose only; af313e2b… since 2026-11-08, was 27a39e6f…3a18; up-revision-services.json 55407e8f…01d7, and the whole-file bodies under
// compose-files/<commit>/<file>, byte-identical to iOS's conformance/nearai-trust).
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RunningWalkTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()).jsonObject
    private val report = resource("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
    private val expected = resource("expected.json")
    private val actions = report.getValue("compose_manager_attestation").jsonObject.getValue("actions").jsonArray
    private val pairs = expected.getValue("running_pairs").jsonObject.getValue("pairs").jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    private val revisions = resource("up-revision-services.json").getValue("services").jsonObject
        .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

    /**
     * Every up revision's service names (up-revision-services.json, keyed commit/file), then the whole-file bodies as
     * vendored; a body that doesn't hash to the pinned value is unreadable.
     */
    private val vendored: (DeployLog.Source) -> List<String>? = { src ->
        revisions["${src.commit}/${src.file}"] ?: javaClass.getResource("/fixtures/nearai-trust/compose-files/${src.commit}/${src.file}")?.readBytes()
            ?.takeIf { body -> src.fileSha256.isEmpty() || MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) } == src.fileSha256 }
            ?.let { DeployLog.serviceNames(String(it, Charsets.UTF_8)) }
    }

    private fun log(vararg entries: Map<String, Any>): JsonArray = JsonArray(entries.map { e ->
        JsonObject(e.mapValues { (_, v) -> if (v is List<*>) JsonArray(v.map { JsonPrimitive(it as String) }) else JsonPrimitive(v as String) as JsonElement })
    })
    private fun RunningService.at() = "$service@$commit"

    @Test fun theRunningPairsAreAuditsUnderTheWholeFileRule() {
        val table = DeployLog.runningServices(actions, vendored)
        val got = ComposeFiles.pairs(table, emptyMap())
        assertEquals(4, pairs.size)
        assertEquals(pairs.map { "${it.s("commit")} ${it.s("file")}" }, got.map { "${it.commit} ${it.file}" })
        for (p in pairs) {
            val rows = table.filter { it.commit == p.s("commit") && it.file == p.s("file") }
            assertEquals(p.s("file"), p.getValue("services").jsonArray.map { it.jsonPrimitive.content }.toSet(), rows.map { it.service }.toSet())
            assertTrue(rows.all { it.fileSHA256 == p.s("file_sha256") })
        }
        val dropped = listOf("2781fa66", "1121ee56", "0e55b0e7", "0a51138e")
        assertFalse("mutation #5: 1121ee56 is absent", got.any { g -> dropped.any { g.commit.startsWith(it) } })
    }

    /** Mutation #9: an up of the canary whose revision lacks r4, newer than the last up naming r4, retires r4 (rule 3). */
    @Test fun anyUpRetiresServicesItsRevisionLacks() {
        val canary = "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8-Q8Canary.yaml"
        val i = actions.indexOfLast { val a = it.jsonObject
            a["file"]?.jsonPrimitive?.content == canary && (a["services"] as? JsonArray)?.map { s -> s.jsonPrimitive.content } == listOf("model-sg-glm53-w4afp8-tp2-r4") }
        assertTrue(i >= 0)
        val mutated = JsonArray(actions.take(i + 1) + log(mapOf("action" to "compose_up", "file" to canary, "commit" to "mut9", "file_sha256" to "m9",
            "services" to listOf("proxy-glm53"), "timestamp" to "2026-10-28T01:30:00Z")) + actions.drop(i + 1))
        val table = DeployLog.runningServices(mutated) { if (it.commit == "mut9") listOf("proxy-glm53") else vendored(it) }
        assertTrue(table.none { it.service == "model-sg-glm53-w4afp8-tp2-r4" })
        assertTrue("listing it is red", ComposeFiles.pairs(table, emptyMap()).none { it.file == canary })
        assertEquals("rule 3 leaves the fixture's four pairs", 4, ComposeFiles.pairs(DeployLog.runningServices(actions, vendored), emptyMap()).size)
    }

    /** Mutation #10: a middle revision drops S, so S is retired there; neither the older revision nor another file claims it. */
    @Test fun aMiddleRevisionThatDroppedAServiceRetiresIt() {
        val actions = log(
            mapOf("action" to "compose_up", "file" to "g.yaml", "commit" to "g0", "file_sha256" to "sg", "services" to listOf("S")),
            mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "r1", "file_sha256" to "s1", "services" to listOf("S", "X")),
            mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "r2", "file_sha256" to "s2", "services" to listOf("X")),
            mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "r3", "file_sha256" to "s3", "services" to listOf("Y")))
        val revisions = mapOf("g0" to listOf("S"), "r1" to listOf("S", "X"), "r2" to listOf("X", "Y"), "r3" to listOf("S", "X", "Y"))
        assertEquals(listOf("Y@r3", "X@r2"), DeployLog.runningServices(actions) { revisions[it.commit] }.map { it.at() })
    }

    @Test fun everyUpRevisionIsFetchedOnce() {
        val sources = DeployLog.upRevisionSources(actions)
        assertEquals(47, sources.size); assertEquals(47, sources.toSet().size)
    }

    /** Mutation #6: a cap on fetches changes only how many pairs are marked fetched, never the set. */
    @Test fun theCapNeverChangesWhichPairsAreListed() {
        val table = DeployLog.runningServices(actions, vendored)
        val all = ComposeFiles.pairs(table, emptyMap()).map { it.key }
        assertEquals(all.take(ComposeFiles.CAP), ComposeFiles.toFetch(table))
        assertTrue(ComposeFiles.pairs(table, emptyMap()).none { it.check == ComposeFileCheck.NotFetched } == (all.size <= ComposeFiles.CAP))
    }

    /** Mutation #5: a whole-file up of F, newer than a scoped up of F that named S, where the new revision has no S. */
    @Test fun aWholeFileUpRetiresServicesItsRevisionDropped() {
        val a = log(mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "old", "file_sha256" to "s1", "services" to listOf("r1", "r2"), "timestamp" to "1"),
                    mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "new", "file_sha256" to "s2", "timestamp" to "2"))
        val rows = DeployLog.runningServices(a) { if (it.commit == "new") listOf("dp0", "dp1") else null }
        assertEquals(listOf("dp0", "dp1"), rows.map { it.service })
        assertEquals(listOf("new"), ComposeFiles.pairs(rows, emptyMap()).map { it.commit })
    }

    @Test fun aNewerEntryDecidesBeforeAnOlderWholeFile() {
        val a = log(mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "c1", "file_sha256" to "s1", "timestamp" to "1"),
                    mapOf("action" to "compose_down", "file" to "f.yaml", "commit" to "c1", "file_sha256" to "s1", "services" to listOf("y"), "timestamp" to "2"),
                    mapOf("action" to "compose_up", "file" to "g.yaml", "commit" to "c2", "file_sha256" to "s2", "services" to listOf("x"), "timestamp" to "3"))
        assertEquals(listOf("x@c2", "z@c1"), DeployLog.runningServices(a) { listOf("x", "y", "z") }.map { it.at() })
    }

    @Test fun aWholeFileDownTakesTheFile() {
        val a = listOf(mapOf("action" to "compose_up", "file" to "a.yaml", "commit" to "c1", "file_sha256" to "s1", "services" to listOf("x", "y"), "timestamp" to "1"),
                       mapOf("action" to "compose_up", "file" to "b.yaml", "commit" to "c2", "file_sha256" to "s2", "services" to listOf("z"), "timestamp" to "2"),
                       mapOf("action" to "compose_down", "file" to "a.yaml", "commit" to "c1", "file_sha256" to "s1", "timestamp" to "3"))
        assertEquals(listOf("z"), DeployLog.runningServices(log(*a.toTypedArray())) { listOf("x", "y") }.map { it.service })
        val back = a + mapOf("action" to "compose_up", "file" to "a.yaml", "commit" to "c3", "file_sha256" to "s3", "services" to listOf("x"), "timestamp" to "4")
        assertEquals(listOf("x", "z"), DeployLog.runningServices(log(*back.toTypedArray())) { listOf("x", "y") }.map { it.service })
    }

    /** A whole file that can't be read is one row with services unknown; it still retires its file's older entries. */
    @Test fun anUnreadableWholeFileStillSupersedesItsFile() {
        val a = log(mapOf("action" to "compose_up", "file" to "g.yaml", "commit" to "c0", "file_sha256" to "s0", "services" to listOf("other"), "timestamp" to "0"),
                    mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "c0", "file_sha256" to "s0f", "services" to listOf("old"), "timestamp" to "1"),
                    mapOf("action" to "compose_up", "file" to "f.yaml", "commit" to "c1", "file_sha256" to "s1", "timestamp" to "2"),
                    mapOf("action" to "compose_up", "file" to "h.yaml", "commit" to "c2", "file_sha256" to "s2", "services" to listOf("x"), "timestamp" to "3"))
        assertEquals(listOf("x@c2", "@c1", "other@c0"), DeployLog.runningServices(a) { null }.map { it.at() })
    }

    /** rotate-token's file has no entry since the newest whole-file up of another file: undecidable; the other three run. */
    /**
     * audit 2026-11-06, per service: rotate-token is undecidable, evicted (or not) by entry 76, the 2026-09-30T19:49:51Z
     * TP4-W4AFP8 up whose revision lacks the name. Every other service runs, so the three other pairs are running and
     * rotate-token's pair is the one leftover.
     */
    @Test fun livenessIsAuditsPerService() {
        val running = DeployLog.runningServices(actions, vendored)
        val undecidable = DeployLog.undecidableServices(actions, running, vendored)
        assertEquals(mapOf("rotate-token" to 76), undecidable)
        for (p in pairs) {
            val services = running.filter { it.file == p.s("file") }.map { it.service }
            assertEquals(p.s("file"), p.s("liveness") == "undecidable", services.all { it in undecidable })
        }
    }

    /**
     * #7: rotate-token's up moved after every other up → running. #8: an up of another file after everything, whose
     * revision defines only "late" → every service but that one undecidable.
     */
    @Test fun livenessMutations() {
        val rotate = "prod/rotate-compose-manager-token.yaml"
        val list = actions.toMutableList()
        val i = list.indexOfFirst { (it as JsonObject)["file"]?.jsonPrimitive?.content == rotate }
        assertTrue(i >= 0)
        val moved = JsonArray(list.toMutableList().also { it.add(it.removeAt(i)) })
        assertFalse("rotate-token" in DeployLog.undecidableServices(moved, DeployLog.runningServices(moved, vendored), vendored))

        val late = JsonArray(list + log(mapOf("action" to "compose_up", "file" to "prod/late.yaml", "commit" to "c", "file_sha256" to "s", "timestamp" to "2026-10-30T00:00:00Z")))
        val names: (DeployLog.Source) -> List<String>? = { if (it.file == "prod/late.yaml") listOf("late") else vendored(it) }
        val running = DeployLog.runningServices(late, names)
        assertEquals(setOf("late"), running.map { it.service }.toSet() - DeployLog.undecidableServices(late, running, names).keys)
    }

    @Test fun aNameTheLaterFileAlsoDefinesIsNotAnOrphanAndDownsNeverCount() {
        val a = log(mapOf("action" to "compose_up", "file" to "a.yaml", "commit" to "c1", "file_sha256" to "s1", "services" to listOf("x", "shared")),
                    mapOf("action" to "compose_down", "file" to "b.yaml", "commit" to "c2"),
                    mapOf("action" to "compose_up", "file" to "c.yaml", "commit" to "c3", "file_sha256" to "s3", "services" to listOf("y")))
        val names = mapOf("c1" to listOf("x", "shared"), "c3" to listOf("y", "shared"))
        val running = DeployLog.runningServices(a) { names[it.commit] }
        assertEquals("x: c.yaml's up lacks it; shared: c.yaml defines it; the down never counts",
            mapOf("x" to 2), DeployLog.undecidableServices(a, running, servicesIn = { names[it.commit] }))
        val unread = DeployLog.undecidableServices(a, running, servicesIn = { if (it.commit == "c3") null else names[it.commit] })
        assertEquals("an unreadable later revision counts as evicting", listOf("shared", "x"), unread.keys.sorted())
    }

    /**
     * The proxy that answered this report is alive: a later up of another file lacking its name didn't evict its file,
     * so that file's ups count against nothing in the proxy's file (gpu13, sage-pull).
     */
    @Test fun theAnsweringProxyRefutesAnEviction() {
        val small = Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/actions-5e513dbd72e1-2026-11-04T1933Z.json")!!.readText()).jsonArray
        val running = DeployLog.runningServices(small, vendored)
        val unrefuted = DeployLog.undecidableServices(small, running, vendored)
        assertTrue("precondition: sage-pull's later whole-file up reads as evicting the serving stack", unrefuted.size > 20)
        val alive = running.filter { s -> ChatPath.sourceOf(s)?.let(ChatPathTest::body)?.let { ChatPath.Compose(it).services[s.service]?.modelName == "Qwen/Qwen3.8-27B" } == true }
        assertEquals("MODEL_NAME matches exactly, not by prefix", listOf("proxy-qwen38-27b"), alive.map { it.service })
        assertEquals(emptyMap<String, Int>(), DeployLog.undecidableServices(small, running, vendored, alive))
    }

    @Test fun aDownIsHashCheckedAgainstAnUpOfTheSameRevision() {
        val a = log(mapOf("action" to "compose_up", "file" to "p.yaml", "commit" to "c1", "file_sha256" to "pin", "services" to listOf("x")),
                    mapOf("action" to "compose_down", "file" to "p.yaml", "commit" to "c1"),
                    mapOf("action" to "compose_down", "file" to "q.yaml", "commit" to "c9"))
        val sources = DeployLog.wholeFileSources(a)
        assertEquals("pin", sources.first { it.file == "p.yaml" }.fileSha256)
        assertEquals("no up pins it: read unhashed", "", sources.first { it.file == "q.yaml" }.fileSha256)
        val seen = ArrayList<DeployLog.Source>()
        DeployLog.runningServices(a) { seen += it; listOf("x") }
        assertTrue("the walk asks for exactly the prefetched sources", seen.all { it in sources })
    }

    @Test fun onlyTheNewestWholeFileOfEachFileIsFetched() {
        val sources = DeployLog.wholeFileSources(actions)
        assertEquals(sources.size, sources.map { it.file }.toSet().size)
        assertTrue(sources.any { it.commit.startsWith("cd948600") && it.file == "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8.yaml" })
        assertFalse("an older whole file of sage.yaml is dead", sources.any { it.commit.startsWith("2781fa66") && it.file == "prod/sage.yaml" })
    }
}
