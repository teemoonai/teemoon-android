package ai.teemoon.app

import kotlinx.coroutines.flow.first

// ports: teemoon/App/ContentView.swift (the `--uitesting` seeding task, seededCloudProvider) @ v1.0.1
// ports: teemoon/App/UITestHostSecrets.swift @ v1.0.5 (the OpenRouter and NVIDIA key files and presets)
//        teemoon/App/UITestHostSecrets.swift (hostKeyFileNames, keyFromHostFile) @ v1.0.1

import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.Provider
import java.io.File
import ai.teemoon.chat.ChatRepository
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.providers.ConfigStore
import ai.teemoon.providers.LocalModel

/**
 * The iOS app under `--uitesting` seeds its providers from launch environment and reads the keys from host key
 * files staged into a directory — so a capture or a UI test never types a secret on screen. This is the same
 * hook for a DEBUGGABLE build: launch extras name the presets, the keys come from files pushed into the app's
 * private `uitest-host-keys/` directory, and the seeding runs before the first frame.
 *
 *     adb shell am start -n ai.teemoon/.app.MainActivity --ez uitesting true \
 *         --es seed_presets nearai,grok --es seed_local_endpoint http://10.0.2.2:8765/v1 \
 *         --es seed_local_model fake-model --ez seed_grounding true
 *
 * As on iOS the seeding REPLACES the configured places (a test must never see or touch real ones), and a release
 * build ignores the extras entirely.
 */
object UiTestSeeding {
    const val EXTRA_FLAG = "uitesting"

    /**
     * Whether this launch may be seeded at all. A debuggable build used to be enough, and `uitesting=true` is an extra
     * any app can send — so while the e2e build was installed, any app could reseed its places, point its Brave key at a
     * host of its choosing, or wipe its chats (the local audit, an INFO since ba4d2b2, 2026-10-13). Now a debuggable
     * build AND a signal no other app can produce: the instrumentation runner sets [instrumentationArmed] inside this
     * process (TeemoonTestRunner), and adb scripts write a token into [ARM_FILE] in the app's private files through
     * `run-as`, and launch with the same token as [EXTRA_ARM].
     *
     * Once per launch: call it once in onCreate and reuse the answer. The marker is spent by ANY launch — the scripts
     * created it and never removed it, so on the dev phone it outlived the run and any app's launch could seed again
     * (the local audit of 15df68e, 2026-10-13). And it arms only the launch that names its token, from a marker under
     * [ARM_MAX_AGE_MS] old: between the script writing it and its own launch, another app's launch could take it, and a
     * script killed before its exit trap left it behind (the local audit of 5af7d25, 2026-10-14). Another app can read
     * neither the file nor the script's shell, so it cannot name the token.
     */
    fun consumeArm(context: android.content.Context, intent: android.content.Intent?): Boolean {
        // The benchmark build types (`benchmark`, and the Baseline Profile plugin's `nonMinifiedRelease` /
        // `benchmarkRelease`) are NOT debuggable — a debuggable build measures nothing — so neither arm below exists
        // for them: no runner in the process, no `run-as`. The build type is the arm. Those builds install under their
        // own id (ai.teemoon.benchmark), are never shipped, and hold only seeded data; every shipped build reads false.
        if (BuildConfig.BENCHMARK_SEEDING) return true
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false
        if (instrumentationArmed) return true
        val marker = java.io.File(context.filesDir, ARM_FILE).takeIf { it.isFile } ?: return false
        val fresh = System.currentTimeMillis() - marker.lastModified() in 0..ARM_MAX_AGE_MS
        val token = runCatching { marker.readText().trim() }.getOrDefault("")
        marker.delete()
        val named = intent?.getStringExtra(EXTRA_ARM)?.trim().orEmpty()
        return fresh && token.length >= 16 && named == token
    }

