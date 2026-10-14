package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.net.URI

/** The vendored conformance classes, as the tests read them. Never edited here. */
object KitFixtures {
    private fun doc(name: String): JsonObject = Json.parseToJsonElement(KitFixtures::class.java.getResourceAsStream("/$name.json")!!.reader().readText()).jsonObject["vectors"]!!.jsonObject
    fun JsonObject.s(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    val quotes: JsonObject by lazy { doc("quotes") }
    val collateral: JsonObject by lazy { doc("collateral") }

    val realQuote: JsonObject get() = quotes["quotes"]!!.jsonArray[0].jsonObject
    val quoteNegatives: List<JsonObject> get() = quotes["negatives"]!!.jsonArray.map { it.jsonObject }
    val quoteHex: String get() = realQuote.s("quoteHex")!!
    val nowSecs: Long get() = realQuote["nowSecs"]!!.jsonPrimitive.long
    val measurements: JsonObject get() = realQuote["measurements"]!!.jsonObject

    /** The captured PCS responses by exact URL, as a stub client. */
    class StubHttp(private val tamper: Set<String> = emptySet()) : CollateralHttpClient {
        var count = 0
        val responses: Map<String, Pair<ByteArray, Map<String, String>>> = collateral["responses"]!!.jsonArray.map { it.jsonObject }.associate { r ->
            r.s("url")!! to (Base64.getDecoder().decode(r.s("bodyB64")!!) to r["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        }
        override suspend fun get(url: String): Pair<ByteArray, Map<String, String>> {
            count++
            val (body, headers) = responses[url] ?: throw CollateralHttpException(404, URI(url).host, URI(url).path)
            if (url in tamper && body.isNotEmpty()) { val t = body.copyOf(); t[t.size / 2] = (t[t.size / 2].toInt() xor 0x01).toByte(); return t to headers }
            return body to headers
        }
    }
    val assembledFields: Map<String, String> get() = collateral["assembledCollateralFields"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
}
