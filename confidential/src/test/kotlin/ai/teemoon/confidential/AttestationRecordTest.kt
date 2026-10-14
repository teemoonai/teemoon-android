// ports: teemoonTests/AttestationRecordTests.swift @ v1.0.1
package ai.teemoon.confidential

import ai.teemoon.confidential.AttestationFixtures.verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttestationRecordTest {

    private fun makeRecord(
        signingAddress: String? = null,
        gpuSigningAddress: String? = null,
        modelEd25519PubKey: ByteArray? = null,
        quoteVerification: TdxVerificationResult? = null,
        modelQuoteVerification: TdxVerificationResult? = null,
        gpuArch: String? = null,
        gatewayNonce: String? = null,
        modelNonce: String? = null,
    ) = AttestationRecord(
        composeHash = "abc", mrtd = "def", osImageHash = "ghi", intelQuote = "",
        gatewayNonce = gatewayNonce, modelNonce = modelNonce,
        composeManifest = null, gpuArch = gpuArch, gpuNodeComposeHash = null, modelFileHash = null,
        signingAddress = signingAddress, gpuSigningAddress = gpuSigningAddress,
        modelEd25519PubKey = modelEd25519PubKey,
        quoteVerification = quoteVerification, gpuQuoteVerification = null, modelQuoteVerification = modelQuoteVerification,
        fetchedAt = Instant.now(), providerID = "p",
    )

    /** 64-byte report_data with [nonceHex] in bytes [32..64]. */
    private fun reportData(echoing: String): ByteArray {
        val data = ByteArray(64)
        echoing.hexToBytesOrNull()?.let { it.copyInto(data, 32, 0, minOf(it.size, 32)) }
        return data
    }

    // MARK: - nonceEchoed

    @Test fun nonceEchoed_trueWhenAllPairsMatch() {
        val nonce = "ab".repeat(32)
        val record = makeRecord(quoteVerification = verification(reportData(nonce)), modelQuoteVerification = verification(reportData(nonce)),
            gatewayNonce = nonce, modelNonce = nonce)
        assertEquals(true, record.nonceEchoed)
    }

    @Test fun nonceEchoed_falseWhenAnyPairMismatches() {
        val nonce = "ab".repeat(32)
        val record = makeRecord(quoteVerification = verification(reportData(nonce)),
            modelQuoteVerification = verification(reportData("cd".repeat(32))), gatewayNonce = nonce, modelNonce = nonce)
        assertEquals(false, record.nonceEchoed)
    }

    @Test fun nonceEchoed_nilWhenNothingCheckable() {
        // No nonces recorded at all.
        assertNull(makeRecord(quoteVerification = verification(ByteArray(64))).nonceEchoed)
        // Nonce recorded but no parsed quote.
        assertNull(makeRecord(gatewayNonce = "aa").nonceEchoed)
    }

    @Test fun nonceEchoed_falseWhenReportDataTooShort() {
        val record = makeRecord(quoteVerification = verification(ByteArray(16)), gatewayNonce = "ab".repeat(32))
        assertEquals(false, record.nonceEchoed)
    }

    @Test fun nonceEchoed_uncheckedQuoteDoesNotBlockOthers() {
        // Gateway pair matches; model quote exists but no nonce was recorded
        // for it (legacy fetch) — result stays true, not nil/false.
        val nonce = "ab".repeat(32)
        val record = makeRecord(quoteVerification = verification(reportData(nonce)),
            modelQuoteVerification = verification(ByteArray(64)), gatewayNonce = nonce)
        assertEquals(true, record.nonceEchoed)
    }

    // MARK: - gpuModelName

    @Test fun gpuModelName_hopper() = assertEquals("NVIDIA H100", makeRecord(gpuArch = "HOPPER").gpuModelName)
    @Test fun gpuModelName_ampere() = assertEquals("NVIDIA A100", makeRecord(gpuArch = "AMPERE").gpuModelName)
    @Test fun gpuModelName_ada() = assertEquals("NVIDIA L40S", makeRecord(gpuArch = "ADA").gpuModelName)
    @Test fun gpuModelName_blackwell() = assertEquals("NVIDIA B200", makeRecord(gpuArch = "BLACKWELL").gpuModelName)
    @Test fun gpuModelName_unknown() = assertEquals("NVIDIA Volta", makeRecord(gpuArch = "VOLTA").gpuModelName)
    @Test fun gpuModelName_nil() = assertNull(makeRecord(gpuArch = null).gpuModelName)
    @Test fun gpuModelName_caseInsensitive() = assertEquals("NVIDIA H100", makeRecord(gpuArch = "hopper").gpuModelName)

    // MARK: - signingKeyBoundToHardware

    @Test fun signingKeyBound_nilWhenNoQuoteVerification() = assertNull(makeRecord(signingAddress = "0xABC").signingKeyBoundToHardware)
    @Test fun signingKeyBound_nilWhenNoSigningAddress() = assertNull(makeRecord().signingKeyBoundToHardware)
    @Test fun signingKeyBound_nilWhenEmptySigningAddress() = assertNull(makeRecord(signingAddress = "").signingKeyBoundToHardware)

    // MARK: - e2eeKeyBoundToModelTEE

    @Test fun e2eeKeyBound_nilWhenNoModelQuoteVerification() =
        assertNull(makeRecord(modelEd25519PubKey = ByteArray(32) { 0xAB.toByte() }).e2eeKeyBoundToModelTEE)
    @Test fun e2eeKeyBound_nilWhenNoKey() = assertNull(makeRecord().e2eeKeyBoundToModelTEE)
    @Test fun e2eeKeyBound_nilWhenKeyWrongLength() =
        assertNull(makeRecord(modelEd25519PubKey = ByteArray(16) { 0xAB.toByte() }).e2eeKeyBoundToModelTEE)

    // MARK: - Preview records

    @Test fun previewRecord_isNotNil() {
        val record = AttestationRecord.preview
        assertTrue(record.composeHash.isNotEmpty())
        assertTrue(record.mrtd.isNotEmpty())
        assertNotNull(record.modelEd25519PubKey)
    }

    @Test fun previewDegradedRecord_hasNoE2EEKey() = assertNull(AttestationRecord.previewDegraded.modelEd25519PubKey)

    // MARK: Android-only — the bindings the preview record is built to satisfy, and their negatives

    @Test fun previewBindingsHold() {
        val r = AttestationRecord.preview
        assertEquals(true, r.signingKeyBoundToHardware)
        assertEquals(true, r.e2eeKeyBoundToModelTEE)
        assertEquals(true, r.codeIdentityVerified)
        assertEquals("config deployed 2026-07-16 · previous 2026-07-12 · per the signed action log", r.deploymentCaption)
        assertNull(AttestationRecord.previewDegraded.deploymentCaption)
    }

    @Test fun bindingsFailClosedOnWrongBytes() {
        val addr = "0x4a8b3f2e9d1c7a5e2b0f8d3c6e1a4b7f2e9d1c7a"
        // A quote whose report_data does not carry the address: bound = false, not nil.
        assertEquals(false, makeRecord(signingAddress = addr, quoteVerification = verification(ByteArray(64))).signingKeyBoundToHardware)
        // A malformed address cannot bind.
        assertEquals(false, makeRecord(signingAddress = "0xzz", quoteVerification = verification(ByteArray(64))).signingKeyBoundToHardware)
        // A key present but absent from the model quote's report_data.
        assertEquals(false, makeRecord(modelEd25519PubKey = ByteArray(32) { 1 }, modelQuoteVerification = verification(ByteArray(64))).e2eeKeyBoundToModelTEE)
        // Manifest tampered after hashing → the first link breaks.
        val tampered = AttestationRecord.preview.copy(composeManifest = AttestationRecord.preview.composeManifest + "\n  extra: {}")
        assertEquals(false, tampered.codeIdentityVerified)
        // Right hash, wrong MRCONFIGID → the second link breaks.
        val wrongQuote = AttestationRecord.preview.copy(quoteVerification = verification(ByteArray(64)))
        assertEquals(false, wrongQuote.codeIdentityVerified)
    }

    @Test fun hexRoundTripsAndRejectsGarbage() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
        assertTrue("00ff10".hexToBytes().contentEquals(byteArrayOf(0, -1, 16)))
        assertNull("abc".hexToBytesOrNull())
        assertNull("zz".hexToBytesOrNull())
    }
}
