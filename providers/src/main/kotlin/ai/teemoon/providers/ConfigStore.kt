// ports: teemoon/Providers/ConfigStore.swift @ v1.0.1
//
// Where connection config lives: the servers that answer, the models equipped
// on each, and which one is running. A versioned JSON file, replaced
// atomically, migrated once from the legacy flat `providers` blob.
//
// One detail must not be got wrong: a `Server` INHERITS its `Provider`'s id,
// because the API key sits in the Keystore under that string. A migration
// that mints fresh ids orphans every key, silently, because a missing key
// reads as "not configured" rather than as an error.
//
// Android differences, all deliberate:
//  - Dates are epoch millis (`Long`), not Swift reference-date seconds.
//  - The legacy source is the flat file this store used to write at the SAME
//    path (iOS's was a UserDefaults blob). Migration first freezes a `.v1.json`
//    copy so the v2 write that follows cannot destroy the only v1 we had.
//  - `load()` returns `ConfigLoad` for the app layer; `loadFailure`,
//    `didMigrate` and `snapshot` are the iOS properties and mean the same.
//
// Private persistence. Provider is the public API; this file is not.
// Related: ProviderConfigProjection (Provider ⇄ snapshot).
package ai.teemoon.providers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

// MARK: - Schema

/**
 * What kind of thing answers at an endpoint.
 *
 * Folds in the on-device case, which is what removes the `endpoint:
 * "on-device"` sentinel and with it the class of bug where a non-URL endpoint
 * gets classified by URL parsing.
 */
@Serializable(with = ServerKindSerializer::class)
enum class ServerKind(val rawValue: String) {
    ON_DEVICE("onDevice"), OLLAMA("ollama"), LM_STUDIO("lmStudio"), OPENAI_COMPATIBLE("openAICompatible");
}

/**
 * An unknown kind decodes to OPENAI_COMPATIBLE rather than failing the whole
 * blob. A build that has never heard of a newer kind should lose one server's
 * classification, not every provider the user has configured.
 */
object ServerKindSerializer : KSerializer<ServerKind> {
    override val descriptor = PrimitiveSerialDescriptor("ServerKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ServerKind) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): ServerKind {
        val raw = decoder.decodeString()
        return ServerKind.entries.firstOrNull { it.rawValue == raw } ?: ServerKind.OPENAI_COMPATIBLE
    }
}

/** One place that answers. The credential lives in the Keystore under [id]. */
@Serializable
data class Server(
    /** MUST equal the `Provider.id` this row came from — see the file header. */
    val id: String,
    val name: String,
    val endpoint: String,
    val kind: ServerKind = ServerKind.OPENAI_COMPATIBLE,
    val requiresAPIKey: Boolean = true,
    val authHeaderName: String? = null,
    val extraParams: Map<String, String> = emptyMap(),
    val maxMessages: Int? = null,
    val hasBuiltInGrounding: Boolean = false,
    val omitSystemPrompt: Boolean = false,
    val supportsModelBrowsing: Boolean = false,
    /**
     * The model this server runs when it is selected — a per-server memory, so
     * switching away from a machine and back keeps what you were running on it.
     * The *global* selection is [ConfigSnapshot.currentEquippedID] alone, and
     * `validated()` forces this field to agree with it for the current server.
     */
    val activeModelID: String? = null,
    /** Shown in the preset picker; supplied by presets, not by the user. */
    val presetDescription: String? = null,
    val signupURL: String? = null,
    /**
     * What the server reported having, last time it was asked. NOT the user's
     * selection — that is the [EquippedModel] rows. Keeping them one column is
     * §2.3, the silent revert of a removed home model.
     */
    val servedModels: List<String>? = null,
)

/** One model you can pick, on one server. */
@Serializable
data class EquippedModel(
    val id: String = UUID.randomUUID().toString(),
    val serverID: String,
    val modelID: String,
    val displayName: String? = null,
    /**
     * Per MODEL, which is the fix for §2.2: `Provider.modelCapabilities` is a
     * single optional, so equipping a second model overwrote the first one's
     * capabilities and `modelSupportsTools` then answered for the wrong model.
     */
    val capabilities: ModelCapabilities? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Written when a model first produces output — used, not merely picked. */
    val lastUsedAt: Long? = null,
    /**
     * When this model stopped being equipped, if it has. The row OUTLIVES the
     * unequip: "recently used" is almost entirely made of models that were
     * used and then unequipped, so deleting the row on unequip would throw
     * away the only fact the section is built from. Absent means still
     * equipped, which is what every existing row is.
     */
    val unequippedAt: Long? = null,
) {
    /** Whether this row is part of the user's current selection. */
    val isEquipped: Boolean get() = unequippedAt == null
}

