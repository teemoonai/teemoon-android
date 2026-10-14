// ports: teemoon/Confidential/ImageProvenance.swift @ v1.0.1 (BuildIdentityPolicy, ProvenanceVerifier, the bundle model;
//        the ImageProvenance value type is ImageProvenance.kt)
//
// Verifies that a container image running inside the enclave was built and
// signed by near.ai's published GitHub workflow — the "running code traces
// to published source" guarantee that the manifest↔quote binding does not
// itself provide.
//
// Input is a GitHub Artifact Attestation (a Sigstore bundle) fetched for an
// image digest. Verification (all against the kit's sigstore class in tests):
//   1. DSSE signature over the in-toto payload, ECDSA-P256-SHA256, under the
//      bundle's leaf certificate's public key.
//   2. Leaf certificate chains to the pinned Sigstore/Fulcio roots.
//   3. The leaf's SAN identity (the GitHub workflow URI) matches a pinned
//      near.ai repository policy — this binds the signature to near.ai's
//      source, not merely to "some Fulcio-issued cert."
//   4. The signed SLSA subject digest equals the running image digest.
//   5. Rekor transparency-log inclusion: the entry's signed entry timestamp
//      (SET) verifies under the pinned Rekor key, that key's ID matches the
//      entry's logId, and the logged entry is bound to THIS attestation by
//      payloadHash == SHA256(our DSSE payload). Surfaced as
//      `transparencyLogChecked`.
//
// Scope note: (5) verifies the inclusion *promise* (SET) — the standard
// Sigstore client check — not a Merkle inclusion *proof* against a fetched
// checkpoint.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * A trusted build identity: the signing certificate's SAN workflow URI must
 * name a workflow of a repository directly under [organizationURL].
 *
 * The org is pinned (not per-repo, and not per-digest) because it is the
 * stable trust anchor: near.ai adds build repositories and rotates images
 * freely (a pinned repo list fail-closed the day `dstack-vpc-client`
 * appeared in the live compose), while forging an accepted identity still
 * requires a Fulcio-issued certificate for a workflow inside near.ai's
 * GitHub organization.
 */
data class BuildIdentityPolicy(val organizationURL: String) {
    /** The repository URL this identity belongs to, when it is a workflow of a repo directly under the pinned org; null otherwise. */
    fun repository(identity: String): String? {
        val orgPrefix = "$organizationURL/"
        if (!identity.startsWith(orgPrefix)) return null
        val rest = identity.removePrefix(orgPrefix)
        val workflows = rest.indexOf("/.github/workflows/").takeIf { it >= 0 } ?: return null
        val repo = rest.substring(0, workflows)
        if (repo.isEmpty() || repo.contains('/')) return null
        return orgPrefix + repo
    }

    companion object {
        val nearAI: List<BuildIdentityPolicy> = listOf(BuildIdentityPolicy("https://github.com/nearai"))
    }
}

class ProvenanceVerifier(val policies: List<BuildIdentityPolicy> = BuildIdentityPolicy.nearAI) {

