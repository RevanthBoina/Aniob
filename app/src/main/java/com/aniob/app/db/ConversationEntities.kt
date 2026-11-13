package com.aniob.app.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aniob.core.external.ChatTurn
import kotlinx.coroutines.flow.Flow

/** One chat thread. Its messages live in [ChatMessageEntity] and are deleted with it. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'unknown'") val status: String = "running",
    @androidx.room.ColumnInfo(defaultValue = "''") val stopReason: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val lastTaskId: String = ""
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    val provider: String?,
    val timestamp: Long
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun find(id: String): ConversationEntity?

    /** Oldest first; rowid breaks ties between messages written in the same millisecond. */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :cid ORDER BY timestamp ASC, rowid ASC")
    suspend fun messages(cid: String): List<ChatMessageEntity>

    /** Update-or-insert. Never REPLACE: replacing the parent row would cascade-delete its messages. */
    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Query("UPDATE conversations SET status = :status, stopReason = :reason, updatedAt = :time WHERE id = :id")
    suspend fun setStatus(id: String, status: String, reason: String, time: Long)

    @Query("UPDATE conversations SET status = :status, stopReason = :reason, updatedAt = :time WHERE id = :id AND lastTaskId = :taskId AND status = 'running'")
    suspend fun finishTask(id: String, taskId: String, status: String, reason: String, time: Long)

    /** Saves one question and its answer together, creating the conversation on first use. */
    @Transaction
    suspend fun appendExchange(
        conversation: ConversationEntity,
        user: ChatMessageEntity,
        assistant: ChatMessageEntity
    ) {
        upsertConversation(conversation)
        insertMessage(user)
        insertMessage(assistant)
    }
}

/**
 * Conversation memory for the answer path: what the model is told about earlier turns, and what
 * the previous-chats list shows. Only answered question/answer pairs are stored, so a failed
 * request never leaves a dangling user turn in the history sent to the model.
 */
class ConversationStore(
    private val dao: ConversationDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    fun observeAll(): Flow<List<ConversationEntity>> = dao.observeAll()
    suspend fun all(): List<ConversationEntity> = dao.all()
    suspend fun find(id: String): ConversationEntity? = dao.find(id)
    suspend fun create(id: String, title: String = "New chat") {
        if (dao.find(id) == null) dao.upsertConversation(ConversationEntity(id, title, now(), now()))
    }
    suspend fun close(id: String, reason: String) = dao.setStatus(id, "stopped", reason, now())
    suspend fun beginTask(id: String, taskId: String, prompt: String) {
        val existing = dao.find(id) ?: ConversationEntity(id, titleFor(prompt), now(), now())
        dao.upsertConversation(existing.copy(status = "running", stopReason = "", lastTaskId = taskId, updatedAt = now(),
            title = if (existing.title == "New chat") titleFor(prompt) else existing.title))
    }
    suspend fun finishTask(id: String, taskId: String, outcome: String, reason: String) = dao.finishTask(
        id, taskId, when (outcome) {
            "ANSWERED", "VERIFIED", "LAUNCH_ACCEPTED" -> "completed"
            "STOPPED", "INTERRUPTED" -> "stopped"
            "WAITING", "NEEDS_CHOICE", "NEEDS_CLARIFICATION", "DELEGATED" -> "waiting"
            else -> "errored"
        }, reason, now())

    suspend fun messages(conversationId: String): List<ChatMessageEntity> = dao.messages(conversationId)

    /** The newest [maxTurns] stored turns, oldest first. Does not include the message being asked now. */
    suspend fun historyForLlm(conversationId: String, maxTurns: Int = ChatTurn.MAX_TURNS): List<ChatTurn> =
        dao.messages(conversationId).takeLast(maxTurns).map { ChatTurn(it.role, it.content) }

    suspend fun appendExchange(conversationId: String, userText: String, answer: String, provider: String?) {
        val time = now()
        val existing = dao.find(conversationId)
        val conversation = existing?.copy(updatedAt = time)
            ?: ConversationEntity(conversationId, titleFor(userText), createdAt = time, updatedAt = time)
        dao.appendExchange(
            conversation,
            ChatMessageEntity(newId(), conversationId, ChatTurn.USER, userText, null, time),
            ChatMessageEntity(newId(), conversationId, ChatTurn.ASSISTANT, answer, provider, time)
        )
    }

    suspend fun delete(conversationId: String) = dao.deleteConversation(conversationId)

    companion object {
        const val TITLE_MAX_CHARS = 60

        /** First line of the first question, shortened for the list. */
        fun titleFor(firstUserText: String): String {
            val line = firstUserText.trim().lineSequence().firstOrNull().orEmpty().trim()
            return when {
                line.isEmpty() -> "New chat"
                line.length <= TITLE_MAX_CHARS -> line
                else -> line.take(TITLE_MAX_CHARS - 1).trimEnd() + "…"
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversations (" +
                        "id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, pinned INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_messages (" +
                        "id TEXT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, provider TEXT, timestamp INTEGER NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(conversationId) REFERENCES conversations(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_conversationId " +
                        "ON chat_messages (conversationId)"
                )
            }
        }
    }
}
