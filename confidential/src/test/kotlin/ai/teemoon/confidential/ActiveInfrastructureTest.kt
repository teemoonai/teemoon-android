// ports: teemoonTests/ActiveInfrastructureTests.swift @ 71f83846
//
// The sheet's "running now, per the action log" list shows active infrastructure only (the maintainer, 2026-11-08): the rows
// the log vouches for, plus this chat's path. Vectors: fixtures/nearai-trust expected.json sheet_rows (6e8f1e1e… since iOS ce5cada8, prose only; af313e2b… before,
// byte-identical to iOS's conformance/nearai-trust). Before this, the 2026-11-08 glm-5-3-flash sheet listed all 20
// never-closed services, among them GLM 5.2's 2 Oct proxy and engines, the 21 Oct canary set and a 23 Sep TP4 pair, each
// "can't tell whether this is still running", beside the four live engines. When the path can't be derived the whole list
// stays: "can't tell" is then the state. Compose revisions the kit lacks are in nearai_actionlog_revisions_20261108.json
// (iOS's test fixture, f25fd4a6…), hash-checked against the log.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ActiveInfrastructureTest {
    private fun text(name: String) = javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()
    private fun JsonObject.s(k: String) = (get(k) as? JsonPrimitive)?.content ?: ""

    private class Revisions(val services: Map<String, List<String>>, val bodies: Map<String, String>)
    private fun revisions(): Revisions {
        val o = Json.parseToJsonElement(text("nearai_actionlog_revisions_20261108.json")).jsonObject
        return Revisions(o.getValue("services").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } },
            o.getValue("bodies").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content })
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun body(s: DeployLog.Source, extra: Revisions): String? {
        val t = ChatPathTest.body(s) ?: extra.bodies["${s.commit}/${s.file}"] ?: return null
        return if (s.fileSha256.isEmpty() || hex(t.toByteArray()) == s.fileSha256) t else null
    }

    private class Case(val name: String, val actions: JsonArray, val model: String, val host: String, val rows: List<JsonObject>, val path: List<String>?)

    private fun cases(): List<Case> {
        val sheet = Json.parseToJsonElement(text("expected.json")).jsonObject.getValue("sheet_rows").jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        return sheet.map { c ->
            val file = c.getValue("log").jsonObject.s("file")
            val json = Json.parseToJsonElement(text(file))
            val report = json as? JsonObject
            val actions = (report?.get("compose_manager_attestation") as? JsonObject)?.get("actions")?.jsonArray ?: json.jsonArray
            // The small-models log is a bare log: the model is the one its Qwen3.8 host serves.
            val model = report?.s("model_name")?.takeIf { it.isNotEmpty() } ?: if ("5e513dbd72e1" in file) "Qwen/Qwen3.8-27B" else "z-ai/glm-5.3-flash"
            Case(c.s("name"), actions, model, c.s("host"),
                ((c["rows"] as? JsonArray).orEmpty() + (c["dropped"] as? JsonArray).orEmpty()).map { it.jsonObject },
                (c["path"] as? JsonArray)?.map { it.jsonPrimitive.content })
        }
    }

    /** The scope as the session builds it from a bound log; [check] is each attributed pair's fetch outcome. */
    private fun scope(c: Case, extra: Revisions, host: String? = null, check: (Pair<String, String>) -> ComposeFileFetch? = { null }): NearTrustScope {
        val sources = (DeployLog.wholeFileSources(c.actions) + DeployLog.upRevisionSources(c.actions)).distinct()
        val names = sources.associateWith { s -> extra.services["${s.commit}/${s.file}"] ?: ChatPathTest.vendored(s) }
        val bodies = sources.mapNotNull { s -> body(s, extra)?.let { s to it } }.toMap().toMutableMap()
        val walked = DeployLog.runningServices(c.actions) { names[it] }
        for (r in walked) ChatPath.sourceOf(r)?.let { s -> if (s !in bodies) body(s, extra)?.let { bodies[s] = it } }
        val (running, path) = NearTrustScope.runningSection(c.actions, { names[it] }, bodies, c.model, host ?: c.host)
        val fetched = running.mapNotNull { r -> val k = (r.commit ?: return@mapNotNull null) to (r.file ?: return@mapNotNull null); check(k)?.let { k to it } }.toMap()
        return NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, running, LauncherIndex.NotChecked,
            ComposeFiles.pairs(running, fetched), sources, path)
    }

    private data class Row(val service: String, val commit: String, val file: String, val sha: String)
    private fun row(r: RunningService) = Row(r.service, r.commit.orEmpty(), r.file.orEmpty(), r.fileSHA256.orEmpty())

    /** sheet_rows.cases: shown rows by (service, commit, file, file_sha256); "none" rows are dropped. */
    @Test fun everySheetCaseShowsItsRows() {
        val cases = cases(); val extra = revisions()
        assertEquals(5, cases.size)
        for (c in cases) {
            val scope = scope(c, extra)
            val want = c.rows.map { Row(it.s("service"), it.s("commit"), it.s("file"), it.s("file_sha256")) }
            val shown = want.zip(c.rows.map { it.s("shown_by") }).filter { it.second != "none" }.map { it.first }.toSet()
            assertEquals("${c.name}: the log's running table", want.toSet(), scope.runningServices.map(::row).toSet())
            assertEquals("${c.name}: the sheet", shown, scope.activeRunning.map(::row).toSet())
            if (c.path != null) {
                val hops = (scope.chatPath as? ChatPath.Result.Path)?.hops ?: error("${c.name}: ${scope.chatPath}")
                assertEquals(c.name, c.path, hops.map { it.service })
                // Each hop wears its own attributing revision, never the proxy's.
                for (hop in hops) assertTrue("${c.name} ${hop.service}", scope.runningServices.any { it.service == hop.service && ChatPath.sourceOf(it) == hop.source })
            } else {
                assertTrue("${c.name} resolved: ${scope.chatPath}", scope.chatPath is ChatPath.Result.Ambiguous)
                assertEquals("${c.name}: ambiguous keeps the full list", scope.runningServices, scope.activeRunning)
            }
        }
    }

    private fun glm1108() = cases().first { it.name.startsWith("the other glm-5-3-flash CVM") }

    @Test fun todaysGlmDropsTheDeadSets() {
        val scope = scope(glm1108(), revisions())
        assertEquals(20, scope.runningServices.size)
        assertEquals(8, scope.activeRunning.size)
        val shown = scope.activeRunning.map { it.service }.toSet()
        for (dead in listOf("proxy-glm52", "model-sg-glm52-w4a-tp4-r1", "dcgm-glm52", "model-sg-glm53-w4afp8-tp4-r1",
                "glm53-ghost-aggregator", "glm53-soak-relay", "otelcol-contrib")) assertFalse(dead, dead in shown)
        val engine = scope.activeRunning.firstOrNull { it.service == "model-sg-glm53-w4afp8-tp2-r1" }
        assertNotNull(engine)
        assertTrue("the engine's own revision, not the proxy's 5a5119f7", engine!!.commit!!.startsWith("7ee4a4c7"))
        assertTrue("liveness unchanged: the path shows it, the log still can't vouch", engine.undecidable)
    }

    /** The sheet's section draws the active list (iOS RecipeCardView reads activeRunning). */
    @Test fun theRunningNowSectionDrawsTheActiveRows() {
        val scope = scope(glm1108(), revisions())
        assertEquals(8, TrustScopeText.runningRows(scope).size)
    }

    @Test fun anUnreadRunningRevisionKeepsTheWholeList() {
        val c = glm1108(); val all = revisions()
        val extra = Revisions(all.services, all.bodies.filterKeys { "GLM-5.2" !in it })
        val scope = scope(c, extra)
        assertTrue("an unread revision resolved: ${scope.chatPath}", scope.chatPath is ChatPath.Result.Ambiguous)
        assertEquals(scope.runningServices, scope.activeRunning)
        val unbound = scope(c, all).copy(chatPath = null)
        assertEquals("an unbound log has no path", unbound.runningServices, unbound.activeRunning)
    }

    /** Display only: every attributed pair is still hash-checked, and a stale one that fails still breaks. */
    @Test fun aStalePairsMismatchStillBreaksWhenItsRowIsHidden() {
        val scope = scope(glm1108(), revisions()) { (_, file) -> if (file.startsWith("prod/GLM-5.2")) ComposeFileFetch.Digest("0".repeat(64)) else null }
        assertTrue("precondition: GLM 5.2's pair is within the cap", scope.composeFiles.any { it.check is ComposeFileCheck.Mismatch })
        assertFalse(scope.activeRunning.any { it.file?.startsWith("prod/GLM-5.2") == true })
        assertEquals(NearTrustScope.RECIPE_UNVERIFIED, scope.hardBreakReason)
        assertEquals(NearTrustScope.Severity.HARD, scope.severity)
    }

    // routers (rule iii)

    private fun compose(proxyBackends: String, extra: String = "") = """
services:
  nginx:
    image: nginx@sha256:${"a".repeat(64)}
    ports:
      - "443:443"
    configs:
      - source: nginx_conf
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:${"b".repeat(64)}
    environment:
      - MODEL_NAME=m
      - VLLM_BACKEND_URLS=$proxyBackends
$extra
configs:
  nginx_conf:
    content: |
      server {
          listen 443 ssl;
          server_name m.completions.near.ai;
          location / { proxy_pass http://proxy:8000; }
      }
"""

    private fun router(name: String, next: String) = """  $name:
    image: nearaidev/vllm-proxy-rs@sha256:${"c".repeat(64)}
    environment:
      VLLM_BASE_URL: $next"""

    private val engineBlock = """  engine:
    image: lmsysorg/sglang@sha256:${"d".repeat(64)}"""

    private fun resolve(text: String): ChatPath.Result {
        val running = ChatPath.Compose(text).services.keys.sorted().map { RunningService(it, "prod/m.yaml", "", "c1", null, null) }
        return ChatPath.resolve("m.completions.near.ai", running) { text }
    }

    @Test fun routersAreFollowedToTheEngine() {
        val one = compose("http://filter:8000", router("filter", "http://engine:8000") + "\n" + engineBlock)
        assertEquals(listOf("nginx", "proxy", "filter", "engine"), (resolve(one) as? ChatPath.Result.Path)?.hops?.map { it.service })

        val deepest = compose("http://r1:8000", listOf(router("r1", "http://r2:8000"), router("r2", "http://engine:8000"), engineBlock).joinToString("\n"))
        assertEquals("four hops", listOf("nginx", "proxy", "r1", "r2", "engine"), (resolve(deepest) as? ChatPath.Result.Path)?.hops?.map { it.service })

        val tooDeep = compose("http://r1:8000", listOf(router("r1", "http://r2:8000"), router("r2", "http://r3:8000"),
            router("r3", "http://engine:8000"), engineBlock).joinToString("\n"))
        assertTrue("five hops resolved", resolve(tooDeep) is ChatPath.Result.Ambiguous)

        val overridden = compose("http://filter:8000", router("filter", "\${UPSTREAM}") + "\n" + engineBlock)
        assertTrue("an unrecorded router override resolved", resolve(overridden) is ChatPath.Result.Ambiguous)

        val undefined = compose("http://filter:8000", router("filter", "http://ghost:8000"))
        assertTrue("an undefined upstream resolved", resolve(undefined) is ChatPath.Result.Ambiguous)
    }
}
