// ports: teemoonTests/ChatPathTests.swift @ 4d2a41e3
//
// expected.json chat_path (audit 2026-11-06; fixtures/nearai-trust expected.json af313e2b… since 2026-11-08 (sheet_rows; was 27a39e6f…3a18), the small-models log
// actions-5e513dbd72e1-2026-11-04T1933Z.json 4a427d46…641b, up-revision-services.json 55407e8f…01d7, byte-identical to
// iOS's conformance/nearai-trust): the small-models CVM's signed log and running revisions, per host. A Qwen3.8 chat is
// off FLUX's path, so FLUX's recipe LEAKS pages don't judge it; a FLUX request is on it. Anything unresolvable counts
// every LEAKS page.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ChatPathTest {
    private fun text(name: String) = javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()
    private val chatPath = Json.parseToJsonElement(text("expected.json")).jsonObject.getValue("chat_path").jsonObject
    private val cases = chatPath.getValue("cases").jsonArray.map { it.jsonObject }
    private val actions = Json.parseToJsonElement(text(chatPath.getValue("log").jsonObject.getValue("file").jsonPrimitive.content)).jsonArray
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    companion object {
        private val revisions = Json.parseToJsonElement(ChatPathTest::class.java.getResource("/fixtures/nearai-trust/up-revision-services.json")!!.readText())
            .jsonObject.getValue("services").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

        /** A vendored body, as the app reads one: hash-checked against the log's pin. */
        fun body(s: DeployLog.Source): String? = ChatPathTest::class.java.getResource("/fixtures/nearai-trust/compose-files/${s.commit}/${s.file}")?.readBytes()
            ?.takeIf { b -> s.fileSha256.isEmpty() || MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } == s.fileSha256 }
            ?.let { String(it, Charsets.UTF_8) }

        /** Every up revision's names (up-revision-services.json), then the bodies: the walk's service names. */
        val vendored: (DeployLog.Source) -> List<String>? = { s -> revisions["${s.commit}/${s.file}"] ?: body(s)?.let(DeployLog::serviceNames) }

        fun running(actions: JsonArray) = DeployLog.runningServices(actions, vendored)
    }

    @Test fun eachHostGetsAuditsPath() {
        val running = running(actions)
        assertEquals(2, cases.size)
        for (c in cases) {
            val host = c.s("host")
            val want = c.getValue("path").jsonArray.map { it.jsonPrimitive.content }
            val r = ChatPath.resolve(host, running, ::body)
            val hops = (r as? ChatPath.Result.Path)?.hops ?: error("$host: $r")
            assertEquals(host, want, hops.map { it.service })
            for ((hop, key) in hops.zip(listOf("ingress", "proxy"))) {
                assertTrue("$host $key revision", hop.source.fileSha256.startsWith(c.getValue(key).jsonObject.s("revision")))
            }
            for ((hop, e) in hops.drop(2).zip(c.getValue("engines").jsonArray.map { it.jsonObject })) {
                assertTrue("$host engine revision", hop.source.fileSha256.startsWith(e.s("revision")))
            }
        }
    }

    @Test fun theEngineImageIsItsOwnOrItsBuildsBase() {
        val running = running(actions)
        val flux = (ChatPath.resolve("flux2-klein.completions.near.ai", running, ::body) as ChatPath.Result.Path).hops
        val qwen = (ChatPath.resolve("qwen3-8-27b.completions.near.ai", running, ::body) as ChatPath.Result.Path).hops
        assertEquals("FLUX's FROM base", "8ece90ad52faa8b56149f0117227d9009db34513213e35990da468aeb6fe0b75", flux.last().image?.digest)
        assertTrue(qwen.last().image?.digest?.startsWith("febfb971") == true)
        assertTrue("the proxy image", qwen[1].image?.digest?.startsWith("0be4ee8f") == true)
    }

    /** The rollup judges a chat by its own path: FLUX's recipe pages count for a FLUX request, not a Qwen chat. */
    @Test fun aSiblingsLeakDoesNotJudgeThisChat() {
        val running = running(actions)
        val flux = ChatPath.resolve("flux2-klein.completions.near.ai", running, ::body)
        val qwen = ChatPath.resolve("qwen3-8-27b.completions.near.ai", running, ::body)
        val pages = listOf("320afade84f1606bda2c473ec0d09d3b6cd745038133bf353252f2683cc24eba",
            "dfac289866183c8fdd21606a0c431f7a5e8b240f2aeeb939461b823aec9541ac",
            "68b80451c141f21eab9f80302e60f48857ddf2d6304b485327c23abe8c9423b2")
        val file = "prod/small-models.yaml"
        val key = "$MODEL_COMPOSE_REPO/$file"
        fun page(sha: String) = "manifests/nearai/cvm-compose-files/prod/small-models/sha256-$sha.md"
        val classes = pages.take(2).associate { page(it) to "leaks" } + (page(pages[2]) to "private")
        val exposure = pages.take(2).associate { page(it) to AuditIndex.Exposure("the FLUX service logs every prompt.", scopeKind = "recipe", services = listOf("model-sg-flux2-klein-4b-tp1")) }
        val audit = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = emptyMap(), manifests = mapOf(key to pages), measured = emptyList(),
            verdictClass = classes, exposure = exposure))
        val newest = audit.manifestAuditURL(file, pages[0])!!
        fun classesFor(path: ChatPath.Result) = ModelNodeAuditPath.onPath(listOf(newest), path, emptyList(), audit).map { audit.verdictClass(it) }
        assertFalse("a Qwen3.8 chat isn't judged by FLUX's leak", AuditVerdictClass.LEAKS in classesFor(qwen))
        assertTrue("its own engine's revision still judges it", AuditVerdictClass.PRIVATE_REVIEW in classesFor(qwen))
        assertEquals("320afade and dfac2898 both count for FLUX", 2, classesFor(flux).count { it == AuditVerdictClass.LEAKS })
        val unsure = ModelNodeAuditPath.onPath(emptyList(), ChatPath.Result.Ambiguous("no server_name"), running.mapNotNull(ChatPath::sourceOf), audit)
        assertEquals("ambiguous: every LEAKS page counts", 2, unsure.count { audit.verdictClass(it) == AuditVerdictClass.LEAKS })
    }

    @Test fun anUnknownHostOrAnOverriddenBackendIsAmbiguous() {
        val running = running(actions)
        assertTrue("an unknown host resolved", ChatPath.resolve("nobody.completions.near.ai", running, ::body) is ChatPath.Result.Ambiguous)
        assertTrue("unread files resolved", ChatPath.resolve("qwen3-8-27b.completions.near.ai", running) { null } is ChatPath.Result.Ambiguous)
        val override = """
            services:
              proxy:
                environment:
                  - VLLM_BACKEND_URLS=${'$'}{VLLM_BACKEND_URLS}
        """.trimIndent()
        assertNull("an override the log can't show", ChatPath.Compose(override).services["proxy"]?.backendURLs)
        val defaulted = override.replace("\${VLLM_BACKEND_URLS}", "\${VLLM_BACKEND_URLS:-http://e1:8000,http://e2:8000}")
        assertEquals(listOf("http://e1:8000", "http://e2:8000"), ChatPath.Compose(defaulted).services["proxy"]?.backendURLs)
    }

    /** A backend URL's host is the compose name exactly — underscores and case kept, as iOS's URLComponents gives it. */
    @Test fun aBackendNameKeepsItsUnderscoresAndCase() {
        assertEquals("model_sg_x", ChatPath.serviceName("http://model_sg_x:8000"))
        assertEquals("Model.Engine_2", ChatPath.serviceName("http://Model.Engine_2:9000"))
        assertNull(ChatPath.serviceName("ftp://x:1"))
    }

    @Test fun serverNamesMatchExactlyOrByRegex() {
        val conf = """
            server {
                listen 443 ssl http2;
                server_name a.completions.near.ai
                            ~^a-i\d+\.completions(-stg)?\.near\.ai$;
                location ^~ /metrics { return 404; }
                location / { set ${'$'}up http://proxy-a:8000; proxy_pass ${'$'}up; }
            }
        """.trimIndent()
        assertEquals("http://proxy-a:8000", ChatPath.Nginx.proxyTarget(conf, "a.completions.near.ai"))
        assertEquals("http://proxy-a:8000", ChatPath.Nginx.proxyTarget(conf, "a-i3.completions-stg.near.ai"))
        assertNull(ChatPath.Nginx.proxyTarget(conf, "b.completions.near.ai"))
    }
}
