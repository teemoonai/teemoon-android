// ports: teemoonTests/PrivateHostTests.swift @ 60c6158 — the private-host half. That Swift suite was WRITTEN from
//        this one: the label-count bug was reported upstream from here on 2026-09-20, and iOS then added the
//        both-ends-of-every-range cases which were mirrored back. What it still had and this did not — the trailing
//        root dot, `::1`, the shipped presets as public hosts — is folded in above.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProviderTest {

    private fun p(endpoint: String) =
        Provider(id = "1", name = "x", endpoint = endpoint, model = "m")

    @Test fun presetMatchKeyForgivesTheChatCompletionsSuffix() {
        assertEquals(
            Provider.presetMatchKey("https://api.example.com/v1"),
            Provider.presetMatchKey("https://api.example.com/v1/chat/completions"))
    }

    @Test fun presetMatchKeyIsCaseAndSlashInsensitive() {
        assertEquals(
            Provider.presetMatchKey("https://API.Example.com/v1/"),
            Provider.presetMatchKey("https://api.example.com/v1"))
    }

    /** Two presets can share a host and differ by path — do not collapse them. */
    @Test fun presetMatchKeyKeepsPathsDistinct() {
        assertTrue(Provider.presetMatchKey("https://h/a") != Provider.presetMatchKey("https://h/b"))
    }

    @Test fun buildsAChatCompletionsUrlEitherWay() {
        assertEquals("https://h/v1/chat/completions", p("https://h/v1").chatCompletionsUrl)
        assertEquals("https://h/v1/chat/completions", p("https://h/v1/chat/completions").chatCompletionsUrl)
    }

    @Test fun recognisesEveryPrivateRange() {
        for (h in listOf("localhost", "::1", "127.0.0.1", "10.1.2.3", "192.168.1.5",
                         "172.16.0.1", "172.31.255.254", "169.254.1.1",
                         "100.64.0.1", "box.local", "mac.local", "host.ts.net",
                         "ringzero.tailnet-name.ts.net", "10.0.2.2")) {
            assertTrue("$h must be private", Provider.isPrivateHost(h))
        }
    }

    /**
     * Every preset teemoon ships is in here on purpose. A regression that made these read private would make the
     * api key optional on every cloud provider at once — the loudest possible version of this bug, and the one
     * least likely to be noticed, because a key is usually present anyway.
     */
    @Test fun rejectsPublicHosts() {
        for (h in listOf("api.openai.com", "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "example.com",
                         "api.near.ai", "api.fireworks.ai", "openrouter.ai", "api.x.ai", "integrate.api.nvidia.com")) {
            assertFalse("$h must be public", Provider.isPrivateHost(h))
        }
    }

    /**
     * EVERY RANGE STOPS WHERE IT SAYS IT DOES.
     *
     * In-range addresses prove a range MATCHES and never prove it ENDS: widening
     * `172.16..31` to `16..32`, or CGNAT to `64..128`, leaves every in-range case
     * passing. That is the same shape of gap as the bypass above, pointed the
     * other way — a range that silently grows hands "stays on your own network"
     * to addresses that are not on it.
     *
     * One address either side of each bound. Raised by the teemoon-ios session
     * after the 2026-09-20 exchange; mirrored here because the guard is this
     * side's and the Swift's suite is not.
     */
    @Test fun everyPrivateRangeStopsAtItsBoundary() {
        val justInside = listOf(
            "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1",
            "192.168.255.254", "169.254.0.1", "169.254.255.254",
            "100.64.0.1", "100.127.255.254", "127.0.0.1", "127.255.255.254",
        )
        val justOutside = listOf(
            "9.255.255.254", "11.0.0.1",
            "172.15.255.254", "172.32.0.1",
            "192.167.1.1", "192.169.1.1",
            "169.253.0.1", "169.255.0.1",
            "100.63.255.254", "100.128.0.1",
            "126.0.0.1", "128.0.0.1",
        )
        for (h in justInside) assertTrue("$h is inside a private range", Provider.isPrivateHost(h))
        for (h in justOutside) assertFalse("$h is outside every private range", Provider.isPrivateHost(h))
    }

    /**
     * A PUBLIC HOST MAY NOT WEAR A PRIVATE PREFIX.
     *
     * Parsing an IPv4 literal by collecting the labels that parse as octets and
     * counting them is the bug: the non-numeric labels are dropped rather than
     * rejecting the host, so `10.0.0.1.attacker.com` yields four octets and
     * reads as 10.0.0.0/8. Out-of-range labels drop the same way, so
     * `10.999.0.0.1.evil.com` passes too.
     *
     * The consequence is not cosmetic. Self-hosted means the key is optional,
     * cleartext http is not warned about, and — the worst of it — the trust chip
     * reads SELF_HOSTED, which tells the user their plaintext is staying on a
     * machine they own while it goes to an attacker's public server. That is the
     * product's central claim, made falsely.
     *
     * This is the iOS bug reported upstream on 2026-09-20 and fixed in e1f8636;
     * the Kotlin always had the label-count and range guards and this pins them.
     * Do NOT "simplify" to a single count of parsed octets.
     */
    @Test fun aPublicHostCannotWearAPrivatePrefix() {
        val attackerHosts = listOf(
            "10.0.0.1.attacker.com", "192.168.1.1.evil.com", "127.0.0.1.evil.com",
            "172.16.0.1.evil.com", "169.254.1.1.evil.com", "100.64.0.1.evil.com",
            "10.999.0.0.1.evil.com", "10.0.0.1.localhost.evil.com",
            // A trailing root dot is a legal, resolvable spelling of the same public name, and it changes the
            // label count — so it is a second way to arrive at the octet parse with different input.
            // Carried over from the Swift suite (PrivateHostTests), which had it and this one did not.
            "10.0.0.1.attacker.com.",
        )
        for (h in attackerHosts) {
            assertFalse("$h must be public", Provider.isPrivateHost(h))
            val provider = p("https://$h/v1")
            assertFalse("$h must not read as self-hosted", provider.isSelfHosted)
            assertEquals("$h must not claim the local trust label", TrustLabel.TLS_ONLY, provider.trustLabel())
        }
    }

    /** Trust is proven, never inferred from a URL. */
    @Test fun cloudIsTlsOnlyUntilAttestationExists() {
        assertEquals(TrustLabel.TLS_ONLY, p("https://api.near.ai/v1").trustLabel())
        assertEquals(TrustLabel.LOCAL, p("http://192.168.1.5:11434/v1").trustLabel())
    }

    /**
     * `isLocal` is on-device, not private-host. `Provider.local` carries
     * endpoint "on-device", which is not a URL — classifying it via isSelfHosted
     * rates the most private place as cloud.
     */
    @Test fun isLocalIsOnDeviceNotPrivateHost() {
        val phone = Provider.local(LocalModelCatalog.all[0])
        assertTrue(phone.isLocal)
        assertFalse(phone.isSelfHosted)
        assertEquals(WhereLocality.PHONE, WhereLocality.of(phone))
        val home = p("http://192.168.1.5:11434/v1")
        assertFalse(home.isLocal)
        assertTrue(home.isSelfHosted)
        assertEquals(WhereLocality.HOME, WhereLocality.of(home))
        val cloud = p("https://api.x.ai/v1")
        assertFalse(cloud.isLocal)
        assertFalse(cloud.isSelfHosted)
        assertEquals(WhereLocality.CLOUD, WhereLocality.of(cloud))
    }

    // MARK: capabilities — the near.ai tier gate (ProviderTests.swift:210-235)

    private fun near(model: String, endpoint: String = "https://cloud-api.near.ai/v1/chat/completions") =
        Provider(id = UUID.randomUUID().toString(), name = "near.ai", endpoint = endpoint, model = model)

    @Test fun capabilities_nearAIConfidentialModelIsAttested() {
        NearAIModelCatalog.resetTierCache()
        val p = near("zai-org/GLM-5.1-FP8")
        assertTrue(ProviderCapability.ATTESTATION in p.capabilities)
        assertTrue(ProviderCapability.END_TO_END_ENCRYPTION in p.capabilities)
    }

    @Test fun capabilities_nearAIProxiedModelIsNotAttested() {
        // near.ai proxies closed frontier models with no enclave — teemoon must
        // not imply attestation/E2EE for them (correctness: no false proof).
        NearAIModelCatalog.resetTierCache()
        for (proxied in listOf("anthropic/claude-opus-4-6", "openai/gpt-5.2", "google/gemini-2.5-pro")) {
            val p = near(proxied)
            assertFalse("$proxied is proxied — no attestation", ProviderCapability.ATTESTATION in p.capabilities)
            assertFalse("$proxied is proxied — no E2EE", ProviderCapability.END_TO_END_ENCRYPTION in p.capabilities)
        }
    }

    @Test fun capabilities_nearAIGptOssAttestedButGpt5NotWithinSameVendor() {
        NearAIModelCatalog.resetTierCache()
        val oss = near("openai/gpt-oss-120b", endpoint = "https://cloud-api.near.ai/v1")
        val gpt5 = near("openai/gpt-5.2", endpoint = "https://cloud-api.near.ai/v1")
        assertTrue(ProviderCapability.ATTESTATION in oss.capabilities)
        assertFalse(ProviderCapability.ATTESTATION in gpt5.capabilities)
    }

    /** The attested-3p tier (Chutes) has no confidential endpoint via near.ai: neither claim. */
    @Test fun capabilities_nearAIThirdPartyTierClaimsNothing() {
        NearAIModelCatalog.resetTierCache()
        val kimi = near("moonshotai/kimi-k2.6")
        assertFalse(ProviderCapability.ATTESTATION in kimi.capabilities)
        assertFalse(ProviderCapability.END_TO_END_ENCRYPTION in kimi.capabilities)
    }

    /** The direct TEE host is used for inference only when the model is attestable; everyone else keeps the gateway base. */
    @Test fun inferenceBaseUrlRoutesAttestedModelsToTheirDirectHost() {
        NearAIModelCatalog.resetTierCache()
        EndpointDirectory.resetPersisted()
        assertEquals("https://cloud-api.near.ai/v1", Provider.nearAI.openAIBaseUrl)
        // The host is whatever near.ai's /endpoints says (c93914c), never a compiled column. Until 2026-09-22 this
        // read a shipped snapshot that had no glm-5.3-flash row, so near.ai's default model routed to the GATEWAY on
        // Android while iOS went to its own TEE — "encrypted — image unpublished" on a Pixel 10 Pro.
        EndpointDirectory.rememberPersisted(mapOf("z-ai/glm-5.3-flash" to "https://glm-5-3-flash.completions.near.ai/v1"))
        val direct = Provider.nearAI.copy(model = "z-ai/glm-5.3-flash")
        assertEquals("https://glm-5-3-flash.completions.near.ai/v1", direct.inferenceBaseUrl)
        assertEquals("https://glm-5-3-flash.completions.near.ai/v1", direct.directGPUNodeUrl)
        // A model the directory does not list routes to the gateway; the session, not this getter, decides by tier
        // whether that degrades the send.
        val proxied = Provider.nearAI.copy(model = "anthropic/claude-opus-4-6")
        assertEquals("https://cloud-api.near.ai/v1", proxied.inferenceBaseUrl)
        assertNull(proxied.directGPUNodeUrl)
        // A cloud without attestation never asks the directory, even for an id it lists.
        assertEquals("https://api.x.ai/v1", Provider.grok.copy(model = "z-ai/glm-5.3-flash").inferenceBaseUrl)
        EndpointDirectory.resetPersisted()
        assertEquals("https://api.x.ai/v1", Provider.grok.inferenceBaseUrl)
        assertNull(Provider.grok.directGPUNodeUrl)
    }

    /**
     * COLD LAUNCH: the send path reads the host synchronously before anything in this process has fetched, so the
     * saved directory must answer — iOS reads UserDefaults on first use. Without it a relaunched app sent every
     * own-fleet model to the gateway until the first attestation happened to load the directory.
     */
    @Test fun aColdLaunchRoutesFromTheSavedDirectory() {
        EndpointDirectory.resetPersisted()
        val saved = """{"endpoints":[{"domain":"glm-5-3-flash.completions.near.ai","models":["z-ai/glm-5.3-flash"]}]}"""
        EndpointDirectory.configurePersistence(object : EndpointDirectory.BytesStore {
            override fun load() = saved.toByteArray()
            override fun save(data: ByteArray) = Unit
        })
        assertEquals("https://glm-5-3-flash.completions.near.ai/v1", Provider.nearAI.copy(model = "z-ai/glm-5.3-flash").inferenceBaseUrl)
        EndpointDirectory.resetPersisted()
    }
}
