// android-only until iOS's sha: audit's 1.1.0 near.ai trust spec (2026-10-30, every value checked against a live
// glm-5-3-flash report), items (a) and (b). The fixture both platforms pin is
// src/test/resources/fixtures/nearai-trust/ (audit's bytes; iOS vendors the same files).
package ai.teemoon.confidential

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One binding check's outcome. FAILED is hard (like a replayed quote); INCONCLUSIVE moves off green without blocking. */
sealed interface BindingCheck {
    data class Verified(val detail: String) : BindingCheck
    data class Failed(val reason: String) : BindingCheck
    data class Inconclusive(val reason: String) : BindingCheck
}

/**
 * The bytes near.ai hashes into actions_hash: the parsed JSON value re-serialised with object keys sorted, compact
 * separators and raw UTF-8 — Python's `json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`.
 * It walks the parsed VALUE, not a typed class, so a field this app does not know still counts: dropping one would turn
 * every honest log into a mismatch the day near.ai adds it.
 */
object CanonicalJson {
    fun encode(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sorted().forEachIndexed { i, k -> if (i > 0) out.append(','); quote(k, out); out.append(':'); write(e.getValue(k), out) }
                out.append('}')
            }
            is JsonArray -> { out.append('['); e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }; out.append(']') }
            is JsonPrimitive -> if (e.isString) quote(e.content, out) else out.append(e.content)
        }
    }

    /** Python's escaping with ensure_ascii off: the quote, the backslash and control characters; everything else raw. */
    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\""); '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
            '\b' -> out.append("\\b"); '\u000C' -> out.append("\\f")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }
}

