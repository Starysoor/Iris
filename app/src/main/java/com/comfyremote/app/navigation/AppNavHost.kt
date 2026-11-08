package com.comfyremote.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.ui.components.IrisDivider
import com.comfyremote.app.ui.components.LocalShellOwnsBottomInset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comfyremote.app.AppContainer
import com.comfyremote.app.AppViewModel
import com.comfyremote.app.AppViewModelFactory
import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.feature.ai.AiSettingsScreen
import com.comfyremote.app.feature.ai.AiSettingsViewModel
import com.comfyremote.app.feature.cloud.CloudProviderPlaceholderScreen
import com.comfyremote.app.feature.cloud.CloudProviderSettingsScreen
import com.comfyremote.app.feature.cloud.CloudServicesScreen
import com.comfyremote.app.feature.cloud.CloudServicesViewModel
import com.comfyremote.app.feature.connect.ConnectScreen
import com.comfyremote.app.feature.connect.ConnectViewModel
import com.comfyremote.app.feature.connect.ServerManagerScreen
import com.comfyremote.app.feature.generate.CloudConfirmDialog
import com.comfyremote.app.feature.generate.GenerateScreen
import com.comfyremote.app.feature.generate.GenerateViewModel
import com.comfyremote.app.feature.history.HistoryScreen
import com.comfyremote.app.feature.history.HistoryViewModel
import com.comfyremote.app.feature.settings.AppSettingsScreen
import com.comfyremote.app.feature.settings.AppSettingsViewModel
import com.comfyremote.app.feature.settings.CloudSettingsScreen
import com.comfyremote.app.feature.settings.CloudSettingsViewModel
import com.comfyremote.app.feature.settings.SettingsScreen
import com.comfyremote.app.feature.project.AssetLibraryScreen
import com.comfyremote.app.feature.project.DirectorScreen
import com.comfyremote.app.feature.project.TimelineScreen
import com.comfyremote.app.feature.project.ProjectHomeScreen
import com.comfyremote.app.feature.project.ProjectListScreen
import com.comfyremote.app.feature.project.ProjectViewModel
import com.comfyremote.app.feature.project.ShotsWorkspaceScreen
import com.comfyremote.app.feature.tasks.TaskDetailScreen
import com.comfyremote.app.feature.tasks.TasksScreen
import com.comfyremote.app.feature.tasks.TasksViewModel
import com.comfyremote.app.feature.update.UpdateScreen
import com.comfyremote.app.feature.update.UpdateViewModel
import com.comfyremote.app.feature.workflows.WorkflowsScreen
import com.comfyremote.app.feature.workflows.WorkflowsViewModel
import kotlinx.coroutines.launch

