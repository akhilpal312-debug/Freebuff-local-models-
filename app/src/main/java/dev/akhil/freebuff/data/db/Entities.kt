package dev.akhil.freebuff.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

enum class MessageStatus { COMPLETE, STREAMING, STOPPED, ERROR }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val mode: String?,
    val createdAt: Long,
    val status: String,
    val attachmentNames: String? = null,
    val tokenCount: Int = 0,
    val elapsedMs: Long = 0L,
    val error: String? = null,
    /** True when the answer came from a remote provider (never mixed with local output silently). */
    val fromRemote: Boolean = false,
)

@Entity(tableName = "model_records")
data class ModelRecordEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val kind: String,
    val repo: String?,
    val fileName: String,
    val url: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val quantization: String,
    val architecture: String,
    val role: String,
    val status: String,
    val downloadedBytes: Long = 0,
    val installedPath: String? = null,
    val installedAt: Long = 0,
    val custom: Boolean = false,
    val notes: String? = null,
    val error: String? = null,
)

enum class ProjectType { HTML, PYTHON, KOTLIN_ANDROID, COMPOSE_ANDROID, REACT, EMPTY }

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val directory: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long = 0,
    val buildReady: Boolean = false,
)

enum class TaskStatus { QUEUED, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

@Entity(tableName = "tasks", indices = [Index("status"), Index("projectId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val request: String,
    val mode: String,
    val modelId: String,
    val projectId: String?,
    val status: String,
    val createdAt: Long,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val report: String = "",
    val verification: String = "",
    val filesCreated: String = "",
    val filesModified: String = "",
    val testsRun: String = "",
    val artifactPath: String? = null,
    val error: String? = null,
    val logs: String = "",
    val verified: Boolean = false,
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class TaskStepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val position: Int,
    val title: String,
    val detail: String = "",
    val status: String,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)

@Entity(tableName = "research_sessions", indices = [Index("createdAt")])
data class ResearchSessionEntity(
    @PrimaryKey val id: String,
    val question: String,
    val plan: String,
    val report: String,
    val status: String,
    val createdAt: Long,
    val finishedAt: Long = 0,
    val offline: Boolean = false,
)

@Entity(tableName = "research_sources", indices = [Index("sessionId")])
data class ResearchSourceEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val provider: String,
    val fetchedAt: Long,
    val verified: Boolean = false,
    val note: String = "",
)

@Entity(tableName = "terminal_entries", indices = [Index("sessionId")])
data class TerminalEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val cwd: String,
    val startedAt: Long,
    val finishedAt: Long,
)

@Entity(tableName = "build_records", indices = [Index("projectId")])
data class BuildRecordEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val variant: String,
    val status: String,
    val command: String,
    val logPath: String,
    val artifactPath: String?,
    val artifactBytes: Long = 0,
    val verified: Boolean = false,
    val startedAt: Long,
    val finishedAt: Long = 0,
)
