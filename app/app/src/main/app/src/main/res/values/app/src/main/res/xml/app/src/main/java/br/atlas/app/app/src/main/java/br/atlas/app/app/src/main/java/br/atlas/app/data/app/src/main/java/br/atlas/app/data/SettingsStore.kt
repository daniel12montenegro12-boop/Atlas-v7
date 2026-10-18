package br.atlas.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    val createdAt: Long,
)

@Dao
interface AtlasDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    fun observeMessages(id: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    suspend fun messagesOnce(id: Long): List<MessageEntity>

    @Query("SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversationId(): Long?

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countConversations(): Int

    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Insert suspend fun insertMessage(m: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AtlasDatabase : RoomDatabase() {
    abstract fun dao(): AtlasDao
}
