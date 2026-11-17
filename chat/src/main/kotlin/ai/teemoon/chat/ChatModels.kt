// ports: teemoon/Chat/ChatModels.swift @ v1.0.1 (SwiftData @Model → Room @Entity); AttachmentEntity: IMAGES.md §1 (iOS b2c2ea2)
package ai.teemoon.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    val timestamp: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ThreadEntity::class,
        parentColumns = ["id"],
        childColumns = ["threadId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("threadId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    /** "user" | "assistant" | "system" | "tool" */
    val role: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "0") val isE2EE: Boolean = false,
    val sourcesJson: String? = null,
    val generatingTimeMs: Long? = null,
    /**
     * How the reply's turn ended (G3, migration 2 → 3, the maintainer approved 2026-11-06): one of [MessageStatus]. Null reads as
     * complete — every row from before the column, and every user message. A turn writes INTERRUPTED at its start and
     * its final status at its end, so a row still INTERRUPTED at launch is a turn the app was killed in.
     */
    val status: String? = null,
    /**
     * The sent-before-confirmed mark (the maintainer approved 2026-11-06; iOS's field shape): null — no mark; "pending|<recordKey>"
     * — sent sealed while DCAP/NRAS/TLS were pending for that record; "failed|<recordKey>" — those checks then failed
     * hard (terminal). recordKey = "<fetchedAt epoch ms>|<gatewayNonce ?: gpuNonce, lowercase hex>". A pass clears it
     * to null; only the record named resolves it. The trust report counts a reply as E2EE only when this is null.
     */
    val sentBeforeConfirmed: String? = null,
)

/**
 * [MessageEntity.status]'s words: one vocabulary with G4's stream outcomes, agreed with iOS. [ReplyStatus] (the reply
 * machine, conformance/reply-machine.json) is the single source; these name its raw values for code that reads the
 * column. A new word is a new [ReplyStatus] case — a TEXT value, no schema change.
 */
object MessageStatus {
    val COMPLETE: String = ReplyStatus.COMPLETE.rawValue
    /** Length limit or content filter (finish_reason), or a stream that closed without [DONE] after some text. */
    val CUT_OFF: String = ReplyStatus.CUT_OFF.rawValue
    /** Written at turn start; still here with no live generation (always so at launch) = the turn did not finish. */
    val INTERRUPTED: String = ReplyStatus.INTERRUPTED.rawValue
    val STOPPED: String = ReplyStatus.STOPPED.rawValue
    val FAILED: String = ReplyStatus.FAILED.rawValue
    /** A sealed field did not decrypt; the row keeps no text (never ciphertext). */
    val DECRYPT_FAILED: String = ReplyStatus.DECRYPT_FAILED.rawValue
    /** Every word the column may hold besides null (a row from before the column, read as complete). */
    val all: Set<String> = ReplyStatus.entries.map { it.rawValue }.toSet()
}

/**
 * An image in a message (IMAGES.md §1, the maintainer approved 2026-10-25): the pixels re-encoded from a decode in the isolated
 * process — never the bytes as they arrived, so no EXIF/XMP/ICC — as a file in app-private storage ([path], relative to
 * the attachments directory), with a small JPEG [thumbnail] for the bubble. A NEW table, Room 1→2, and nothing else:
 * `threads` and `messages` are untouched. The row goes with its message (ON DELETE CASCADE); the file goes through
 * the store's post-delete sweep.
 *
 * [id]: the string `<message key>#<index>` for an import (iOS derives a v5 UUID from the same string), random for a
 * new send.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("message_id")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    /** Order within the message. */
    val idx: Int,
    /** "image/jpeg" or "image/png", after re-encoding. */
    val mime: String,
    val width: Int,
    val height: Int,
    val path: String,
    val thumbnail: ByteArray,
) {
    // A data class with a ByteArray compares by reference otherwise; rows are equal when every field is.
    override fun equals(other: Any?): Boolean = other is AttachmentEntity && id == other.id && messageId == other.messageId &&
        idx == other.idx && mime == other.mime && width == other.width && height == other.height && path == other.path &&
        thumbnail.contentEquals(other.thumbnail)
    override fun hashCode(): Int = id.hashCode()
}

/** A list-row projection: the thread and its latest assistant reply. */
data class ThreadPreview(val threadId: String, val content: String)