    @Volatile var instrumentationArmed = false
    const val ARM_FILE = "uitest-armed"
    const val EXTRA_ARM = "uitest_arm"
    const val ARM_MAX_AGE_MS = 60_000L
    const val EXTRA_PRESETS = "seed_presets"
    const val EXTRA_LOCAL_ENDPOINT = "seed_local_endpoint"
    const val EXTRA_LOCAL_MODEL = "seed_local_model"
    /** The seeded local setup's NAME (default "local"): the error card finds a vendor console by the source's name, so the recovery journeys name the fake server after a preset. */
    const val EXTRA_LOCAL_NAME = "seed_local_name"
    /** `ollama`: the seeded server is stored as an Ollama box, so Think Harder is offered on it (ReasoningControl, iOS 7cd8c10). */
    const val EXTRA_LOCAL_KIND = "seed_local_kind"
    /** Live runs: the FIRST preset's model (iOS UITEST_SEED_MODEL) — a cheap one, so a live suite costs cents. */
    const val EXTRA_PRESET_MODEL = "seed_preset_model"
    /** B9: the seeded home server answers one question at a time (`maxMessages`), as Brave Answers' preset does. */
    const val EXTRA_LOCAL_MAX_MESSAGES = "seed_local_max_messages"
    /** A second, NOT active local setup beside the cloud seed — for the switch-then-send journeys (iOS UITEST_SEED_ALSO_LOCAL_ENDPOINT). */
    const val EXTRA_ALSO_LOCAL_ENDPOINT = "seed_also_local_endpoint"
    /** Model downloads fetch from here instead of Hugging Face (the journeys' fake server). */
    const val EXTRA_MODEL_HOST = "seed_model_host"
    /** Pin the network path to mobile data (true) or wi-fi (false) for the cellular-download journeys. */
    const val EXTRA_MOBILE_DATA = "seed_mobile_data"
    const val EXTRA_GROUNDING = "seed_grounding"
    /** `degraded`: the attestation fetch is skipped and reads as failed, so the first send asks (UITEST_SEED_ATTESTATION). */
    const val EXTRA_ATTESTATION = "seed_attestation"
    /** `UITEST_SEED_LONG_THREAD=<n>`: ONE thread of n turns (and a scratch thread older than it), for the follow suites. */
    const val EXTRA_LONG_THREAD = "seed_long_thread"
    /** `UITEST_SEED_THREADS=1`: the four design fixtures — a populated list and a real exchange, for captures and step goldens. */
    const val EXTRA_THREADS = "seed_threads"
    /** `seed_many_threads=<n>`: n short threads with markdown replies, all older than the long thread — the list-scroll fixture for :macrobenchmark. */
    const val EXTRA_MANY_THREADS = "seed_many_threads"
    /** `seed_stream_reply=true`: every turn is answered by [ScriptedReplyTransport] playing [StreamingReplyFixture.MARKDOWN] — the streaming fixture for :macrobenchmark. */
    const val EXTRA_STREAM_REPLY = "seed_stream_reply"
    /** Points the Brave tool at an in-process fake, so a grounded turn runs on a device with no key and no network. */
    const val EXTRA_BRAVE_ENDPOINT = "seed_brave_endpoint"
    /** Start from an empty chat store — what a fresh install gives the iOS UI tests. Debuggable builds only, and only with the flag. */
    const val EXTRA_FRESH_STORE = "seed_fresh_store"
    /** Forget the Brave key and turn grounding off — the "no key" state, whatever the device held before. */
    const val EXTRA_NO_BRAVE_KEY = "seed_no_brave_key"
    /** Developer mode on, so the request debug card renders under the reply (the settings toggle, without the screens). */
    const val EXTRA_DEVELOPER = "seed_developer"
    /**
     * Equip the on-device model whose weights are ALREADY on disk, and make it current.
     *
     * The `get` section offers a model until a provider is equipped for it (`WhereGetPolicy.recommendedDownload`
     * asks the providers, not the filesystem), and only a finished download equips one — `onInstalled` builds the
     * `Provider.local`. A harness that stages the bundle with `adb push` therefore lands weights nothing points at,
     * and the sheet still reads "download". This extra closes that gap WITHOUT re-fetching 2.4 GB, which is what a
     * device run needs: the download path is a separate test, not a prerequisite for every on-device test.
     */
    const val EXTRA_ON_DEVICE = "seed_ondevice"
    /**
     * Start the catalog leader's download on launch, through the app's own downloader — the in-app path, which no
     * harness had exercised end to end. A remote lab device cannot take a 2.6 GB `adb push` inside a 30-minute
     * session, but it has Wi-Fi, and the download is the thing worth testing there anyway.
     */
    const val EXTRA_DOWNLOAD = "seed_download"
    /**
     * C9: plant a config problem and run the app's own launch load over it — `corrupt` (an unreadable providers.json,
     * which must be quarantined and surfaced, never read as a first run) or `duplicates` (one connection saved twice,
     * which the launch heal merges and announces). REPLACES the preset seeding: the planted file is the whole world.
     */
    const val EXTRA_CONFIG = "seed_config"
    /** C9: pin the network path offline (true) — the get section must say why it cannot add anything. */
    const val EXTRA_OFFLINE = "seed_offline"
    /** The free memory (MB) the get section is told, so a journey's first run does not depend on the phone's other apps. */
    const val EXTRA_AVAILABLE_MB = "seed_available_mb"
    const val KEY_DIR = "uitest-host-keys"

