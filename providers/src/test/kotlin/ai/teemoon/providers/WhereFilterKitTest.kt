// spec: teemoon-ios docs/IMAGES.md §4e @ c69b3492 — the where-sheet filter over the kit's `wherefilter` class (0.7.0): every
// scenario × tab/filter cell iOS generated must come out of WhereFilter field for field (count, control, ready, recents,
// get with its trailing text, the empty line).
package ai.teemoon.providers

import ai.teemoon.providers.ModelBrowserQuery.Filter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereFilterKitTest {
    private val kit by lazy { Json.parseToJsonElement(WhereFilterKitTest::class.java.getResourceAsStream("/wherefilter.json")!!.readBytes().decodeToString()).jsonObject }
    private val scenarios get() = kit["scenarios"]!!.jsonArray.map { it.jsonObject }

    private val filterKeys = mapOf("none" to emptySet(), "images" to setOf(Filter.VISION), "e2ee" to setOf(Filter.ENCRYPTED), "images+e2ee" to setOf(Filter.VISION, Filter.ENCRYPTED))
    private val tabKeys = mapOf("all" to null, "phone" to WhereLocality.PHONE, "home" to WhereLocality.HOME, "cloud" to WhereLocality.CLOUD)

    private data class Model(val id: String, val tab: WhereLocality, val localModel: String?, val ability: WhereFilter.Ability)
    private data class Cell(val count: String?, val control: String, val ready: List<String>, val refused: List<String>, val recents: List<String>, val get: List<Pair<String, String?>>?, val line: String?)

    private fun JsonObject.s(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun counts(o: JsonObject): (Set<Filter>) -> Int? {
        val c = o["counts"]?.jsonObject ?: return { null }
        return { f -> c[filterKeys.entries.first { it.value == f }.key]?.jsonPrimitive?.int }
    }

    private fun id(row: WhereFilter.GetRow) = when (row) {
        is WhereFilter.GetRow.Download -> "download:${row.id}"
        is WhereFilter.GetRow.Pull -> "pull:${row.machine}"
        WhereFilter.GetRow.Connect -> "connect a computer"
        is WhereFilter.GetRow.Server -> (if (row.custom) "browse:" else "preset:") + row.name
        WhereFilter.GetRow.AddCloudKey -> "add a cloud key"
    }

    /** What the sheet would show for one cell, built from the scenario the way the sheet builds it from the person's setups. */
    private fun cell(s: JsonObject, tab: WhereLocality?, filters: Set<Filter>): Cell {
        val online = s["online"]?.jsonPrimitive?.booleanOrNull ?: true
        val imagesOnDevice = s["imagesOnDevice"]!!.jsonPrimitive.boolean
        val models = s["models"]!!.jsonArray.map { it.jsonObject }.map { m ->
            Model(m.s("id")!!, tabKeys[m.s("tab")]!!, m.s("localModel"),
                WhereFilter.Ability(m["readsImages"]?.jsonPrimitive?.booleanOrNull ?: false, m["e2ee"]!!.jsonPrimitive.boolean,
                    imagesRefused = m["imagesRefused"]?.jsonPrimitive?.booleanOrNull ?: false))
        }
        // WhereGetPolicy.filteredProviders: offline, phone rows on every tab
        val inTab = models.filter { if (!online) it.tab == WhereLocality.PHONE else tab == null || it.tab == tab }
        val ready = inTab.filter { WhereFilter.passes(it.ability, filters) }
        // a phone model this phone can never give the memory images need: listed under reads images, not counted
        val refused = inTab.filter { WhereFilter.refused(it.ability, filters) }
        // recently used: all tab, online only (as today)
        val recents = if (online && tab == null) s["recents"]!!.jsonArray.map { r -> models.first { it.id == r.jsonPrimitive.content } }.filter { WhereFilter.passes(it.ability, filters) } else emptyList()
        // WhereGetPolicy.recommendedDownload / downloadableModels: what the phone doesn't hold yet
        val owned = models.mapNotNull { it.localModel }.toSet()
        // a download carries its own image ability (E2B, not E4B); one without follows the scenario's imagesOnDevice
        val downloads = s["downloads"]!!.jsonArray.map { it.jsonObject }.map { d -> WhereFilter.GetRow.Download(d.s("id")!!, readsImages = d["readsImages"]?.jsonPrimitive?.booleanOrNull ?: imagesOnDevice) }
        val recommended = downloads.firstOrNull()?.takeIf { it.id !in owned }
        val pulls = s["homes"]!!.jsonArray.map { it.jsonObject }.filter { it.s("kind") == "ollama" }.map { WhereFilter.GetRow.Pull(it.s("name")!!) }
        val customs = s["customs"]?.jsonArray.orEmpty().map { it.jsonObject }.map { c -> WhereFilter.GetRow.Server(c.s("name")!!, custom = true, keyed = true, claimsE2ee = c["claimsE2ee"]!!.jsonPrimitive.boolean, listCount = counts(c)) }
        val presets = s["presets"]!!.jsonObject.map { (name, v) -> val p = v.jsonObject
            WhereFilter.GetRow.Server(name, custom = false, keyed = p["keyed"]!!.jsonPrimitive.boolean, claimsE2ee = p["claimsE2ee"]!!.jsonPrimitive.boolean,
                fixedAnswer = p["fixedAnswer"]?.jsonPrimitive?.booleanOrNull ?: false, configured = p["configured"]?.jsonPrimitive?.booleanOrNull ?: false, listCount = counts(p))
        }
        val get = WhereFilter.candidates(tab, online, recommended, downloads.filter { it.id !in owned }, pulls, customs, presets)?.let { WhereFilter.get(it, filters) }
        return Cell(WhereFilter.count(ready.size, inTab.size), WhereFilter.control(filters), ready.map { it.id }, refused.map { it.id }, recents.map { it.id },
            get?.map { id(it.row) to it.trailing }, WhereFilter.line(tab, filters, ready.size, recents.size, get, imagesOnDevice))
    }

    private fun expected(c: JsonObject) = Cell(c.s("count"), c.s("control")!!, c["ready"]!!.jsonArray.map { it.jsonPrimitive.content },
        c["refused"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), c["recents"]!!.jsonArray.map { it.jsonPrimitive.content },
        c["get"]?.takeIf { it !is JsonNull }?.jsonArray?.map { g -> g.jsonObject.let { it.s("id")!! to it.s("trailing") } }, c.s("line"))

    @Test fun everyCellIsWhatIosGenerated() {
        var cells = 0
        for (s in scenarios) {
            val expect = s["expect"]!!.jsonObject
            for ((key, c) in expect["cells"]?.jsonObject.orEmpty()) {
                val (tab, filter) = key.split("/")
                assertEquals("${s.s("name")} $key", expected(c.jsonObject), cell(s, tabKeys.getValue(tab), filterKeys.getValue(filter)))
                cells++
            }
        }
        // 0.7.0: mock-v18 16, none-of-yours 16, unknown 8, custom 8, phone-reads-images 8, e4b-downloaded 4, small-phone 4, offline 4 — a kit that lost cells fails here
        assertEquals("cells compared", 68, cells)
    }

    /** First run draws no filter row: it has no tabs at all, and the policy's own rule says when that sheet shows. */
    @Test fun firstRunHasNoFilterRow() {
        val s = scenarios.single { it["expect"]!!.jsonObject["filterRow"]?.jsonPrimitive?.boolean == false }
        assertTrue(s["models"]!!.jsonArray.isEmpty())
        val policy = WhereGetPolicy(null, emptyList(), true, emptyMap(), { "" }, { "" })
        assertTrue(policy.showsFirstRun(loadFailed = false, recommended = LocalModelCatalog.all.first()))
    }

    @Test fun theMenuIsTheKits() {
        val menu = kit["menu"]!!.jsonObject
        assertEquals(menu.s("heading"), WhereFilter.MENU_HEADING)
        assertEquals(menu["items"]!!.jsonArray.map { it.jsonPrimitive.content }, WhereFilter.offered.map { it.label })
    }

    /**
     * IMAGES.md §4b (the maintainer 2026-11-12, "check how much memory is needed and how much is available at run time"):
     * at the image turn, in order — killed before, or the ceiling under the need: the permanent line, even when free
     * memory is short too (never "close some apps" on a phone that can never fit it); free now under the need: "now";
     * else run.
     */
    @Test fun theTurnCasesAreTheKits() {
        val cases = kit["phoneImages"]!!.jsonObject["turnCases"]!!.jsonArray.map { it.jsonObject }
        for (c in cases) {
            val v = PhoneImageMemory.verdict(c["needMB"]!!.jsonPrimitive.int, c["ceilingMB"]!!.jsonPrimitive.int, c["availableMB"]!!.jsonPrimitive.int,
                killedBefore = c["killedBefore"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(c.s("name"), c.s("expect"), v.name.lowercase())
        }
        assertEquals(6, cases.size)
    }

    /**
     * The memory an image turn needs, per image path (phoneImages.imageNeedMB.<model>.android, the maintainer's D,
     * 2026-11-12): the worst availMem drop of the device pass (docs/bench/2026-11-12-images) on the path this phone takes —
     * the NPU bundle that reads images, else the GPU on a Tensor or Snapdragon 8, else the CPU. A path the model has no
     * bundle for is absent.
     */
    @Test fun androidsImageNeedIsTheKitsPerPath() {
        val need = kit["phoneImages"]!!.jsonObject["imageNeedMB"]!!.jsonObject
        for (m in LocalModelCatalog.all.filter { it.readsImages }) {
            val key = m.displayName.lowercase().replace(' ', '-')
            val android = need[key]!!.jsonObject["android"]!!.jsonObject
            assertEquals(key, android.mapValues { it.value.jsonPrimitive.int }, m.imageNeedMB.mapKeys { it.key.name.lowercase() })
        }
        val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }
        assertEquals(2500, e2b.imageNeedMB(ImagePath.NPU)); assertEquals(3700, e2b.imageNeedMB(ImagePath.GPU)); assertEquals(2100, e2b.imageNeedMB(ImagePath.CPU))
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        assertEquals("a path with no figure takes the model's largest", 5000, e4b.imageNeedMB(ImagePath.NPU))
    }

    /** The path an image turn takes (Android 646e7c7f, 18531682): an NPU bundle that reads images, else the rule's GPU, else the CPU. */
    @Test fun anImageTurnsPathIsTheNpuBundleThenTheGpuRuleThenTheCpu() {
        assertEquals(ImagePath.NPU, ImagePath.of(npuReadsImages = true, gpuReadsImages = true))
        assertEquals(ImagePath.GPU, ImagePath.of(npuReadsImages = false, gpuReadsImages = true))
        assertEquals(ImagePath.CPU, ImagePath.of(npuReadsImages = false, gpuReadsImages = false))
    }

    /** Android's ceiling (phoneImages.ceiling.android): max(totalMem − 3000, the highest availMem seen here); it only rises. */
    @Test fun androidsCeilingIsTotalLessTheReserveOrTheMostSeen() {
        assertEquals(2580, PhoneImageMemory.ceilingMB(totalMB = 5580, highestSeenMB = 0))       // Pixel 6a
        assertEquals(4558, PhoneImageMemory.ceilingMB(totalMB = 7558, highestSeenMB = 3399))    // Pixel 8a
        assertEquals(3100, PhoneImageMemory.ceilingMB(totalMB = 5580, highestSeenMB = 3100))    // once seen, the phone has had the room
        assertEquals(600, PhoneImageMemory.ceilingMB(totalMB = 3600, highestSeenMB = 0))        // a ~4 GB phone
    }

    /**
     * The design review of e793238b: whether a phone can ever fit images is asked with what the turn's gate will ask —
     * the need plus the load headroom — or a phone whose ceiling sits between the two is told "close some apps" forever.
     */
    @Test fun aPhoneFitsWhenItsCeilingHoldsTheNeedPlusTheHeadroom() {
        assertTrue(PhoneImageMemory.fits(needMB = 2500, headroomMB = 0, ceilingMB = 2580))
        assertFalse(PhoneImageMemory.fits(needMB = 2500, headroomMB = 512, ceilingMB = 2580))
        assertTrue(PhoneImageMemory.fits(needMB = 2500, headroomMB = 512, ceilingMB = 3012))
    }

    /**
     * IMAGES.md §4b: the refused row's "use <smaller model>" is the phone model ahead of it in the catalogue (smallest
     * first) that fits this phone — switched to if it's here, downloaded if not (iOS PhoneImageMemory.smallerModel).
     */
    @Test fun theSmallerModelIsTheNearestOneAheadThatFits() {
        val (e2b, e4b) = LocalModelCatalog.all.let { it[0] to it[1] }
        assertEquals(e2b, PhoneImageMemory.smallerModel(LocalModelCatalog.all, e4b) { it.id == e2b.id })
        assertEquals("nothing smaller fits", null, PhoneImageMemory.smallerModel(LocalModelCatalog.all, e4b) { false })
        assertEquals("nothing is smaller than the smallest", null, PhoneImageMemory.smallerModel(LocalModelCatalog.all, e2b) { true })
    }
}
