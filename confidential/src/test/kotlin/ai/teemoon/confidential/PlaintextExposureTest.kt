// ports: teemoonTests/PlaintextExposureTests.swift @ v1.0.1 (PlaintextExposureTests, MultiModelComposeTests,
//        AnchoredComposeTests minus measuredConfigSeesThroughAnchors (MeasuredConfig row), PlaintextCapabilityTests,
//        RealComposeTests. NOT ported: the three realNodeScoped… tests, which read Fixtures/dsv4-qwen36-gemma4.yaml)
package ai.teemoon.confidential

import ai.teemoon.confidential.PlaintextExposure.Capability
import ai.teemoon.confidential.PlaintextExposure.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PlaintextExposureTest {

    /** Shaped like the real near.ai model compose: an OHTTP/TLS-terminating proxy, two model-server replicas, and sidecars that never see plaintext. */
    private val modelCompose = """
services:
  proxy-glm51:
    image: nearaidev/vllm-proxy-rs@sha256:b183677aaabbccddeeff00112233445566778899aabbccddeeff001122334455
    privileged: true
    environment:
      - OHTTP_ENABLED=true
      - TLS_CERT_PATH=/certs/completions.near.ai
  model-sg-glm51-awq-tp4-r1:
    image: glm51-sgl-awq-tp4-patched:local
    command:
      - python3
      - -m
      - sglang.launch_server
      - --model-path
      - QuantTrio/GLM-5.1-AWQ
  model-sg-glm51-awq-tp4-r2:
    image: glm51-sgl-awq-tp4-patched:local
    command:
      - python3
      - -m
      - sglang.launch_server
      - --model-path
      - QuantTrio/GLM-5.1-AWQ
  nginx:
    image: nginx:1.25
    ports:
      - "443:443"
  dcgm-glm51:
    image: nvidia/dcgm-exporter:3.3.5
    cap_add:
      - SYS_ADMIN
  otelcol:
    image: otel/opentelemetry-collector-contrib:0.98.0
networks:
  default:
""".trimIndent()

    @Test fun identifiesTheTwoTouchers() {
        val x = PlaintextExposure.analyze(modelCompose)
        assertEquals(2, x.touchers.size)
        assertEquals(Role.E2EE_TERMINATOR, x.touchers.first().role)   // terminator first
        assertEquals("vllm-proxy-rs", x.touchers.first().image)
        assertEquals(Role.MODEL_SERVER, x.touchers.last().role)
        assertEquals("glm51-sgl-awq-tp4-patched", x.touchers.last().image)
    }

    @Test fun doesNotFlagCiphertextOnlySidecars() {
        val images = PlaintextExposure.analyze(modelCompose).touchers.map { it.image }
        assertFalse("nginx" in images); assertFalse("dcgm-exporter" in images); assertFalse("opentelemetry-collector-contrib" in images)
    }

    @Test fun collapsesModelReplicas() {
        assertEquals(1, PlaintextExposure.analyze(modelCompose).touchers.count { it.role == Role.MODEL_SERVER })
    }

    @Test fun unrecognizedComposeYieldsNothing() {
        assertTrue(PlaintextExposure.analyze("services:\n  web:\n    image: nginx").touchers.isEmpty())
    }

    @Test fun emptyInputIsSafe() { assertTrue(PlaintextExposure.analyze("").touchers.isEmpty()) }

    // A COMBINED node: SGLang (deepseek, --model-path) + vLLM (gemma, vllm serve) engines behind one shared E2EE proxy.
    private val combined = """
x-gemma-common: &gemma-common
  image: vllm/vllm-openai@sha256:aaaa000000000000000000000000000000000000000000000000000000000000
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:bbbb000000000000000000000000000000000000000000000000000000000000
    environment:
      - OHTTP_ENABLED=true
  deepseek:
    image: lmsysorg/sglang@sha256:cccc000000000000000000000000000000000000000000000000000000000000
    command: [--model-path, deepseek-ai/DeepSeek-V4-Flash, --served-model-name, deepseek-ai/DeepSeek-V4-Flash]
  gemma:
    <<: *gemma-common
    command:
      - >
        exec vllm serve google/gemma-4-31B --served-model-name google/gemma-4-31B-it
""".trimIndent()

    @Test fun scopesCombinedNodeToRequestedModel() {
        val g = PlaintextExposure.scoped(combined, "google/gemma-4-31B-it")
        assertFalse(g.contains("deepseek-ai/DeepSeek-V4-Flash")); assertFalse(g.contains("lmsysorg/sglang"))
        assertTrue(g.contains("vllm/vllm-openai")); assertTrue(g.contains("vllm-proxy-rs"))
        val tiers = PlaintextExposure.analyze(g)
        assertTrue(tiers.touchers.any { it.role == Role.MODEL_SERVER && it.image == "vllm-openai" })
        assertTrue(tiers.touchers.any { it.role == Role.E2EE_TERMINATOR })
        assertFalse(tiers.touchers.any { it.image == "sglang" })
    }

    @Test fun scopingIsNoOpForSingleModelOrNilRequest() {
        val d = PlaintextExposure.scoped(combined, "deepseek-ai/DeepSeek-V4-Flash")
        assertTrue(d.contains("lmsysorg/sglang")); assertFalse(d.contains("google/gemma-4-31B"))
        assertEquals(combined, PlaintextExposure.scoped(combined, null))
    }

    @Test fun stripsDigestAndRegistryAndTag() {
        assertEquals("foo", PlaintextExposure.shortImageName("image: reg.io/ns/foo@sha256:abcd"))
        assertEquals("bar", PlaintextExposure.shortImageName("image: bar:local"))
        assertEquals("baz", PlaintextExposure.shortImageName("  image: ns/baz:1.2.3"))
    }

    // MARK: wrong-document regression

    private val outerHarnessCompose = """
services:
  compose-manager:
    image: nearaidev/compose-manager@sha256:b487f391aabbccddeeff00112233445566778899aabbccddeeff001122334455
    pid: host
  launcher:
    image: nearaidev/compose-manager-launcher@sha256:6e035c8faabbccddeeff00112233445566778899aabbccddeeff001122334455
  datadog:
    image: datadog/agent:7.55.0
  otelcol:
    image: otel/opentelemetry-collector-contrib:0.98.0
networks:
  default:
""".trimIndent()

    private val innerEngineCompose = """
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:b183677aaabbccddeeff00112233445566778899aabbccddeeff001122334455
    environment:
      - OHTTP_ENABLED=true
      - TLS_CERT_PATH=/certs/completions.near.ai
  model-sg-glm52-w4afp8:
    image: glm52-w4afp8-patched:local
    command: >
      sglang serve --model-path PhalaCloud/GLM-5.2-W4AFP8
      --revision d975c19a1111222233334444555566667777888899990000aaaabbbbccccdddd
      --served-model-name z-ai/glm-5.2 --log-requests-level 0
  nginx:
    image: nginx:1.25
networks:
  default:
""".trimIndent()

    @Test fun productionShapeTouchersComeFromInnerDocument() {
        assertTrue(PlaintextExposure.analyze(outerHarnessCompose).touchers.isEmpty())
        val x = PlaintextExposure.analyze(innerEngineCompose, outerHarnessCompose)
        assertEquals(2, x.touchers.size)
        assertEquals(Role.E2EE_TERMINATOR, x.touchers.first().role); assertEquals("vllm-proxy-rs", x.touchers.first().image)
        assertEquals(Role.MODEL_SERVER, x.touchers.last().role); assertEquals("glm52-w4afp8-patched", x.touchers.last().image)
    }

    @Test fun modelPathAloneIsAModelServerSignal() {
        assertTrue(PlaintextExposure.analyze(innerEngineCompose).touchers.any { it.role == Role.MODEL_SERVER && it.image == "glm52-w4afp8-patched" })
    }

    @Test fun fallsBackToOuterManifestWhenInnerAbsent() {
        val x = PlaintextExposure.analyze(null, modelCompose)
        assertEquals(2, x.touchers.size); assertEquals("vllm-proxy-rs", x.touchers.first().image)
    }

    @Test fun emptyInnerFallsThroughToOuter() {
        assertEquals(2, PlaintextExposure.analyze("services:\n  web:\n    image: nginx", modelCompose).touchers.size)
    }

    @Test fun bothDocumentsAbsentYieldsEmpty() { assertTrue(PlaintextExposure.analyze(null, null).touchers.isEmpty()) }

    // MARK: group caption invariant

    @Test fun emptyToucherSetCaptionNeverReadsAsSafe() {
        val caption = PlaintextExposure(emptyList()).groupCaption
        assertTrue(caption.contains("couldn't determine which images see your message"))
        assertFalse(caption.contains("only handle encrypted data"))
    }

    @Test fun knownToucherSetCaptionStatesCounterpartPositively() {
        val x = PlaintextExposure.analyze(modelCompose)
        assertTrue(x.touchers.isNotEmpty())
        assertTrue(x.groupCaption.contains("only handle encrypted data or content-free telemetry"))
        assertTrue(x.groupCaption.contains("per the attested compose"))
        assertFalse(x.groupCaption.contains("couldn't determine"))
    }

    // MARK: role matching for the known-code list

    @Test fun roleMatchingNormalizesImageNames() {
        val x = PlaintextExposure.analyze(modelCompose)
        assertEquals(Role.E2EE_TERMINATOR, x.role("nearaidev/vllm-proxy-rs"))
        assertEquals(Role.E2EE_TERMINATOR, x.role("vllm-proxy-rs@sha256:b183677a"))
        assertEquals(Role.MODEL_SERVER, x.role("glm51-sgl-awq-tp4-patched:local"))
        assertNull(x.role("nginx:1.25")); assertNull(x.role("datadog/agent"))
    }

    @Test fun roleCapsuleCopy() {
        assertEquals("decrypts your request", Role.E2EE_TERMINATOR.capsule)
        assertEquals("runs the model", Role.MODEL_SERVER.capsule)
    }

    // MARK: multi-model shared compose (fleet regression)

    private val a64 = "a".repeat(64); private val b64 = "b".repeat(64); private val c64 = "c".repeat(64); private val e64 = "e".repeat(64)

    private val sharedCompose = """
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:$a64
    environment:
      - OHTTP_ENABLED=true
      - TLS_CERT_PATH=/certs/completions.near.ai
  model-dsv4-flash-r1:
    image: dsv4-flash-sgl-fp8:local
    command: >
      sglang serve --model-path deepseek-ai/DeepSeek-V4-Flash
      --revision 1111111111111111111111111111111111111111
      --log-requests-level 0 --tp 2
  model-dsv4-flash-r2:
    image: dsv4-flash-sgl-fp8:local
    command: >
      sglang serve --model-path deepseek-ai/DeepSeek-V4-Flash
      --revision 1111111111111111111111111111111111111111
      --log-requests-level 0 --tp 2
  model-qwen36-27b:
    image: qwen36-27b-sgl-fp8:local
    command: >
      sglang serve --model-path Qwen/Qwen3.6-27B-FP8
      --revision 2222222222222222222222222222222222222222
      --log-requests-level 0 --tp 1
  model-gemma4-31b:
    image: gemma4-31b-sgl:local
    command: >
      sglang serve --model-path google/gemma-4-31B-it
      --revision 3333333333333333333333333333333333333333
      --log-requests-level 0 --tp 1
  dns-cloudflare:
    image: nearai/dns-cloudflare@sha256:$b64
  otel:
    image: otel/opentelemetry-collector-contrib@sha256:$c64
networks:
  default:
""".trimIndent()

    @Test fun allEnginesSurfaceAsTouchers() {
        val x = PlaintextExposure.analyze(sharedCompose, null)
        assertEquals(4, x.touchers.size)   // 1 terminator + 3 DISTINCT engine images (replicas collapse)
        assertEquals(listOf("vllm-proxy-rs"), x.touchers.filter { it.role == Role.E2EE_TERMINATOR }.map { it.image })
        assertEquals(setOf("dsv4-flash-sgl-fp8", "qwen36-27b-sgl-fp8", "gemma4-31b-sgl"), x.touchers.filter { it.role == Role.MODEL_SERVER }.map { it.image }.toSet())
        assertEquals(Role.E2EE_TERMINATOR, x.touchers.first().role)
        assertNull(x.role("nearai/dns-cloudflare@sha256:bb")); assertNull(x.role("otel/opentelemetry-collector-contrib"))
    }

    @Test fun roleLookupMatchesEveryEngine() {
        val x = PlaintextExposure.analyze(sharedCompose, null)
        assertEquals(Role.MODEL_SERVER, x.role("dsv4-flash-sgl-fp8:local"))
        assertEquals(Role.MODEL_SERVER, x.role("qwen36-27b-sgl-fp8:local"))
        assertEquals(Role.MODEL_SERVER, x.role("gemma4-31b-sgl:local"))
        assertEquals(Role.E2EE_TERMINATOR, x.role("nearaidev/vllm-proxy-rs@sha256:aa"))
        assertTrue(x.groupCaption.contains("images without a tag"))
    }

    // MARK: anchored production compose (live-shape regression)

    private val anchored = """
x-logging-conf: &logging-conf
  logging:
    driver: json-file
x-vllm-proxy-common: &vllm-proxy-common
  image: nearaidev/vllm-proxy-rs@sha256:$b64
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
    container_name: proxy-glm51
    environment:
      - MODEL_NAME=zai-org/GLM-5.1-FP8
      - OHTTP_ENABLED=true
  glm51-awq-tp4-r1:
    image: glm51-sgl-awq-tp4-patched:local
    command: *awq-cmd
  glm51-awq-tp4-r2:
    image: glm51-sgl-awq-tp4-patched:local
    command: *awq-cmd
  nginx:
    <<: *logging-conf
    image: nginx@sha256:$e64
networks:
  default:
""".trimIndent()

    @Test fun anchoredProxyAndAliasedEngineBothSurface() {
        val x = PlaintextExposure.analyze(anchored, null)
        assertEquals(2, x.touchers.size)   // proxy + one engine image (replicas collapse)
        assertEquals(Role.E2EE_TERMINATOR, x.role("nearaidev/vllm-proxy-rs@sha256:bb"))
        assertEquals(Role.MODEL_SERVER, x.role("glm51-sgl-awq-tp4-patched:local"))
        assertNull(x.role("nginx"))   // the anchored sidecar must not surface
        assertTrue(x.groupCaption.contains("images without a tag"))
    }

    // MARK: capability accessors (audit-scope rule, parsed from attested composes)

    private val harness = """
services:
  compose-manager:
    image: nearaidev/compose-manager@sha256:b487f39160e9
    pid: host
    cap_add:
      - SYS_ADMIN
      - SYS_PTRACE
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
  compose-manager-launcher:
    image: nearaidev/compose-manager-launcher@sha256:78afb8233013
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
  datadog-agent:
    image: datadog/agent@sha256:5556fb80b952
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
  otelcol-management:
    image: otel/opentelemetry-collector-contrib@sha256:85ac41c2db88
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
  certbot:
    image: certbot/dns-cloudflare@sha256:742dbd2e61c8
    volumes:
      - certs:/etc/letsencrypt
""".trimIndent()

    private val inner = """
services:
  proxy-glm51:
    image: nearaidev/vllm-proxy-rs@sha256:b183677a5d32
    environment:
      - OHTTP_ENABLED=true
  model-sg:
    image: lmsysorg/sglang@sha256:aac6b242680d
    command: python -m sglang.launch_server --model-path QuantTrio/GLM-5.1-AWQ
  registrar:
    image: curlimages/curl@sha256:d94d07ba9e7d
""".trimIndent()

    private val both get() = PlaintextExposure.analyze(inner, harness)

    @Test fun dockerSocketAndHostPidAreProcessAccess() {
        assertEquals(Capability.PROCESS_ACCESS, both.capability("nearaidev/compose-manager@sha256:b487…"))
        assertEquals(Capability.PROCESS_ACCESS, both.capability("compose-manager-launcher"))
    }

    @Test fun readOnlyDockerSocketStillProcessAccess() { assertEquals(Capability.PROCESS_ACCESS, both.capability("datadog/agent")) }

    @Test fun logMountsAloneAreLogAccess() { assertEquals(Capability.LOG_ACCESS, both.capability("otel/opentelemetry-collector-contrib")) }

    @Test fun incapableSidecarsStayUntagged() {
        assertNull(both.capability("certbot/dns-cloudflare")); assertNull(both.capability("curlimages/curl"))
    }

    @Test fun touchersAreNotAccessors() {
        val x = both
        assertEquals(Role.E2EE_TERMINATOR, x.role("vllm-proxy-rs")); assertNull(x.capability("vllm-proxy-rs"))
        assertEquals(Role.MODEL_SERVER, x.role("sglang")); assertNull(x.capability("sglang"))
    }

    @Test fun accessorsUnionAcrossDocuments() {
        val x = both
        assertTrue(x.touchers.isNotEmpty())
        assertEquals(4, x.accessors.size)
        assertEquals(Capability.PROCESS_ACCESS, x.accessors.first().capability)
    }

    @Test fun capabilityCapsuleCopy() {
        assertEquals("can reach enclave processes", Capability.PROCESS_ACCESS.capsule)
        assertEquals("reads container logs", Capability.LOG_ACCESS.capsule)
        assertEquals("GPU-privileged", Capability.DEVICE_PRIVILEGE.capsule)
    }

    private val dcgmBlock = "\n    cap_add:\n      - SYS_ADMIN\n    runtime: nvidia\n    deploy:\n      resources:\n        reservations:\n          devices:\n            - driver: nvidia\n              count: all\n              capabilities: [gpu]\n"

    @Test fun dcgmShapedServiceIsDevicePrivilege() {
        val compose = "services:\n  dcgm-exporter:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter@sha256:aabbccddeeff" + dcgmBlock
        assertEquals(Capability.DEVICE_PRIVILEGE, PlaintextExposure.analyze(compose, null).capability("nvcr.io/nvidia/k8s/dcgm-exporter"))
    }

    @Test fun realTagPinnedDcgmIsDevicePrivilege() {
        val compose = "services:\n  dcgm-exporter:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless" + dcgmBlock
        assertEquals(Capability.DEVICE_PRIVILEGE, PlaintextExposure.analyze(compose, null).capability("nvcr.io/nvidia/k8s/dcgm-exporter"))
    }

    @Test fun eachDevicePrivilegeSignalDetects() {
        assertTrue(PlaintextExposure.hasDevicePrivilegeSignal("    privileged: true"))
        assertTrue(PlaintextExposure.hasDevicePrivilegeSignal("    cap_add:\n      - SYS_ADMIN"))
        assertTrue(PlaintextExposure.hasDevicePrivilegeSignal("    runtime: nvidia\n    deploy:\n      resources:\n        reservations:\n          devices:\n            - driver: nvidia"))
        assertFalse(PlaintextExposure.hasDevicePrivilegeSignal("    runtime: nvidia"))   // runtime alone is NOT the signal
        assertFalse(PlaintextExposure.hasDevicePrivilegeSignal("    image: certbot/dns-cloudflare\n    volumes:\n      - certs:/etc/letsencrypt"))
    }

    @Test fun processAccessOutranksDevicePrivilege() { assertEquals(Capability.PROCESS_ACCESS, both.capability("nearaidev/compose-manager")) }

    // MARK: REAL production compose (captured live from near.ai 2026-07-20; the base64 is a literal in the Swift test source)

    private val realComposeB64 = "c2VydmljZXM6CiAgIyBEYXRhZG9nIFNlcnZpY2UgRGVmaW5pdGlvbgogIGRhdGFkb2ctYWdlbnQ6CiAgICBpbWFnZTogZGF0YWRvZy9hZ2VudEBzaGEyNTY6NTU1NmZiODBiOTUyODMyNzE5YTc2YjAxNmY5MDU2MTZjNzZlZTA5ODlhMjM5YzQ2ODBjNjIyMDE0OGU4NjVkNgogICAgY29udGFpbmVyX25hbWU6IGRhdGFkb2ctYWdlbnQKICAgIGVudmlyb25tZW50OgogICAgICAtIEREX0FQSV9LRVk9JHtERF9BUElfS0VZfQogICAgICAtIEREX1NJVEU9dXMzLmRhdGFkb2docS5jb20KICAgICAgLSBERF9FTlY9cHJvZAogICAgICAtIEREX0xPR1NfRU5BQkxFRD10cnVlCiAgICAgIC0gRERfT1RMUF9DT05GSUdfTE9HU19FTkFCTEVEPXRydWUKICAgICAgLSBERF9MT0dTX0NPTkZJR19DT05UQUlORVJfQ09MTEVDVF9BTEw9dHJ1ZQogICAgICAtIEREX0NPTlRBSU5FUl9FWENMVURFX0xPR1M9bmFtZTpkYXRhZG9nLWFnZW50CiAgICAgIC0gRERfUFJPQ0VTU19BR0VOVF9FTkFCTEVEPXRydWUKICAgICAgLSBERF9ET0dTVEFUU0RfTk9OX0xPQ0FMX1RSQUZGSUM9dHJ1ZQogICAgICAtIEREX0hPU1ROQU1FPSRERF9IT1NUTkFNRQogICAgICAjIERhdGFkb2cgQWdlbnQgNy42MSsgY2FuIGZhaWwgdG8gc3RhcnQgaXRzIE9UTFAgcmVjZWl2ZXIgaW4gRG9ja2VyCiAgICAgICMgd2hlbiBpdCB0cmllcyB0byBjb2xsZWN0IHByb2Nlc3MgbWV0cmljcyB0aHJvdWdoIC9ob3N0L3Byb2MuCiAgICAgIC0gSE9TVF9QUk9DPS9wcm9jCiAgICAgIC0gRERfT1RMUF9DT05GSUdfUkVDRUlWRVJfUFJPVE9DT0xTX0dSUENfRU5EUE9JTlQ9MC4wLjAuMDo0MzE3CiAgICB2b2x1bWVzOgogICAgICAtIC92YXIvcnVuL2RvY2tlci5zb2NrOi92YXIvcnVuL2RvY2tlci5zb2NrOnJvCiAgICAgIC0gL3Byb2MvOi9ob3N0L3Byb2MvOnJvCiAgICAgIC0gL3N5cy9mcy9jZ3JvdXAvOi9ob3N0L3N5cy9mcy9jZ3JvdXA6cm8KICAgICAgLSAvdmFyL2xpYi9kb2NrZXIvY29udGFpbmVyczovdmFyL2xpYi9kb2NrZXIvY29udGFpbmVyczpybwogICAgICAtIC9ydW4vbG9nL2pvdXJuYWw6L3J1bi9sb2cvam91cm5hbDpybwogICAgICAtIC9ydW4vc3lzdGVtZC86L2hvc3QvcnVuL3N5c3RlbWQvOnJvCiAgICBjb25maWdzOgogICAgICAtIHNvdXJjZTogam91cm5hbGRfY29uZmlnX2ZpbGUKICAgICAgICB0YXJnZXQ6IC9ldGMvZGF0YWRvZy1hZ2VudC9jb25mLmQvam91cm5hbGQuZC9jb25mLnlhbWwKICAgICAgICBtb2RlOiAwNzU1CiAgICByZXN0YXJ0OiB1bmxlc3Mtc3RvcHBlZAogICAgbG9nZ2luZzoKICAgICAgZHJpdmVyOiAibG9jYWwiCiAgICAgIG9wdGlvbnM6CiAgICAgICAgbWF4LXNpemU6ICIyMG0iCiAgICAgICAgbWF4LWZpbGU6ICIzIgoKICAjIE9wZW5UZWxlbWV0cnkgY29sbGVjdG9yIHNpZGVjYXIgZm9yIHN0YWJsZSBtYW5hZ2VtZW50IHZpc2liaWxpdHkgb25seS4KICBvdGVsY29sLW1hbmFnZW1lbnQ6CiAgICBpbWFnZTogb3RlbC9vcGVudGVsZW1ldHJ5LWNvbGxlY3Rvci1jb250cmliQHNoYTI1Njo4NWFjNDFjMmRiODhkMGRmOWJkNjE0NWU2MDhhM2NiMDIzZjVkODQ0Mzg2OGFkYmZiYmY2NmVmYjUxMDg3OTE3CiAgICBjb250YWluZXJfbmFtZTogb3RlbGNvbC1tYW5hZ2VtZW50CiAgICBjb21tYW5kOiBbIi0tY29uZmlnPS9ldGMvb3RlbGNvbC1tYW5hZ2VtZW50L2NvbmZpZy55YW1sIl0KICAgIHVzZXI6ICIwOjAiCiAgICBtZW1fbGltaXQ6ICI3NjhtIgogICAgZW52aXJvbm1lbnQ6CiAgICAgIC0gTU9OSVRPUklOR19JTkdFU1RfVE9LRU49JHtNT05JVE9SSU5HX0lOR0VTVF9UT0tFTn0KICAgICAgLSBDVk1fTkFNRT0ke0NWTV9OQU1FfQogICAgICAtIENWTV9IT1NUPSR7Q1ZNX0hPU1R9CiAgICAgIC0gRERfSE9TVE5BTUU9JHtERF9IT1NUTkFNRX0KICAgICAgLSBFTlY9JHtFTlZ9CiAgICB2b2x1bWVzOgogICAgICAtIC92YXIvbGliL2RvY2tlci9jb250YWluZXJzOi92YXIvbGliL2RvY2tlci9jb250YWluZXJzOnJvCiAgICAgIC0gb3RlbGNvbC1tYW5hZ2VtZW50LXN0b3JhZ2U6L3Zhci9saWIvb3RlbGNvbC1tYW5hZ2VtZW50CiAgICBjb25maWdzOgogICAgICAtIHNvdXJjZTogb3RlbGNvbF9tYW5hZ2VtZW50X2NvbmZpZwogICAgICAgIHRhcmdldDogL2V0Yy9vdGVsY29sLW1hbmFnZW1lbnQvY29uZmlnLnlhbWwKICAgICAgICBtb2RlOiAwNDQ0CiAgICByZXN0YXJ0OiB1bmxlc3Mtc3RvcHBlZAogICAgIyBBdm9pZCB0aGUgY29sbGVjdG9yIHRhaWxpbmcgaXRzIG93biBEb2NrZXIgSlNPTiBsb2cgYW5kIGNyZWF0aW5nIGEKICAgICMgZmVlZGJhY2sgbG9vcC4gVGhlIGhvc3Qtc2lkZSBQaGFzZSAxIGNvbGxlY3RvciBzdGlsbCBjYXB0dXJlcyBWTQogICAgIyBzZXJpYWwvc3RkZXJyIGlmIHRoaXMgc2lkZWNhciBpdHNlbGYgbmVlZHMgZGVidWdnaW5nLgogICAgbG9nZ2luZzoKICAgICAgZHJpdmVyOiAibG9jYWwiCiAgICAgIG9wdGlvbnM6CiAgICAgICAgbWF4LXNpemU6ICIyMG0iCiAgICAgICAgbWF4LWZpbGU6ICIzIgoKCiAgY2VydGJvdDoKICAgIGltYWdlOiBjZXJ0Ym90L2Rucy1jbG91ZGZsYXJlQHNoYTI1Njo3NDJkYmQyZTYxYzg3MDliOTMwNzEyYzM4OTU4Mzg2YzNjYjM5MjhlMDllZWIxZjFlNDkwNjAwYzEyN2UyZWRiCiAgICByZXN0YXJ0OiB1bmxlc3Mtc3RvcHBlZAogICAgZW50cnlwb2ludDogIi9iaW4vc2ggLWMgJ3RyYXAgZXhpdCBURVJNOyB3aGlsZSA6OyBkbyBjZXJ0Ym90IHJlbmV3OyBzbGVlcCAxMmggJiB3YWl0ICQkeyF9OyBkb25lOyciCiAgICB2b2x1bWVzOgogICAgICAtIGNlcnRzOi9ldGMvbGV0c2VuY3J5cHQKICAgIGNvbmZpZ3M6CiAgICAgIC0gc291cmNlOiBjbG91ZGZsYXJlX2NyZWRlbnRpYWxzCiAgICAgICAgdGFyZ2V0OiAvZXRjL2Nsb3VkZmxhcmUvY3JlZGVudGlhbHMuaW5pCiAgICAgICAgbW9kZTogMDYwMAogICAgZGVwZW5kc19vbjoKICAgICAgY2VydGJvdC1pbml0OgogICAgICAgIGNvbmRpdGlvbjogc2VydmljZV9jb21wbGV0ZWRfc3VjY2Vzc2Z1bGx5CiAgICBsYWJlbHM6CiAgICAgIGNvbS5kYXRhZG9naHEuYWQubG9nczogJ1t7InNvdXJjZSI6ICJkbnMtY2xvdWRmbGFyZSIsICJzZXJ2aWNlIjogImRucy1jbG91ZGZsYXJlIn1dJwogICAgbG9nZ2luZzoKICAgICAgZHJpdmVyOiAianNvbi1maWxlIgogICAgICBvcHRpb25zOgogICAgICAgIG1heC1zaXplOiAiMTAwbSIKICAgICAgICBtYXgtZmlsZTogIjEwIgogICAgICAgIGxhYmVsczogImNvbS5kYXRhZG9naHEuYWQubG9ncyxjb20uZG9ja2VyLmNvbXBvc2Uuc2VydmljZSIKCgogIGNlcnRib3QtaW5pdDoKICAgIGltYWdlOiBjZXJ0Ym90L2Rucy1jbG91ZGZsYXJlQHNoYTI1Njo3NDJkYmQyZTYxYzg3MDliOTMwNzEyYzM4OTU4Mzg2YzNjYjM5MjhlMDllZWIxZjFlNDkwNjAwYzEyN2UyZWRiCiAgICB2b2x1bWVzOgogICAgICAtIGNlcnRzOi9ldGMvbGV0c2VuY3J5cHQKICAgIHJlc3RhcnQ6IG9uLWZhaWx1cmUKICAgIGNvbmZpZ3M6CiAgICAgIC0gc291cmNlOiBjbG91ZGZsYXJlX2NyZWRlbnRpYWxzCiAgICAgICAgdGFyZ2V0OiAvZXRjL2Nsb3VkZmxhcmUvY3JlZGVudGlhbHMuaW5pCiAgICAgICAgbW9kZTogMDYwMAogICAgY29tbWFuZDogPgogICAgICBjZXJ0b25seQogICAgICAtLWRucy1jbG91ZGZsYXJlCiAgICAgIC0tZG5zLWNsb3VkZmxhcmUtY3JlZGVudGlhbHMgL2V0Yy9jbG91ZGZsYXJlL2NyZWRlbnRpYWxzLmluaQogICAgICAtLWRucy1jbG91ZGZsYXJlLXByb3BhZ2F0aW9uLXNlY29uZHMgMzAKICAgICAgLS1lbWFpbCAke0NFUlRCT1RfRU1BSUx9CiAgICAgIC0tYWdyZWUtdG9zIC0tbm8tZWZmLWVtYWlsIC0tbm9uLWludGVyYWN0aXZlCiAgICAgIC0ta2V5LXR5cGUgZWNkc2EgLS1lbGxpcHRpYy1jdXJ2ZSBzZWNwMjU2cjEKICAgICAgLS1jZXJ0LW5hbWUgY29tcGxldGlvbnMubmVhci5haQogICAgICAtZCAiKi5jb21wbGV0aW9ucy5uZWFyLmFpIgogICAgICAtZCAiY29tcGxldGlvbnMubmVhci5haSIKICAgICAgLWQgIiRIT1NUTkFNRS5ob3N0cy5uZWFyLmFpIgogICAgbGFiZWxzOgogICAgICBjb20uZGF0YWRvZ2hxLmFkLmxvZ3M6ICdbeyJzb3VyY2UiOiAiZG5zLWNsb3VkZmxhcmUiLCAic2VydmljZSI6ICJkbnMtY2xvdWRmbGFyZSJ9XScKICAgIGxvZ2dpbmc6CiAgICAgIGRyaXZlcjogImpzb24tZmlsZSIKICAgICAgb3B0aW9uczoKICAgICAgICBtYXgtc2l6ZTogIjEwMG0iCiAgICAgICAgbWF4LWZpbGU6ICIxMCIKICAgICAgICBsYWJlbHM6ICJjb20uZGF0YWRvZ2hxLmFkLmxvZ3MsY29tLmRvY2tlci5jb21wb3NlLnNlcnZpY2UiCgoKICBjb21wb3NlLW1hbmFnZXI6CiAgICBpbWFnZTogJHtDT01QT1NFX01BTkFHRVJfSU1BR0U6LW5lYXJhaWRldi9jb21wb3NlLW1hbmFnZXJAc2hhMjU2OmI0ODdmMzkxNjBlOWE1M2MzZDk4OTQzYTljNzA5ZDI4ZTEyYmFiZWY3NWUwYmI1YTZjZDU2OTJhYmM4YjJkYjZ9CiAgICBjb250YWluZXJfbmFtZTogY29tcG9zZS1tYW5hZ2VyCiAgICByZXN0YXJ0OiB1bmxlc3Mtc3RvcHBlZAogICAgcG9ydHM6CiAgICAgIC0gIjgwODA6ODA4MCIKICAgICMgcGlkOmhvc3QgKyBDQVBfU1lTX0FETUlOICsgQ0FQX1NZU19QVFJBQ0UgbGV0IGNvbXBvc2UtbWFuYWdlciBydW4KICAgICMgYG5zZW50ZXIgLXQgMSAtbSAtdSAtaSAtbiAtcCAtLSAuLi5gIGludG8gUElEIDEncyBuYW1lc3BhY2VzLgogICAgIyAgIC0gU1lTX1BUUkFDRTogbmVlZGVkIHRvIG9wZW4gL3Byb2MvMS9ucy8qIChwdHJhY2VfbWF5X2FjY2VzcyBnYXRlcwogICAgIyAgICAgdGhpcyDigJQgUElEIDEgaGFzIGBkdW1wYWJsZT0wYCBiZWNhdXNlIGl0IHdhcyBsYXVuY2hlZCB3aXRoIGEgZnVsbAogICAgIyAgICAgY2FwIHNldCwgc28gZXZlbiByb290IGluIHRoZSBzYW1lIHVzZXItbnMgbmVlZHMgU1lTX1BUUkFDRSkuCiAgICAjICAgLSBTWVNfQURNSU46IG5lZWRlZCBmb3IgdGhlIHNldG5zKCkgdGhhdCBmb2xsb3dzIHRoZSBvcGVuLgogICAgIyBXaXRob3V0IEJPVEgsIGV2ZXJ5IG5zZW50ZXIgY2FsbCBmYWlscyB3aXRoICJjYW5ub3Qgb3BlbiAvcHJvYy8xL25zLyo6CiAgICAjIFBlcm1pc3Npb24gZGVuaWVkIiDigJQgYWZmZWN0aW5nIGAvZHN0YWNrLWFnZW50LzphY3Rpb25gIChwcmUtZXhpc3RpbmcpCiAgICAjIGFuZCBgL2FkbWluL2tlcm5lbC9hbGdpZi1ibGFja2xpc3RgIChQUiAjMTQgLyBDVkUtMjAyNi0zMTQzMSkuCiAgICAjCiAgICAjIE5PVEUgb24gQXBwQXJtb3I6IG9uIGEgaG9zdCB3aXRoIEFwcEFybW9yIGVuYWJsZWQsIGRvY2tlci1kZWZhdWx0IGFsc28KICAgICMgYmxvY2tzIHRoaXMgcmVnYXJkbGVzcyBvZiBjYXBzOyB0aGUgd29ya2FibGUgY29tYm8gdGhlcmUgaXMKICAgICMgYGNhcF9hZGQ6IFtTWVNfQURNSU4sIFNZU19QVFJBQ0VdYCBQTFVTIGBzZWN1cml0eV9vcHQ6CiAgICAjIGFwcGFybW9yPXVuY29uZmluZWRgLiBkc3RhY2stT1MgZG9lcyBOT1Qgc2hpcCBBcHBBcm1vciAodmVyaWZpZWQKICAgICMgMjAyNi0wNS0xOSDigJQgbm8gYXBwYXJtb3IgYmluYXJpZXMvcHJvZmlsZXMgaW4gdGhlIHJvb3Rmcywgbm8gQXBwQXJtb3IKICAgICMgc3RyaW5ncyBpbiBiekltYWdlKSwgc28gd2Ugb21pdCB0aGUgYHNlY3VyaXR5X29wdGAgbGluZS4gSWYgYSBmdXR1cmUKICAgICMgZHN0YWNrLU9TIGltYWdlIGVuYWJsZXMgQXBwQXJtb3IsIHRoaXMgdGVtcGxhdGUgbXVzdCBhZGQgaXQuCiAgICBwaWQ6IGhvc3QKICAgIGNhcF9hZGQ6CiAgICAgIC0gU1lTX0FETUlOCiAgICAgIC0gU1lTX1BUUkFDRQogICAgdm9sdW1lczoKICAgICAgLSAvdmFyL3J1bi9kb2NrZXIuc29jazovdmFyL3J1bi9kb2NrZXIuc29jawogICAgICAtIC92YXIvcnVuL2RzdGFjay5zb2NrOi92YXIvcnVuL2RzdGFjay5zb2NrCiAgICAgIC0gL2RzdGFjay8uaG9zdC1zaGFyZWQvLmRlY3J5cHRlZC1lbnY6L2FwcC8uZW52OnJvCiAgICAgIC0gd29yazovYXBwL3dvcmsKICAgIGVudmlyb25tZW50OgogICAgICAtIEdJVEhVQl9SRVBPPWh0dHBzOi8vZ2l0aHViLmNvbS9uZWFyYWkvY3ZtLWNvbXBvc2UtZmlsZXMKICAgICAgLSBCRUFSRVJfVE9LRU49JHtCRUFSRVJfVE9LRU59CiAgICAgIC0gV09SS19ESVI9L2FwcC93b3JrCiAgICAgIC0gTUlOX1RBR19BR0VfSE9VUlM9JHtDTV9NSU5fVEFHX0FHRV9IT1VSUzotMH0KICAgICAgLSBFTlZfRklMRVM9L2FwcC8uZW52CiAgICAgICMgU2xhY2sgb3BzIG5vdGlmaWNhdGlvbnMgKG5lYXJhaS9pbmZyYSMxNDEpLiBJTlNUQU5DRV9MQUJFTCBpZGVudGlmaWVzCiAgICAgICMgdGhpcyBob3N0IGluIHRoZSBtZXNzYWdlICgiRGVwbG95ZWQgb24gZ3B1WFgiKTsgU0xBQ0tfV0VCSE9PS19VUkwgaXMgYQogICAgICAjIHNlY3JldCBzb3VyY2VkIGZyb20gdGhlIENWTSBkZWNyeXB0ZWQgZW52IChlbXB0eSA9IG5vdGlmaWNhdGlvbnMgb2ZmKS4KICAgICAgLSBJTlNUQU5DRV9MQUJFTD1ncHUwMwogICAgICAtIFNMQUNLX1dFQkhPT0tfVVJMPSR7U0xBQ0tfV0VCSE9PS19VUkw6LX0KICAgIGxhYmVsczoKICAgICAgY29tLmRhdGFkb2docS5hZC5sb2dzOiAnW3sic291cmNlIjogImNvbXBvc2UtbWFuYWdlciIsICJzZXJ2aWNlIjogImNvbXBvc2UtbWFuYWdlciJ9XScKICAgIGxvZ2dpbmc6CiAgICAgIGRyaXZlcjogImpzb24tZmlsZSIKICAgICAgb3B0aW9uczoKICAgICAgICBtYXgtc2l6ZTogIjEwMG0iCiAgICAgICAgbWF4LWZpbGU6ICIxMCIKICAgICAgICBsYWJlbHM6ICJjb20uZGF0YWRvZ2hxLmFkLmxvZ3MsY29tLmRvY2tlci5jb21wb3NlLnNlcnZpY2UiCgoKICBjb21wb3NlLW1hbmFnZXItbGF1bmNoZXI6CiAgICBpbWFnZTogbmVhcmFpZGV2L2NvbXBvc2UtbWFuYWdlci1sYXVuY2hlckBzaGEyNTY6NzhhZmI4MjMzMDEzN2MyZmQ5MGE5YzhjNmU0NTJmOGYxNzhjMTVkODYzZjg3YzA3OWExMWMxM2U5MjIxNmUwMAogICAgY29udGFpbmVyX25hbWU6IGNvbXBvc2UtbWFuYWdlci1sYXVuY2hlcgogICAgcmVzdGFydDogdW5sZXNzLXN0b3BwZWQKICAgICMgSG9zdCBuZXR3b3JrIHNvIHRoZSBsYXVuY2hlcidzIGhlYWx0aCBjaGVjayAoTEFVTkNIRVJfSEVBTFRIX1VSTCBiZWxvdywKICAgICMgaHR0cDovLzEyNy4wLjAuMTo4MDgwL3ZlcnNpb24pIGNhbiByZWFjaCBjb21wb3NlLW1hbmFnZXIsIHdoaWNoIHB1Ymxpc2hlcwogICAgIyA4MDgwIG9uIHRoZSBndWVzdCBob3N0IHZpYSBgcG9ydHM6IDgwODA6ODA4MGAuIE9uIHRoZSBkZWZhdWx0IGJyaWRnZSwKICAgICMgMTI3LjAuMC4xIGlzIHRoZSBsYXVuY2hlcidzIG93biBsb29wYmFjayBhbmQgdGhlIGNoZWNrIGNhbiBuZXZlciBwYXNzIOKAlAogICAgIyBldmVyeSBzd2FwL3JvbGxiYWNrIHdvdWxkIGJlIHdyb25nbHkgZGVjbGFyZWQgZmFpbGVkLgogICAgbmV0d29ya19tb2RlOiBob3N0CiAgICB2b2x1bWVzOgogICAgICAtIC92YXIvcnVuL2RvY2tlci5zb2NrOi92YXIvcnVuL2RvY2tlci5zb2NrCiAgICAgICMgTW91bnRlZCByZWFkLW9ubHkgc28gdGhlIGxhdW5jaGVyIGNhbiBwYXNzIGl0IGFzIC0tZW52LWZpbGUgdG8gZG9ja2VyIGNvbXBvc2UsCiAgICAgICMgYWxsb3dpbmcgQkVBUkVSX1RPS0VOIGFuZCBvdGhlciBzZWNyZXRzIHRvIGJlIHN1YnN0aXR1dGVkIGluIHRoZSBidW5kbGVkCiAgICAgICMgY29tcG9zZSBmaWxlIHdpdGhvdXQgdGhlIGxhdW5jaGVyIGhvbGRpbmcgdGhlbSBpbiBpdHMgb3duIGVudmlyb25tZW50LgogICAgICAtIC9kc3RhY2svLmhvc3Qtc2hhcmVkLy5kZWNyeXB0ZWQtZW52Oi9kc3RhY2stZW52OnJvCiAgICAgIC0gd29yazovYXBwL3dvcmsKICAgIGVudmlyb25tZW50OgogICAgICAtIENPTVBPU0VfTUFOQUdFUl9JTUFHRV9SRVBPPW5lYXJhaWRldi9jb21wb3NlLW1hbmFnZXIKICAgICAgIyBLZWVwIGxhdW5jaGVyLWRyaXZlbiByZWNyZWF0ZXMgYWxpZ25lZCB3aXRoIHRoZSBpbml0aWFsbHkgYm9vdGVkCiAgICAgICMgY29tcG9zZS1tYW5hZ2VyIGNvbnRhaW5lcjsgdGhlIGxhdW5jaGVyJ3MgYnVuZGxlZCBjb21wb3NlIGZpbGUgYWxzbwogICAgICAjIGNvbnN1bWVzIHRoaXMgdmFsdWUuCiAgICAgIC0gQ01fTUlOX1RBR19BR0VfSE9VUlM9JHtDTV9NSU5fVEFHX0FHRV9IT1VSUzotMH0KICAgICAgLSBMQVVOQ0hFUl9DSEFOTkVMPSR7TEFVTkNIRVJfQ0hBTk5FTDotbGF0ZXN0fQogICAgICAtIExBVU5DSEVSX0NPTVBPU0VfUFJPSkVDVD1kc3RhY2sKICAgICAgLSBMQVVOQ0hFUl9FTlZfRklMRT0vYXBwL3dvcmsvLmVudi5sYXVuY2hlcgogICAgICAtIExBVU5DSEVSX0JBU0VfRU5WX0ZJTEU9L2RzdGFjay1lbnYKICAgICAgLSBMQVVOQ0hFUl9IRUFMVEhfVVJMPWh0dHA6Ly8xMjcuMC4wLjE6ODA4MC92ZXJzaW9uCiAgICAgICMgY29tcG9zZS1tYW5hZ2VyIGRvZXMgVExTL2NlcnQgKyBTMyArIGdpdCB3b3JrIG9uIHN0YXJ0dXAsIHNvIGEgY29udGFpbmVyCiAgICAgICMgcmVjcmVhdGUgY2FuIHRha2UgPjYwcyB0byBhbnN3ZXIgL3ZlcnNpb24uIDYwcyBjYXVzZWQgYSBnZW51aW5lIHN3YXAgKGFuZAogICAgICAjIGl0cyByb2xsYmFjaykgdG8gYmUgZGVjbGFyZWQgZmFpbGVkIG1pZC1zdGFydHVwIC0+IGZhbHNlIDMwLW1pbiBiYWNrb2ZmLgogICAgICAtIExBVU5DSEVSX0hFQUxUSF9USU1FT1VUPTE4MAogICAgICAtIExBVU5DSEVSX0NPU0lHTl9JREVOVElUWV9SRUdFWFA9aHR0cHM6Ly9naXRodWIuY29tL25lYXJhaS9jb21wb3NlLW1hbmFnZXIvLmdpdGh1Yi93b3JrZmxvd3MvLioKICAgICAgLSBET0NLRVJfUkVHSVNUUllfVVNFUj0ke0RPQ0tFUl9SRUdJU1RSWV9VU0VSOi19CiAgICAgIC0gRE9DS0VSX1JFR0lTVFJZX1RPS0VOPSR7RE9DS0VSX1JFR0lTVFJZX1RPS0VOOi19CiAgICAgIC0gTEFVTkNIRVJfU1RBVEVfRklMRT0vYXBwL3dvcmsvbGF1bmNoZXItc3RhdGUuanNvbgogICAgZGVwZW5kc19vbjoKICAgICAgLSBjb21wb3NlLW1hbmFnZXIKICAgIGxhYmVsczoKICAgICAgY29tLmRhdGFkb2docS5hZC5sb2dzOiAnW3sic291cmNlIjogImNvbXBvc2UtbWFuYWdlci1sYXVuY2hlciIsICJzZXJ2aWNlIjogImNvbXBvc2UtbWFuYWdlci1sYXVuY2hlciJ9XScKICAgIGxvZ2dpbmc6CiAgICAgIGRyaXZlcjogImpzb24tZmlsZSIKICAgICAgb3B0aW9uczoKICAgICAgICBtYXgtc2l6ZTogIjEwMG0iCiAgICAgICAgbWF4LWZpbGU6ICIxMCIKICAgICAgICBsYWJlbHM6ICJjb20uZGF0YWRvZ2hxLmFkLmxvZ3MsY29tLmRvY2tlci5jb21wb3NlLnNlcnZpY2UiCgoKbmV0d29ya3M6CiAgIyBNb2RlbCBjb21wb3NlIGZpbGVzIGF0dGFjaCB0byB0aGlzIHNoYXJlZCBuZXR3b3JrIHNvIGFwcC1zaWRlIHRlbGVtZXRyeSBjYW4KICAjIHJlYWNoIG1hbmFnZW1lbnQgc2VydmljZXMgc3VjaCBhcyBkYXRhZG9nLWFnZW50IHdpdGhvdXQgcmVzdGFydGluZyB0aGUKICAjIGNvbXBvc2UtbWFuYWdlciBDVk0gZm9yIG1vZGVsIHNjcmFwZS9sb2cvdHJhY2UgY2hhbmdlcy4KICBkZWZhdWx0OgogICAgbmFtZTogZHN0YWNrX2RlZmF1bHQKCnZvbHVtZXM6CiAgb3RlbGNvbC1tYW5hZ2VtZW50LXN0b3JhZ2U6CgogIHdvcms6CiAgIyBUaGlzIHZvbHVtZSBjYW4gYmUgcmUtdXNlZCBieSBvdGhlciBjb250YWluZXJzIHRoYXQgbmVlZCBhY2Nlc3MgdG8gdGhlIFRMUyBjZXJ0aWZpY2F0ZXMuCiAgY2VydHM6CiAgICBuYW1lOiBjZXJ0cwoKY29uZmlnczoKICBjbG91ZGZsYXJlX2NyZWRlbnRpYWxzOgogICAgY29udGVudDogfAogICAgICBkbnNfY2xvdWRmbGFyZV9hcGlfdG9rZW4gPSAke0NMT1VERkxBUkVfQVBJX1RPS0VOfQoKICAjIEpvdXJuYWxkIGNvbmYgZmlsZSBjb250ZW50CiAgam91cm5hbGRfY29uZmlnX2ZpbGU6CiAgICBjb250ZW50OiB8CiAgICAgIGxvZ3M6CiAgICAgICAgLSB0eXBlOiBqb3VybmFsZAogICAgICAgICAgY29udGFpbmVyX21vZGU6IHRydWUKICAgICAgICAgICMgVE9ETzogQWRkIGZpbHRlcnMgdG8gcmVkdWNlIGxvZyB2b2x1bWUKCiAgIyBvdGVsY29sLW1hbmFnZW1lbnQgY29uZmlnIGZvciBjb21wb3NlLW1hbmFnZXIvbGF1bmNoZXIgdGVsZW1ldHJ5IG9ubHkuCiMgRG9sbGFyIHNpZ25zIGFyZSBlc2NhcGVkIGFzICQkIHNvIGRvY2tlciBjb21wb3NlIGxlYXZlcyBPVGVsJ3MKIyAke2VudjouLi59IHJlZmVyZW5jZXMgaW50YWN0LgogIG90ZWxjb2xfbWFuYWdlbWVudF9jb25maWc6CiAgICBjb250ZW50OiB8CiAgICAgIHJlY2VpdmVyczoKICAgICAgICBmaWxlbG9nL2RvY2tlcl9jb250YWluZXJzOgogICAgICAgICAgaW5jbHVkZTogWy92YXIvbGliL2RvY2tlci9jb250YWluZXJzLyovKi1qc29uLmxvZ10KICAgICAgICAgIHN0YXJ0X2F0OiBlbmQKICAgICAgICAgIHN0b3JhZ2U6IGZpbGVfc3RvcmFnZQogICAgICAgICAgaW5jbHVkZV9maWxlX3BhdGg6IHRydWUKICAgICAgICAgIG9wZXJhdG9yczoKICAgICAgICAgICAgLSB0eXBlOiBqc29uX3BhcnNlcgogICAgICAgICAgICAgIHBhcnNlX2Zyb206IGJvZHkKICAgICAgICAgICAgICBwYXJzZV90bzogYXR0cmlidXRlcwogICAgICAgICAgICAgIHRpbWVzdGFtcDoKICAgICAgICAgICAgICAgIHBhcnNlX2Zyb206IGF0dHJpYnV0ZXMudGltZQogICAgICAgICAgICAgICAgbGF5b3V0X3R5cGU6IGdvdGltZQogICAgICAgICAgICAgICAgbGF5b3V0OiAnMjAwNi0wMS0wMlQxNTowNDowNS45OTk5OTk5OTlaMDc6MDAnCiAgICAgICAgICAgICAgb25fZXJyb3I6IHNlbmRfcXVpZXQKICAgICAgICAgICAgLSB0eXBlOiBtb3ZlCiAgICAgICAgICAgICAgZnJvbTogYXR0cmlidXRlcy5sb2cKICAgICAgICAgICAgICB0bzogYm9keQogICAgICAgICAgICAgIGlmOiAnYXR0cmlidXRlcy5sb2cgIT0gbmlsJwogICAgICAgICAgICAtIHR5cGU6IG1vdmUKICAgICAgICAgICAgICBmcm9tOiBhdHRyaWJ1dGVzLnN0cmVhbQogICAgICAgICAgICAgIHRvOiBhdHRyaWJ1dGVzWyJsb2cuaW9zdHJlYW0iXQogICAgICAgICAgICAgIGlmOiAnYXR0cmlidXRlcy5zdHJlYW0gIT0gbmlsJwogICAgICAgICAgICAtIHR5cGU6IHJlbW92ZQogICAgICAgICAgICAgIGZpZWxkOiBhdHRyaWJ1dGVzLnRpbWUKICAgICAgICAgICAgICBpZjogJ2F0dHJpYnV0ZXMudGltZSAhPSBuaWwnCiAgICAgICAgICAgIC0gdHlwZTogcmVnZXhfcGFyc2VyCiAgICAgICAgICAgICAgcGFyc2VfZnJvbTogYXR0cmlidXRlc1sibG9nLmZpbGUucGF0aCJdCiAgICAgICAgICAgICAgcmVnZXg6ICcvdmFyL2xpYi9kb2NrZXIvY29udGFpbmVycy8oP1A8Y29udGFpbmVyX2lkPlthLWYwLTldezEyfSlbYS1mMC05XSovJwogICAgICAgICAgICAgIG9uX2Vycm9yOiBzZW5kX3F1aWV0CiAgICAgICAgICAgIC0gdHlwZTogYWRkCiAgICAgICAgICAgICAgZmllbGQ6IGF0dHJpYnV0ZXNbInNlcnZpY2UubmFtZSJdCiAgICAgICAgICAgICAgdmFsdWU6IGN2bS1kb2NrZXIKCiAgICAgICAgcHJvbWV0aGV1cy9zZWxmOgogICAgICAgICAgY29uZmlnOgogICAgICAgICAgICBzY3JhcGVfY29uZmlnczoKICAgICAgICAgICAgICAtIGpvYl9uYW1lOiBvdGVsY29sLW1hbmFnZW1lbnQKICAgICAgICAgICAgICAgIHNjcmFwZV9pbnRlcnZhbDogMzBzCiAgICAgICAgICAgICAgICBzdGF0aWNfY29uZmlnczoKICAgICAgICAgICAgICAgICAgLSB0YXJnZXRzOiBbJ2xvY2FsaG9zdDo4ODg4J10KCiAgICAgIHByb2Nlc3NvcnM6CiAgICAgICAgbWVtb3J5X2xpbWl0ZXI6CiAgICAgICAgICBjaGVja19pbnRlcnZhbDogMXMKICAgICAgICAgIGxpbWl0X21pYjogNTEyCiAgICAgICAgICBzcGlrZV9saW1pdF9taWI6IDEyOAoKICAgICAgICByZXNvdXJjZToKICAgICAgICAgIGF0dHJpYnV0ZXM6CiAgICAgICAgICAgIC0ga2V5OiBob3N0Lm5hbWUKICAgICAgICAgICAgICB2YWx1ZTogJCR7ZW52OkREX0hPU1ROQU1FfQogICAgICAgICAgICAgIGFjdGlvbjogdXBzZXJ0CiAgICAgICAgICAgIC0ga2V5OiBjdm0ubmFtZQogICAgICAgICAgICAgIHZhbHVlOiAkJHtlbnY6Q1ZNX05BTUV9CiAgICAgICAgICAgICAgYWN0aW9uOiB1cHNlcnQKICAgICAgICAgICAgLSBrZXk6IGhvc3QubWFjaGluZQogICAgICAgICAgICAgIHZhbHVlOiAkJHtlbnY6Q1ZNX0hPU1R9CiAgICAgICAgICAgICAgYWN0aW9uOiBpbnNlcnQKICAgICAgICAgICAgLSBrZXk6IGRlcGxveW1lbnQuZW52aXJvbm1lbnQKICAgICAgICAgICAgICB2YWx1ZTogJCR7ZW52OkVOVn0KICAgICAgICAgICAgICBhY3Rpb246IHVwc2VydAogICAgICAgICAgICAtIGtleTogc2VydmljZS5uYW1lc3BhY2UKICAgICAgICAgICAgICB2YWx1ZTogbmVhci1haQogICAgICAgICAgICAgIGFjdGlvbjogaW5zZXJ0CgogICAgICAgIHRyYW5zZm9ybS9kb2NrZXJfbWV0YWRhdGE6CiAgICAgICAgICBlcnJvcl9tb2RlOiBpZ25vcmUKICAgICAgICAgIGxvZ19zdGF0ZW1lbnRzOgogICAgICAgICAgICAtIGNvbnRleHQ6IGxvZwogICAgICAgICAgICAgIHN0YXRlbWVudHM6CiAgICAgICAgICAgICAgICAtIHNldChhdHRyaWJ1dGVzWyJzZXJ2aWNlLm5hbWUiXSwgYXR0cmlidXRlc1siYXR0cnMiXVsiY29tLmRvY2tlci5jb21wb3NlLnNlcnZpY2UiXSkgd2hlcmUgYXR0cmlidXRlc1siYXR0cnMiXSAhPSBuaWwgYW5kIGF0dHJpYnV0ZXNbImF0dHJzIl1bImNvbS5kb2NrZXIuY29tcG9zZS5zZXJ2aWNlIl0gIT0gbmlsCiAgICAgICAgICAgICAgICAtIHNldChhdHRyaWJ1dGVzWyJzZXJ2aWNlLm5hbWUiXSwgUGFyc2VKU09OKGF0dHJpYnV0ZXNbImF0dHJzIl1bImNvbS5kYXRhZG9naHEuYWQubG9ncyJdKVswXVsic2VydmljZSJdKSB3aGVyZSBhdHRyaWJ1dGVzWyJhdHRycyJdICE9IG5pbCBhbmQgYXR0cmlidXRlc1siYXR0cnMiXVsiY29tLmRhdGFkb2docS5hZC5sb2dzIl0gIT0gbmlsCiAgICAgICAgICAgICAgICAtIHNldChhdHRyaWJ1dGVzWyJsb2cuc291cmNlIl0sIFBhcnNlSlNPTihhdHRyaWJ1dGVzWyJhdHRycyJdWyJjb20uZGF0YWRvZ2hxLmFkLmxvZ3MiXSlbMF1bInNvdXJjZSJdKSB3aGVyZSBhdHRyaWJ1dGVzWyJhdHRycyJdICE9IG5pbCBhbmQgYXR0cmlidXRlc1siYXR0cnMiXVsiY29tLmRhdGFkb2docS5hZC5sb2dzIl0gIT0gbmlsCiAgICAgICAgICAgICAgICAtIHNldChhdHRyaWJ1dGVzWyJjb250YWluZXIubmFtZSJdLCBhdHRyaWJ1dGVzWyJhdHRycyJdWyJjb20uZG9ja2VyLmNvbXBvc2Uuc2VydmljZSJdKSB3aGVyZSBhdHRyaWJ1dGVzWyJhdHRycyJdICE9IG5pbCBhbmQgYXR0cmlidXRlc1siYXR0cnMiXVsiY29tLmRvY2tlci5jb21wb3NlLnNlcnZpY2UiXSAhPSBuaWwKCiAgICAgICAgZmlsdGVyL21hbmFnZW1lbnRfbG9nczoKICAgICAgICAgIGVycm9yX21vZGU6IGlnbm9yZQogICAgICAgICAgbG9nczoKICAgICAgICAgICAgbG9nX3JlY29yZDoKICAgICAgICAgICAgICAtICdhdHRyaWJ1dGVzWyJzZXJ2aWNlLm5hbWUiXSAhPSAiY29tcG9zZS1tYW5hZ2VyIiBhbmQgYXR0cmlidXRlc1sic2VydmljZS5uYW1lIl0gIT0gImNvbXBvc2UtbWFuYWdlci1sYXVuY2hlciIgYW5kIGF0dHJpYnV0ZXNbInNlcnZpY2UubmFtZSJdICE9ICJkbnMtY2xvdWRmbGFyZSInCgogICAgICAgIGdyb3VwYnlhdHRycy9kb2NrZXJfc2VydmljZToKICAgICAgICAgIGtleXM6CiAgICAgICAgICAgIC0gc2VydmljZS5uYW1lCiAgICAgICAgICAgIC0gY29udGFpbmVyLm5hbWUKICAgICAgICAgICAgLSBsb2cuc291cmNlCiAgICAgICAgICAgIC0gbG9nLmlvc3RyZWFtCgogICAgICAgIGJhdGNoOgogICAgICAgICAgc2VuZF9iYXRjaF9zaXplOiAxMDI0CiAgICAgICAgICBzZW5kX2JhdGNoX21heF9zaXplOiAyMDQ4CiAgICAgICAgICB0aW1lb3V0OiA1cwoKICAgICAgZXhwb3J0ZXJzOgogICAgICAgIG90bHBodHRwL2dhdGV3YXk6CiAgICAgICAgICBlbmRwb2ludDogaHR0cHM6Ly90ZWxlbWV0cnkuaW5mcmEubmVhci5haQogICAgICAgICAgaGVhZGVyczoKICAgICAgICAgICAgQXV0aG9yaXphdGlvbjogIkJlYXJlciAkJHtlbnY6TU9OSVRPUklOR19JTkdFU1RfVE9LRU59IgogICAgICAgICAgc2VuZGluZ19xdWV1ZToKICAgICAgICAgICAgcXVldWVfc2l6ZTogMTI4CiAgICAgICAgICAgIHN0b3JhZ2U6IGZpbGVfc3RvcmFnZQoKICAgICAgZXh0ZW5zaW9uczoKICAgICAgICBmaWxlX3N0b3JhZ2U6CiAgICAgICAgICBkaXJlY3Rvcnk6IC92YXIvbGliL290ZWxjb2wtbWFuYWdlbWVudC9zdG9yYWdlCiAgICAgICAgICBjcmVhdGVfZGlyZWN0b3J5OiB0cnVlCgogICAgICBzZXJ2aWNlOgogICAgICAgIGV4dGVuc2lvbnM6IFtmaWxlX3N0b3JhZ2VdCiAgICAgICAgcGlwZWxpbmVzOgogICAgICAgICAgbG9ncy9jdm06CiAgICAgICAgICAgIHJlY2VpdmVyczogW2ZpbGVsb2cvZG9ja2VyX2NvbnRhaW5lcnNdCiAgICAgICAgICAgIHByb2Nlc3NvcnM6IFttZW1vcnlfbGltaXRlciwgcmVzb3VyY2UsIHRyYW5zZm9ybS9kb2NrZXJfbWV0YWRhdGEsIGZpbHRlci9tYW5hZ2VtZW50X2xvZ3MsIGdyb3VwYnlhdHRycy9kb2NrZXJfc2VydmljZSwgYmF0Y2hdCiAgICAgICAgICAgIGV4cG9ydGVyczogW290bHBodHRwL2dhdGV3YXldCiAgICAgICAgICBtZXRyaWNzL3NlbGY6CiAgICAgICAgICAgIHJlY2VpdmVyczogW3Byb21ldGhldXMvc2VsZl0KICAgICAgICAgICAgcHJvY2Vzc29yczogW21lbW9yeV9saW1pdGVyLCByZXNvdXJjZSwgYmF0Y2hdCiAgICAgICAgICAgIGV4cG9ydGVyczogW290bHBodHRwL2dhdGV3YXldCiAgICAgICAgdGVsZW1ldHJ5OgogICAgICAgICAgbWV0cmljczoKICAgICAgICAgICAgYWRkcmVzczogbG9jYWxob3N0Ojg4ODgKICAgICAgICAgIGxvZ3M6CiAgICAgICAgICAgIGxldmVsOiBpbmZvCg=="

    @Test fun realProductionComposeYieldsDesignTiers() {
        val outer = String(Base64.getDecoder().decode(realComposeB64), Charsets.UTF_8)
        val x = PlaintextExposure.analyze(null, outer)
        assertEquals(Capability.PROCESS_ACCESS, x.capability("nearaidev/compose-manager"))
        assertEquals(Capability.PROCESS_ACCESS, x.capability("nearaidev/compose-manager-launcher"))
        assertEquals(Capability.PROCESS_ACCESS, x.capability("datadog/agent"))
        assertEquals(Capability.LOG_ACCESS, x.capability("otel/opentelemetry-collector-contrib"))
        assertNull(x.capability("certbot/dns-cloudflare"))   // certs volume only
    }

    @Test fun jsonWrappedAppComposeIsUnwrapped() {
        val json = """{"manifest_version":2,"name":"dstack-nvidia-0.5.3","runner":"docker-compose","docker_compose_file":"services:\n  compose-manager:\n    image: nearaidev/compose-manager@sha256:aaa\n    pid: host\n  datadog:\n    image: datadog/agent:7.55\n    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock:ro\n  otelcol:\n    image: otel/opentelemetry-collector-contrib:0.98\n    volumes:\n      - /var/lib/docker/containers:/var/lib/docker/containers:ro\n"}"""
        val x = PlaintextExposure.analyze(null, json)
        assertEquals(Capability.PROCESS_ACCESS, x.capability("nearaidev/compose-manager"))
        assertEquals(Capability.PROCESS_ACCESS, x.capability("datadog/agent"))
        assertEquals(Capability.LOG_ACCESS, x.capability("otel/opentelemetry-collector-contrib"))
        val yaml = "services:\n  cm:\n    image: nearaidev/compose-manager@sha256:b\n    pid: host\n"
        assertEquals(Capability.PROCESS_ACCESS, PlaintextExposure.analyze(null, yaml).capability("nearaidev/compose-manager"))
    }

    /** REGRESSION: an engine that is neither vLLM nor sglang went untagged. The grounded signal is the terminator's own backend list. */
    @Test fun proxyBackendIsTaggedAsModelServerEvenWithNoEngineImageName() {
        val yaml = """
services:
  proxy-privacy-filter:
    image: nearaidev/vllm-proxy-rs@sha256:b183677a
    environment:
      - OHTTP_ENABLED=true
      - MODEL_NAME=openai/privacy-filter
      - VLLM_BASE_URL=http://model-privacy-filter:8000
  model-privacy-filter:
    image: privacy-filter-hf
    build:
      dockerfile_inline: |
        FROM pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime
        RUN pip install transformers fastapi uvicorn
    command: python /app/server.py
""".trimIndent()
        val x = PlaintextExposure.analyze(yaml, null)
        assertEquals(Role.E2EE_TERMINATOR, x.role("nearaidev/vllm-proxy-rs"))
        assertEquals(Role.MODEL_SERVER, x.role("privacy-filter-hf"))

        val multi = """
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:b
    environment:
      - OHTTP_ENABLED=true
      - VLLM_BACKEND_URLS=http://a-srv:8000,http://b-srv:8000
  a-srv:
    image: some-opaque-build
  b-srv:
    image: another-opaque-build
""".trimIndent()
        val y = PlaintextExposure.analyze(multi, null)
        assertEquals(Role.MODEL_SERVER, y.role("some-opaque-build")); assertEquals(Role.MODEL_SERVER, y.role("another-opaque-build"))

        val noProxy = "services:\n  otelcol:\n    image: otel/opentelemetry-collector-contrib@sha256:c\n    volumes:\n      - /var/lib/docker/containers:/var/lib/docker/containers:ro"
        assertNull(PlaintextExposure.analyze(noProxy, null).role("otel/opentelemetry-collector-contrib"))
    }

    /** Android-only: the session's scope seam now scopes a combined node to the requested model. */
    @Test fun sessionSeamScopesTheCombinedNode() {
        val scoped = ConfidentialSession.Services().scopeManifest(combined, "google/gemma-4-31B-it")
        assertFalse(scoped.contains("lmsysorg/sglang")); assertTrue(scoped.contains("vllm/vllm-openai"))
    }
}