    /** The bytes [EXTRA_CONFIG] plants, or null for an unknown name. `duplicates` is the fireworks preset saved twice. */
    fun plantConfig(name: String, file: File): Boolean {
        when (name) {
            "corrupt" -> file.writeText("{\"version\": 2, \"servers\": [ this is not json")
            "duplicates" -> {
                val a = Provider.fireworks; val b = Provider.fireworks.copy(id = "uitest-duplicate")
                ConfigStore(file).save(listOf(a, b), a.id)
            }
            else -> return false
        }
        return true
    }

    data class Plan(val presets: List<String>, val localEndpoint: String?, val localModel: String?, val grounding: Boolean, val attestation: String? = null, val longThread: Int? = null, val onDevice: Boolean = false, val download: Boolean = false, val threads: Boolean = false, val alsoLocalEndpoint: String? = null, val localName: String? = null, val localMaxMessages: Int? = null, val presetModel: String? = null, val manyThreads: Int? = null, val streamReply: Boolean = false, val localKind: String? = null)

    /** Null unless the flag is set: a normal launch never seeds. Preset names are iOS's (`nearai`, `grok`, `fireworks`, `braveanswers`). */
    fun plan(extras: Map<String, Any?>): Plan? {
        if (extras[EXTRA_FLAG] != true) return null
        val presets = (extras[EXTRA_PRESETS] as? String).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return Plan(presets, (extras[EXTRA_LOCAL_ENDPOINT] as? String)?.takeIf { it.isNotBlank() }, (extras[EXTRA_LOCAL_MODEL] as? String)?.takeIf { it.isNotBlank() }, extras[EXTRA_GROUNDING] == true,
            (extras[EXTRA_ATTESTATION] as? String)?.takeIf { it == "degraded" || it == "verifyingPending" || ConfidentialSession.UiTestSeed.of(it) != null }, (extras[EXTRA_LONG_THREAD] as? Int)?.takeIf { it > 0 }, extras[EXTRA_ON_DEVICE] == true, extras[EXTRA_DOWNLOAD] == true,
            extras[EXTRA_THREADS] == true, (extras[EXTRA_ALSO_LOCAL_ENDPOINT] as? String)?.takeIf { it.isNotBlank() }, (extras[EXTRA_LOCAL_NAME] as? String)?.takeIf { it.isNotBlank() }, (extras[EXTRA_LOCAL_MAX_MESSAGES] as? Int)?.takeIf { it > 0 }, (extras[EXTRA_PRESET_MODEL] as? String)?.takeIf { it.isNotBlank() },
            (extras[EXTRA_MANY_THREADS] as? Int)?.takeIf { it > 0 }, extras[EXTRA_STREAM_REPLY] == true, (extras[EXTRA_LOCAL_KIND] as? String)?.takeIf { it == "ollama" })
    }

