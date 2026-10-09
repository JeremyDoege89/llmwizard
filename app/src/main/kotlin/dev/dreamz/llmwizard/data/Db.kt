package dev.dreamz.llmwizard.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** How the app logs in to a server. Passwords are never stored (rule 3). */
enum class AuthType { PASSWORD, KEY }

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val authType: AuthType,
    val hostKeyType: String? = null,
    val hostKeyFingerprint: String? = null,
    /** Last scan as HardwareProfile JSON, so screens can be rebuilt without reconnecting. */
    val profileJson: String? = null,
    /** "none", "installing", "installed", "failed": a hint only; the manifest on the server is the truth. */
    val installState: String = "none",
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A detached step running on a server (rule 4: rebuilt from what is stored). */
@Entity(tableName = "jobs", primaryKeys = ["serverName", "recipeId", "stepId"])
data class JobEntity(val serverName: String, val recipeId: String, val stepId: String, val jobId: String, val step: Int, val startedAt: String)

/** Every script sent to a server, its exit code and output tail. Never anything sent on stdin. */
@Entity(tableName = "transcript", indices = [Index("serverName")])
data class TranscriptRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverName: String,
    val at: String,
    val kind: String,
    val script: String,
    val exitCode: Int?,
    val outputTail: String,
)

@Entity(tableName = "benchmarks", indices = [Index("serverId")])
data class BenchmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val model: String,
    val tokensPerSecond: Double,
    val loadSeconds: Double,
    val estimate: String?,
    val at: String,
)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY createdAt") fun all(): Flow<List<ServerEntity>>
    @Query("SELECT * FROM servers WHERE id = :id") fun observe(id: Long): Flow<ServerEntity?>
    @Query("SELECT * FROM servers WHERE id = :id") suspend fun get(id: Long): ServerEntity?
    @Query("SELECT * FROM servers WHERE host = :host AND port = :port LIMIT 1") fun byHostSync(host: String, port: Int): ServerEntity?
    @Insert suspend fun insert(s: ServerEntity): Long
    @Upsert suspend fun upsert(s: ServerEntity)
    @Query("UPDATE servers SET hostKeyType = :type, hostKeyFingerprint = :fp WHERE host = :host AND port = :port") fun setHostKeySync(host: String, port: Int, type: String?, fp: String?)
    @Query("DELETE FROM servers WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE serverName = :s AND recipeId = :r AND stepId = :st") fun getSync(s: String, r: String, st: String): JobEntity?
    @Query("SELECT * FROM jobs WHERE serverName = :s") fun forServer(s: String): Flow<List<JobEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSync(j: JobEntity)
    @Query("DELETE FROM jobs WHERE serverName = :s AND recipeId = :r AND stepId = :st") fun clearSync(s: String, r: String, st: String)
}

@Dao
interface TranscriptDao {
    @Insert fun insertSync(r: TranscriptRow)
    @Query("SELECT * FROM transcript WHERE serverName = :s ORDER BY id DESC LIMIT 500") fun forServer(s: String): Flow<List<TranscriptRow>>
}

@Dao
interface BenchmarkDao {
    @Insert suspend fun insert(r: BenchmarkRow)
    @Query("SELECT * FROM benchmarks WHERE serverId = :id ORDER BY id DESC") fun forServer(id: Long): Flow<List<BenchmarkRow>>
}

@Entity(tableName = "conversations", indices = [Index("serverId")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val model: String,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    /** Measured by Ollama for this reply; null for the user's messages or a stopped reply. */
    val tokensPerSecond: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE serverId = :serverId ORDER BY updatedAt DESC") fun conversations(serverId: Long): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: Long): ConversationEntity?
    @Insert suspend fun insert(c: ConversationEntity): Long
    @Upsert suspend fun upsert(c: ConversationEntity)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: Long)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteMessages(id: Long)
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id") fun messages(id: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id") suspend fun messagesOnce(id: Long): List<MessageEntity>
    @Insert suspend fun insert(m: MessageEntity): Long
}

@Database(
    entities = [ServerEntity::class, JobEntity::class, TranscriptRow::class, BenchmarkRow::class, ConversationEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDb : RoomDatabase() {
    abstract fun chat(): ChatDao
    abstract fun servers(): ServerDao
    abstract fun jobs(): JobDao
    abstract fun transcript(): TranscriptDao
    abstract fun benchmarks(): BenchmarkDao
}