    /** Verifies a GitHub Artifact Attestation bundle against [expectedDigest] (hex sha256, no prefix). Pure/offline — the caller fetches the bundle. */
    fun verify(attestationJson: ByteArray, expectedDigest: String): ImageProvenance {
        // Parse the GitHub response envelope → the first bundle.
        val bundle = runCatching { parseBundle(attestationJson) }.getOrNull()
            ?: return ImageProvenance.Unverified(ImageProvenance.Reason.MalformedBundle("no attestation bundle"))
        val payload = b64(bundle.payload); val signature = b64(bundle.signature); val certDer = b64(bundle.certificateRawBytes)
        if (payload == null || signature == null || certDer == null)
            return ImageProvenance.Unverified(ImageProvenance.Reason.MalformedBundle("payload/signature/certificate not decodable"))

        // 1. DSSE signature (ECDSA-P256-SHA256 over the PAE) under the leaf key.
        val leaf = runCatching { certificate(certDer) }.getOrNull()
            ?: return ImageProvenance.Unverified(ImageProvenance.Reason.MalformedBundle("leaf certificate public key unreadable"))
        val pae = preAuthEncoding(bundle.payloadType, payload)
        if (!ecdsaP256Valid(leaf.publicKey, pae, signature)) return ImageProvenance.Unverified(ImageProvenance.Reason.SignatureInvalid)

        // 2. Leaf chains to the pinned Sigstore roots, evaluated AS OF the
        //    signing time. Fulcio leaf certs live ~10 minutes and are expected
        //    to be expired at verification time, so we evaluate against the
        //    Rekor-logged `integratedTime` rather than "now". That time is
        //    taken from the bundle; step 5's Rekor SET verification
        //    (transparencyLogChecked) is what makes it authoritative — chain
        //    validity is evaluated first, against the claimed time.
        val signingTime = bundle.tlog?.integratedTime?.toLongOrNull()?.let { Date(it * 1000) }
        chainError(leaf, signingTime)?.let { return ImageProvenance.Unverified(ImageProvenance.Reason.CertificateChainInvalid(it)) }

        // 3. SAN identity matches a pinned near.ai workflow.
        val identity = subjectAltNameURI(certDer)
            ?: return ImageProvenance.Unverified(ImageProvenance.Reason.MalformedBundle("no SAN URI in leaf certificate"))
        val repositoryURL = policies.firstNotNullOfOrNull { it.repository(identity) }
            ?: return ImageProvenance.Unverified(ImageProvenance.Reason.IdentityNotTrusted(identity))

        // 4. Signed subject digest equals the running image digest.
        val statement = runCatching { InTotoStatement.parse(payload) }.getOrNull()
        val subjectDigest = statement?.subjectSha256
            ?: return ImageProvenance.Unverified(ImageProvenance.Reason.MalformedBundle("no subject digest in statement"))
        if (!subjectDigest.equals(expectedDigest, ignoreCase = true))
            return ImageProvenance.Unverified(ImageProvenance.Reason.DigestMismatch(expectedDigest, subjectDigest))

        // 5. Rekor transparency-log inclusion: prove the entry was publicly
        //    logged, and that the logged entry is THIS attestation.
        val tlogVerified = transparencyLogVerified(bundle.tlog, payload)

        return ImageProvenance.Verified(repositoryURL, identity, statement.workflowRef, statement.sourceCommit, tlogVerified)
    }

    // MARK: Rekor transparency log