    /** UITestHostSecrets.keyFileNames(forPreset:) — the same file names, so one `~` serves both harnesses. */
    fun keyFileNames(preset: String): List<String> = when (preset.lowercase()) {
        "nearai", "near.ai", "near" -> listOf(".NEAR_AI_API_KEY", ".nearai_api_key")
        "grok", "xai" -> listOf(".XAI_API_KEY", ".xai_api_key")
        "fireworks" -> listOf(".FIREWORKS_API_KEY", ".fireworks_api_key")
        "openrouter" -> listOf(".OPENROUTER_API_KEY", ".openrouter_api_key")
        "nvidia" -> listOf(".NVIDIA_API_KEY", ".nvidia_api_key")
        "brave", "braveanswers" -> listOf(".BRAVE_ANSWERS_API_KEY")
        "grounding", "bravesearch" -> listOf(".BRAVE_API_KEY", ".brave_api_key")
        else -> emptyList()
    }

    fun preset(name: String): Provider? = when (name.lowercase()) {
        "nearai", "near.ai", "near" -> Provider.nearAI
        "grok", "xai" -> Provider.grok
        "fireworks" -> Provider.fireworks
        "openrouter" -> Provider.openRouter
        "nvidia" -> Provider.nvidia
        "brave", "braveanswers" -> Provider.braveAnswers
        else -> null
    }

