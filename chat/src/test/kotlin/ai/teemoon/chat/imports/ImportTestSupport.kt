package ai.teemoon.chat.imports

import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** The store, as lists. [insertChat] is all or nothing, as Room's transaction is; [failOnInsert] interrupts an import. */
class ListImportStore : ImportStore {
    val threads = LinkedHashMap<String, ThreadEntity>()
    val messages = LinkedHashMap<String, MessageEntity>()
    var inserts = 0
    var failOnInsert: Int? = null

    override suspend fun hasThread(id: String) = id in threads
    override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>) {
        inserts++
        if (inserts == failOnInsert) throw IllegalStateException("killed mid-import")
        check(thread.id !in threads) { "thread ${thread.id} written twice" }
        threads[thread.id] = thread
        for (m in messages) { check(m.id !in this.messages) { "message ${m.id} written twice" }; this.messages[m.id] = m }
    }

    val attachments = LinkedHashMap<String, ai.teemoon.chat.AttachmentEntity>()
    override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>, attachments: List<ai.teemoon.chat.AttachmentEntity>, files: List<IngestedImage>) {
        insertChat(thread, messages)
        for (a in attachments) { check(a.id !in this.attachments); check(a.messageId in this.messages); this.attachments[a.id] = a }
    }

    fun delete(threadId: String) { threads.remove(threadId); messages.values.removeAll { it.threadId == threadId }; attachments.values.removeAll { it.messageId !in messages } }
    fun transcript(threadId: String) = messages.values.filter { it.threadId == threadId }.sortedBy { it.timestamp }
    fun snapshot() = threads.toMap() to messages.toMap()
}

object ImportTestSupport {
    fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/import/near/$name")!!.readBytes()
    val full: ByteArray by lazy { resource("near-export-v1.json") }
    val single: ByteArray by lazy { resource("near-chat-export-v1.json") }

    fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A stand-in for GroundingSource.encodedJSON with the same field names; the app test checks the real one. */
    val encode: (List<StoredSource>) -> String? = { list ->
        if (list.isEmpty()) null
        else JsonArray(list.map { JsonObject(mapOf("url" to JsonPrimitive(it.url), "domain" to JsonPrimitive(it.domain), "title" to JsonPrimitive(it.title), "snippet" to JsonPrimitive(""))) }).toString()
    }
    fun decode(json: String?): List<StoredSource> = json?.let { Json.parseToJsonElement(it) as JsonArray }.orEmpty().map {
        val o = it.jsonObject
        assert(o["snippet"]!!.jsonPrimitive.content == "")
        StoredSource(o["url"]!!.jsonPrimitive.content, o["domain"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content)
    }

    suspend fun import(bytes: ByteArray, store: ListImportStore): ImportSummary =
        ChatImport.run(ByteArrayInputStream(bytes), ChatImportWriter(store, encode))

    fun summary(chats: Int, messages: Int, here: Int, skipped: Int, incomplete: Boolean = false) =
        ImportSummary(chats, messages, here, skipped, incomplete)

    fun parse(bytes: ByteArray): JsonArray = Json.parseToJsonElement(bytes.decodeToString()) as JsonArray
    fun compact(e: Any): ByteArray = e.toString().toByteArray()
}