private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
private fun sha384(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-384").digest(b)
private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/**
 * (a) The compose-manager quote binds the deploy log to the hardware. Its TD report's report_data is
 * SHA256(canonical actions) ‖ the nonce WE sent — not the response's `.nonce`, which the server writes — and its MRTD and
 * RTMR0–3 equal the model node quote's, because it is the same TD. They are compared, never pinned: they move with every
 * OS or compose change. Until this passes, nothing from `.actions` may be shown as pinned.
 */
object ComposeManagerBinding {
    // The outcomes NearTrustScope reads as (a)'s field; the words are placeholders until the review page settles them.
    const val CHECKING = "checking the deploy log's quote"
    const val NO_NODE_QUOTE = "no model node quote to compare"
    const val ACTIONS_HASH_MISMATCH = "the deploy log does not match the hash its quote signs"
    const val NONCE_MISMATCH = "the deploy log's quote does not echo this session's nonce (possible replay)"
    const val OTHER_TD = "the deploy log's quote comes from a different machine than the model"

    /**
     * [dcapPassed]: the manager quote's DCAP verdict under the node quote's policy (an out-of-date TCB is advisory,
     * anything else hard); null while it runs. A manager quote whose TCB verdict differs from the node's is a hard
     * mismatch — one platform cannot report two (audit, 2026-10-30) — and the caller passes false for it.
     */
    fun check(cm: JsonObject?, sentNonceHex: String, nodeQuoteHex: String?, dcapPassed: Boolean?): BindingCheck {
        val quoteHex = cm?.str("quote") ?: return BindingCheck.Inconclusive("deploy log not bound")
        val actions = cm["actions"] as? JsonArray ?: return BindingCheck.Inconclusive("deploy log not bound")
        val quote = runCatching { QuoteParser.parseHex(quoteHex) }.getOrNull() ?: return BindingCheck.Failed("the deploy log's quote does not parse")
        when (dcapPassed) {
            false -> return BindingCheck.Failed("the deploy log's quote failed Intel DCAP verification")
            null -> return BindingCheck.Inconclusive(CHECKING)
            true -> {}
        }
        val rd = quote.body.reportData
        val actionsHash = sha256(CanonicalJson.encode(actions).toByteArray(Charsets.UTF_8))
        if (!rd.copyOfRange(0, 32).contentEquals(actionsHash)) return BindingCheck.Failed(ACTIONS_HASH_MISMATCH)
        val nonce = sentNonceHex.hexToBytesOrNull()
        if (nonce == null || nonce.size != 32 || !rd.copyOfRange(32, 64).contentEquals(nonce)) return BindingCheck.Failed(NONCE_MISMATCH)
        val node = nodeQuoteHex?.let { runCatching { QuoteParser.parseHex(it) }.getOrNull() } ?: return BindingCheck.Inconclusive(NO_NODE_QUOTE)
        val a = quote.body; val b = node.body
        val same = listOf(a.mrtd to b.mrtd, a.rtmr0 to b.rtmr0, a.rtmr1 to b.rtmr1, a.rtmr2 to b.rtmr2, a.rtmr3 to b.rtmr3).all { (x, y) -> x.contentEquals(y) }
        if (!same) return BindingCheck.Failed(OTHER_TD)
        return BindingCheck.Verified("${actions.size} deploy actions bound to the model's hardware")
    }

    /** The actions_hash field against the recomputed hash: display only, never a verdict. */
    fun actionsHashFieldMatches(cm: JsonObject): Boolean? {
        val field = cm.str("actions_hash") ?: return null
        val actions = cm["actions"] as? JsonArray ?: return null
        return sha256(CanonicalJson.encode(actions).toByteArray(Charsets.UTF_8)).toHex() == field.lowercase()
    }
}

/**
 * (b) RTMR3's event log replayed against the model node quote: what binds compose_hash and os_image_hash to the
 * hardware. The log is info.tcb_info.event_log — not the top-level event_log and not the compose manager's, which are
 * different logs. `imr` and `event_type` arrive as decimal strings or numbers (both seen live, 2026-10-30).
 */
object Rtmr3Replay {
    // The failures NearTrustScope reads as (b)'s field; placeholder words, as above.
    const val COMPOSE_MISMATCH = "the launch config is not the one the hardware measured"
    const val OS_MISMATCH = "the OS image is not the one the hardware measured"
    const val DIGEST_MISMATCH_PREFIX = "the boot event “"

    data class Entry(val imr: Int, val eventType: Long, val digest: ByteArray, val event: String, val payload: ByteArray)

    fun entries(log: JsonArray): List<Entry>? = runCatching {
        log.map { e ->
            val o = e as JsonObject
            Entry(o.getValue("imr").jsonPrimitive.content.toInt(), o.getValue("event_type").jsonPrimitive.content.toLong(),
                o.getValue("digest").jsonPrimitive.content.hexToBytes(), o.str("event").orEmpty(), o.str("event_payload").orEmpty().hexToBytes())
        }
    }.getOrNull()

    /** 48 zero bytes, then SHA384(rtmr3 ‖ digest) for every imr-3 entry in array order. */
    fun replay(entries: List<Entry>): ByteArray = entries.filter { it.imr == 3 }.fold(ByteArray(48)) { acc, e -> sha384(acc + e.digest) }

    /** An entry's digest is SHA384(u32le(event_type) ‖ ":" ‖ event ‖ ":" ‖ payload). */
    fun digestRecomputes(e: Entry): Boolean {
        val t = e.eventType.toInt()
        val le = byteArrayOf(t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
        return sha384(le + ":".toByteArray() + e.event.toByteArray(Charsets.UTF_8) + ":".toByteArray() + e.payload).contentEquals(e.digest)
    }

    fun check(tcbInfo: JsonObject?, nodeQuoteHex: String?, osImageHash: String?): BindingCheck {
        val log = (tcbInfo?.get("event_log") as? JsonArray) ?: return BindingCheck.Inconclusive("no boot event log")
        val entries = entries(log) ?: return BindingCheck.Failed("the boot event log does not parse")
        val compose = entries.lastOrNull { it.imr == 3 && it.event == "compose-hash" } ?: return BindingCheck.Inconclusive("the boot event log names no compose")
        val node = nodeQuoteHex?.let { runCatching { QuoteParser.parseHex(it) }.getOrNull() } ?: return BindingCheck.Inconclusive("no model node quote to replay against")
        if (!replay(entries).contentEquals(node.body.rtmr3)) return BindingCheck.Failed("the boot event log does not replay to the quote's RTMR3")
        val os = entries.lastOrNull { it.imr == 3 && it.event == "os-image-hash" }
        for (e in listOfNotNull(compose, os)) if (!digestRecomputes(e)) return BindingCheck.Failed("$DIGEST_MISMATCH_PREFIX${e.event}” does not match its digest")
        val appCompose = tcbInfo.str("app_compose") ?: return BindingCheck.Inconclusive("no launch config to compare")
        if (!compose.payload.contentEquals(sha256(appCompose.toByteArray(Charsets.UTF_8))) || tcbInfo.str("compose_hash")?.lowercase() != compose.payload.toHex())
            return BindingCheck.Failed(COMPOSE_MISMATCH)
        if (os != null && osImageHash != null && os.payload.toHex() != osImageHash.lowercase()) return BindingCheck.Failed(OS_MISMATCH)
        return BindingCheck.Verified("launch config and OS image bound to the model's hardware")
    }
}

/**
 * (d) A measured child image whose attestation was made for its multi-platform INDEX (the launcher 079a14b4…: GitHub's
 * attestation API answers 404 for the child, and lists the index 91fdff3c…). OCI has no child → parent lookup, so the
 * index digest comes from outside — a hint or a registry listing — and these checks are what make that safe: the
 * index is content-addressed, so a wrong hint cannot forge a pass. The caller then verifies the index's attestation
 * exactly as any image's, with the subject digest == the index digest.
 */
object ImageIndexResolution {
    const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"

    /** [indexBytes] as the registry served them; [indexDigest] and [childDigest] are 64 hex, without "sha256:". */
    fun check(indexBytes: ByteArray, indexDigest: String, childDigest: String): BindingCheck {
        if (sha256(indexBytes).toHex() != indexDigest.lowercase()) return BindingCheck.Failed("the index is not the one its digest names")
        val index = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(indexBytes.decodeToString()) as JsonObject }.getOrNull()
            ?: return BindingCheck.Failed("the index does not parse")
        if (index.str("mediaType") != INDEX_MEDIA_TYPE) return BindingCheck.Failed("not an image index")
        val manifests = index["manifests"] as? JsonArray ?: return BindingCheck.Failed("the index lists no images")
        val listed = manifests.any { m ->
            val o = m as? JsonObject ?: return@any false
            // Build-attestation entries ride along in the same index (platform unknown/unknown): never the image itself.
            val ref = (o["annotations"] as? JsonObject)?.str("vnd.docker.reference.type")
            val p = o["platform"] as? JsonObject
            ref != "attestation-manifest" && o.str("digest") == "sha256:${childDigest.lowercase()}" && p?.str("os") == "linux" && p.str("architecture") == "amd64"
        }
        return if (listed) BindingCheck.Verified("attested via its linux/amd64 index ${indexDigest.take(8)}")
        else BindingCheck.Failed("the image is not this index's linux/amd64 image")
    }
}

/**
 * What the deploy log (compose_manager_attestation.actions) names — only once [ComposeManagerBinding] has passed; before
 * that nothing from it may be shown as pinned. "Names", not "pins": the compose-manager seat is not measured (the
 * measured launcher only requires near.ai's cosign signature on it), and the log records no compose project.
 */
object DeployLog {
    private val managerDefault = Regex("""\$\{COMPOSE_MANAGER_IMAGE:-[^}@]*@sha256:([0-9a-f]{64})\}""")
    private val digestRef = Regex("""@sha256:([0-9a-f]{64})""")

    /** (2) The compose-manager the MEASURED launch config declares: its `${COMPOSE_MANAGER_IMAGE:-…}` default. */
    fun declaredManager(appCompose: String): String? = managerDefault.find(appCompose)?.groupValues?.get(1)

    /** (2) The compose-manager the log says started: the LAST compose_manager_started (the log is append-only). */
    fun runningManager(actions: JsonArray): String? = actions.lastOrNull { (it as? JsonObject)?.str("action") == "compose_manager_started" }
        ?.let { (it as JsonObject).str("image") }?.let { digestRef.find(it)?.groupValues?.get(1) }

    /** A compose file at a commit, with the hash the log pins for it ("" when no up pins it: read unhashed). */
    data class Source(val commit: String, val file: String, val fileSha256: String)

    /**
     * (c) What the log names as up, walked newest first (audit 2026-11-06, canonical rule A; iOS
     * ActionLogReading.runningServices @ 28c43414): an entry naming services decides the ones no newer entry decided
     * (an up attributes, a down retires). An entry naming none is the whole file — compose-manager runs
     * `up -d --remove-orphans` per model project — so it decides every service [servicesIn] reads from that revision,
     * and every older entry of that file is ignored. A whole file it can't read is one row with service "", services
     * unknown; the walk never stops early. Every up also runs `--remove-orphans`, so an older entry of F only keeps
     * services in every newer up revision of F (rule 3, iOS 37784653): a name outside that bound is decided — as gone —
     * so an older file naming the same service can't reclaim it. Cross-file eviction (two files sharing one project)
     * isn't in the log and isn't modelled. Each row carries its entry's index, for [undecidableServices].
     */
    fun runningServices(actions: JsonArray, servicesIn: (Source) -> List<String>?): List<RunningService> {
        val pinned = pinnedHashes(actions)
        val decided = HashSet<String>(); val deadFiles = HashSet<String>()
        val bound = HashMap<String, Set<String>>()
        val rows = ArrayList<RunningService>()
        for (index in actions.indices.reversed()) {
            val a = actions[index] as? JsonObject ?: continue
            val action = a.str("action")
            if (action != "compose_up" && action != "compose_down") continue
            val file = a.str("file") ?: continue; val commit = a.str("commit") ?: continue
            if (file in deadFiles) continue
            val source = Source(commit, file, a.str("file_sha256") ?: pinned["$commit/$file"] ?: "")
            val named = (a["services"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (named == null) deadFiles += file
            fun row(service: String) = RunningService(service, file, source.fileSha256, commit, a.str("tag"), a.str("timestamp"), entry = index)
            val limit = bound[file]
            if (action == "compose_up") servicesIn(source)?.toSet()?.let { revision -> bound[file] = limit?.intersect(revision) ?: revision }
            val all = named ?: servicesIn(source)
            if (all == null) { if (action == "compose_up") rows += row(""); continue }
            // A name a newer up's revision lacks was removed as an orphan: this entry still decides it, as gone.
            val retired = limit?.let { l -> all.filter { it !in l } }.orEmpty()
            decided += retired
            for (name in all) if (name !in retired && decided.add(name) && action == "compose_up") rows += row(name)
        }
        return rows
    }

    /** The whole-file entries the walk can reach: the newest of each file, since it kills the older ones. Fetched before the walk. */
    fun wholeFileSources(actions: JsonArray): List<Source> {
        val pinned = pinnedHashes(actions)
        val seen = HashSet<String>()
        return actions.reversed().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            if (a.str("action") !in setOf("compose_up", "compose_down") || a["services"] != null) return@mapNotNull null
            val file = a.str("file") ?: return@mapNotNull null; val commit = a.str("commit") ?: return@mapNotNull null
            if (!seen.add(file)) return@mapNotNull null
            Source(commit, file, a.str("file_sha256") ?: pinned["$commit/$file"] ?: "")
        }
    }

    /** Every up revision: rule 3 bounds older entries by each one's services. Fetched with the whole files. */
    fun upRevisionSources(actions: JsonArray): List<Source> {
        val seen = LinkedHashSet<Source>()
        for (e in actions.reversed()) {
            val a = e as? JsonObject ?: continue
            if (a.str("action") != "compose_up") continue
            val file = a.str("file") ?: continue; val commit = a.str("commit") ?: continue; val sha = a.str("file_sha256") ?: continue
            seen += Source(commit, file, sha)
        }
        return seen.toList()
    }

    /** A down pins no hash; an up of the same (commit, file) elsewhere in the log does, and binds the down too. */
    private fun pinnedHashes(actions: JsonArray): Map<String, String> {
        val out = HashMap<String, String>()
        for (e in actions) {
            val a = e as? JsonObject ?: continue
            if (a.str("action") != "compose_up") continue
            val c = a.str("commit") ?: continue; val f = a.str("file") ?: continue; val sha = a.str("file_sha256") ?: continue
            out["$c/$f"] = sha
        }
        return out
    }

    /**
     * Running services the log can't vouch for (audit 2026-11-06, per service; iOS 4d2a41e3), each with the entry that
     * may have removed it: the first later up of ANOTHER file whose revision lacks the name. Every up removes orphans;
     * whether that file shares the project isn't in the log. An unreadable revision counts. Downs never count. A proxy
     * that answered this report ([alive]) is alive, so a later up of a file lacking its name didn't evict its file: that
     * file's ups count against nothing in the proxy's file. A pair is leftover only when all its services are here.
     */
    fun undecidableServices(actions: JsonArray, running: List<RunningService>, servicesIn: (Source) -> List<String>?,
                            alive: List<RunningService> = emptyList()): Map<String, Int> {
        val pinned = pinnedHashes(actions)
        class Up(val index: Int, val file: String, val names: Set<String>?)
        val ups = actions.mapIndexedNotNull { i, e ->
            val a = e as? JsonObject ?: return@mapIndexedNotNull null
            if (a.str("action") != "compose_up") return@mapIndexedNotNull null
            val file = a.str("file") ?: return@mapIndexedNotNull null; val commit = a.str("commit") ?: return@mapIndexedNotNull null
            Up(i, file, servicesIn(Source(commit, file, a.str("file_sha256") ?: pinned["$commit/$file"] ?: ""))?.toSet())
        }
        fun lacks(up: Up, name: String) = up.names?.contains(name) != true
        val refuted = HashMap<String, MutableSet<String>>()   // the proxy's file → files shown not to evict it
        for (proxy in alive) {
            val pf = proxy.file ?: continue
            for (up in ups) if (up.index > proxy.entry && up.file != pf && lacks(up, proxy.service)) refuted.getOrPut(pf) { HashSet() } += up.file
        }
        val out = LinkedHashMap<String, Int>()
        for (s in running) {
            val sf = s.file ?: continue
            if (s.service.isEmpty()) continue
            val shown = refuted[sf].orEmpty()
            ups.firstOrNull { it.index > s.entry && it.file != sf && it.file !in shown && lacks(it, s.service) }?.let { out[s.service] = it.index }
        }
        return out
    }

    /** The service names of a compose file (manifest JSON unwrapped first): the headers of its `services:` block. */
    fun serviceNames(compose: String): List<String> =
        PlaintextExposure.serviceBlocks(PlaintextExposure.dockerComposeYaml(compose)).mapNotNull { block ->
            val header = block.substringBefore('\n').trim()
            if (header.endsWith(":")) header.dropLast(1) else null
        }
}