    fun readKey(dir: File, names: List<String>): String? =
        names.map { File(dir, it) }.firstOrNull { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Applies [plan]: every configured place is removed, the named presets are added in order (the first becomes
     * current) with their keys, a local endpoint becomes a home server, and the grounding key is saved and enabled.
     * Returns one line per thing seeded, for the log.
     */
    fun apply(
        plan: Plan, store: AddEditProviderModel.Store, key: (List<String>) -> String?,
        setBraveKey: (String) -> String?, setGroundingEnabled: (Boolean) -> Unit,
        /** The catalog model whose weights are on disk, or null. `LocalModelStorage::isInstalled` in the app. */
        installedLocal: () -> LocalModel? = { null },
    ): List<String> {
        val seeded = mutableListOf<String>()
        store.providers.toList().forEach { store.removeProvider(it) }
        var first: String? = null
        // FIRST, so `seed_ondevice` alone selects it: an on-device run wants the phone answering, not a server.
        if (plan.onDevice) {
            val model = installedLocal()
            if (model == null) seeded += "on-device (no weights on disk)"
            else { first = store.addProvider(Provider.local(model)); seeded += "on-device ${model.id} equipped" }
        }
        plan.localEndpoint?.let { base ->
            val local = Provider(id = "uitest-local", name = plan.localName ?: "local", endpoint = base.trimEnd('/') + "/chat/completions", model = plan.localModel ?: "local-model",
                // keyless, as the fake server is: seeded after the launch heal, a default `requiresAPIKey = true` met the
                // no-key send gate (148a131) and every seeded turn was refused before the wire
                requiresAPIKey = false, maxMessages = plan.localMaxMessages)
            // The add is its own statement: as `first = first ?: store.addProvider(local)` the elvis short-circuits once
            // anything has claimed `first`, and the server is silently never added. Harmless while this ran first, a
            // missing home server the moment anything is seeded ahead of it.
            val id = store.addProvider(local)
            first = first ?: id; seeded += "local ${local.endpoint} model=${local.model}"
        }
        for (name in plan.presets) {
            val base = preset(name) ?: run { seeded += "unknown preset $name"; continue }
            val p = plan.presetModel?.takeIf { first == null && !base.isFixedAnswerService }?.let { base.equipping(it) } ?: base
            val id = store.addProvider(p)
            val k = key(keyFileNames(name))
            if (k != null) { store.setCredential(k, id); seeded += "${p.name} keyed" } else seeded += "${p.name} (no key file)"
            first = first ?: id
        }
        // Added AFTER the presets and never made current: the cloud seed stays active, the local one waits on the Where sheet.
        plan.alsoLocalEndpoint?.let { base ->
            val local = Provider(id = "uitest-also-local", name = "local", endpoint = base.trimEnd('/') + "/chat/completions", model = plan.localModel ?: "local-model",
                // keyless, as the fake server is: seeded after the launch heal, a default `requiresAPIKey = true` met the
                // no-key send gate (148a131) and every seeded turn was refused before the wire
                requiresAPIKey = false)
            val id = store.addProvider(local); first = first ?: id; seeded += "also local ${local.endpoint} model=${local.model}"
        }
        first?.let { store.currentProviderID = it }
        if (plan.grounding) {
            val k = key(keyFileNames("grounding"))
            if (k != null) { val problem = setBraveKey(k); setGroundingEnabled(problem == null); seeded += if (problem == null) "grounding keyed" else "grounding: $problem" }
            else seeded += "grounding (no key file)"
        }
        return seeded
    }
}

/**
 * teemoonApp.seedLongThread: a scratch thread (older, so it never takes the first row — a cold re-open needs somewhere
 * else to go first) and ONE long thread of [turns] turns, question / reply alternating, every tenth reply carrying a
 * think block so the folded-reasoning path is in the history. Idempotent: a second launch finds the thread and leaves it.
 */
suspend fun UiTestSeeding.seedLongThread(repo: ChatRepository, turns: Int): String {
    val base = 1_770_000_000_000L
    val existing = repo.threads().first()
    if (existing.any { it.title?.startsWith("long thread") == true }) return "long thread present"
    val scratch = repo.createThread("uitest-scratch", base - 86_400_000L)
    repo.addMessage("uitest-scratch-0", scratch.id, "user", "scratch", base - 86_400_000L)
    repo.addMessage("uitest-scratch-1", scratch.id, "assistant", "scratch reply", base - 86_400_000L + 30_000L)
    repo.titleFromFirstMessage(scratch.id, "scratch")
    val thread = repo.createThread("uitest-long", base)
    for (i in 0 until turns) {
        val content = when {
            i % 2 == 0 -> "question $i: what does this actually prove, and how would I check it myself?"
            i % 10 == 1 -> "<think>\nTurn $i. The user is asking about verification, so the useful split is what the quote covers versus what it does not.\n</think>\nReply $i. " + "The measurement binds the code, not the intent behind it. ".repeat(6)
            // code blocks and TABLES, deliberately: the 2026-08-06 device freeze was upward realisation of table-bearing history
            // while a reply streamed, and a history with no tables cannot reproduce it
            i % 5 == 1 -> "Reply $i, with a block that needs the structured renderer:\n\n```swift\nlet quote = try TDXQuote(parsing: report)\nguard quote.mrConfigID == expected else { throw Err.mismatch }\n```\n\n" + "Followed by prose that wraps across several lines. ".repeat(5)
            i % 7 == 3 -> "reply $i: the fields, side by side\n\n| field | quote | expected |\n|---|---|---|\n| mrtd | 8f3a…c1 | 8f3a…c1 |\n| mrconfigid | 00…00 | 00…00 |\n| tcb | up to date | up to date |\n\n" + "Each row is one claim the verifier checked. ".repeat(3)
            else -> "Reply $i. " + "The quote names the enclave and the model it loaded; the collateral says whether that platform is current. ".repeat(3)
        }
        repo.addMessage("uitest-long-$i", thread.id, if (i % 2 == 0) "user" else "assistant", content, base + i * 30_000L)
    }
    repo.titleFromFirstMessage(thread.id, "long thread ($turns turns)")
    return "long thread seeded: $turns turns"
}

/**
 * UITestStoreSeeding.seedFixtureThreadsIfRequested: the DESIGN fixtures — four short threads, chosen for how they
 * LOOK rather than how much of them there is, which is what separates them from [seedLongThread] above.
 *
 * A capture of the chat screen needs it to look like a chat screen: a populated list, a real exchange, paragraphs
 * that wrap. The only other source of that is the developer's own history, which cannot be published, so a
 * screenshot diff was reading as design drift every time someone opened the app. Fixed text and a fixed base date
 * for the same reason — a capture taken tomorrow must differ from today's only where the design did.
 *
 * Off unless asked for: most tests want the empty state, and an empty transcript is itself a screen worth capturing.
 */
suspend fun UiTestSeeding.seedFixtureThreads(repo: ChatRepository): String {
    val base = 1_770_000_000_000L
    if (repo.threads().first().any { it.id.startsWith("uitest-fixture-") }) return "fixture threads present"

    // (title, daysAgo, turns). The title is the FIRST message's opening, as `titleFromFirstMessage` derives it
    // everywhere else — a seeded title the app would not itself have produced is a fixture that tests nothing.
    val threads = listOf(
        Triple("what attestation actually proves", 0, listOf(
            "user" to "if the server is attested, what exactly am I trusting?",
            "assistant" to """
                You're trusting three things, and it's worth separating them.

                The hardware vendor, that the TEE does what it claims. The measurement, that the code hashed into the quote is the code you think it is. And the chain from that measurement back to source you can read.

                Attestation proves the second one cleanly. The first is an assumption you inherit. The third is only as good as the build being reproducible.
            """.trimIndent())),
        // Deliberately long opening line: the thread title is derived from it, and an untruncated title is only
        // visibly wrong past a certain length.
        Triple("a long opening question", 1, listOf(
            "user" to "when a provider says the model runs in a secure enclave, which specific claims can I check myself and which ones am I taking on faith from the vendor?",
            "assistant" to "Check the quote and the measurement. Take the silicon on faith.")),
        Triple("a shorter one", 2, listOf(
            "user" to "does this run offline?",
            "assistant" to "On-device models do. Cloud providers don't, by definition.")),
        Triple("weekend reading", 9, listOf(
            "user" to "summarise the confidential computing landscape",
            "assistant" to "The short version: three vendors, two threat models, and a lot of overloaded words.")),
    )
    threads.forEachIndexed { t, (title, daysAgo, turns) ->
        val at = base - daysAgo * 86_400_000L
        val thread = repo.createThread("uitest-fixture-$t", at)
        turns.forEachIndexed { i, (role, content) -> repo.addMessage("uitest-fixture-$t-$i", thread.id, role, content, at + i * 30_000L) }
        repo.titleFromFirstMessage(thread.id, title)
    }
    return "fixture threads seeded: ${threads.size}"
}

/**
 * The list-scroll fixture (Android-only, for :macrobenchmark): [count] two-message threads, an hour apart and all older
 * than [seedLongThread]'s long thread, so a launch still opens that one. Replies carry markdown — a list, bold, inline
 * code — so a row's snippet and an opened thread go through the same renderer a real history does. Idempotent.
 */
suspend fun UiTestSeeding.seedManyThreads(repo: ChatRepository, count: Int): String {
    val base = 1_770_000_000_000L
    if (repo.threads().first().any { it.id.startsWith("uitest-many-") }) return "many threads present"
    for (k in 0 until count) {
        val at = base - (k + 1) * 3_600_000L
        val thread = repo.createThread("uitest-many-$k", at)
        val question = "thread $k: how would I check that claim ${k % 7} holds on my own machine?"
        repo.addMessage("uitest-many-$k-0", thread.id, "user", question, at)
        repo.addMessage("uitest-many-$k-1", thread.id, "assistant",
            "Three steps for claim ${k % 7}:\n\n1. Fetch the **quote** with `attest --raw`.\n2. Compare the measurement to the published build.\n3. Check the collateral is current.\n\n" +
                "Each step is something you can rerun yourself. ".repeat(2), at + 30_000L)
        repo.titleFromFirstMessage(thread.id, question)
    }
    return "many threads seeded: $count"
}

/**
 * The streaming fixture (Android-only, for :macrobenchmark's StreamingReplyBenchmark): with `seed_stream_reply`, every
 * turn on a seeded launch is answered by [ScriptedReplyTransport] instead of the wire — through the app's real
 * ChatViewModel → GenerationEngine path (AppState.transportFor is the makeTransport seam), so what is measured is the
 * transcript repainting a long markdown reply as it grows, and nothing leaves the device.
 */
object StreamingReplyFixture {
    /** ~6,000 characters: headings, nested lists, inline code and links, a fenced code block, a table — every block kind the renderer draws. */
    val MARKDOWN: String = buildString {
        append("# Checking an attested reply yourself\n\n")
        append("The short version: the quote binds the **code**, the collateral binds the **platform**, and the signature binds the **reply**. ")
        append("Each of those is a separate check, and each can fail on its own, so it is worth walking through them one at a time.\n\n")
        val sections = listOf("The quote", "The collateral", "The signature", "The build", "The transport", "What is left on faith")
        sections.forEachIndexed { s, title ->
            append("## ${s + 1}. $title\n\n")
            append("This step answers one question and only one. If it passes, you know something specific; if it fails, nothing after it means anything. ")
            append("Read the `$title` section of the report with `attest --raw --section ${s + 1}` and compare it against what the [published build](#build-${s + 1}) says.\n\n")
            append("- Fetch the report over a channel you already trust.\n")
            append("- Check the field named in this step:\n")
            append("  - against the expected value, byte for byte;\n")
            append("  - and against the previous run, so a change is noticed.\n")
            append("- Write down what you checked and when.\n\n")
            if (s == 1) {
                append("```kotlin\n")
                append("val quote = TdxQuote.parse(report.bytes)\n")
                append("require(quote.mrTd.contentEquals(expected.mrTd)) { \"mrtd mismatch\" }\n")
                append("require(quote.mrConfigId.contentEquals(expected.mrConfigId)) { \"config mismatch\" }\n")
                append("val tcb = collateral.tcbStatus(quote.fmspc)\n")
                append("check(tcb == TcbStatus.UP_TO_DATE) { \"platform is $" + "tcb\" }\n")
                append("```\n\n")
            }
            if (s == 2) {
                append("| field | quote | expected | verdict |\n")
                append("|---|---|---|---|\n")
                for (r in 0 until 6) append("| field-$r | 8f3a…c$r | 8f3a…c$r | matches |\n")
                append("\n")
            }
            append("1. Run the check.\n2. Record the result.\n3. Repeat it tomorrow, because collateral expires and a platform that was current can fall behind.\n\n")
            append("> A check you cannot rerun yourself is a claim, not a check.\n\n")
        }
        append("### Summary\n\n")
        append("Attestation proves the measurement cleanly. The silicon is an assumption you inherit, and the chain from the measurement back to source is only as good as the build being reproducible. ")
        append("None of this needs the provider's cooperation after the fact: everything above runs on your own machine, against bytes you fetched yourself.")
    }