    /** Verifies the Rekor entry's SET under the pinned Rekor key, and binds it to our attestation via the entry's payloadHash. True only when both hold. */
    private fun transparencyLogVerified(entry: TlogEntry?, dssePayload: ByteArray): Boolean {
        entry ?: return false
        val setSig = b64(entry.signedEntryTimestamp) ?: return false
        val bodyData = b64(entry.canonicalizedBody) ?: return false
        val integratedTime = entry.integratedTime.toLongOrNull() ?: return false
        val logIndex = entry.logIndex.toLongOrNull() ?: return false
        val keyId = b64(entry.logIdKeyId) ?: return false

        // Pinned Rekor key, and its identity must match the entry's logId (keyId is SHA256 of the key's DER SubjectPublicKeyInfo).
        val rekorDer = pemBody(SigstoreTrustRoot.REKOR_PUBLIC_KEY_PEM, "PUBLIC KEY") ?: return false
        if (!sha256(rekorDer).contentEquals(keyId)) return false
        val rekorKey = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(rekorDer)) }.getOrNull() ?: return false

        // Bind the logged entry to THIS attestation: its payloadHash must equal SHA256(our DSSE payload).
        val body = runCatching { Json.parseToJsonElement(String(bodyData, Charsets.UTF_8)) as JsonObject }.getOrNull() ?: return false
        val hashValue = ((body["spec"] as? JsonObject)?.get("payloadHash") as? JsonObject)?.get("value")?.str ?: return false
        if (sha256(dssePayload).toHex() != hashValue.lowercase()) return false

        // Verify the SET signature over the canonical {body,integratedTime,logIndex,logID}.
        val canonical = rekorSETCanonicalJSON(entry.canonicalizedBody, integratedTime, logIndex, keyId.toHex())
        return ecdsaP256Valid(rekorKey, canonical, setSig)
    }

    // MARK: Bundle model (GitHub attestations API → Sigstore bundle subset)

    private class TlogEntry(val integratedTime: String, val logIndex: String, val logIdKeyId: String, val signedEntryTimestamp: String, val canonicalizedBody: String)
    private class Bundle(val payload: String, val payloadType: String, val signature: String, val certificateRawBytes: String, val tlog: TlogEntry?)

    /** Strict like Swift's Decodable: a missing required field throws, so the caller answers malformedBundle. */
    private fun parseBundle(data: ByteArray): Bundle {
        val root = Json.parseToJsonElement(String(data, Charsets.UTF_8)) as JsonObject
        val bundle = ((root["attestations"] as JsonArray).first() as JsonObject)["bundle"] as JsonObject
        val env = bundle["dsseEnvelope"] as JsonObject
        val vm = bundle["verificationMaterial"] as JsonObject
        val sig = ((env["signatures"] as JsonArray).firstOrNull() as? JsonObject)?.get("sig")?.str ?: ""
        val tlog = (vm["tlogEntries"] as? JsonArray)?.firstOrNull()?.let { it as JsonObject }?.let { t ->
            TlogEntry(t["integratedTime"]!!.str!!, t["logIndex"]!!.str!!, (t["logId"] as JsonObject)["keyId"]!!.str!!,
                (t["inclusionPromise"] as JsonObject)["signedEntryTimestamp"]!!.str!!, t["canonicalizedBody"]!!.str!!)
        }
        return Bundle(env["payload"]!!.str!!, env["payloadType"]!!.str!!, sig, (vm["certificate"] as JsonObject)["rawBytes"]!!.str!!, tlog)
    }

    /**
     * SLSA v1 provenance predicate (GitHub Artifact Attestations). Optional at
     * every level: a payload without it still verifies — the digest match is
     * the requirement; ref/commit are extra attested claims.
     */
    private class InTotoStatement(val subjectSha256: String?, val workflowRef: String?, val sourceCommit: String?) {
        companion object {
            fun parse(payload: ByteArray): InTotoStatement {
                val root = Json.parseToJsonElement(String(payload, Charsets.UTF_8)) as JsonObject
                val subject = (root["subject"] as? JsonArray)?.firstOrNull() as? JsonObject
                val sha = (subject?.get("digest") as? JsonObject)?.get("sha256")?.str
                val build = (root["predicate"] as? JsonObject)?.get("buildDefinition") as? JsonObject
                val ref = ((build?.get("externalParameters") as? JsonObject)?.get("workflow") as? JsonObject)?.get("ref")?.str
                val commit = (build?.get("resolvedDependencies") as? JsonArray)?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("digest") as? JsonObject)?.get("gitCommit")?.str }
                return InTotoStatement(sha, ref, commit)
            }
        }
    }

    companion object {
        /** Rekor's canonical SET message: JSON with lexicographically sorted keys and no whitespace ("logID" < "logIndex" since 'D' < 'n'). */
        fun rekorSETCanonicalJSON(body: String, integratedTime: Long, logIndex: Long, logID: String): ByteArray =
            "{\"body\":\"$body\",\"integratedTime\":$integratedTime,\"logID\":\"$logID\",\"logIndex\":$logIndex}".toByteArray(Charsets.UTF_8)

        /** DSSE PAE v1: `DSSEv1 SP len(type) SP type SP len(payload) SP payload`, payload raw bytes. */
        fun preAuthEncoding(payloadType: String, payload: ByteArray): ByteArray =
            "DSSEv1 ${payloadType.toByteArray(Charsets.UTF_8).size} $payloadType ${payload.size} ".toByteArray(Charsets.UTF_8) + payload

        /**
         * Null when [leaf] chains to the pinned Sigstore roots, else an error string.
         * [verifyDate] should be the signing time (Fulcio leaves are short-lived);
         * null falls back to "now", which will reject any real (expired) Fulcio leaf.
         */
        fun chainError(leaf: X509Certificate, verifyDate: Date?): String? {
            val intermediate = pinnedCertificate(SigstoreTrustRoot.INTERMEDIATE_PEM)
            val root = pinnedCertificate(SigstoreTrustRoot.ROOT_PEM)
            if (intermediate == null || root == null) return "pinned Sigstore roots not loadable"
            return try {
                val cf = CertificateFactory.getInstance("X.509")
                val path = cf.generateCertPath(listOf(leaf, intermediate))
                val params = PKIXParameters(setOf(TrustAnchor(root, null))).apply { isRevocationEnabled = false; date = verifyDate }
                CertPathValidator.getInstance("PKIX").validate(path, params); null
            } catch (e: Exception) { e.message ?: "chain evaluation failed" }
        }

        private fun pinnedCertificate(pem: String): X509Certificate? = pemBody(pem, "CERTIFICATE")?.let { runCatching { certificate(it) }.getOrNull() }

        private fun certificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        /** The first URI (GeneralName type 6) in the certificate's SubjectAltName extension — the workflow identity Fulcio signs. */
        fun subjectAltNameURI(certDer: ByteArray): String? {
            val cert = runCatching { certificate(certDer) }.getOrNull() ?: return null
            return runCatching { cert.subjectAlternativeNames }.getOrNull()?.firstOrNull { (it[0] as? Int) == 6 }?.get(1) as? String
        }

        private fun ecdsaP256Valid(key: PublicKey, message: ByteArray, derSignature: ByteArray): Boolean = runCatching {
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(derSignature) }
        }.getOrDefault(false)

        private fun pemBody(pem: String, label: String): ByteArray? =
            b64(pem.replace("-----BEGIN $label-----", "").replace("-----END $label-----", "").replace("\n", "").trim())

        private fun b64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
        private val JsonElement.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