/**
 * The whole persisted config, version included. The version lives INSIDE the
 * blob rather than in a sibling preference: two stores that can disagree
 * eventually do, and a restore from backup is enough to do it.
 */
@Serializable
data class ConfigSnapshot(
    /** Bump when a change stops being readable by the previous decoder. */
    val version: Int = CURRENT_VERSION,
    val servers: List<Server> = emptyList(),
    val equipped: List<EquippedModel> = emptyList(),
    val currentEquippedID: String? = null,
) {
    val isEmpty: Boolean get() = servers.isEmpty() && equipped.isEmpty()

    /** The equipped rows of one server, in stored order. */
    fun rows(serverID: String): List<EquippedModel> = equipped.filter { it.serverID == serverID }

    /** The flat view the app layer still reads (see ProviderConfigProjection). */
    val providers: List<Provider> get() = ProviderConfigProjection.providers(this).providers
    val selectedProviderId: String? get() = ProviderConfigProjection.providers(this).currentProviderID

    /**
     * The two invariants a database would have enforced, applied on every read
     * and every write. Without a foreign key an equipped row can outlive its
     * server — a dangling reference with no sweep.
     *
     * - drops rows whose server is gone, loudly;
     * - collapses duplicate (server, model) rows, keeping the first;
     * - clears a `currentEquippedID` that points at neither;
     * - forces each server's `activeModelID` to be a model it actually has, and
     *   the CURRENT server's to be the current row — so the pair that is live
     *   cannot disagree with itself.
     */
    fun validated(warn: (String) -> Unit = {}): ConfigSnapshot {
        val serverIDs = servers.map { it.id }.toSet()
        val seen = HashSet<String>()
        val kept = ArrayList<EquippedModel>()
        var droppedOrphans = 0; var droppedDuplicates = 0
        for (row in equipped) {
            if (row.serverID !in serverIDs) { droppedOrphans++; continue }
            if (!seen.add(ProviderConfigProjection.rowKey(row.serverID, row.modelID))) { droppedDuplicates++; continue }
            kept.add(row)
        }
        if (droppedOrphans > 0) warn("[config] dropped $droppedOrphans equipped row(s) with no server")
        if (droppedDuplicates > 0) warn("[config] collapsed $droppedDuplicates duplicate equipped row(s)")

        val current = currentEquippedID?.let { id -> kept.firstOrNull { it.id == id } }
        var currentID = currentEquippedID
        if (currentEquippedID != null && current == null) {
            warn("[config] cleared a selection pointing at a model that is no longer equipped")
            currentID = null
        }
        val reconciled = servers.map { server ->
            val models = kept.filter { it.serverID == server.id }.map { it.modelID }
            val active = server.activeModelID
            when {
                // The live pair, reconciled at the one place that can see both.
                current != null && current.serverID == server.id -> server.copy(activeModelID = current.modelID)
                active != null && active !in models -> server.copy(activeModelID = models.firstOrNull())
                active == null -> server.copy(activeModelID = models.firstOrNull())
                else -> server
            }
        }
        return copy(servers = reconciled, equipped = kept, currentEquippedID = currentID)
    }

    companion object {
        const val CURRENT_VERSION = 2
        val empty = ConfigSnapshot()
    }
}

/**
 * The app layer's flat save. Carries no `previous`, so it flattens what a
 * `Provider` cannot express (a probed kind, other rows' capabilities,
 * `lastUsedAt`) — prefer [ConfigStore.save] with providers, which passes the
 * store's last snapshot through.
 */
fun ConfigSnapshot(providers: List<Provider>, selectedProviderId: String?): ConfigSnapshot =
    ProviderConfigProjection.snapshot(providers, selectedProviderId)

// MARK: - The legacy blob

