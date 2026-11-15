package dev.akhil.freebuff.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 0 AND (title LIKE '%' || :query || '%' OR id IN (SELECT conversationId FROM messages WHERE content LIKE '%' || :query || '%')) ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now, modelId = COALESCE(:modelId, modelId), mode = COALESCE(:mode, mode) WHERE id = :id")
    suspend fun touch(id: String, now: Long, modelId: String? = null, mode: String? = null)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun list(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("UPDATE messages SET content = :content, status = :status, elapsedMs = :elapsedMs, tokenCount = :tokens, error = :error WHERE id = :id")
    suspend fun finish(id: String, content: String, status: String, elapsedMs: Long, tokens: Int, error: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt > :after")
    suspend fun deleteAfter(conversationId: String, after: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun count(conversationId: String): Int
}

@Dao
interface ModelDao {

    @Query("SELECT * FROM model_records")
    fun observeAll(): Flow<List<ModelRecordEntity>>

    @Query("SELECT * FROM model_records")
    suspend fun list(): List<ModelRecordEntity>

    @Query("SELECT * FROM model_records WHERE id = :id")
    suspend fun get(id: String): ModelRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ModelRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<ModelRecordEntity>)

    @Query("UPDATE model_records SET status = :status, downloadedBytes = :downloaded, installedPath = :path, installedAt = :installedAt, error = :error WHERE id = :id")
    suspend fun updateInstallState(
        id: String,
        status: String,
        downloaded: Long,
        path: String?,
        installedAt: Long,
        error: String?,
    )

    @Query("UPDATE model_records SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("DELETE FROM model_records WHERE id = :id AND custom = 1")
    suspend fun deleteCustom(id: String)
}

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE projects SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, startedAt = :startedAt WHERE id = :id")
    suspend fun markRunning(id: String, status: String, startedAt: Long)

    @Query(
        "UPDATE tasks SET status = :status, finishedAt = :finishedAt, report = :report, verification = :verification, " +
            "filesCreated = :created, filesModified = :modified, testsRun = :tests, artifactPath = :artifact, " +
            "error = :error, logs = :logs, verified = :verified WHERE id = :id",
    )
    suspend fun finish(
        id: String,
        status: String,
        finishedAt: Long,
        report: String,
        verification: String,
        created: String,
        modified: String,
        tests: String,
        artifact: String?,
        error: String?,
        logs: String,
        verified: Boolean,
    )

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks WHERE status IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun clearFinished()

    @Query("SELECT * FROM tasks WHERE status IN ('RUNNING','QUEUED')")
    suspend fun unfinished(): List<TaskEntity>
}

@Dao
interface TaskStepDao {

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY position ASC")
    fun observe(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY position ASC")
    suspend fun list(taskId: String): List<TaskStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(step: TaskStepEntity)

    @Query("UPDATE task_steps SET status = :status, detail = :detail, finishedAt = :finishedAt WHERE id = :id")
    suspend fun update(id: String, status: String, detail: String, finishedAt: Long)

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface ResearchDao {

    @Query("SELECT * FROM research_sessions ORDER BY createdAt DESC")
    fun observeSessions(): Flow<List<ResearchSessionEntity>>

    @Query("SELECT * FROM research_sessions WHERE id = :id")
    suspend fun getSession(id: String): ResearchSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ResearchSessionEntity)

    @Query("SELECT * FROM research_sources WHERE sessionId = :sessionId ORDER BY fetchedAt ASC")
    fun observeSources(sessionId: String): Flow<List<ResearchSourceEntity>>

    @Query("SELECT * FROM research_sources WHERE sessionId = :sessionId ORDER BY fetchedAt ASC")
    suspend fun sources(sessionId: String): List<ResearchSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: ResearchSourceEntity)

    @Query("UPDATE research_sources SET verified = :verified, note = :note WHERE id = :id")
    suspend fun updateSource(id: String, verified: Boolean, note: String)

    @Query("DELETE FROM research_sources WHERE sessionId = :sessionId")
    suspend fun deleteSources(sessionId: String)
}

@Dao
interface TerminalDao {

    @Query("SELECT * FROM terminal_entries WHERE sessionId = :sessionId ORDER BY startedAt DESC LIMIT 200")
    fun observe(sessionId: String): Flow<List<TerminalEntryEntity>>

    @Query("SELECT * FROM terminal_entries WHERE sessionId = :sessionId ORDER BY startedAt DESC LIMIT 200")
    suspend fun list(sessionId: String): List<TerminalEntryEntity>

    /** Newest first, capped by the caller instead of a fixed 200. */
    @Query("SELECT * FROM terminal_entries WHERE sessionId = :sessionId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(sessionId: String, limit: Int): List<TerminalEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TerminalEntryEntity)

    @Query("DELETE FROM terminal_entries WHERE sessionId = :sessionId")
    suspend fun clear(sessionId: String)
}

@Dao
interface BuildDao {

    @Query("SELECT * FROM build_records ORDER BY startedAt DESC LIMIT 100")
    fun observeAll(): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_records WHERE projectId = :projectId ORDER BY startedAt DESC")
    fun observeForProject(projectId: String): Flow<List<BuildRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BuildRecordEntity)

    @Update
    suspend fun update(record: BuildRecordEntity)

    @Delete
    suspend fun delete(record: BuildRecordEntity)

    @Query("DELETE FROM build_records WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface MaintenanceDao {
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM research_sources")
    suspend fun clearResearch()
}
