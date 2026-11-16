package dev.universalai.agent.data.repository

import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.safeCall
import dev.universalai.agent.data.model.Agent
import dev.universalai.agent.data.model.Approval
import dev.universalai.agent.data.model.Attachment
import dev.universalai.agent.data.model.ChatMessage
import dev.universalai.agent.data.model.CommandResult
import dev.universalai.agent.data.model.ConnectionResponse
import dev.universalai.agent.data.model.ConnectionTest
import dev.universalai.agent.data.model.Conversation
import dev.universalai.agent.data.model.ConversationDetail
import dev.universalai.agent.data.model.DiffResult
import dev.universalai.agent.data.model.Doc
import dev.universalai.agent.data.model.FileEntry
import dev.universalai.agent.data.model.Profile
import dev.universalai.agent.data.model.Project
import dev.universalai.agent.data.model.Provider
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.data.model.ResearchResult
import dev.universalai.agent.data.model.Run
import dev.universalai.agent.data.model.RunDetail
import dev.universalai.agent.data.model.RunEvent
import dev.universalai.agent.data.model.RuntimeConnection
import dev.universalai.agent.data.model.SearchHit
import dev.universalai.agent.data.model.ToolInfo
import dev.universalai.agent.data.model.Usage
import dev.universalai.agent.data.remote.AccountApi
import dev.universalai.agent.data.remote.AgentsApi
import dev.universalai.agent.data.remote.ChatApi
import dev.universalai.agent.data.remote.ChatStreamEvent
import dev.universalai.agent.data.remote.CustomModelInput
import dev.universalai.agent.data.remote.FileContent
import dev.universalai.agent.data.remote.KnowledgeApi
import dev.universalai.agent.data.remote.ModelsApi
import dev.universalai.agent.data.remote.ProjectsApi
import dev.universalai.agent.data.remote.RuntimeApi
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val api: ChatApi) {
    suspend fun conversations(): AppResult<List<Conversation>> = safeCall { api.list() }
    suspend fun conversation(id: String): AppResult<ConversationDetail> = safeCall { api.detail(id) }
    suspend fun messages(id: String): AppResult<List<ChatMessage>> = safeCall { api.messages(id) }
    suspend fun create(mode: String, projectId: String? = null): AppResult<Conversation> =
        safeCall { api.create(mode, projectId = projectId) }
    suspend fun rename(id: String, title: String): AppResult<Unit> = safeCall { api.rename(id, title) }
    suspend fun setMode(id: String, mode: String): AppResult<Unit> = safeCall { api.setMode(id, mode) }
    suspend fun delete(id: String): AppResult<Unit> = safeCall { api.delete(id) }

    fun stream(
        conversationId: String,
        content: String,
        mode: String,
        modelId: String?,
        reasoningEffort: String?,
        attachments: List<Attachment> = emptyList(),
    ): Flow<ChatStreamEvent> =
        api.stream(conversationId, content, mode, modelId, reasoningEffort, attachments)

    fun regenerate(conversationId: String): Flow<ChatStreamEvent> = api.regenerate(conversationId)
    fun edit(conversationId: String, messageId: String, content: String, mode: String?): Flow<ChatStreamEvent> =
        api.edit(conversationId, messageId, content, mode)
}

class ModelRepository(private val api: ModelsApi) {
    suspend fun providers(): AppResult<List<Provider>> = safeCall { api.providers() }
    suspend fun models(): AppResult<List<ProviderModel>> = safeCall { api.models() }
    suspend fun createCustom(
        name: String,
        baseUrl: String,
        apiCompat: String,
        privacyTier: String,
        apiKey: String?,
        models: List<CustomModelInput>,
    ): AppResult<Provider> = safeCall {
        api.createCustomProvider(name, baseUrl, apiCompat, privacyTier, apiKey, models)
    }
    suspend fun setEnabled(id: String, enabled: Boolean): AppResult<Provider> =
        safeCall { api.updateProvider(id, enabled = enabled) }
    suspend fun delete(id: String): AppResult<Unit> = safeCall { api.deleteProvider(id) }
    suspend fun setKey(id: String, key: String): AppResult<Unit> = safeCall { api.setCredentials(id, key) }
    suspend fun clearKey(id: String): AppResult<Unit> = safeCall { api.deleteCredentials(id) }
    suspend fun test(id: String): AppResult<ConnectionTest> = safeCall { api.testConnection(id) }
    suspend fun sync(id: String): AppResult<Int> = safeCall {
        val result = api.syncModels(id)
        result["total"]?.toString()?.toIntOrNull() ?: 0
    }
    suspend fun setDefault(modelId: String): AppResult<ProviderModel> =
        safeCall { api.setDefaultModel(modelId) }
    suspend fun updateModel(
        modelId: String,
        supportsTools: Boolean? = null,
        supportsVision: Boolean? = null,
        supportsReasoning: Boolean? = null,
    ): AppResult<ProviderModel> = safeCall {
        api.updateModel(modelId, supportsTools, supportsVision, supportsReasoning)
    }
}