/** The v1 data, read once and then left alone. An interface so the migration can be tested against captured JSON. */
interface LegacyConfigSource {
    /** A JSON array of the flat `Provider` records. */
    val providersJSON: String?
    val currentProviderID: String?
    /** iOS's WhereRecentsStore blob: `[{providerID, modelID, recordedAt}]`, recordedAt in seconds since 2001-01-01. */
    val whereRecentsJSON: String?
    /** Called once when the migration has read this source: keep it somewhere the v2 write cannot reach. */
    fun freeze() {}
}

/** No v1 data at all — what an in-memory store migrates from. */
object EmptyLegacyConfig : LegacyConfigSource {
    override val providersJSON: String? get() = null
    override val currentProviderID: String? get() = null
    override val whereRecentsJSON: String? get() = null
}

/**
 * Android's v1: the flat `{"version":2,"providers":[…],"selectedProviderId":…}`
 * this store wrote before the schema moved, at the SAME path the v2 file now
 * uses. [freeze] copies it to `<name>.v1.json` before the first v2 write.
 */
class FlatFileLegacyConfig(private val file: File) : LegacyConfigSource {
    private val root: JsonObject? by lazy {
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()?.takeIf { isFlatShape(it) }
    }
    override val providersJSON: String? get() = root?.get("providers")?.jsonArray?.toString()
    override val currentProviderID: String? get() = (root?.get("selectedProviderId") as? JsonPrimitive)?.contentOrNull
    override val whereRecentsJSON: String? get() = null
    override fun freeze() {
        val copy = File(file.parentFile, file.nameWithoutExtension + ".v1.json")
        if (root != null && !copy.exists()) runCatching { file.copyTo(copy) }
    }

    companion object {
        /** The flat shape has a `providers` array and no `servers`. A v2 file has `servers`. */
        fun isFlatShape(obj: JsonObject): Boolean = "providers" in obj && "servers" !in obj
    }
}

/** What load() found. Corruption is a state the caller must SEE, never a silent reset. */
sealed interface ConfigLoad {
    data class Ok(val snapshot: ConfigSnapshot, val repaired: Boolean, val migrated: Boolean = false) : ConfigLoad
    /** The file was unreadable. It has been moved aside, not deleted. */
    data class Quarantined(val quarantinePath: String, val reason: String) : ConfigLoad
    data object Empty : ConfigLoad
}

// MARK: - The store

/**
 * FAIL LOUD. A config teemoon cannot parse is moved aside and reported; it is
 * never silently replaced with defaults, because that reads to the user as
 * "the app forgot my providers" with no evidence left to explain why.
 *
 * [file] null → in-memory: nothing is read from or written to disk. Tests and
 * previews MUST use in-memory stores.
 */
