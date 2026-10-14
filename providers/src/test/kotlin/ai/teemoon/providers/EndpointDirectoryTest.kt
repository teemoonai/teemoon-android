// ports: teemoonTests/EndpointDirectoryTests.swift @ v1.0.4 (incl. the host-rule and
//        failure-remembered suites; the URLProtocol stub is a fetch lambda here)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDirectoryTest {

    /**
     * Authoritative mappings — including slugs no heuristic would derive.
     * Payload reconstructed from the Swift assertions, not copied from
     * teemoonTests/Fixtures (no kit class for nearai_endpoints_directory.json).
     */
    @Test fun parsesRealDirectory() {
        val map = EndpointDirectory.parseDirectory(SNAPSHOT.toByteArray())!!
        assertEquals("https://glm-5-1.completions.near.ai/v1", map["zai-org/glm-5.1-fp8"])
        assertEquals("https://dsv4-flash.completions.near.ai/v1", map["deepseek-ai/deepseek-v4-flash"])
        assertEquals("https://glm-5-2.completions.near.ai/v1", map["z-ai/glm-5.2"])
        assertEquals("https://glm-5-2.completions.near.ai/v1", map["zai-org/glm-5.2-fp8"])
    }

    @Test fun lookupIsCaseInsensitiveOnModelId() {
        val map = EndpointDirectory.parseDirectory(SNAPSHOT.toByteArray())!!
        assertEquals(
            "https://glm-5-1.completions.near.ai/v1",
            map["ZAI-ORG/GLM-5.1-FP8".lowercase()],
        )
    }

    @Test fun malformedJSONReturnsNil() {
        val empty = EndpointDirectory.parseDirectory("{}".toByteArray())
        assertTrue(empty == null || empty.isEmpty())
        assertNull(EndpointDirectory.parseDirectory("not json".toByteArray()))
    }

    @Test fun absoluteDomainIsNotDoublePrefixed() {
        val data = """{"endpoints":[{"domain":"https://x.completions.near.ai","models":["a/b"]}]}"""
            .toByteArray()
        assertEquals(
            "https://x.completions.near.ai/v1",
            EndpointDirectory.parseDirectory(data)?.get("a/b"),
        )
    }

    @Test fun persistSeedsCacheWhenFetchFails() = runTest {
        val store = MemoryStore(SNAPSHOT.toByteArray())
        val dir = EndpointDirectory(fetch = { null }, store = store)
        assertEquals(
            "https://glm-5-1.completions.near.ai/v1",
            dir.directBase("zai-org/GLM-5.1-FP8"),
        )
    }

    /**
     * A cold miss resolves nothing. It used to be askable as
     * `hasAuthoritativeData()`, and ConfidentialSession used that to decide
     * whether a null host meant "ordinary model" or "couldn't check". That
     * question moved: the answer is now the model's TIER, not the directory's
     * mood — see ConfidentialSession.outcomeWhenNoDirectHost.
     */
    @Test fun coldMissResolvesNothing() = runTest {
        val dir = EndpointDirectory(fetch = { null }, store = MemoryStore(null))
        assertNull(dir.directBase("zai-org/GLM-5.1-FP8"))
    }

    @Test fun fetch200ReplacesCacheAndPersists() = runTest {
        val store = MemoryStore(null)
        val body = SNAPSHOT.toByteArray()
        val dir = EndpointDirectory(fetch = { 200 to body }, store = store)
        assertEquals(
            "https://dsv4-flash.completions.near.ai/v1",
            dir.directBase("deepseek-ai/DeepSeek-V4-Flash"),
        )
        // After the lookup, not before: the fetch is lazy, and the old
        // hasAuthoritativeData() call was what used to trigger it here.
        assertNotNull(store.load())
    }

    private class MemoryStore(private var data: ByteArray?) : EndpointDirectory.BytesStore {
        override fun load(): ByteArray? = data
        override fun save(data: ByteArray) { this.data = data }
    }

    companion object {
        private const val SNAPSHOT = """
        {"endpoints":[
          {"domain":"glm-5-1.completions.near.ai","models":["zai-org/GLM-5.1-FP8"]},
          {"domain":"dsv4-flash.completions.near.ai","models":["deepseek-ai/DeepSeek-V4-Flash"]},
          {"domain":"glm-5-2.completions.near.ai","models":["z-ai/GLM-5.2","zai-org/GLM-5.2-FP8"]}
        ]}
        """
    }

    // MARK: - The host rule (EndpointDirectoryHostRuleTests)
    //
    // The directory chooses where sealed prompts go, so it is also a filter.

    @Test fun nearAIHostsOverHttpsAreAccepted() {
        assertEquals("https://glm-5-3-flash.completions.near.ai",
            EndpointDirectory.confidentialHost("glm-5-3-flash.completions.near.ai"))
        assertEquals("https://dsv4-flash.completions.near.ai",
            EndpointDirectory.confidentialHost("https://dsv4-flash.completions.near.ai"))
    }

    @Test fun everythingElseIsRejected() {
        assertNull(EndpointDirectory.confidentialHost("http://glm.completions.near.ai"))
        assertNull(EndpointDirectory.confidentialHost("glm.completions.near.ai.evil.com"))
        assertNull(EndpointDirectory.confidentialHost("evil.example"))
        assertNull(EndpointDirectory.confidentialHost("glm.completions.near.ai:8443"))
        assertNull(EndpointDirectory.confidentialHost("glm.completions.near.ai/redirect"))
    }

    /** A rejected row drops out of the map rather than poisoning it. */
    @Test fun aRejectedRowIsDroppedNotKept() {
        val json = """
        {"endpoints":[{"domain":"evil.example","models":["z-ai/glm-5.3-flash"]},
                      {"domain":"glm-5-3-flash.completions.near.ai","models":["z-ai/glm-5.3-flash"]}]}
        """.trimIndent()
        val map = EndpointDirectory.parseDirectory(json.toByteArray())!!
        assertEquals("https://glm-5-3-flash.completions.near.ai/v1", map["z-ai/glm-5.3-flash"])
        assertTrue(map.values.none { it.contains("evil.example") })
    }

    // MARK: - A failure is remembered (EndpointDirectoryFailureTests)

    /**
     * `buildModels` asks once per own-fleet row. A host that is down must cost
     * one round trip per minute, not one timeout per row. iOS drives this with a
     * URLProtocol stub returning 503; the fetch seam is the lambda here.
     */
    @Test fun aFailedFetchIsNotRepeatedForEveryRow() = runTest {
        var requests = 0
        val dir = EndpointDirectory(fetch = { requests += 1; 503 to ByteArray(0) }, store = MemoryStore(null))
        for (id in listOf("own/one", "own/two", "own/three")) dir.directBase(id)
        assertEquals(1, requests)
    }

    /** The minute passes and the directory is asked again. */
    @Test fun theFailureIsForgottenAfterItsTtl() = runTest {
        var requests = 0
        var nowMs = 0L
        val dir = EndpointDirectory(
            fetch = { requests += 1; 503 to ByteArray(0) },
            store = MemoryStore(null),
            clockMs = { nowMs },
        )
        dir.directBase("own/one")
        nowMs = 61_000
        dir.directBase("own/two")
        assertEquals(2, requests)
    }

    // MARK: - The synchronous last-good copy

    /**
     * Read on every send and on every render of the verification screen, where
     * suspending is not an option. A successful fetch keeps it in step.
     */
    @Test fun aFetchUpdatesTheSynchronousCopy() = runTest {
        EndpointDirectory.resetPersisted()
        assertNull(EndpointDirectory.persistedBase("zai-org/GLM-5.1-FP8"))
        EndpointDirectory(fetch = { 200 to SNAPSHOT.toByteArray() }, store = MemoryStore(null))
            .directBase("zai-org/GLM-5.1-FP8")
        assertEquals("https://glm-5-1.completions.near.ai/v1",
            EndpointDirectory.persistedBase("zai-org/GLM-5.1-FP8"))
        EndpointDirectory.resetPersisted()
    }
}