@Composable
fun ComfyRemoteRoot(container: AppContainer) {
    val factory = AppViewModelFactory(container)
    val appViewModel: AppViewModel = viewModel(factory = factory)
    val appState by appViewModel.state.collectAsState()
    if (!appState.ready) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        return
    }
    AppNavHost(
        container = container,
        factory = factory,
        startDestination = if (appState.session != null || container.autoDlRepository.configured()) {
            Routes.CREATE
        } else {
            Routes.CONNECT
        },
    )
    val cloudPending by container.cloudSubmissionGate.pending.collectAsState()
    cloudPending?.let { pending ->
        CloudConfirmDialog(
            confirmation = pending.confirmation,
            onConfirm = container.cloudSubmissionGate::confirm,
            onDismiss = container.cloudSubmissionGate::dismiss,
        )
    }
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    factory: AppViewModelFactory,
    startDestination: String,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottom = route in setOf(Routes.CREATE, Routes.TASKS, Routes.HISTORY, Routes.PROJECTS)

    fun openTransfer(transfer: DraftTransfer) {
        scope.launch {
            // Cloud workflows are submitted through their provider; the AutoDL base URL
            // is not a ComfyUI server and must not trigger the local server picker.
            val cloud = container.cloudProviders.forPath(transfer.workflowPath) != null
            val source = transfer.serverUrl?.trim()?.removeSuffix("/").orEmpty()
            val ready = if (cloud || source.isBlank() || source == container.serverSession.url) {
                true
            } else {
                runCatching {
                    container.serverSession.connect(source)
                    container.serverRepository.remember(source)
                    container.serverRepository.saveLastUrl(source)
                }.isSuccess
            }
            if (!ready) {
                container.draftTransferRepository.clear()
                navController.navigate(Routes.SERVERS)
            } else if (transfer.reuseAcrossWorkflows) {
                // Keep the transfer pending so the workflow the user picks consumes it.
                navigateTopLevel(navController, Routes.CREATE)
            } else {
                navController.navigate(Routes.generate(transfer.workflowPath, transfer.workflowName))
            }
        }
    }

    LaunchedEffect(navController) {
        container.navigationRequests.route.collect { requested ->
            if (requested != null) {
                navController.navigate(requested) { launchSingleTop = true }
                container.navigationRequests.consume(requested)
            }
        }
    }

    // The shell draws edge-to-edge; each screen's IrisScaffold handles the status
    // bar, and the bottom inset belongs to whichever bar is at the bottom.
    Scaffold(
        bottomBar = { if (showBottom) MainBottomBar(navController, route) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { outerPadding ->
        CompositionLocalProvider(LocalShellOwnsBottomInset provides showBottom) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(outerPadding).consumeWindowInsets(outerPadding),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Routes.CONNECT) {
                val vm: ConnectViewModel = viewModel(factory = factory)
                ConnectScreen(
                    viewModel = vm,
                    onConnected = { navigateToCreate(navController) },
                    onCloudOnly = { navigateToCreate(navController) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CREATE) {
                val vm: WorkflowsViewModel = viewModel(factory = factory)
                WorkflowsScreen(
                    viewModel = vm,
                    onOpenWorkflow = { navController.navigate(Routes.generate(it.path, it.name)) },
                    onOpenTransfer = ::openTransfer,
                    onServers = { navController.navigate(Routes.SERVERS) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onCloudSettings = { navController.navigate(Routes.CLOUD_SERVICES) },
                )
            }
            composable(Routes.TASKS) {
                val vm: TasksViewModel = viewModel(factory = factory)
                TasksScreen(vm) { navController.navigate(Routes.task(it)) }
            }
            composable(Routes.HISTORY) {
                val vm: HistoryViewModel = viewModel(factory = factory)
                HistoryScreen(
                    vm,
                    onReuse = ::openTransfer,
                    onOpenProject = { projectId -> navController.navigate(Routes.project(projectId)) },
                )
            }
            composable(Routes.PROJECTS) {
                val vm: ProjectViewModel = viewModel(factory = factory)
                ProjectListScreen(
                    viewModel = vm,
                    onOpenProject = { projectId -> navController.navigate(Routes.project(projectId)) },
                )
            }
            composable(
                Routes.PROJECT_PATTERN,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val vm: ProjectViewModel = viewModel(factory = factory)
                ProjectHomeScreen(
                    viewModel = vm,
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenWorkspace = {
                        navController.navigate(
                            Routes.projectShots(entry.arguments?.getString("projectId").orEmpty()),
                        )
                    },
                    onOpenLibrary = {
                        navController.navigate(
                            Routes.projectAssets(entry.arguments?.getString("projectId").orEmpty()),
                        )
                    },
                    onOpenTimeline = {
                        navController.navigate(
                            Routes.projectTimeline(entry.arguments?.getString("projectId").orEmpty()),
                        )
                    },
                    onOpenDirector = {
                        navController.navigate(
                            Routes.projectDirector(entry.arguments?.getString("projectId").orEmpty()),
                        )
                    },
                    onOpenTask = { taskId -> navController.navigate(Routes.task(taskId)) },
                )
            }
            composable(
                Routes.PROJECT_SHOTS_PATTERN,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val vm: ProjectViewModel = viewModel(factory = factory)
                ShotsWorkspaceScreen(
                    viewModel = vm,
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenShot = { path, name -> navController.navigate(Routes.generate(path, name)) },
                )
            }
            composable(
                Routes.PROJECT_DIRECTOR_PATTERN,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val vm: ProjectViewModel = viewModel(factory = factory)
                DirectorScreen(
                    viewModel = vm,
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.PROJECT_TIMELINE_PATTERN,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val vm: ProjectViewModel = viewModel(factory = factory)
                TimelineScreen(
                    viewModel = vm,
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.PROJECT_ASSETS_PATTERN,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val vm: ProjectViewModel = viewModel(factory = factory)
                AssetLibraryScreen(
                    viewModel = vm,
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.GENERATE_PATTERN,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                )
            ) { entry ->
                val vm: GenerateViewModel = viewModel(factory = factory)
                GenerateScreen(
                    viewModel = vm,
                    workflowPath = entry.arguments?.getString("path").orEmpty(),
                    workflowName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenTask = { navController.navigate(Routes.task(it)) },
                    onOpenCloudVideo = { path, name ->
                        navController.navigate(Routes.generate(path, name))
                    },
                    onOpenProject = { projectId -> navController.navigate(Routes.project(projectId)) },
                )
            }
            composable(
                Routes.TASK_PATTERN,
                arguments = listOf(navArgument("taskId") { type = NavType.StringType })
            ) { entry ->
                val vm: TasksViewModel = viewModel(factory = factory)
                TaskDetailScreen(
                    viewModel = vm,
                    taskId = entry.arguments?.getString("taskId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onReuse = ::openTransfer,
                    onOpenProject = { projectId -> navController.navigate(Routes.project(projectId)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    version = container.updateRepository.currentVersion(),
                    onBack = { navController.popBackStack() },
                    onApp = { navController.navigate(Routes.APP_SETTINGS) },
                    onServers = { navController.navigate(Routes.SERVERS) },
                    onAi = { navController.navigate(Routes.AI_SETTINGS) },
                    onCloud = { navController.navigate(Routes.CLOUD_SERVICES) },
                    onUpdate = { navController.navigate(Routes.UPDATE) },
                    cloudProviders = container.cloudProviders.info(),
                    onOpenProvider = { providerId ->
                        navController.navigate(Routes.cloudProvider(providerId))
                    },
                )
            }
            composable(Routes.APP_SETTINGS) {
                val vm: AppSettingsViewModel = viewModel(factory = factory)
                AppSettingsScreen(vm) { navController.popBackStack() }
            }
            composable(Routes.CLOUD_SETTINGS) {
                val vm: CloudSettingsViewModel = viewModel(factory = factory)
                CloudSettingsScreen(vm) { navController.popBackStack() }
            }
            composable(Routes.CLOUD_SERVICES) {
                val vm: CloudServicesViewModel = viewModel(factory = factory)
                CloudServicesScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenProvider = { providerId ->
                        navController.navigate(Routes.cloudProvider(providerId))
                    },
                )
            }
            composable(
                Routes.CLOUD_PROVIDER_PATTERN,
                arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId").orEmpty()
                val provider = container.cloudProviders.byId(providerId)
                when {
                    providerId == AutoDlConfig.PROVIDER -> {
                        val vm: CloudSettingsViewModel = viewModel(factory = factory)
                        CloudSettingsScreen(vm) { navController.popBackStack() }
                    }
                    provider != null -> CloudProviderSettingsScreen(
                        provider = provider,
                        settings = container.cloudProviderSettingsStore,
                        onBack = { navController.popBackStack() },
                    )
                    else -> CloudProviderPlaceholderScreen(
                        title = providerId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Routes.SERVERS) {
                val vm: ConnectViewModel = viewModel(factory = factory)
                ServerManagerScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onConnected = { navigateToCreate(navController) },
                )
            }
            composable(Routes.AI_SETTINGS) {
                val vm: AiSettingsViewModel = viewModel(factory = factory)
                AiSettingsScreen(vm) { navController.popBackStack() }
            }
            composable(Routes.UPDATE) {
                val vm: UpdateViewModel = viewModel(factory = factory)
                UpdateScreen(vm) { navController.popBackStack() }
            }
        }
        }
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val TopLevelDestinations = listOf(
    TopLevelDestination(Routes.CREATE, R.string.nav_create, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    TopLevelDestination(Routes.TASKS, R.string.nav_tasks, Icons.Outlined.Pending, Icons.Filled.Pending),
    TopLevelDestination(Routes.HISTORY, R.string.nav_results, Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    TopLevelDestination(Routes.PROJECTS, R.string.nav_projects, Icons.Outlined.Movie, Icons.Filled.Movie),
)

@Composable
private fun MainBottomBar(navController: NavHostController, current: String?) {
    val scheme = MaterialTheme.colorScheme
    Column {
        IrisDivider()
        NavigationBar(containerColor = scheme.surfaceContainerLow, tonalElevation = 0.dp) {
            TopLevelDestinations.forEach { dest ->
                val selected = current == dest.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { navigateTopLevel(navController, dest.route) },
                    icon = { Icon(if (selected) dest.selectedIcon else dest.icon, null) },
                    label = { Text(stringResource(dest.label), style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.onPrimaryContainer,
                        selectedTextColor = scheme.onSurface,
                        indicatorColor = scheme.primaryContainer,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

private fun navigateTopLevel(navController: NavHostController, route: String) {
    if (navController.currentDestination?.route == route) return
    navController.navigate(route) {
        popUpTo(Routes.CREATE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun navigateToCreate(navController: NavHostController) {
    if (navController.popBackStack(Routes.CREATE, inclusive = false)) return
    navController.navigate(Routes.CREATE) {
        popUpTo(Routes.CONNECT) { inclusive = true }
        launchSingleTop = true
    }
}