class AgentRepository(private val api: AgentsApi) {
    suspend fun agents(): AppResult<List<Agent>> = safeCall { api.agents() }
    suspend fun templates(): AppResult<List<Agent>> = safeCall { api.templates() }
    suspend fun create(
        name: String,
        kind: String,
        instructions: String,
        modelId: String?,
        runtime: String,
        permissionProfile: String,
        memoryScope: String,
        tools: List<String>,
        projectId: String?,
        templateName: String? = null,
    ): AppResult<Agent> = safeCall {
        api.createAgent(
            name, kind, instructions, modelId, runtime, permissionProfile,
            memoryScope, tools, projectId, templateName,
        )
    }
    suspend fun delete(id: String): AppResult<Unit> = safeCall { api.deleteAgent(id) }
    suspend fun tools(): AppResult<List<ToolInfo>> = safeCall { api.tools() }
    suspend fun runs(): AppResult<List<Run>> = safeCall { api.runs() }
    suspend fun run(id: String): AppResult<RunDetail> = safeCall { api.run(id) }
    suspend fun start(
        objective: String,
        agentId: String?,
        projectId: String?,
        runtime: String?,
        modelId: String?,
        permissionProfile: String?,
        tools: List<String>?,
    ): AppResult<Run> = safeCall {
        api.createRun(objective, agentId, projectId, runtime, modelId, permissionProfile, tools)
    }
    suspend fun cancel(id: String): AppResult<Run> = safeCall { api.cancelRun(id) }
    suspend fun approvals(): AppResult<List<Approval>> = safeCall { api.approvals() }
    suspend fun decide(id: String, approved: Boolean): AppResult<Approval> =
        safeCall { api.decideApproval(id, approved) }
    fun events(runId: String, after: Int = 0): Flow<RunEvent> = api.events(runId, after)
}

class ProjectRepository(private val api: ProjectsApi) {
    suspend fun list(): AppResult<List<Project>> = safeCall { api.list() }
    suspend fun create(name: String, kind: String): AppResult<Project> = safeCall { api.create(name, kind) }
    suspend fun setReadOnly(id: String, readOnly: Boolean): AppResult<Project> =
        safeCall { api.update(id, readOnly) }
    suspend fun delete(id: String): AppResult<Unit> = safeCall { api.delete(id) }
    suspend fun importGithub(repoUrl: String): AppResult<Project> = safeCall { api.importGithub(repoUrl) }
    suspend fun importZip(name: String, fileName: String, bytes: ByteArray): AppResult<Project> =
        safeCall { api.importZip(name, fileName, bytes) }
    suspend fun files(id: String, path: String = "."): AppResult<List<FileEntry>> =
        safeCall { api.files(id, path) }
    suspend fun readFile(id: String, path: String): AppResult<FileContent> =
        safeCall { api.readFile(id, path) }
    suspend fun writeFile(id: String, path: String, content: String): AppResult<Unit> =
        safeCall { api.writeFile(id, path, content); Unit }
    suspend fun search(id: String, query: String): AppResult<List<SearchHit>> =
        safeCall { api.search(id, query) }
    suspend fun gitStatus(id: String): AppResult<Pair<Boolean, String>> = safeCall { api.gitStatus(id) }
    suspend fun gitDiff(id: String): AppResult<DiffResult> = safeCall { api.gitDiff(id) }
    suspend fun runCommand(id: String, command: String): AppResult<CommandResult> =
        safeCall { api.runCommand(id, command) }
}

class KnowledgeRepository(private val api: KnowledgeApi) {
    suspend fun documents(query: String? = null, projectId: String? = null): AppResult<List<Doc>> =
        safeCall { api.documents(query, projectId) }
    suspend fun document(id: String): AppResult<Doc> = safeCall { api.document(id) }
    suspend fun create(title: String, content: String, projectId: String? = null): AppResult<Doc> =
        safeCall { api.create(title, content, projectId = projectId) }
    suspend fun update(id: String, title: String?, content: String?): AppResult<Doc> =
        safeCall { api.update(id, title, content) }
    suspend fun delete(id: String): AppResult<Unit> = safeCall { api.delete(id) }
    suspend fun summarise(id: String): AppResult<String> = safeCall { api.summarise(id) }
    suspend fun research(query: String, save: Boolean): AppResult<ResearchResult> =
        safeCall { api.research(query, save = save) }
}

class RuntimeRepository(
    private val runtimeApi: RuntimeApi,
    private val accountApi: AccountApi,
) {
    suspend fun connections(): AppResult<List<RuntimeConnection>> = safeCall { runtimeApi.connections() }
    suspend fun connect(url: String, token: String?): AppResult<ConnectionResponse> =
        safeCall { runtimeApi.connect(url, token) }
    suspend fun test(id: String): AppResult<ConnectionTest> = safeCall { runtimeApi.test(id) }
    suspend fun disconnect(id: String): AppResult<Unit> = safeCall { runtimeApi.disconnect(id) }
    suspend fun profile(): AppResult<Profile> = safeCall { accountApi.me() }
    suspend fun usage(): AppResult<Usage> = safeCall { accountApi.usage() }
}
