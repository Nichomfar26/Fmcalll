package com.fmcall.serval.data

import androidx.room.*
import com.fmcall.serval.data.model.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────

@Dao
interface MeshNodeDao {
    @Query("SELECT * FROM mesh_nodes ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<MeshNode>>

    @Query("SELECT * FROM mesh_nodes WHERE meshId = :meshId LIMIT 1")
    suspend fun getByMeshId(meshId: String): MeshNode?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: MeshNode)

    @Query("DELETE FROM mesh_nodes WHERE lastSeen < :cutoff")
    suspend fun pruneStale(cutoff: Long)

    @Query("SELECT COUNT(*) FROM mesh_nodes WHERE lastSeen > :since")
    suspend fun activeCount(since: Long): Int
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE meshId = :meshId LIMIT 1")
    suspend fun getByMeshId(meshId: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun observeConversation(convId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Query("UPDATE messages SET status = :status WHERE messageId = :id")
    suspend fun updateStatus(id: String, status: MessageStatus)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId AND status != 'READ' AND isOutgoing = 0")
    suspend fun unreadCount(convId: String): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTime DESC")
    fun observeAll(): Flow<List<Conversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :id")
    suspend fun markRead(id: String)
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY startTime DESC LIMIT 100")
    fun observeRecent(): Flow<List<CallLog>>

    @Insert
    suspend fun insert(log: CallLog)

    @Query("DELETE FROM call_logs WHERE startTime < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}

// ─────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────

@Database(
    entities = [MeshNode::class, Contact::class, Message::class, Conversation::class, CallLog::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FMcallDatabase : RoomDatabase() {
    abstract fun meshNodeDao(): MeshNodeDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun callLogDao(): CallLogDao
}

class Converters {
    @TypeConverter fun fromTransport(t: TransportType) = t.name
    @TypeConverter fun toTransport(s: String) = TransportType.valueOf(s)

    @TypeConverter fun fromMsgType(t: MessageType) = t.name
    @TypeConverter fun toMsgType(s: String) = MessageType.valueOf(s)

    @TypeConverter fun fromMsgStatus(t: MessageStatus) = t.name
    @TypeConverter fun toMsgStatus(s: String) = MessageStatus.valueOf(s)

    @TypeConverter fun fromCallDir(t: CallDirection) = t.name
    @TypeConverter fun toCallDir(s: String) = CallDirection.valueOf(s)

    @TypeConverter fun fromCallStatus(t: CallStatus) = t.name
    @TypeConverter fun toCallStatus(s: String) = CallStatus.valueOf(s)
}