    /** The seeded prompt the benchmark types (any prompt gets [MARKDOWN]). */
    const val PROMPT = "how would I check an attested reply myself?"
    /** ~30 tokens/s at a few characters a token: one partial every 33 ms. */
    const val PARTIAL_INTERVAL_MS = 33L
}

/**
 * Plays [text] back as a stream: CUMULATIVE partials (what OpenAiTransport's onPartial delivers), 2–6 characters
 * apart in a fixed rotation (token-sized), one every [intervalMs]. No network, no tools; the turn ends with the whole text.
 */
class ScriptedReplyTransport(private val text: String, private val intervalMs: Long = StreamingReplyFixture.PARTIAL_INTERVAL_MS) : ai.teemoon.inference.GenerationTransport {
    override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<ai.teemoon.inference.Tool>, onPartial: (String) -> Unit): ai.teemoon.inference.TurnResult {
        for (end in partialEnds(text.length)) { kotlinx.coroutines.delay(intervalMs); onPartial(text.substring(0, end)) }
        return ai.teemoon.inference.TurnResult(content = text)
    }

    companion object {
        private val STEPS = intArrayOf(4, 3, 5, 2, 6, 4, 3)
        /** The end offset of each partial: token-sized steps, the last one exactly [length]. */
        fun partialEnds(length: Int): List<Int> {
            val ends = ArrayList<Int>(); var at = 0; var k = 0
            while (at < length) { at = minOf(length, at + STEPS[k++ % STEPS.size]); ends += at }
            return ends
        }
    }
}
