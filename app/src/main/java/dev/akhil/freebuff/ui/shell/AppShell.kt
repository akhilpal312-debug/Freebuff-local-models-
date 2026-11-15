package dev.akhil.freebuff.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.StatusDot
import dev.akhil.freebuff.ui.AppViewModel
import dev.akhil.freebuff.ui.chat.ChatScreen
import dev.akhil.freebuff.ui.history.HistoryScreen
import dev.akhil.freebuff.ui.models.ModelsScreen
import dev.akhil.freebuff.ui.onboarding.OnboardingScreen
import dev.akhil.freebuff.ui.projects.ProjectsScreen
import dev.akhil.freebuff.ui.research.ResearchScreen
import dev.akhil.freebuff.ui.settings.SettingsScreen
import dev.akhil.freebuff.ui.tasks.TasksScreen
import dev.akhil.freebuff.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val PROJECTS = "projects"
    const val MODELS = "models"
    const val TERMINAL = "terminal"
    const val TASKS = "tasks"
    const val RESEARCH = "research"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CHAT_WITH_ID = "chat/{conversationId}"
    fun chat(id: String) = "chat/$id"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.CHAT, "Chat", AppIcons.Sparkle),
    NavItem(Routes.PROJECTS, "Projects", AppIcons.Folder),
    NavItem(Routes.MODELS, "Models", AppIcons.Cube),
    NavItem(Routes.TERMINAL, "Terminal", AppIcons.Terminal),
    NavItem(Routes.TASKS, "Tasks", AppIcons.Layers),
    NavItem(Routes.RESEARCH, "Research", AppIcons.Globe),
    NavItem(Routes.HISTORY, "History", AppIcons.Clock),
    NavItem(Routes.SETTINGS, "Settings", Icons.Filled.Menu),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val toast by viewModel.toast.collectAsState()
    val models by viewModel.models.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    val currentRoute = backStackEntry?.destination?.route
    val title = when {
        currentRoute == null -> "Freebuff Mobile"
        currentRoute.startsWith("chat") -> "Chat"
        else -> navItems.firstOrNull { it.route == currentRoute }?.label ?: "Freebuff Mobile"
    }

    if (!settings.onboardingComplete) {
        OnboardingScreen(viewModel)
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 26.dp)) {
                    Text(
                        "Freebuff Mobile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Local AI workstation · by Akhil",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                navItems.forEach { item ->
                    val selected = currentRoute == item.route || (item.route == Routes.CHAT && currentRoute?.startsWith("chat") == true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                scope.launch { drawerState.close() }
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Routes.CHAT) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            if (activeModel != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeModel?.displayName ?: "No model loaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${models.count { it.status == "INSTALLED" }} of ${models.size} components installed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            val subtitle = activeModel?.displayName
                            if (subtitle != null && currentRoute?.startsWith("chat") == true) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(navController = navController, startDestination = Routes.CHAT) {
                    composable(Routes.CHAT) {
                        ChatScreen(viewModel) { route -> navController.navigate(route) }
                    }
                    composable(
                        Routes.CHAT_WITH_ID,
                        arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("conversationId")
                        if (id != null) {
                            LaunchedEffect(id) { viewModel.openConversation(id) }
                        }
                        ChatScreen(viewModel) { route -> navController.navigate(route) }
                    }
                    composable(Routes.PROJECTS) { ProjectsScreen(viewModel) }
                    composable(Routes.MODELS) { ModelsScreen(viewModel) }
                    composable(Routes.TERMINAL) { TerminalScreen(viewModel) }
                    composable(Routes.TASKS) { TasksScreen(viewModel) }
                    composable(Routes.RESEARCH) { ResearchScreen(viewModel) }
                    composable(Routes.HISTORY) {
                        HistoryScreen(
                            viewModel = viewModel,
                            onOpenConversation = { id ->
                                viewModel.openConversation(id)
                                navController.navigate(Routes.chat(id))
                            },
                        )
                    }
                    composable(Routes.SETTINGS) { SettingsScreen(viewModel) }
                }
            }
        }
    }
}
