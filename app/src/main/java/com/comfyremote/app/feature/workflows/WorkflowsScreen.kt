package com.comfyremote.app.feature.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comfyremote.app.R
import com.comfyremote.app.WorkflowEntry
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.ui.Iris
import com.comfyremote.app.ui.LocalAppSettings
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.LargeTopBar
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.SegmentedControl
import com.comfyremote.app.ui.components.StatusDot
import com.comfyremote.app.ui.components.TextAction

@Composable
fun WorkflowsScreen(
    viewModel: WorkflowsViewModel,
    onOpenWorkflow: (WorkflowEntry) -> Unit,
    onOpenTransfer: (DraftTransfer) -> Unit,
    onServers: () -> Unit,
    onSettings: () -> Unit,
    onCloudSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val settings = LocalAppSettings.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val cloud = state.source == WorkflowSource.CLOUD
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocal()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    // Re-check whenever the picker regains composition: reuse may be requested from
    // History/Tasks while this ViewModel already exists.
    LaunchedEffect(Unit) { viewModel.syncReuse() }

    // The list may restore a scroll offset; bring the reuse prompt back into view.
    LaunchedEffect(state.reuseTransfer) {
        if (state.reuseTransfer != null) listState.scrollToItem(0)
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            WorkflowsTopBar(
                state = state,
                onServers = onServers,
                onRefresh = {
                    if (cloud) viewModel.loadCloud(force = true)
                    else {
                        viewModel.refreshLocal(refreshDefinition = true)
                        viewModel.refreshServerHealth()
                    }
                },
                onSettings = onSettings,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            state.reuseTransfer?.let { transfer ->
                item("reuse") {
                    InfoBanner(
                        title = stringResource(R.string.wf_reuse_choose_title),
                        text = stringResource(R.string.wf_reuse_choose_detail, transfer.workflowName),
                        tone = BannerTone.Info,
                        actionLabel = stringResource(R.string.action_cancel),
                        onAction = viewModel::cancelReuse,
                    )
                }
            }

            item("source") {
                SegmentedControl(
                    options = listOf(stringResource(R.string.wf_source_local), stringResource(R.string.wf_source_cloud)),
                    selectedIndex = if (cloud) 1 else 0,
                    onSelect = { viewModel.setSource(if (it == 1) WorkflowSource.CLOUD else WorkflowSource.LOCAL) },
                )
            }

            item("search") {
                IrisTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.wf_search),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            }

            if (!cloud && state.query.isBlank() && settings.showServerWorkspace && state.servers.isNotEmpty()) {
                item("servers-heading") {
                    SectionHeader(stringResource(R.string.wf_server_workspace)) {
                        if (state.probingServers) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        }
                        TextAction(
                            stringResource(R.string.wf_least_busy),
                            onClick = viewModel::switchToLeastBusy,
                            enabled = !state.probingServers && state.switchingServer == null,
                        )
                    }
                }
                item("servers") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(state.servers, key = { it.url }) { server ->
                            ServerCard(
                                server = server,
                                health = state.serverHealth[server.url],
                                selected = state.session?.url == server.url,
                                appActive = state.activeByServer(server.url),
                                switching = state.switchingServer == server.url,
                                onClick = { viewModel.switchServer(server.url) },
                            )
                        }
                    }
                }
            }

            if (cloud && state.query.isBlank()) {
                item("cloud-workspace-heading") {
                    SectionHeader(stringResource(R.string.wf_cloud_workspace)) {
                        TextAction(
                            text = stringResource(R.string.wf_cloud_manage),
                            onClick = onCloudSettings,
                        )
                    }
                }
                if (state.cloudProviderCards.isEmpty()) {
                    item("cloud-no-providers") {
                        InfoBanner(
                            title = stringResource(R.string.wf_cloud_no_providers),
                            text = stringResource(R.string.wf_cloud_no_providers_detail),
                            tone = BannerTone.Warning,
                            actionLabel = stringResource(R.string.wf_go_settings),
                            onAction = onCloudSettings,
                        )
                    }
                } else {
                    item("cloud-providers") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            item("cloud-provider-all") {
                                AllCloudProviderCard(
                                    modelCount = state.cloudProviderCards.sumOf { it.modelCount },
                                    selected = state.selectedCloudProviderId == null,
                                    onClick = { viewModel.selectCloudProvider(null) },
                                )
                            }
                            items(state.cloudProviderCards, key = { "cloud-provider:${it.id}" }) { provider ->
                                CloudProviderCardItem(
                                    provider = provider,
                                    active = state.cloudActiveByProvider[provider.id] ?: 0,
                                    selected = state.selectedCloudProviderId == provider.id,
                                    onClick = {
                                        viewModel.selectCloudProvider(
                                            if (state.selectedCloudProviderId == provider.id) null else provider.id,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (cloud && !state.cloudConfigured && state.cloudProviderCards.isNotEmpty()) {
                item("cloud-token") {
                    InfoBanner(
                        text = stringResource(R.string.wf_cloud_token_hint),
                        tone = BannerTone.Warning,
                        actionLabel = stringResource(R.string.wf_go_settings),
                        onAction = onCloudSettings,
                    )
                }
            }

            if (state.query.isBlank()) {
                val presets = state.recentPresets.filter { preset ->
                    val anyCloudPath = preset.workflowPath?.let { path ->
                        state.allCloudPrefixes.any { prefix -> path.startsWith(prefix) }
                    } == true
                    val visibleCloudPath = preset.workflowPath?.let { path ->
                        state.cloudPrefixes.any { prefix -> path.startsWith(prefix) }
                    } == true
                    if (cloud) {
                        visibleCloudPath
                    } else {
                        !anyCloudPath &&
                            (preset.serverUrl.isNullOrBlank() || preset.serverUrl == state.session?.url)
                    }
                }
                val results = state.recentResults.filter { item ->
                    if (cloud) {
                        item.serverUrl in state.cloudBaseUrls
                    } else {
                        item.serverUrl !in state.allCloudBaseUrls && (
                            state.session == null || item.serverUrl.isBlank() ||
                                item.serverUrl == state.session?.url
                            )
                    }
                }
                if (settings.recentResultsCount > 0 && results.isNotEmpty()) {
                    val shown = results.take(settings.recentResultsCount)
                    val withMedia = shown.mapNotNull { item -> item.images.firstOrNull()?.let { item to it } }
                    val withoutMedia = shown.filter { it.images.isEmpty() }
                    item("result-heading") { SectionHeader(stringResource(R.string.wf_recent_results)) }
                    if (withMedia.isNotEmpty()) {
                        item("result-tiles") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                items(withMedia, key = { "tile:${it.first.id}" }) { (result, path) ->
                                    RecentResultTile(result, path) { viewModel.prepareResult(result)?.let(onOpenTransfer) }
                                }
                            }
                        }
                    }
                    items(withoutMedia, key = { "result:${it.id}" }) { result ->
                        RecentResultRow(result) { viewModel.prepareResult(result)?.let(onOpenTransfer) }
                    }
                }
                if (settings.recentPresetsCount > 0 && presets.isNotEmpty()) {
                    item("preset-heading") { SectionHeader(stringResource(R.string.wf_recent_setups)) }
                    items(presets.take(settings.recentPresetsCount), key = { "preset:${it.id}" }) { preset ->
                        RecentPresetRow(preset) { viewModel.preparePreset(preset)?.let(onOpenWorkflow) }
                    }
                }
            }

            val loading = if (cloud) state.cloudLoading else state.loading
            val error = if (cloud) state.cloudError else state.error
            when {
                loading && state.visible.isEmpty() -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                error != null && state.visible.isEmpty() -> item("error") {
                    // Error-tone banner (what ErrorCard wraps) without an empty detail line.
                    InfoBanner(
                        text = error,
                        tone = BannerTone.Error,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = viewModel::load,
                    )
                }
                state.visible.isEmpty() -> item("empty") {
                    EmptyState(
                        title = stringResource(
                            when {
                                !cloud -> R.string.wf_empty
                                state.cloudProviderCards.isEmpty() -> R.string.wf_cloud_no_providers
                                else -> R.string.wf_empty_cloud
                            }
                        ),
                        detail = stringResource(
                            if (cloud) R.string.wf_cloud_empty_detail else R.string.wf_empty_detail
                        ),
                        icon = if (cloud) Icons.Outlined.CloudQueue else Icons.Outlined.AutoAwesome,
                    )
                }
                cloud -> {
                    state.cloudSections.forEach { section ->
                        item("cloud-section:${section.providerId}") {
                            SectionHeader(section.providerLabel) {
                                Text(
                                    stringResource(R.string.wf_cloud_section_count, section.workflows.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val recent = state.recentPaths.toSet()
                        items(section.workflows, key = { it.path }) { wf ->
                            WorkflowRow(
                                workflow = wf,
                                favorite = wf.path in state.favoritePaths,
                                recent = wf.path in recent,
                                onToggleFavorite = { viewModel.toggleFavorite(wf.path) },
                                onClick = {
                                    viewModel.opened(wf)
                                    onOpenWorkflow(wf)
                                },
                            )
                        }
                    }
                }
                else -> {
                    item("workflow-heading") {
                        SectionHeader(stringResource(R.string.wf_workflows))
                    }
                    val recent = state.recentPaths.toSet()
                    items(state.visible, key = { it.path }) { wf ->
                        WorkflowRow(
                            workflow = wf,
                            favorite = wf.path in state.favoritePaths,
                            recent = wf.path in recent,
                            onToggleFavorite = { viewModel.toggleFavorite(wf.path) },
                            onClick = {
                                viewModel.opened(wf)
                                onOpenWorkflow(wf)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowsTopBar(
    state: WorkflowsUiState,
    onServers: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val cloud = state.source == WorkflowSource.CLOUD
    val session = state.session
    val subtitle = when {
        cloud -> stringResource(if (state.cloudConfigured) R.string.wf_subtitle_cloud_ready else R.string.wf_subtitle_cloud_token)
        session != null -> session.device.ifBlank { session.url }
        else -> stringResource(R.string.wf_no_local_server)
    }
    val dot = when {
        cloud -> if (state.cloudConfigured) Iris.colors.success else Iris.colors.warning
        session != null -> Iris.colors.success
        else -> MaterialTheme.colorScheme.outline
    }
    LargeTopBar(
        title = stringResource(R.string.nav_create),
        subtitle = subtitle,
        subtitleLeading = { StatusDot(dot) },
        actions = {
            if (!cloud) {
                IconButton(onClick = onServers) { Icon(Icons.Outlined.Dns, stringResource(R.string.wf_server)) }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh)) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.action_settings)) }
        },
    )
}

private fun WorkflowsUiState.activeByServer(url: String): Int = appActiveByServer[url] ?: 0
