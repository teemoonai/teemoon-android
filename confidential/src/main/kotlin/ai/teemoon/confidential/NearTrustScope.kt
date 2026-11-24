// ports: teemoon/Confidential/TrustScope.swift @ 01a337a4 (1.1.1 audit integ read item 2: run(): info.tcb_info as an object or a string holding the JSON)
// ports: teemoon/Confidential/TrustScope.swift @ 976543da (1.1.1 audit A10 (listedHardBreakReason): a listed scope's break reads the listed-enclave lines, the read scope's own are unchanged)
// ports: teemoon/Confidential/RunningPairCheck.swift @ 9d21baba (1.1.1 audit cut review item 3: FetchBudget = RunningPairCheck.Budget, ComposeFiles.FETCH_BUDGET = fetchBudget)
// ports: teemoon/Confidential/TrustScope.swift @ 71f83846 (activeRunning: the sheet's running list is active infrastructure only;
//        runningSection)
package ai.teemoon.confidential

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// The 1.1.0 near.ai trust scope as data (pm 2026-11-04, iOS trust scope draft 1): the field names iOS keeps stable on
// AttestationSummary.Input and TrustVerdict.Input. AHEAD OF iOS's code — wired to its field names, not ported from a
// commit; reconcile with that commit when it lands. No words here: the screens' lines wait on the maintainer's review page
// (near-trust-scope), and the log is "the action log" (compose_manager_attestation.actions) everywhere.

/** (a) The compose-manager quote binds the action log to the model's hardware ([ComposeManagerBinding]). */
sealed interface DeployLogBinding {
    data object Bound : DeployLogBinding
    data class NotBound(val reason: Reason) : DeployLogBinding
    data class Mismatch(val check: Check) : DeployLogBinding
    /**
     * Intel couldn't be asked about the compose manager's quote, or either side of the TCB comparison; every local
     * check (actions hash, nonce, same TD) passed. Soft, never hard: the send asks (near-send-gate-rule, iOS
     * ActionLogBinding.unchecked). Not bound, so nothing from the log is shown as pinned.
     */
    data object Unchecked : DeployLogBinding

    enum class Reason { NO_LOG, CHECKING, NO_NODE_QUOTE }
    /** quote: unparseable or failed DCAP; tcbDiffers: its TCB verdict is not the node quote's (one platform, one verdict). */
    enum class Check { QUOTE, ACTIONS_HASH, NONCE, SAME_TD, TCB_DIFFERS }
}

/** (b) RTMR3's boot event log replayed against the model node quote ([Rtmr3Replay]). */
sealed interface BootLogReplay {
    data object Matches : BootLogReplay
    data object Absent : BootLogReplay
    data class Mismatch(val what: What) : BootLogReplay

    enum class What { RTMR3, EVENT_DIGEST, COMPOSE_HASH, OS_IMAGE_HASH }
}

/** (2) The compose-manager seat: what the measured launch config declares, and what the action log says started. */
data class ComposeManagerSeat(val declared: String, val running: String?, val source: Source) {
    /** DEPLOY_LOG only once (a) is Bound: before that nothing from the log may be shown as pinned. */
    enum class Source { DEPLOY_LOG, DECLARED_ONLY }
}

/** (c) One row of what the action log names as up. Only the engine's and the proxy's files are fetched and hash-checked. */
/**
 * (c) One service the action log names as up. [service] "" = a whole file that couldn't be read: services unknown.
 * [undecidable]: a later up of another file whose revision lacks this name may have removed it, so the log can't say
 * whether it still runs (audit 2026-11-06, per service: DeployLog.undecidableServices).
 */
data class RunningService(val service: String, val file: String?, val fileSHA256: String?, val commit: String?, val tag: String?, val since: String?,
                          val undecidable: Boolean = false,
                          /** The log entry that attributed it (its index): liveness asks what came after (iOS 4d2a41e3). */
                          val entry: Int = 0)

/** (d) The measured launcher found through its multi-platform index ([ImageIndexResolution]). */
sealed interface LauncherIndex {
    data class ViaIndex(val indexDigest: String) : LauncherIndex
    data object NotFound : LauncherIndex
    data object Checking : LauncherIndex
    data object NotChecked : LauncherIndex
}

