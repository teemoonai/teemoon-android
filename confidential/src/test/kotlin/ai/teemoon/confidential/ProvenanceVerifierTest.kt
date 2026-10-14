// ports: teemoonTests/ImageProvenanceTests.swift @ v1.0.1 — the "ImageProvenance" suite. Its fixture
//        (nearai_cloudapi_attestation.json) crosses only as the kit's sigstore class, so realAttestation_verifies,
//        wrongDigest, untrustedRepo, tamperedSignature and emptyBundle are the kit's positive + four negatives;
//        orgPolicy and sanExtraction are portable.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The kit's sigstore class: one real GitHub build attestation and the negatives production answered. */
object SigstoreKit {
    class Positive(val note: String, val attestationJson: ByteArray, val expectedDigest: String, val organizationURL: String, val leafSubjectAltNameURI: String, val expected: JsonObject)
    class Negative(val note: String, val attestationJson: ByteArray, val expectedDigest: String, val organizationURL: String, val reason: String)
    private val doc by lazy { Json.parseToJsonElement(SigstoreKit::class.java.getResourceAsStream("/sigstore.json")!!.readBytes().decodeToString()).jsonObject }
    private fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
    val positives: List<Positive> by lazy { (doc["vectors"]!!.jsonObject["positives"] as JsonArray).map { it.jsonObject }.map {
        Positive(s(it, "note"), Base64.getDecoder().decode(s(it, "attestationJSONB64")), s(it, "expectedDigest"), s(it, "organizationURL"), s(it, "leafSubjectAltNameURI"), it["expected"]!!.jsonObject) } }
    val negatives: List<Negative> by lazy { (doc["vectors"]!!.jsonObject["negatives"] as JsonArray).map { it.jsonObject }.map {
        Negative(s(it, "note"), Base64.getDecoder().decode(s(it, "attestationJSONB64")), s(it, "expectedDigest"), s(it, "organizationURL"), s(it, "reason")) } }
    fun verifier(org: String) = ProvenanceVerifier(listOf(BuildIdentityPolicy(org)))
}

class ProvenanceVerifierTest {

    /** realAttestation_verifies: DSSE signature, Sigstore chain as of the Rekor time, SAN identity, subject digest, and the Rekor SET all pass on genuine production data. */
    @Test fun kitPositives_verifyWithTheRecordedClaims() {
        assertTrue(SigstoreKit.positives.isNotEmpty())
        for (p in SigstoreKit.positives) {
            val r = SigstoreKit.verifier(p.organizationURL).verify(p.attestationJson, p.expectedDigest)
            val v = r as? ImageProvenance.Verified ?: throw AssertionError("${p.note}: expected Verified, got $r")
            fun e(k: String) = p.expected[k]?.jsonPrimitive?.takeIf { it.content != "null" }?.content
            assertEquals(p.note, e("repositoryURL"), v.repositoryURL)
            assertEquals(p.note, e("workflowIdentity"), v.workflowIdentity)
            assertEquals(p.note, e("sourceRef"), v.sourceRef)
            assertEquals(p.note, e("sourceCommit"), v.sourceCommit)
            assertEquals(p.note, e("transparencyLogChecked") == "true", v.transparencyLogChecked)
        }
    }

    /** wrongDigest, tamperedSignature, untrustedRepo, emptyBundle: each negative answers the reason production answered. */
    @Test fun kitNegatives_answerTheRecordedReason() {
        assertTrue(SigstoreKit.negatives.size >= 4)
        for (n in SigstoreKit.negatives) {
            val r = SigstoreKit.verifier(n.organizationURL).verify(n.attestationJson, n.expectedDigest)
            val reason = (r as? ImageProvenance.Unverified)?.reason ?: throw AssertionError("${n.note}: expected Unverified, got $r")
            val name = when (reason) {
                is ImageProvenance.Reason.MalformedBundle -> "malformedBundle"
                is ImageProvenance.Reason.SignatureInvalid -> "signatureInvalid"
                is ImageProvenance.Reason.CertificateChainInvalid -> "certificateChainInvalid"
                is ImageProvenance.Reason.IdentityNotTrusted -> "identityNotTrusted"
                is ImageProvenance.Reason.DigestMismatch -> "digestMismatch"
                is ImageProvenance.Reason.FetchFailed -> "fetchFailed"
                is ImageProvenance.Reason.FetchTransient -> "fetchTransient"
            }
            assertEquals(n.note, n.reason, name)
            if (reason is ImageProvenance.Reason.IdentityNotTrusted) assertTrue(reason.got.startsWith("https://github.com/nearai/cloud-api"))
        }
    }

    @Test fun orgPolicy_extractsRepoOnlyForDirectWorkflowIdentities() {
        val policy = BuildIdentityPolicy("https://github.com/nearai")
        // Any repo directly under the org, any workflow file, any ref.
        assertEquals("https://github.com/nearai/dstack-vpc-client", policy.repository("https://github.com/nearai/dstack-vpc-client/.github/workflows/build.yml@refs/heads/main"))
        // Other orgs — including prefix-crafted names — are rejected.
        assertNull(policy.repository("https://github.com/nearai-evil/x/.github/workflows/build.yml@main"))
        assertNull(policy.repository("https://github.com/someone/else/.github/workflows/build.yml@main"))
        // Non-workflow identities under the org are rejected.
        assertNull(policy.repository("https://github.com/nearai/x"))
        assertNull(policy.repository("https://github.com/nearai//.github/workflows/build.yml@main"))
    }

    @Test fun emptyBundle_isMalformed() {
        val r = ProvenanceVerifier().verify("""{"attestations":[]}""".toByteArray(), SigstoreKit.positives.first().expectedDigest)
        assertTrue(r.toString(), (r as? ImageProvenance.Unverified)?.reason is ImageProvenance.Reason.MalformedBundle)
    }

    /** sanExtraction_returnsWorkflowURI: the leaf cert's SAN URI is the identity the kit recorded. */
    @Test fun sanExtraction_returnsWorkflowURI() {
        val p = SigstoreKit.positives.first()
        val root = Json.parseToJsonElement(p.attestationJson.decodeToString()).jsonObject
        val cert = ((root["attestations"] as JsonArray).first().jsonObject["bundle"]!!.jsonObject["verificationMaterial"]!!.jsonObject["certificate"]!!.jsonObject)["rawBytes"]!!.jsonPrimitive.content
        assertEquals(p.leafSubjectAltNameURI, ProvenanceVerifier.subjectAltNameURI(Base64.getDecoder().decode(cert)))
    }

    /** Android-only: the canonical SET message and PAE are byte-exact — a whitespace or key-order slip would silently unverify every Rekor entry. */
    @Test fun canonicalForms_areExact() {
        assertEquals("""{"body":"Yg==","integratedTime":1776413946,"logID":"c0d2","logIndex":1327484311}""", ProvenanceVerifier.rekorSETCanonicalJSON("Yg==", 1776413946, 1327484311, "c0d2").decodeToString())
        assertEquals("DSSEv1 28 application/vnd.in-toto+json 2 {}", ProvenanceVerifier.preAuthEncoding("application/vnd.in-toto+json", "{}".toByteArray()).decodeToString())
    }
}
