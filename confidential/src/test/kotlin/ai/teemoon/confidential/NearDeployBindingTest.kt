package ai.teemoon.confidential

// android-only until iOS's sha: audit's live glm-5-3-flash report (gpu04, 2026-10-30T22:41Z) and its expected.json,
// the same bytes iOS pins. Every intermediate value is held to expected.json, and audit's mutations each break the
// check they name.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearDeployBindingTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()).jsonObject
    private val report = resource("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
    private val expected = resource("expected.json")
    private val nonce = expected.getValue("request_nonce_sent").jsonPrimitive.content
    private val cm = report.getValue("compose_manager_attestation").jsonObject
    private val nodeQuote = report.getValue("intel_quote").jsonPrimitive.content
    private val tcb = report.getValue("info").jsonObject.getValue("tcb_info").jsonObject
    private val osImageHash = report.getValue("info").jsonObject["os_image_hash"]?.jsonPrimitive?.content
    private fun exp(vararg path: String) = path.fold(expected as kotlinx.serialization.json.JsonElement) { e, k -> e.jsonObject.getValue(k) }.jsonPrimitive.content

    // (a)

    @Test fun theCanonicalActionsAreTheBytesTheQuoteSigns() {
        val canonical = CanonicalJson.encode(cm.getValue("actions")).toByteArray(Charsets.UTF_8)
        assertEquals(exp("canonical_actions_sha256_preimage_len").toInt(), canonical.size)
        assertTrue(String(canonical).startsWith(exp("canonical_actions_preimage_first200")))
        assertEquals(exp("actions_hash_recomputed"), java.security.MessageDigest.getInstance("SHA-256").digest(canonical).toHex())
        assertEquals(true, ComposeManagerBinding.actionsHashFieldMatches(cm))
    }

    @Test fun theLiveDeployLogIsBoundToTheModelsHardware() {
        val r = ComposeManagerBinding.check(cm, nonce, nodeQuote, dcapPassed = true)
        assertTrue("$r", r is BindingCheck.Verified)
    }

    @Test fun oneFlippedByteInAnActionFails() {
        val actions = cm.getValue("actions").jsonArray
        val first = actions[0].jsonObject
        val bent = JsonObject(first + ("timestamp" to JsonPrimitive(first.getValue("timestamp").jsonPrimitive.content.replaceFirst('2', '3'))))
        val r = ComposeManagerBinding.check(JsonObject(cm + ("actions" to JsonArray(listOf(bent) + actions.drop(1)))), nonce, nodeQuote, true)
        assertTrue("$r", r is BindingCheck.Failed)
    }

    @Test fun anotherNonceFails() {
        val r = ComposeManagerBinding.check(cm, nonce.reversed(), nodeQuote, true)
        assertTrue("$r", r is BindingCheck.Failed && r.reason.contains("nonce"))
    }

    @Test fun aFailedDcapVerdictFailsAndAPendingOneDoesNotPass() {
        assertTrue(ComposeManagerBinding.check(cm, nonce, nodeQuote, false) is BindingCheck.Failed)
        assertTrue(ComposeManagerBinding.check(cm, nonce, nodeQuote, null) is BindingCheck.Inconclusive)
    }

    @Test fun noManagerQuoteIsInconclusiveNotAFailure() {
        assertEquals(BindingCheck.Inconclusive("deploy log not bound"), ComposeManagerBinding.check(null, nonce, nodeQuote, true))
        assertEquals(BindingCheck.Inconclusive("deploy log not bound"), ComposeManagerBinding.check(JsonObject(cm - "quote"), nonce, nodeQuote, true))
    }

    // (b)

    @Test fun theBootLogReplaysToTheQuotesRtmr3() {
        val entries = Rtmr3Replay.entries(tcb.getValue("event_log").jsonArray)!!
        assertEquals(exp("event_log", "entries").toInt(), entries.size)
        assertEquals(exp("event_log", "imr3_entries").toInt(), entries.count { it.imr == 3 })
        assertEquals(exp("rtmr3_replay", "result"), Rtmr3Replay.replay(entries).toHex())
        assertEquals(exp("model_node_quote", "rtmr3"), Rtmr3Replay.replay(entries).toHex())
        assertTrue(entries.filter { it.imr == 3 }.all(Rtmr3Replay::digestRecomputes))
        val r = Rtmr3Replay.check(tcb, nodeQuote, osImageHash)
        assertTrue("$r", r is BindingCheck.Verified)
    }

    @Test fun twoReorderedEntriesFail() {
        val log = tcb.getValue("event_log").jsonArray
        val idx = log.indices.filter { log[it].jsonObject.getValue("imr").jsonPrimitive.content == "3" }
        val swapped = log.toMutableList().also { val t = it[idx[0]]; it[idx[0]] = it[idx[1]]; it[idx[1]] = t }
        val r = Rtmr3Replay.check(JsonObject(tcb + ("event_log" to JsonArray(swapped))), nodeQuote, osImageHash)
        assertTrue("$r", r is BindingCheck.Failed)
    }

    @Test fun anotherLaunchConfigFails() {
        val r = Rtmr3Replay.check(JsonObject(tcb + ("app_compose" to JsonPrimitive(tcb.getValue("app_compose").jsonPrimitive.content + " "))), nodeQuote, osImageHash)
        assertTrue("$r", r is BindingCheck.Failed)
    }

    @Test fun noBootLogIsInconclusive() {
        assertTrue(Rtmr3Replay.check(JsonObject(tcb - "event_log"), nodeQuote, osImageHash) is BindingCheck.Inconclusive)
    }

    @Test fun pythonsEscapingIsKept() {
        assertEquals("{\"a\":\"q\\\"b\\\\n\\n\\u0001é\",\"b\":[1,true,null]}",
            CanonicalJson.encode(Json.parseToJsonElement("{\"b\":[1,true,null],\"a\":\"q\\\"b\\\\n\\n\\u0001é\"}")))
    }

    // (d)

    private val indexBytes = javaClass.getResource("/fixtures/nearai-trust/launcher-index-91fdff3c.json")!!.readBytes()
    private val indexDigest = "91fdff3cfa3543d72656b2368c7d8a0a83d95a0f1087378c897aa1537acdba56"
    private val launcher = "079a14b41deb11e0e27f9b69648e8b3d89bd6fb9ccf6071643b8f447fa415085"

    @Test fun theLauncherIsTheLinuxAmd64ImageOfItsAttestedIndex() {
        assertEquals(BindingCheck.Verified("attested via its linux/amd64 index 91fdff3c"), ImageIndexResolution.check(indexBytes, indexDigest, launcher))
    }

    @Test fun anotherChildFailsAndTheAttestationManifestNeverCounts() {
        assertTrue(ImageIndexResolution.check(indexBytes, indexDigest, launcher.reversed()) is BindingCheck.Failed)
        // the index's attestation-manifest entry (platform unknown) is not an image of it
        assertTrue(ImageIndexResolution.check(indexBytes, indexDigest, "c3e4bbb11083" + "0".repeat(52)) is BindingCheck.Failed)
    }

    @Test fun bytesThatAreNotTheNamedIndexFail() {
        val bent = indexBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertTrue(ImageIndexResolution.check(bent, indexDigest, launcher) is BindingCheck.Failed)
    }

    @Test fun droppingTheAttestationEntryStillResolves() {
        val o = Json.parseToJsonElement(indexBytes.decodeToString()).jsonObject
        val trimmed = JsonObject(o + ("manifests" to JsonArray(o.getValue("manifests").jsonArray.filter { (it.jsonObject["annotations"] as? JsonObject)?.get("vnd.docker.reference.type") == null })))
        val bytes = trimmed.toString().toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        assertTrue(ImageIndexResolution.check(bytes, digest, launcher) is BindingCheck.Verified)
    }

    // (2) and (c)

    @Test fun theManagerRunningIsNotTheOneTheLaunchConfigDeclares() {
        val m = expected.getValue("compose_manager").jsonObject
        val declared = m.getValue("declared_default_in_measured_compose").jsonArray[0].jsonPrimitive.content.substringAfter("@sha256:")
        assertEquals(declared, DeployLog.declaredManager(tcb.getValue("app_compose").jsonPrimitive.content))
        val started = m.getValue("started_entries").jsonArray
        assertEquals(started.last().jsonObject.getValue("image").jsonPrimitive.content.substringAfter("@sha256:"), DeployLog.runningManager(cm.getValue("actions").jsonArray))
        assertEquals("a320bb4882341c8e19ef1944436f6591ed73303732009ed5db9f6db6c9da756a", DeployLog.runningManager(cm.getValue("actions").jsonArray))
    }

    // The per-service table under the old forward rule (expected.json's per_service_running_table, kept by audit for the
    // record) is superseded by rule A, 2026-11-06: RunningWalkTest holds the walk against running_pairs.
}