class ConfigStore(
    private val file: File?,
    private val legacy: LegacyConfigSource = file?.let { FlatFileLegacyConfig(it) } ?: EmptyLegacyConfig,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /**
     * The last snapshot read or written. Kept so a save can preserve the facts
     * a `Provider` cannot carry — a probed kind, per-model capabilities,
     * `lastUsedAt` — instead of flattening them on every write.
     */
    var snapshot: ConfigSnapshot = ConfigSnapshot.empty
        private set

    /**
     * Non-null when the stored config could not be read. A decode failure must
     * not present as "no setups" to someone with four keys configured. The bad
     * file is preserved next to the good one and this string names what happened.
     */
    var loadFailure: String? = null
        private set

    /** True when `load()` built the snapshot out of the v1 blob. */
    var didMigrate: Boolean = false
        private set

    /** The bad blob, kept for forensics rather than deleted. One fixed name, so a repeatedly-corrupt file cannot fill the disk with copies. */
    val quarantineFile: File? get() = file?.let { File(it.parentFile, it.nameWithoutExtension + ".corrupt.json") }

    class FutureVersion(version: Int) : Exception("config schema v$version was written by a newer version of teemoon")

    /**
     * Reads the v2 file, or migrates v1 once, or returns empty for a fresh
     * install. Never throws: every failure path ends in the most data we can
     * still account for, plus a [loadFailure] for the ones that lost some.
     */
    fun load(): ConfigLoad {
        loadFailure = null
        didMigrate = false
        val f = file ?: run {
            snapshot = snapshot.validated(warn)
            return ConfigLoad.Ok(snapshot, repaired = false)
        }
        if (f.exists()) {
            try {
                val root = json.parseToJsonElement(f.readText()).jsonObject
                if (!FlatFileLegacyConfig.isFlatShape(root)) {
                    val decoded = json.decodeFromJsonElement(ConfigSnapshot.serializer(), root)
                    // Written by a NEWER build. Reading it with this decoder would
                    // silently drop whatever that version added, and the next write
                    // would make the loss permanent.
                    if (decoded.version > ConfigSnapshot.CURRENT_VERSION) throw FutureVersion(decoded.version)
                    snapshot = decoded.validated(warn)
                    return ConfigLoad.Ok(snapshot, repaired = snapshot != decoded)
                }
                // The flat v1 shape at this path: not corrupt, not v2 — migrate below.
            } catch (e: Exception) {
                warn("[config] could not read stored config: $e")
                loadFailure = "The saved connection list could not be read (${e.message})."
                quarantineUnreadableFile()
                // Falls through to v1 — which is still there, because the migration never deletes it.
            }
        }
        snapshot = migrateFromLegacy().validated(warn)
        return when {
            didMigrate -> ConfigLoad.Ok(snapshot, repaired = false, migrated = true)
            loadFailure != null -> ConfigLoad.Quarantined(quarantineFile?.path ?: f.path, loadFailure!!)
            else -> ConfigLoad.Empty
        }
    }

    private fun quarantineUnreadableFile() {
        val f = file ?: return; val q = quarantineFile ?: return
        runCatching {
            if (q.exists()) q.delete()
            if (!f.renameTo(q)) throw IllegalStateException("rename failed")
            warn("[config] unreadable config preserved at ${q.name}")
        }.onFailure { warn("[config] could not preserve unreadable config: $it") }
    }

    /** v1 → v2. `Server.id` inherits `Provider.id` so no Keystore entry moves. */
    private fun migrateFromLegacy(): ConfigSnapshot {
        val data = legacy.providersJSON ?: return ConfigSnapshot.empty   // fresh install
        val providers = try {
            json.decodeFromString(ListSerializer(Provider.serializer()), data)
        } catch (e: Exception) {
            // The v1 blob is left exactly where it is. Something that can read it
            // may still come along; deleting it here would be the one irreversible move.
            warn("[config] legacy providers blob did not decode: $e")
            loadFailure = "The saved connection list could not be read (${e.message})."
            return ConfigSnapshot.empty
        }
        if (providers.isEmpty()) return ConfigSnapshot.empty

        val lastUsed = HashMap<String, Long>()
        legacy.whereRecentsJSON?.let { recentsData ->
            val recents = runCatching { json.parseToJsonElement(recentsData).jsonArray }.getOrNull()
            if (recents == null) {
                // Not worth failing the migration over: recents are a convenience.
                warn("[config] legacy recents blob did not decode; migrating without lastUsedAt")
            } else for (r in recents) {
                val obj = r as? JsonObject ?: continue
                val providerID = (obj["providerID"] as? JsonPrimitive)?.contentOrNull ?: continue
                val modelID = (obj["modelID"] as? JsonPrimitive)?.contentOrNull ?: continue
                val recordedAt = obj["recordedAt"]?.jsonPrimitive?.doubleOrNull ?: continue
                val stamp = ((SWIFT_REFERENCE_DATE_EPOCH_SECONDS + recordedAt) * 1000).toLong()
                val key = ProviderConfigProjection.rowKey(providerID, modelID)
                val existing = lastUsed[key]
                if (existing != null && existing >= stamp) continue
                lastUsed[key] = stamp
            }
        }
        didMigrate = true
        legacy.freeze()
        warn("[config] migrated ${providers.size} provider(s) from v1; the legacy blob is kept and will not be written again")
        return ProviderConfigProjection.snapshot(providers, legacy.currentProviderID, previous = ConfigSnapshot.empty, lastUsed = lastUsed, nowMs = nowMs())
    }

    /**
     * Replaces the file atomically — write temp, rename — so a crash mid-write
     * cannot truncate the config. Writes v2 ONLY. The v1 blob is frozen, never
     * dual-written: keeping it updated would mean two representations that
     * can drift.
     */
    fun write(newSnapshot: ConfigSnapshot) {
        val validated = newSnapshot.validated(warn)
        snapshot = validated
        val f = file ?: return   // in-memory
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(ConfigSnapshot.serializer(), validated))
            if (!tmp.renameTo(f)) throw IllegalStateException("rename failed")
        }.onFailure { warn("[config] failed to write config: $it") }
    }

    /** The app layer's name for [write]. */
    fun save(snapshot: ConfigSnapshot) = write(snapshot)

    /** Saves the flat provider list THROUGH the last snapshot, so probe facts, per-model capabilities and `lastUsedAt` survive. */
    fun save(providers: List<Provider>, currentProviderID: String?, lastUsed: Map<String, Long> = emptyMap()) =
        write(ProviderConfigProjection.snapshot(providers, currentProviderID, previous = snapshot, lastUsed = lastUsed, nowMs = nowMs()))

    /** Stamps a model as used. The one writer of `lastUsedAt`, so "recently used" keeps meaning *used*. */
    fun stampUse(serverID: String, modelID: String, atMs: Long = nowMs()) {
        val index = snapshot.equipped.indexOfFirst { it.serverID == serverID && it.modelID == modelID }
        if (index < 0) return
        val rows = snapshot.equipped.toMutableList(); rows[index] = rows[index].copy(lastUsedAt = atMs)
        write(snapshot.copy(equipped = rows))
    }

    /**
     * Drops a model's row outright — the model AND its history. An explicit
     * delete means "get this out of my list", and leaving it in `recently used`
     * answers a request the user did not make. Unequipping alone cannot express
     * that — the row survives by design — so forgetting is its own verb.
     */
    fun forget(serverID: String, modelID: String) {
        val rows = snapshot.equipped.filterNot { it.serverID == serverID && it.modelID == modelID }
        if (rows.size == snapshot.equipped.size) return
        write(snapshot.copy(equipped = rows))
    }

    /**
     * Records what a probe found: what the server SERVES and what kind it is.
     * Server facts, not the user's selection. Deliberately touches no
     * [EquippedModel] row. A probe is an observation about a machine; it must
     * never add, remove or reorder what someone picked.
     */
    fun recordProbe(serverID: String, kind: ServerKind?, servedModels: List<String>?) {
        val index = snapshot.servers.indexOfFirst { it.id == serverID }
        if (index < 0) return
        var server = snapshot.servers[index]
        // `kind` null means "couldn't tell this time" and must not erase a kind an
        // earlier probe established — losing it costs the machine its /api/pull affordance.
        if (kind != null) server = server.copy(kind = kind)
        if (servedModels != null) server = server.copy(servedModels = servedModels)
        val servers = snapshot.servers.toMutableList(); servers[index] = server
        write(snapshot.copy(servers = servers))
    }

    /** What this model's OWN row knows (ProviderStore.capabilities(ofModel:on:)): the refill after a model move. */
    fun capabilities(serverID: String, modelID: String): ModelCapabilities? =
        snapshot.equipped.firstOrNull { it.serverID == serverID && it.modelID == modelID }?.capabilities

    /** [p] with unknown capabilities filled from its active model's own row; unchanged when it knows them or no row does. */
    fun refilled(p: Provider): Provider =
        if (p.modelCapabilities != null || p.model.isEmpty()) p else capabilities(p.id, p.model)?.let { p.copy(modelCapabilities = it) } ?: p

    /** What a machine was last seen serving — the persisted half of a probe. */
    fun servedModels(serverID: String): List<String>? = snapshot.servers.firstOrNull { it.id == serverID }?.servedModels

    /** What a probe last determined this server to be. */
    fun kind(serverID: String): ServerKind? = snapshot.servers.firstOrNull { it.id == serverID }?.kind

    /**
     * Removes a model's row outright — history included — and drops it from the
     * server's `servedModels`, so the persisted answer stops claiming a machine
     * has something teemoon just removed from it.
     */
    fun forgetModel(serverID: String, modelID: String) {
        val rows = snapshot.equipped.filterNot { it.serverID == serverID && it.modelID == modelID }
        val servers = snapshot.servers.map { s ->
            if (s.id == serverID) s.copy(servedModels = s.servedModels?.filter { it != modelID }) else s
        }
        write(snapshot.copy(servers = servers, equipped = rows))
    }

    companion object {
        fun inMemory() = ConfigStore(file = null, legacy = EmptyLegacyConfig)
        /** Swift `Date` encodes as seconds since 2001-01-01T00:00:00Z. */
        const val SWIFT_REFERENCE_DATE_EPOCH_SECONDS = 978_307_200.0
    }
}
