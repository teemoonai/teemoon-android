package ai.teemoon.app

// android-only for now: the iOS runner will read the same values once the shared benchmark spec lives in teemoon-ios
// (vendored here like the conformance kit). Until then this object IS the spec, and every number the README may
// quote comes from a run that read it: NpuBundleProbeTest, OnDeviceDecodeBenchTest.rawNpuDecode / rawPrefillCompare.

/**
 * The one definition of a reported on-device benchmark, so every chip is measured the same way (the user, 2026-10-14:
 * the published numbers must be apples to apples). Raw runtime, not the app turn: no system prompt, no tools.
 */
object BenchSpec {
    /** Decode: a short prompt (~18 tokens) and enough output to measure steady decode. */
    const val SHORT_PROMPT = "Write a short paragraph about the planet Jupiter."
    const val SHORT_MAX_OUTPUT = 128

    /** Prefill: ~1,900 tokens of fixed text, and a little output for time-to-first-token. */
    private const val PARAGRAPH = "The verifier checks the quote against the collateral, binds the measurement to the model that was loaded, and records the outcome in the ledger so a later reader can audit the claim without trusting the operator. "
    val LONG_PROMPT = "Summarise the following in one sentence.\n\n" + PARAGRAPH.repeat(48)
    /** ~1,000 tokens in, as many out as the model will give up to 256: the card's shape through the app's own engine path. */
    val GOOGLE_SIZED_PROMPT = "Read the following, then retell it at length in your own words.\n\n" + PARAGRAPH.repeat(24)
    /** ~1,000 tokens of varied prose (an original text, not a repeated sentence: repetition flatters speculative decoding). */
    val ARTICLE = """Lighthouses began as fires. Long before glass lenses or electric lamps, sailors approaching a coast at night looked for flames kept burning on headlands and harbour walls, and the keepers of those fires were often paid by the towns whose trade depended on ships arriving safely. The most famous early example stood at Alexandria, on the island of Pharos, and it gave its name to the word for lighthouse in several languages. Ancient writers described it as a tower of three stages, with a fire at the top whose light could be seen far out to sea, although the distances they gave were probably exaggerated. For most of history, though, coastal lights were modest: a brazier on a stone platform, a lantern in a church tower, or a bonfire that a family agreed to tend in exchange for a small fee from passing merchants.

The problem with an open fire is that most of its light goes in directions nobody needs. It shines up into the sky and down onto the rocks, and the part that reaches the horizon is weak and flickering. Coal fires also smoked heavily, and in rain or strong wind they could go out altogether, which was exactly when ships most needed them. In the eighteenth century, oil lamps with reflectors began to replace open fires on the busiest coasts. A polished metal bowl behind the flame gathered some of the scattered light and sent it outward in a beam, and keepers learned to trim wicks and clean reflectors every few hours through the night.

The real change came in the 1820s, when the French physicist Augustin Fresnel designed a lens built from concentric rings of glass prisms. A conventional lens large enough to focus a lighthouse lamp would have been impossibly thick and heavy, but Fresnel's design captured the same light with a fraction of the glass. The rings bent rays that would otherwise have escaped upward or downward and sent them out horizontally, so that a single lamp could be seen many miles farther than before. Fresnel lenses were graded by size into orders, from the enormous first-order lenses used on major seacoast stations to small sixth-order lenses for harbours and rivers. Within a few decades they had spread to lighthouses around the world, and many of the original lenses remained in service for more than a century.

Seeing a light is only half of navigation; a sailor also has to know which light it is. If every tower showed the same steady glow, a captain could easily mistake one headland for another and steer onto the wrong rocks. Lighthouse authorities therefore gave each station a characteristic: a particular pattern of flashes, eclipses and colours that appeared in published lists and on charts. One light might flash white every five seconds, another might show two red flashes followed by a long dark interval, and a third might stay fixed but change colour depending on the direction from which it was seen. Rotating lens assemblies, sometimes floating on a bath of mercury so that a clockwork mechanism could turn several tons of glass smoothly, made these patterns possible.

Life for the keepers was demanding and often lonely. On remote rock stations, several men might share a narrow tower for weeks at a time, separated from their families and dependent on supply boats that could not land in rough weather. Their duties were strict: lamps had to be lit at sunset and extinguished at sunrise, lenses polished, fuel carried up long spiral stairs, and logs kept of the weather and every passing vessel. In fog, when the light could not be seen, keepers sounded bells, horns or sirens, sometimes for days without pause. Some stations had dwellings on the mainland where families lived, and children grew up learning to trim wicks and read the barometer.

Electricity, radio and eventually satellite navigation transformed the work. Electric lamps were brighter and more reliable than oil, and automatic switches could turn them on at dusk without anyone climbing the tower. Radio beacons let ships take bearings in fog, and later systems gave positions accurate to a few metres anywhere at sea. Through the second half of the twentieth century, lighthouse services automated their stations one by one, and the last resident keepers in many countries left their towers in the 1980s and 1990s. Today most lights are monitored remotely, serviced by technicians who visit a few times a year, and powered in many places by solar panels and batteries.

Even so, lighthouses have not disappeared. Satellite receivers can fail, electronic charts can be wrong, and a visible light remains a simple, independent check that a navigator can trust. Many old towers have also found new lives as museums, guest houses and landmarks, cared for by local societies who restore their lenses and tell the stories of the keepers who once lived there."""
    /** A short original Python module for the "code" task. */
    val CODE = """import csv
from dataclasses import dataclass
from datetime import date


@dataclass
class Reading:
    station: str
    day: date
    low: float
    high: float
    rain_mm: float


def parse_row(row):
    station, day, low, high, rain = row
    return Reading(station, date.fromisoformat(day), float(low), float(high), float(rain))


def load(path):
    with open(path, newline="") as f:
        reader = csv.reader(f)
        next(reader)
        return [parse_row(r) for r in reader if r]


def by_station(readings):
    out = {}
    for r in readings:
        out.setdefault(r.station, []).append(r)
    return out


def mean(values):
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def summary(readings):
    rows = []
    for station, rs in sorted(by_station(readings).items()):
        rows.append((station, len(rs), mean(r.low for r in rs), mean(r.high for r in rs), sum(r.rain_mm for r in rs)))
    return rows


def wettest_day(readings):
    return max(readings, key=lambda r: r.rain_mm, default=None)


def warm_spells(readings, threshold, days):
    spells, current = [], []
    for r in sorted(readings, key=lambda r: r.day):
        if r.high >= threshold:
            current.append(r)
        else:
            if len(current) >= days:
                spells.append(current)
            current = []
    if len(current) >= days:
        spells.append(current)
    return spells


def report(path, threshold=25.0, days=3):
    readings = load(path)
    lines = ["station  days  mean low  mean high  rain"]
    for station, n, lo, hi, rain in summary(readings):
        lines.append(f"{station:8} {n:5} {lo:9.1f} {hi:10.1f} {rain:5.1f}")
    wet = wettest_day(readings)
    if wet:
        lines.append(f"wettest: {wet.station} on {wet.day} ({wet.rain_mm} mm)")
    for spell in warm_spells(readings, threshold, days):
        lines.append(f"warm spell: {spell[0].day} to {spell[-1].day}")
    return "\n".join(lines)
"""
    /** The named tasks the speculative on/off comparison runs: long-form writing, a summary (Google's rows: free form vs summarize), and the short-prompt best case. */
    val TASKS = mapOf(
        "retell" to "Read the following article, then retell it at length in your own words.\n\n$ARTICLE",
        "summarize" to "Summarize the following article in about 200 words.\n\n$ARTICLE",
        // The prompt behind the README's "up to 70 tok/s" (S26 Ultra GPU, 2026-09-16: 26 → 69 through the app, one run):
        // ~18 tokens in and familiar facts out — the best case for a drafter, kept as its own named task so it is never
        // quoted as typical.
        "jupiter" to SHORT_PROMPT,
        // Google's two other high rows (S26 Ultra GPU, speculative: tone rewrite 87.4, code 84.4): output that reuses the input.
        "rewrite" to "Rewrite the following article in a formal, academic tone. Keep every fact and the paragraph structure.\n\n$ARTICLE",
        // Proofreading (S26 Ultra GPU, the request debug card's tok/s): the reply is the input with eight typos fixed, the
        // best case for a drafter — a real task, quoted only by name.
        "proofread" to "Fix the spelling and grammar mistakes in the following text and return the corrected text in full, with nothing else.\n\nLighthouses began as fires. Long before glass lenses or electrc lamps, sailors approaching a coast at night looked for flames kept burning on headlands and harbour walls, and the keepers of those fires were often payed by the towns whose trade depended on ships arriving safely. The most famous early example stood at Alexandria, on the island of Pharos, and it gave its name to the word for lighthouse in several langauges. Ancient writers described it as a tower of three stages, with a fire at the top whose light could be seen far out to sea, although the distances they gave were probaly exaggerated.\n\nThe problem with an open fire is that most of it's light goes in directions nobody needs. It shines up into the sky and down onto the rocks, and the part that reaches the horizon is weak and flickering. Coal fires also smoked heavily, and in rain or strong wind they could go out altogether, which was exactly when ships most needed them. In the eighteenth century, oil lamps with reflecters began to replace open fires on the busiest coasts. A polished metal bowl behind the flame gathered some of the scattered light and sent it outward in a beam, and keepers learned to trim wicks and clean reflectors every few hours through the night.\n\nThe real change came in the 1820s, when the French physicist Augustin Fresnel designed a lens built from concentric rings of glass prisms. A conventional lens large enough to focus a lighthouse lamp would of been impossibly thick and heavy, but Fresnel's design captured the same light with a fraction of the glass. The rings bent rays that would otherwise have escaped upward or downward and sent them out horizontaly, so that a single lamp could be seen many miles farther than before.",
        "code" to "Add type hints and a one-line docstring to every function in this Python module. Return the whole module.\n\n$CODE",
    )
    const val LONG_MAX_OUTPUT = 32

    /** The context the engine is built with: 4096, the NPU bundles' compiled ceiling, on every backend. */
    const val MAX_TOKENS = 4096

    /**
     * GOOGLE'S METHOD, for comparing against the model card (huggingface.co/litert-community/gemma-4-E2B-it-litert-lm:
     * "1024 prefill tokens and 256 decode tokens with a context length of 2048 tokens"). Measured with the runtime's
     * own `benchmark()`, whose counters are the engine's, not ours — see GoogleMethodBenchTest. A number from it is
     * labelled "google method"; a number from the constants above is "teemoon bench"; the two are never mixed.
     */
    const val GOOGLE_PREFILL_TOKENS = 1024
    const val GOOGLE_DECODE_TOKENS = 256

    /** Every reported figure is the median of this many runs, after one discarded warm-up, backends interleaved. */
    const val REPEATS = 3

    /** Thinking off, the runtime's default sampler, and speculative decoding set EXPLICITLY: never the runtime's default. */
    const val SPECULATIVE = false
}
