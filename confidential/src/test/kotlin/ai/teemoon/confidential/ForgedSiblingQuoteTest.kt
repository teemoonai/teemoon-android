// ports: teemoonTests/ForgedSiblingQuoteTests.swift @ ba0842d1 (1.1.1 audit A2/A1). everyGenuineListedQuoteStillVerifiesLocally is
//        ForgedQuoteTest.everyGenuineListedQuoteStillVerifies here, and aQuoteSignedByAKeyItCarriesItselfIsNotVerified is its
//        forged-fixture twin below; the fixture is audit's forge of live entry [1] (forge.py, sha256 61d22619...dbc5), byte-identical.
package ai.teemoon.confidential

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections

/** Audit's forged-sibling-entry.json, as iOS's `ForgedSibling` loads it. */
internal object ForgedSibling {
    const val FIXTURE = "/fixtures/nearai-trust/audit_forged_sibling_entry_20261120.json"
    const val FIXTURE_SHA256 = "61d226195311031bebbe3b99b1507d62f6e723a621fa238a3b6f768505dddbc5"

    class Forge(val entry: JsonObject, val nonce: String, val sealedKey: ByteArray) {
        val quoteHex: String get() = entry.getValue("intel_quote").jsonPrimitive.content
        /** The gateway's `?model=` answer listing only the forged enclave. */
        val listBody: ByteArray get() = JsonObject(mapOf("model_attestations" to JsonArray(listOf(entry)))).toString().toByteArray()
    }

    fun load(): Forge {
        val bytes = ForgedSibling::class.java.getResource(FIXTURE)!!.readBytes()
        val root = Json.parseToJsonElement(String(bytes)).jsonObject
        return Forge(root.getValue("entry").jsonObject, root.getValue("nonce").jsonPrimitive.content, root.getValue("sealed_key").jsonPrimitive.content.hexToBytes())
    }

    fun fixtureSha256(): String = MessageDigest.getInstance("SHA-256").digest(ForgedSibling::class.java.getResource(FIXTURE)!!.readBytes()).joinToString("") { "%02x".format(it) }
}

class ForgedSiblingQuoteTest {
    @Test fun theFixtureIsAuditsForgeByteForByte() = assertEquals(ForgedSibling.FIXTURE_SHA256, ForgedSibling.fixtureSha256())

    @Test fun aQuoteSignedByAKeyItCarriesItselfIsNotVerified() {
        assertFalse(TdxQuoteVerifier.verify(ForgedSibling.load().quoteHex).isVerified)
    }

    @Test fun theForgedEnclaveIsAMismatch() {
        val f = ForgedSibling.load()
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.QUOTE_INVALID), ModelCandidates.check(f.entry, f.sealedKey, f.nonce))
    }

    /** Each listed enclave's quote goes through the model leg's DCAP path, not only the local parse; Intel unreachable, so each reads "couldn't check". */
    @Test fun everyListedQuoteGoesThroughDcap() = runTest {
        val c = LiveCandidates.load()
        val record = AttestationRecord.preview.copy(modelCandidates = ModelCandidates.enclaves(c.body, c.nonce, c.key))
        val seen = Collections.synchronizedList(mutableListOf<ByteArray>())
        val collateral = CollateralProvider { quote -> seen.add(quote); throw java.io.IOException("timed out") }
        val result = DcapService(collateral, DcapQvlEngine { _, _, _ -> throw AssertionError("no collateral, no engine") }).verify(record)
        for (listed in c.list) {
            val quote = listed.getValue("intel_quote").jsonPrimitive.content.removePrefix("0x").hexToBytes()
            assertTrue("a listed quote never reached DCAP", seen.any { it.contentEquals(quote) })
        }
        assertTrue(result.hasUnchecked)
    }
}
