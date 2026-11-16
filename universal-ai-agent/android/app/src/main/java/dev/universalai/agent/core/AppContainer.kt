package dev.universalai.agent.core

import android.content.Context
import dev.universalai.agent.data.local.SessionStore
import dev.universalai.agent.data.remote.AccountApi
import dev.universalai.agent.data.remote.AgentsApi
import dev.universalai.agent.data.remote.ApiClient
import dev.universalai.agent.data.remote.ChatApi
import dev.universalai.agent.data.remote.KnowledgeApi
import dev.universalai.agent.data.remote.ModelsApi
import dev.universalai.agent.data.remote.ProjectsApi
import dev.universalai.agent.data.remote.RuntimeApi
import dev.universalai.agent.data.repository.AgentRepository
import dev.universalai.agent.data.repository.ChatRepository
import dev.universalai.agent.data.repository.KnowledgeRepository
import dev.universalai.agent.data.repository.ModelRepository
import dev.universalai.agent.data.repository.ProjectRepository
import dev.universalai.agent.data.repository.RuntimeRepository

/**
 * Manual dependency injection graph. One container per process, created by
 * [dev.universalai.agent.UniversalAgentApp].
 */
class AppContainer(context: Context) {

    val store = SessionStore(context)

    private val apiClient = ApiClient(store)

    val chat = ChatRepository(ChatApi(apiClient))
    val models = ModelRepository(ModelsApi(apiClient))
    val agents = AgentRepository(AgentsApi(apiClient))
    val projects = ProjectRepository(ProjectsApi(apiClient))
    val knowledge = KnowledgeRepository(KnowledgeApi(apiClient))
    val runtime = RuntimeRepository(RuntimeApi(apiClient), AccountApi(apiClient))
}
