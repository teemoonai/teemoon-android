// ports: teemoonTests/MeasuredConfigTests.swift @ v1.0.1
//        teemoonTests/PlaintextExposureTests.swift @ v1.0.1 (measuredConfigSeesThroughAnchors only)
package ai.teemoon.confidential

import ai.teemoon.confidential.MeasuredConfig.Line.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredConfigTest {

    /** Production-shaped inner compose with the real GLM-5.1 launch command: logging off, no dump flags, pinned revision — and the literal proxy env. */
    private val innerCompose = """
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:b183677aaabbccddeeff00112233445566778899aabbccddeeff001122334455
    environment:
      - OHTTP_ENABLED=true
      - TLS_CERT_PATH=/certs/completions.near.ai
  model-sg-glm51-awq-tp4:
    image: glm51-sgl-awq-tp4-patched:local
    command: >
      sglang serve --model-path QuantTrio/GLM-5.1-AWQ
      --revision 8f60817aa28023f2607850d1a1e51d21aa34817a
      --served-model-name zai-org/GLM-5.1-FP8 --tp 4
      --log-requests-level 0 --mem-fraction-static 0.85
      --enable-cache-report --enable-metrics --trust-remote-code
  nginx:
    image: nginx:1.25
networks:
  default:
""".trimIndent()

    private fun engine(yaml: String = innerCompose, link: RunLink? = null) = MeasuredConfig.engineConfig(yaml, link)!!
    private fun line(cfg: MeasuredConfig, id: String) = cfg.lines.first { it.id == id }

    // MARK: engine (sglang)

    @Test fun engineChecksHoldOnProductionCommand() {
        val cfg = engine()
        assertEquals(3, cfg.lines.size)
        assertTrue(cfg.lines.all { it.state == State.HOLDS })
        val logging = line(cfg, "logging")
        assertEquals("request logging off", logging.title)
        assertTrue(logging.detail.contains("--log-requests-level 0"))
        assertTrue(line(cfg, "revision").detail.contains("8f60817aa280"))
    }

    /** `--log-requests-level` shares a prefix with `--log-requests` — the level flag alone must NOT read as logging enabled. */
    @Test fun logRequestsLevelZeroIsNotLoggingOn() { assertEquals(State.HOLDS, line(engine(), "logging").state) }

    @Test fun explicitLogRequestsFlagIsSurfaced() {
        val cfg = engine("services:\n  engine:\n    image: eng:local\n    command: [sglang, serve, --model-path, x/y, --log-requests, --revision, abc123]")
        val logging = line(cfg, "logging")
        assertEquals(State.ATTENTION, logging.state)
        assertTrue(logging.detail.contains("--log-requests"))
    }

    @Test fun dumpFlagsAreNamedLiterally() {
        val cfg = engine("services:\n  engine:\n    image: eng:local\n    command: sglang serve --model-path x/y --crash-dump-folder /tmp/dumps")
        val dumps = line(cfg, "dumps")
        assertEquals(State.ATTENTION, dumps.state)
        assertTrue(dumps.detail.contains("--crash-dump-folder"))
    }

    @Test fun missingRevisionIsSurfaced() {
        assertEquals(State.ATTENTION, line(engine("services:\n  engine:\n    image: eng:local\n    command: sglang serve --model-path x/y"), "revision").state)
    }

    /** No engine block → no panel, never a fabricated one. */
    @Test fun noEngineBlockYieldsNil() { assertNull(MeasuredConfig.engineConfig("services:\n  web:\n    image: nginx")) }

    /** The yaml link rides on the logging line. */
    @Test fun yamlLinkAttachesToLoggingLine() {
        val link = RunLink("yaml @ c545c95", "https://github.com/nearai/cvm-compose-files/blob/c545c955/prod/GLM-5.1.yaml")
        assertEquals(listOf(link), line(engine(innerCompose, link), "logging").links)
    }

    // MARK: proxy (vllm-proxy-rs)

    @Test fun proxyOHTTPEnabledHolds() {
        val ohttp = line(MeasuredConfig.proxyConfig(innerCompose)!!, "ohttp")
        assertEquals(State.HOLDS, ohttp.state)
        assertTrue(ohttp.detail.contains("OHTTP_ENABLED=true"))
    }

    @Test fun proxyOHTTPAbsentIsHonest() {
        val cfg = MeasuredConfig.proxyConfig("services:\n  proxy:\n    image: nearaidev/vllm-proxy-rs@sha256:aa\n    environment:\n      - TLS_CERT_PATH=/certs/x")!!
        assertEquals(State.ATTENTION, line(cfg, "ohttp").state)
    }

    // MARK: copy rules (pinned)

    /** Never "can't log" — sglang's unauthenticated /configure_logging makes that false. */
    @Test fun copyNeverSaysCantLog() {
        val all = (engine().lines + MeasuredConfig.proxyConfig(innerCompose)!!.lines).joinToString(" ") { it.title + " " + it.detail } +
            " " + MeasuredConfig.RECONFIGURE_QUALIFIER + " " + MeasuredConfig.BINDING_NOTE
        val lowered = all.lowercase()
        assertFalse(lowered.contains("can't log")); assertFalse(lowered.contains("cannot log"))
        assertTrue(MeasuredConfig.RECONFIGURE_QUALIFIER.contains("not configured to log"))
        assertTrue(MeasuredConfig.RECONFIGURE_QUALIFIER.contains("measurement change or a signed action-log trace"))
    }

    /** The inner document is action-log-pinned + hash-checked — never claimed as hardware-measured. */
    @Test fun bindingNoteNeverClaimsHardwareMeasured() {
        assertTrue(MeasuredConfig.BINDING_NOTE.contains("pinned by the attested action log"))
        assertTrue(MeasuredConfig.BINDING_NOTE.contains("hash-checked"))
        assertFalse(MeasuredConfig.BINDING_NOTE.lowercase().contains("hardware-measured"))
    }

    // MARK: allowed_envs (group invariant, outer measured compose)

    @Test fun allowedEnvsParsesJSONForm() {
        val envs = MeasuredConfig.allowedEnvs("""{"manifest_version": 2, "allowed_envs": ["BEARER_TOKEN", "DD_API_KEY", "HOST_IP"], "docker_compose_file": "…"}""")!!
        assertEquals(listOf("BEARER_TOKEN", "DD_API_KEY", "HOST_IP"), envs)
        assertTrue(MeasuredConfig.loggingSuspects(envs).isEmpty())
    }

    @Test fun allowedEnvsParsesYAMLListForm() {
        val envs = MeasuredConfig.allowedEnvs("allowed_envs:\n  - BEARER_TOKEN\n  - CM_MIN_TAG_AGE_HOURS\n  - DD_API_KEY\nother_key: x")!!
        assertEquals(3, envs.size); assertTrue("CM_MIN_TAG_AGE_HOURS" in envs)
    }

    @Test fun loggingSuspectsAreFlagged() {
        assertEquals(listOf("LOG_LEVEL", "RUST_DEBUG"), MeasuredConfig.loggingSuspects(listOf("BEARER_TOKEN", "LOG_LEVEL", "RUST_DEBUG", "AWS_REGION")))
    }

    @Test fun allowedEnvsCaptionStatesTheInvariant() {
        val caption = MeasuredConfig.allowedEnvsCaption("""{"allowed_envs": ["BEARER_TOKEN", "DD_API_KEY"]}""")!!
        assertTrue(caption.contains("none of the 2 operator-settable env vars"))
        assertTrue(caption.contains("measurement or a signed action-log trace"))
    }

    @Test fun allowedEnvsCaptionNamesSuspects() {
        assertTrue(MeasuredConfig.allowedEnvsCaption("""{"allowed_envs": ["LOG_LEVEL"]}""")!!.contains("`LOG_LEVEL`"))
    }

    /** No allowed_envs section → no claim at all. */
    @Test fun noAllowedEnvsMeansNoCaption() { assertNull(MeasuredConfig.allowedEnvsCaption("services:\n  a:\n    image: x")) }

    // MARK: egress (disclosed limit)

    @Test fun egressAbsenceIsDerivedFromNetworks() { assertTrue(MeasuredConfig.lacksEgressConfinement(innerCompose)) }

    @Test fun internalTrueMeansConfinementPresent() {
        assertFalse(MeasuredConfig.lacksEgressConfinement("services:\n  engine:\n    image: eng:local\n    networks: [backend]\nnetworks:\n  backend:\n    internal: true"))
    }

    // MARK: anchored production compose (from PlaintextExposureTests)

    @Test fun measuredConfigSeesThroughAnchors() {
        val anchored = """
x-vllm-proxy-common: &vllm-proxy-common
  image: nearaidev/vllm-proxy-rs@sha256:${"b".repeat(64)}
  restart: unless-stopped
x-awq-cmd: &awq-cmd >
  sglang serve
  --model-path QuantTrio/GLM-5.1-AWQ
  --revision 8f60817aa28023f2607850d1a1e51d21aa34817a
  --served-model-name zai-org/GLM-5.1-FP8
  --tp 4
  --log-requests-level 0
services:
  proxy-glm51:
    <<: *vllm-proxy-common
    environment:
      - MODEL_NAME=zai-org/GLM-5.1-FP8
      - OHTTP_ENABLED=true
  glm51-awq-tp4-r1:
    image: glm51-sgl-awq-tp4-patched:local
    command: *awq-cmd
""".trimIndent()
        val eng = MeasuredConfig.engineConfig(anchored)
        assertNotNull(eng)
        // Flags from the aliased command: logging off at the minimum level, pinned revision.
        assertTrue(eng!!.lines.any { it.id == "logging" && it.state == State.HOLDS })
        assertTrue(eng.lines.any { it.id == "revision" && it.state == State.HOLDS })
        assertTrue(MeasuredConfig.proxyConfig(anchored)!!.lines.any { it.id == "ohttp" && it.state == State.HOLDS })
    }

    /** Android-only: forRole dispatches to the right panel. */
    @Test fun forRoleDispatches() {
        assertEquals("logging", MeasuredConfig.forRole(PlaintextExposure.Role.MODEL_SERVER, innerCompose)!!.lines.first().id)
        assertEquals("ohttp", MeasuredConfig.forRole(PlaintextExposure.Role.E2EE_TERMINATOR, innerCompose)!!.lines.first().id)
    }
}
