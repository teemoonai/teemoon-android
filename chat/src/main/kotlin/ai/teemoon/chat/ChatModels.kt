// ports: teemoon/Chat/ChatModels.swift @ v1.0.1 (SwiftData @Model → Room @Entity)
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
)

/** A list-row projection: the thread and its latest assistant reply. */
data class ThreadPreview(val threadId: String, val content: String)