/** The five, as one value the session computes and the verdict and the summary read. */
data class NearTrustScope(
    val deployLogBinding: DeployLogBinding,
    val bootLogReplay: BootLogReplay,
    val composeManager: ComposeManagerSeat?,
    val runningServices: List<RunningService>,
    val launcherIndex: LauncherIndex,
    /** (c) The running table's distinct (commit, file) pairs and what fetching each found; empty until (a) binds. */
    val composeFiles: List<ComposeFilePair> = emptyList(),
    /**
     * (c) What the walk reads service names from, fetched before the pairs (the cap is the pairs'): the newest whole file
     * of each file, and every up revision (rule 3) — each source once.
     */
    val wholeFileSources: List<DeployLog.Source> = emptyList(),
    /** Which running services this chat's requests pass through, from the host the app connected to (ChatPath). */
    val chatPath: ChatPath.Result? = null,
) {
    /**
     * The sheet's "running now" list: active infrastructure only (the maintainer 2026-11-08, iOS 71f83846) — the rows the log
     * vouches for (not undecidable) plus this chat's path; with no path, every row. Display only: the hash checks, the
     * hard break and the LEAKS rollup read [runningServices] and the path.
     */
    val activeRunning: List<RunningService> get() {
        val hops = (chatPath as? ChatPath.Result.Path)?.hops ?: return runningServices
        return runningServices.filter { r -> !r.undecidable || hops.any { it.service == r.service && it.source == ChatPath.sourceOf(r) } }
    }

    /** A listed enclave's log teemoon couldn't check: no compose-manager quote, or no boot log. Soft, never clean (1.1.1 audit A4). */
    val listedEvidenceAbsent: Boolean get() = deployLogBinding is DeployLogBinding.NotBound || bootLogReplay is BootLogReplay.Absent

    enum class Severity { NONE, SOFT, HARD }

    /** The session's degrade line when this scope is a hard break: (a) first, as iOS orders them. */
    val hardBreakReason: String? get() = when {
        deployLogBinding is DeployLogBinding.Mismatch -> ACTION_LOG_BROKEN
        bootLogReplay is BootLogReplay.Mismatch -> BOOT_LOG_BROKEN
        composeFiles.any { it.check is ComposeFileCheck.Mismatch } -> RECIPE_UNVERIFIED
        else -> null
    }

    /** The same break read as a LISTED enclave's: never the read enclave's action-log, boot-log or recipe line (1.1.1 audit A10). */
    val listedHardBreakReason: String? get() = when {
        deployLogBinding is DeployLogBinding.Mismatch -> ListedEnclaveReason.ENCLAVE_FAILED
        bootLogReplay is BootLogReplay.Mismatch -> ListedEnclaveReason.BOOT_LOG
        composeFiles.any { it.check is ComposeFileCheck.Mismatch } -> ListedEnclaveReason.ENCLAVE_FAILED
        else -> null
    }

    /**
     * (a) or (b) mismatch is a hard break (red, blocks); not bound / absent is soft (orange, doesn't block). (d)'s
     * notFound is the existing non-blocking provenance failure and is reported there, not here.
     */
    val severity: Severity get() = when {
        deployLogBinding is DeployLogBinding.Mismatch || bootLogReplay is BootLogReplay.Mismatch -> Severity.HARD
        composeFiles.any { it.check is ComposeFileCheck.Mismatch } -> Severity.HARD
        deployLogBinding is DeployLogBinding.NotBound || deployLogBinding == DeployLogBinding.Unchecked || bootLogReplay is BootLogReplay.Absent -> Severity.SOFT
        composeFiles.any { it.check == ComposeFileCheck.Unpublished } -> Severity.SOFT
        else -> Severity.NONE
    }

    companion object {
        /**
         * The degrade lines for a hard break, one per check — two banner lines, not one combined (near-trust-scope,
         * approved by the maintainer 2026-11-06T10:34Z).
         */
        const val ACTION_LOG_BROKEN = "the action log doesn't match the compose manager's hardware quote."
        const val BOOT_LOG_BROKEN = "the boot event log doesn't match the model's hardware quote."
        /**
         * (c)'s banner: the recipe line the session already uses for the model layer's file (reused as-is), and its form
         * before (a) binds the log, which may not call it signed (near-trust-scope, approved 2026-11-06).
         */
        const val RECIPE_UNVERIFIED = "the running recipe doesn't match the signed action log, so the launched configuration is unverified."
        /** (a)'s approved lines when Intel couldn't check the compose manager's quote (near-send-gate-words, 2026-11-06). */
        const val ACTION_LOG_NOT_CHECKED = "action log not checked: intel's check couldn't be completed, so the log below is what near.ai reports, not proven."
        const val ACTION_LOG_NOT_CHECKED_SHORT = "action log not checked"
        const val RECIPE_UNVERIFIED_UNBOUND = "the running recipe doesn't match the action log, so the launched configuration is unverified."

        /**
         * [from] over a node quote's and a compose-manager quote's DCAP verdicts: either side of the TCB comparison unchecked is
         * not a disagreement, and an unchecked manager quote is not a failure. The read enclave's scope and each listed enclave's
         * (1.1.1 audit A4) are built through here, so the listed ones are judged exactly as the read one is.
         */
        fun fromDcap(report: JsonObject, sentNonceHex: String, nodeQuoteHex: String?, node: DcapVerification?, manager: DcapVerification?,
                     fetched: Map<Pair<String, String>, ComposeFileFetch>, bodies: Map<DeployLog.Source, String> = emptyMap(), host: String? = null): NearTrustScope {
            val managerTcb = (manager as? DcapVerification.Verified)?.tcbStatus
            val nodeTcb = (node as? DcapVerification.Verified)?.tcbStatus
            val osImageHash = ((report["info"] as? JsonObject)?.get("os_image_hash") as? JsonPrimitive)?.content
            val unchecked = manager is DcapVerification.Unchecked || (manager != null && node is DcapVerification.Unchecked)
            return from(report, sentNonceHex, nodeQuoteHex, osImageHash, managerDcap = manager?.takeUnless { it.isUnchecked }?.let { !it.isHardFailure },
                tcbDiffers = managerTcb != null && nodeTcb != null && managerTcb != nodeTcb, launcherIndex = LauncherIndex.NotChecked,
                fetched = fetched, hardwareUnchecked = unchecked, bodies = bodies, host = host)
        }

        /**
         * From the model's attestation report: [report] is one model attestation (it carries
         * compose_manager_attestation and info.tcb_info), [sentNonceHex] the nonce this session sent, [nodeQuoteHex]
         * the model node's quote. [managerDcap]: the manager quote's DCAP verdict, null while it runs; [tcbDiffers]: its
         * TCB status is not the node quote's. [bodies]: the running revisions' files, hash-checked, for the chat path
         * and the answering proxy; [host]: the model node's host this report came from.
         */
        fun from(report: JsonObject, sentNonceHex: String, nodeQuoteHex: String?, osImageHash: String?,
                 managerDcap: Boolean?, tcbDiffers: Boolean, launcherIndex: LauncherIndex,
                 fetched: Map<Pair<String, String>, ComposeFileFetch> = emptyMap(), hardwareUnchecked: Boolean = false,
                 bodies: Map<DeployLog.Source, String> = emptyMap(), host: String? = null): NearTrustScope {
            val cm = report["compose_manager_attestation"] as? JsonObject
            // 1.1.1 audit integ read, item 2 (iOS TrustScope.swift @ 01a337a4 run()): tcb_info is an object, or a string
            // holding that object's JSON (a node that serialises it twice); read the string as the object, never as absent.
            val tcbElement = (report["info"] as? JsonObject)?.get("tcb_info")
            val tcbInfo = tcbElement as? JsonObject
                ?: (tcbElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val binding = deployLogBinding(cm, sentNonceHex, nodeQuoteHex, managerDcap, tcbDiffers, hardwareUnchecked)
            val actions = cm?.get("actions") as? JsonArray
            val bound = binding == DeployLogBinding.Bound && actions != null
            val declared = (tcbInfo?.get("app_compose") as? JsonPrimitive)?.content?.let(DeployLog::declaredManager)
            val seat = declared?.let { d ->
                if (bound) ComposeManagerSeat(d, DeployLog.runningManager(actions), ComposeManagerSeat.Source.DEPLOY_LOG)
                else ComposeManagerSeat(d, null, ComposeManagerSeat.Source.DECLARED_ONLY)
            }
            // A whole file's service names come only from a body that hashes to what the log pins for it (a down no up pins
            // is read unhashed, commit-addressed over TLS); anything else is unreadable: services unknown.
            val servicesIn = { src: DeployLog.Source ->
                (fetched[src.commit to src.file] as? ComposeFileFetch.Digest)
                    ?.takeIf { src.fileSha256.isEmpty() || it.sha256.equals(src.fileSha256, ignoreCase = true) }?.services
            }
            val model = (report["model_name"] as? JsonPrimitive)?.contentOrNull
            val (services, path) = if (bound) runningSection(actions, servicesIn, bodies, model, host) else emptyList<RunningService>() to null
            return NearTrustScope(binding, bootLogReplay(tcbInfo, nodeQuoteHex, osImageHash), seat, services, launcherIndex,
                if (bound) ComposeFiles.pairs(services, fetched) else emptyList(),
                if (bound) (DeployLog.wholeFileSources(actions) + DeployLog.upRevisionSources(actions)).distinct() else emptyList(),
                path)
        }

        /**
         * (c) from a bound log: every running row with its liveness, and this chat's path (iOS TrustScope.runningSection
         * @ 71f83846). [servicesIn]: a whole file's service names; [bodies]: the running revisions' files, hash-checked.
         */
        fun runningSection(actions: JsonArray, servicesIn: (DeployLog.Source) -> List<String>?, bodies: Map<DeployLog.Source, String>,
                           model: String?, host: String?): Pair<List<RunningService>, ChatPath.Result?> {
            val walked = DeployLog.runningServices(actions, servicesIn)
            // The proxy that answered this report is alive: its MODEL_NAME is the report's model, matched EXACTLY — a prefix
            // also catches the -abliterated proxy (iOS 4d2a41e3).
            val alive = if (model == null) emptyList() else walked.filter { s ->
                ChatPath.sourceOf(s)?.let(bodies::get)?.let { ChatPath.Compose(it).services[s.service]?.modelName == model } == true
            }
            val undecidable = DeployLog.undecidableServices(actions, walked, servicesIn, alive)
            val services = walked.map { it.copy(undecidable = it.service.isNotEmpty() && it.service in undecidable) }
            return services to host?.let { h -> ChatPath.resolve(h, services) { bodies[it] } }
        }

        /** [ComposeManagerBinding.check]'s outcome as (a)'s field. */
        fun deployLogBinding(cm: JsonObject?, sentNonceHex: String, nodeQuoteHex: String?, dcapPassed: Boolean?, tcbDiffers: Boolean,
                             hardwareUnchecked: Boolean = false): DeployLogBinding {
            if (tcbDiffers && cm?.get("quote") != null) return DeployLogBinding.Mismatch(DeployLogBinding.Check.TCB_DIFFERS)
            // An Intel outage never reads as a mismatch: the local checks run as if the hardware passed, and still break hard.
            return when (val r = ComposeManagerBinding.check(cm, sentNonceHex, nodeQuoteHex, if (hardwareUnchecked) true else dcapPassed)) {
                is BindingCheck.Verified -> if (hardwareUnchecked) DeployLogBinding.Unchecked else DeployLogBinding.Bound
                is BindingCheck.Inconclusive -> DeployLogBinding.NotBound(when (r.reason) {
                    ComposeManagerBinding.CHECKING -> DeployLogBinding.Reason.CHECKING
                    ComposeManagerBinding.NO_NODE_QUOTE -> DeployLogBinding.Reason.NO_NODE_QUOTE
                    else -> DeployLogBinding.Reason.NO_LOG
                })
                is BindingCheck.Failed -> DeployLogBinding.Mismatch(when (r.reason) {
                    ComposeManagerBinding.ACTIONS_HASH_MISMATCH -> DeployLogBinding.Check.ACTIONS_HASH
                    ComposeManagerBinding.NONCE_MISMATCH -> DeployLogBinding.Check.NONCE
                    ComposeManagerBinding.OTHER_TD -> DeployLogBinding.Check.SAME_TD
                    else -> DeployLogBinding.Check.QUOTE
                })
            }
        }

        /** [Rtmr3Replay.check]'s outcome as (b)'s field. */
        fun bootLogReplay(tcbInfo: JsonObject?, nodeQuoteHex: String?, osImageHash: String?): BootLogReplay =
            when (val r = Rtmr3Replay.check(tcbInfo, nodeQuoteHex, osImageHash)) {
                is BindingCheck.Verified -> BootLogReplay.Matches
                is BindingCheck.Inconclusive -> BootLogReplay.Absent
                is BindingCheck.Failed -> BootLogReplay.Mismatch(when {
                    r.reason == Rtmr3Replay.COMPOSE_MISMATCH -> BootLogReplay.What.COMPOSE_HASH
                    r.reason == Rtmr3Replay.OS_MISMATCH -> BootLogReplay.What.OS_IMAGE_HASH
                    r.reason.startsWith(Rtmr3Replay.DIGEST_MISMATCH_PREFIX) -> BootLogReplay.What.EVENT_DIGEST
                    else -> BootLogReplay.What.RTMR3
                })
            }
    }
}

/**
 * (c) One distinct (commit, file) the running table attributes to a running service (audit's option A, 2026-11-04):
 * the file is fetched from near.ai's compose-files repo at that commit and must hash to the log's file_sha256.
 */
data class ComposeFilePair(val commit: String, val file: String, val fileSHA256: String, val latestUp: String?, val services: List<String>, val check: ComposeFileCheck) {
    val key: Pair<String, String> get() = commit to file
}

sealed interface ComposeFileCheck {
    data object Matches : ComposeFileCheck
    /** Hard: the file at that commit is not what the action log says ran. */
    data class Mismatch(val got: String) : ComposeFileCheck
    /** 404 at the pinned commit: orange, it can't be checked. */
    data object Unpublished : ComposeFileCheck
    /** Network or rate limit: no verdict. */
    data object Inconclusive : ComposeFileCheck
    data object Checking : ComposeFileCheck
    /** Past the cap of [ComposeFiles.CAP] pairs. */
    data object NotFetched : ComposeFileCheck
}

/** What a fetch of one compose file found: its body's digest, a 404, or nothing (network, rate limit). */
sealed interface ComposeFileFetch {
    /** The body's SHA-256, and the service names in it — what a whole-file entry's walk reads (null when not read). */
    data class Digest(val sha256: String, val services: List<String>? = null) : ComposeFileFetch
    data object NotFound : ComposeFileFetch
    data object Failed : ComposeFileFetch
}

/**
 * Fetches left for ONE action log (iOS RunningPairCheck.Budget, 9d21baba, 1.1.1 audit cut review item 3): the whole-file names, the pairs
 * and the chat path's bodies of one log draw on it together, concurrently or not. Each listed enclave's log is its own, so N listed
 * enclaves no longer multiply the keyless fetches from raw.githubusercontent.com. Only a request that would go to the network takes
 * from it: a digest or body already held is free, as iOS's cached outcome and body are. Spent, a file is couldn't-check
 * ([ComposeFileFetch.Failed], never kept on disk), never a pass.
 */
class FetchBudget(fetches: Int = ComposeFiles.FETCH_BUDGET) {
    private var left = fetches
    /** One fetch, or false once spent. */
    @Synchronized fun take(): Boolean { if (left <= 0) return false; left -= 1; return true }
    @Synchronized fun spent(): Boolean = left <= 0
}

object ComposeFiles {
    /**
     * GitHub fetches one action log may make: about twice the 99 compose files the largest live log named (2026-11-20 capture,
     * nearai_live_actionlogs_20261120.json; cold runs measured 99/8/21 served, at most 120 under 429). Over it a file is
     * couldn't-check, never a pass (iOS RunningPairCheck.fetchBudget, 9d21baba).
     */
    const val FETCH_BUDGET = 200
    /** At most this many pairs per report are fetched; the rest read "not fetched". */
    const val CAP = 10

    fun url(commit: String, file: String) = "https://raw.githubusercontent.com/$MODEL_COMPOSE_REPO/$commit/$file"

    /** The distinct (commit, file) pairs, newest attributing compose_up first, the first [CAP] to fetch. */
    fun toFetch(services: List<RunningService>): List<Pair<String, String>> = pairs(services, emptyMap()).take(CAP).map { it.key }

    /**
     * Pairs in fetch order with their checks from [fetched]. Rows that name no commit, file or hash have nothing to fetch.
     * Rows of one pair naming different hashes can't both be right: the pair's hash is the newest row's, and an honest log
     * never splits.
     */
    fun pairs(services: List<RunningService>, fetched: Map<Pair<String, String>, ComposeFileFetch>): List<ComposeFilePair> {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<RunningService>>()
        for (s in services) {
            val commit = s.commit ?: continue; val file = s.file ?: continue
            if (s.fileSHA256 == null) continue
            groups.getOrPut(commit to file) { ArrayList() } += s
        }
        // newest attributing up first; on a tie, by file (iOS pairsToCheck)
        return groups.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, MutableList<RunningService>>> { e -> e.value.mapNotNull { it.since }.maxOrNull().orEmpty() }
            .thenBy { it.key.second })
            .mapIndexed { i, (key, rows) ->
                val newest = rows.maxBy { it.since.orEmpty() }
                val sha = newest.fileSHA256!!.lowercase()
                val check = if (i >= CAP) ComposeFileCheck.NotFetched else when (val f = fetched[key]) {
                    null -> ComposeFileCheck.Checking
                    is ComposeFileFetch.Digest -> if (f.sha256.lowercase() == sha && rows.all { it.fileSHA256?.lowercase() == sha }) ComposeFileCheck.Matches else ComposeFileCheck.Mismatch(f.sha256)
                    ComposeFileFetch.NotFound -> ComposeFileCheck.Unpublished
                    ComposeFileFetch.Failed -> ComposeFileCheck.Inconclusive
                }
                ComposeFilePair(key.first, key.second, sha, newest.since, rows.map { it.service }, check)
            }
    }
}
