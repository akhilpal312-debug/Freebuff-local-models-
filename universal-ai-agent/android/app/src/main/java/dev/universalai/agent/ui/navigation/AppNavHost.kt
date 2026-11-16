package dev.universalai.agent.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.ui.screens.agents.AgentStudioScreen
import dev.universalai.agent.ui.screens.agents.RunDetailScreen
import dev.universalai.agent.ui.screens.chat.ChatScreen
import dev.universalai.agent.ui.screens.chat.ConversationsScreen
import dev.universalai.agent.ui.screens.home.HomeScreen
import dev.universalai.agent.ui.screens.knowledge.DocumentScreen
import dev.universalai.agent.ui.screens.knowledge.KnowledgeScreen
import dev.universalai.agent.ui.screens.models.ModelHubScreen
import dev.universalai.agent.ui.screens.projects.ProjectWorkspaceScreen
import dev.universalai.agent.ui.screens.projects.ProjectsScreen
import dev.universalai.agent.ui.screens.settings.SettingsScreen
import dev.universalai.agent.ui.theme.Palette

internal object Routes {
    const val HOME = "home"
    const val CHAT = "chats"
    const val CONVERSATION = "conversation"
    const val AGENTS = "agents"
    const val RUN = "run"
    const val PROJECTS = "projects"
    const val PROJECT = "project"
    const val KNOWLEDGE = "knowledge"
    const val DOCUMENT = "document"
    const val MODELS = "models"
    const val SETTINGS = "settings"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    Tab(Routes.AGENTS, "Agents", Icons.Filled.SmartToy),
    Tab(Routes.PROJECTS, "Projects", Icons.Filled.Folder),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    Scaffold(
        containerColor = Palette.Pearl,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Palette.Pearl, tonalElevation = 0.dp) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Ink,
                                selectedTextColor = Palette.Ink,
                                indicatorColor = Palette.SoftGrey,
                                unselectedIconColor = Palette.Faint,
                                unselectedTextColor = Palette.Faint,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        container = container,
                        onOpenConversation = { id ->
                            navController.navigate("${Routes.CONVERSATION}/$id")
                        },
                        onOpenAgents = { navController.navigate(Routes.AGENTS) },
                        onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                        onOpenKnowledge = { navController.navigate(Routes.KNOWLEDGE) },
                    )
                }
                composable(Routes.CHAT) {
                    ConversationsScreen(container) { id ->
                        navController.navigate("${Routes.CONVERSATION}/$id")
                    }
                }
                composable(
                    route = "${Routes.CONVERSATION}/{conversationId}",
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                ) { entry ->
                    ChatScreen(
                        container = container,
                        conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.AGENTS) {
                    AgentStudioScreen(container) { runId ->
                        navController.navigate("${Routes.RUN}/$runId")
                    }
                }
                composable(
                    route = "${Routes.RUN}/{runId}",
                    arguments = listOf(navArgument("runId") { type = NavType.StringType }),
                ) { entry ->
                    RunDetailScreen(
                        container = container,
                        runId = entry.arguments?.getString("runId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(container) { projectId ->
                        navController.navigate("${Routes.PROJECT}/$projectId")
                    }
                }
                composable(
                    route = "${Routes.PROJECT}/{projectId}",
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                ) { entry ->
                    ProjectWorkspaceScreen(
                        container = container,
                        projectId = entry.arguments?.getString("projectId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenRun = { runId -> navController.navigate("${Routes.RUN}/$runId") },
                    )
                }
                composable(Routes.KNOWLEDGE) {
                    KnowledgeScreen(container) { documentId ->
                        navController.navigate("${Routes.DOCUMENT}/$documentId")
                    }
                }
                composable(
                    route = "${Routes.DOCUMENT}/{documentId}",
                    arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
                ) { entry ->
                    DocumentScreen(
                        container = container,
                        documentId = entry.arguments?.getString("documentId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.MODELS) {
                    ModelHubScreen(container)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(container)
                }
            }
        }
    }
}
