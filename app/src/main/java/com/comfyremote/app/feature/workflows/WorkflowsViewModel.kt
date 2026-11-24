package com.comfyremote.app.feature.workflows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AppContainer
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.ServerEntry
import com.comfyremote.app.WorkflowEntry
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.data.repository.PromptPreset
import com.comfyremote.app.data.repository.ServerHealth
import com.comfyremote.app.data.repository.ServerSession
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WorkflowSource { LOCAL, CLOUD }

/** One provider card in the Cloud workspace strip. */
data class CloudProviderCard(
    val id: String,
    val label: String,
    val configured: Boolean,
    val enabled: Boolean = true,
    val modelCount: Int = 0,
    val error: String? = null,
)

data class WorkflowsUiState(
    val workflows: List<WorkflowEntry> = emptyList(),
    val cloudWorkflows: List<WorkflowEntry> = emptyList(),
    val cloudProviderCards: List<CloudProviderCard> = emptyList(),
    val selectedCloudProviderId: String? = null,
    val source: WorkflowSource = WorkflowSource.LOCAL,
    val cloudLoading: Boolean = false,
    val cloudError: String? = null,
    val cloudLoaded: Boolean = false,
    val cloudConfigured: Boolean = false,
    val cloudPrefixes: Set<String> = emptySet(),
    val cloudBaseUrls: Set<String> = emptySet(),
    /** All registered cloud prefixes/endpoints, even for hidden providers. */
    val allCloudPrefixes: Set<String> = emptySet(),
    val allCloudBaseUrls: Set<String> = emptySet(),
    val reuseTransfer: DraftTransfer? = null,
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val recentPaths: List<String> = emptyList(),
    val favoritePaths: Set<String> = emptySet(),
    val defaultPaths: Set<String> = emptySet(),
    val recentPresets: List<PromptPreset> = emptyList(),
    val recentResults: List<HistoryItem> = emptyList(),
    val session: ServerSession? = null,
    val servers: List<ServerEntry> = emptyList(),
    val serverHealth: Map<String, ServerHealth> = emptyMap(),
    val appActiveByServer: Map<String, Int> = emptyMap(),
    val cloudActiveByProvider: Map<String, Int> = emptyMap(),
    val probingServers: Boolean = false,
    val switchingServer: String? = null,
    val message: String? = null,
) {
    val visible: List<WorkflowEntry>
        get() {
            val q = query.trim()
            val recentRank = recentPaths.withIndex().associate { it.value to it.index }
            val sourceList = if (source == WorkflowSource.CLOUD) cloudWorkflows else workflows
            return sourceList.asSequence()
                .filter { workflow ->
                    val providerMatch = source != WorkflowSource.CLOUD ||
                        selectedCloudProviderId == null ||
                        workflow.providerId == selectedCloudProviderId
                    providerMatch && (
                        q.isBlank() || workflow.name.contains(q, true) || workflow.path.contains(q, true) ||
                            workflow.description.contains(q, true)
                        )
                }
                .sortedWith(
                    compareByDescending<WorkflowEntry> { it.path in favoritePaths }
                        .thenByDescending { it.path in defaultPaths }
                        .thenBy { recentRank[it.path] ?: Int.MAX_VALUE }
                        .thenBy { it.name.lowercase() }
                )
                .toList()
        }

    /** Cloud workflows grouped by provider, in workspace-card order. */
    val cloudSections: List<CloudWorkflowSection>
        get() = cloudWorkflowSections(visible, cloudProviderCards)

    val selectedCloudProvider: CloudProviderCard?
        get() = selectedCloudProviderId?.let { id -> cloudProviderCards.firstOrNull { it.id == id } }

    val cloudListError: String?
        get() = if (selectedCloudProviderId == null) cloudError else selectedCloudProvider?.error
}

data class CloudWorkflowSection(
    val providerId: String,
    val providerLabel: String,
    val workflows: List<WorkflowEntry>,
)

/** Pure grouping helper so provider separation is regression-tested. */
fun cloudWorkflowSections(
    entries: List<WorkflowEntry>,
    providers: List<CloudProviderCard>,
): List<CloudWorkflowSection> {
    if (entries.isEmpty()) return emptyList()
    val byProvider = entries.groupBy { it.providerId }
    val sections = mutableListOf<CloudWorkflowSection>()
    providers.forEach { provider ->
        byProvider[provider.id]?.takeIf { it.isNotEmpty() }?.let {
            sections += CloudWorkflowSection(provider.id, provider.label, it)
        }
    }
    // Defensive: an entry whose provider card disappeared still renders.
    entries.groupBy { it.providerId }.forEach { (providerId, list) ->
        if (sections.none { it.providerId == providerId }) {
            sections += CloudWorkflowSection(
                providerId,
                list.firstOrNull()?.providerLabel.orEmpty(),
                list,
            )
        }
    }
    return sections
}

class WorkflowsViewModel(private val container: AppContainer) : ViewModel() {
    private data class LoadedProvider(
        val provider: com.comfyremote.app.data.cloud.CloudProvider,
        val entries: List<WorkflowEntry>,
        val error: String?,
    )

    private var cloudReloadQueued = false
    private val pendingAtStart = container.draftTransferRepository.peek()
        ?.takeIf { it.reuseAcrossWorkflows }
    private val initialSource = when {
        pendingAtStart?.workflowPath?.let { container.cloudProviders.forPath(it) != null } == true ->
            WorkflowSource.CLOUD
        pendingAtStart != null -> WorkflowSource.LOCAL
        container.serverSession.connected -> WorkflowSource.LOCAL
        else -> WorkflowSource.CLOUD
    }
    private val _state = MutableStateFlow(
        WorkflowsUiState(
            recentPaths = container.workflowUsageRepository.recentPaths(),
            favoritePaths = container.workflowUsageRepository.favorites(),
            defaultPaths = defaultCloudPaths(),
            recentPresets = recentSetups(container.promptPresetRepository.load()),
            recentResults = recentResults(container.historyRepository.load()),
            session = container.serverSession.session.value,
            servers = container.serverRepository.load(),
            source = initialSource,
            reuseTransfer = pendingAtStart,
            cloudProviderCards = cloudProviderCards(),
            cloudConfigured = container.cloudProviders.enabled.any { it.configured() },
            cloudPrefixes = container.cloudProviders.enabled.mapTo(hashSetOf()) { it.pathPrefix },
            cloudBaseUrls = container.cloudProviders.enabled.flatMapTo(hashSetOf()) { provider ->
                listOfNotNull(
                    provider.baseUrl.takeIf { it.isNotBlank() },
                    provider.endpoint().takeIf { it.isNotBlank() },
                )
            },
            allCloudPrefixes = container.cloudProviders.all.mapTo(hashSetOf()) { it.pathPrefix },
            allCloudBaseUrls = container.cloudProviders.all.flatMapTo(hashSetOf()) { provider ->
                listOfNotNull(
                    provider.baseUrl.takeIf { it.isNotBlank() },
                    provider.endpoint().takeIf { it.isNotBlank() },
                )
            },
        )
    )
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var previousUrl = container.serverSession.session.value?.url
            container.serverSession.session.collect { session ->
                val changed = session?.url != previousUrl
                previousUrl = session?.url
                _state.update {
                    it.copy(
                        session = session,
                        workflows = if (session == null || changed) emptyList() else it.workflows,
                        error = if (session == null || changed) null else it.error,
                        recentResults = recentResults(container.historyRepository.load()),
                        switchingServer = null,
                    )
                }
                if (changed && session != null) loadInternal(force = true)
            }
        }
        viewModelScope.launch {
            container.serverRepository.servers.collect { servers ->
                _state.update { it.copy(servers = servers) }
                refreshServerHealth()
            }
        }
        viewModelScope.launch {
            container.generationManager.tasks.collect { tasks ->
                _state.update { state ->
                    state.copy(
                        appActiveByServer = tasks.filter { it.isActive }
                            .groupingBy { it.request.serverUrl }
                            .eachCount(),
                        cloudActiveByProvider = tasks.filter {
                            it.isActive && it.request.provider != "comfyui"
                        }.groupingBy { it.request.provider }.eachCount(),
                    )
                }
            }
        }
        viewModelScope.launch {
            var first = true
            container.cloudProviderSettingsStore.revision.collect {
                if (first) {
                    first = false
                    return@collect
                }
                _state.update { state ->
                    state.copy(
                        cloudLoaded = false,
                        cloudConfigured = container.cloudProviders.enabled.any { provider -> provider.configured() },
                        cloudProviderCards = if (state.source == WorkflowSource.CLOUD) {
                            cloudProviderCards(state.cloudProviderCards)
                        } else {
                            state.cloudProviderCards
                        },
                    )
                }
                if (_state.value.source == WorkflowSource.CLOUD) loadCloud(force = true)
            }
        }
        viewModelScope.launch {
            container.promptPresetRepository.presets.collect { presets ->
                _state.update { it.copy(recentPresets = recentSetups(presets)) }
            }
        }
        viewModelScope.launch {
            container.historyRepository.items.collect { history ->
                _state.update { state -> state.copy(recentResults = recentResults(history)) }
            }
        }
        load()
        refreshServerHealth()
        if (_state.value.source == WorkflowSource.CLOUD) loadCloud(force = false)
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Pick up a transfer that asked the user to choose a different workflow. */
    fun syncReuse() {
        val pending = container.draftTransferRepository.peek()
        if (pending?.reuseAcrossWorkflows != true) {
            _state.update { it.copy(reuseTransfer = null) }
            return
        }
        val source = if (container.cloudProviders.forPath(pending.workflowPath) != null) {
            WorkflowSource.CLOUD
        } else {
            WorkflowSource.LOCAL
        }
        _state.update { it.copy(source = source, reuseTransfer = pending, query = "") }
        if (source == WorkflowSource.CLOUD && !_state.value.cloudLoaded) loadCloud(force = false)
    }

    fun cancelReuse() {
        container.draftTransferRepository.clear()
        _state.update { it.copy(reuseTransfer = null) }
    }

    fun setSource(source: WorkflowSource) {
        if (_state.value.source == source) return
        _state.update { it.copy(source = source, query = "") }
        if (source == WorkflowSource.CLOUD && !_state.value.cloudLoaded) loadCloud(force = false)
        if (source == WorkflowSource.LOCAL) {
            _state.update {
                it.copy(cloudConfigured = container.cloudProviders.enabled.any { provider -> provider.configured() })
            }
            loadInternal(force = false)
        }
    }

    fun selectCloudProvider(providerId: String?) = _state.update {
        it.copy(selectedCloudProviderId = providerId)
    }

    fun load() {
        if (_state.value.source == WorkflowSource.CLOUD) loadCloud(force = true)
        else loadInternal(force = false)
    }

    fun refreshLocal(refreshDefinition: Boolean = false) {
        if (_state.value.source != WorkflowSource.LOCAL) return
        if (refreshDefinition) container.objectInfoCache.invalidate()
        loadInternal(force = false)
    }

    fun loadCloud(force: Boolean = false) {
        if (_state.value.cloudLoading) {
            cloudReloadQueued = true
            return
        }
        val providers = container.cloudProviders.enabled
        _state.update {
            it.copy(
                cloudLoading = true,
                cloudError = null,
                cloudConfigured = providers.any { provider -> provider.configured() },
                cloudProviderCards = cloudProviderCards(it.cloudProviderCards),
                selectedCloudProviderId = it.selectedCloudProviderId?.takeIf { id ->
                    providers.any { provider -> provider.id == id }
                },
            )
        }
        viewModelScope.launch {
            val loaded = coroutineScope {
                providers.map { provider ->
                    async {
                        if (!provider.configured()) {
                            LoadedProvider(provider, emptyList(), null)
                        } else {
                            runCatching { provider.catalog(force) }
                                .fold(
                                    onSuccess = { list ->
                                        LoadedProvider(
                                            provider,
                                            list.map { summary ->
                                                summary.toEntry(provider.pathPrefix, provider.label, provider.id)
                                            },
                                            null,
                                        )
                                    },
                                    onFailure = { error ->
                                        LoadedProvider(
                                            provider,
                                            emptyList(),
                                            "${provider.label}: ${
                                                error.message?.takeIf(String::isNotBlank)
                                                    ?: error.javaClass.simpleName
                                            }",
                                        )
                                    },
                                )
                        }
                    }
                }.awaitAll()
            }
            val entries = loaded.flatMap { it.entries }
            val errors = loaded.mapNotNull { it.error }
            _state.update {
                it.copy(
                    cloudLoading = false,
                    cloudLoaded = true,
                    cloudWorkflows = entries,
                    cloudProviderCards = loaded.map { result ->
                        CloudProviderCard(
                            id = result.provider.id,
                            label = result.provider.label,
                            configured = result.provider.configured(),
                            enabled = true,
                            modelCount = result.entries.size,
                            error = result.error,
                        )
                    },
                    defaultPaths = defaultCloudPaths(),
                    cloudPrefixes = providers.mapTo(hashSetOf()) { provider -> provider.pathPrefix },
                    cloudBaseUrls = providers.flatMapTo(hashSetOf()) { provider ->
                        listOfNotNull(
                            provider.baseUrl.takeIf { it.isNotBlank() },
                            provider.endpoint().takeIf { it.isNotBlank() },
                        )
                    },
                    allCloudPrefixes = container.cloudProviders.all.mapTo(hashSetOf()) { it.pathPrefix },
                    allCloudBaseUrls = container.cloudProviders.all.flatMapTo(hashSetOf()) { provider ->
                        listOfNotNull(
                            provider.baseUrl.takeIf { it.isNotBlank() },
                            provider.endpoint().takeIf { it.isNotBlank() },
                        )
                    },
                    cloudError = if (entries.isEmpty() && errors.isNotEmpty()) {
                        AppStrings.get(R.string.wf_err_cloud_load, errors.joinToString("; "))
                    } else null,
                    cloudConfigured = providers.any { provider -> provider.configured() },
                )
            }
            if (cloudReloadQueued) {
                cloudReloadQueued = false
                loadCloud(force = true)
            }
        }
    }

    private fun cloudProviderCards(previous: List<CloudProviderCard> = emptyList()): List<CloudProviderCard> =
        container.cloudProviders.enabled.map { provider ->
            val old = previous.firstOrNull { it.id == provider.id }
            CloudProviderCard(
                id = provider.id,
                label = provider.label,
                configured = provider.configured(),
                enabled = true,
                modelCount = old?.modelCount ?: 0,
                error = old?.error,
            )
        }

    private fun defaultCloudPaths(): Set<String> {
        val paths = container.cloudProviders.enabled.mapNotNull { provider ->
            container.cloudProviderSettingsStore.defaultModel(provider.id)
                .takeIf { it.isNotBlank() }
                ?.let { provider.pathPrefix + it }
        }.toMutableSet()
        if (container.cloudProviders.isEnabled(com.comfyremote.app.data.cloud.AutoDlConfig.PROVIDER)) {
            container.appSettingsStore.settings.value.defaultVideoWorkflowId
                .takeIf { it.isNotBlank() }
                ?.let { paths += com.comfyremote.app.data.cloud.AutoDlConfig.PATH_PREFIX + it }
        }
        return paths
    }

    fun refreshServerHealth() {
        val servers = container.serverRepository.load()
        if (servers.isEmpty() || _state.value.probingServers) return
        _state.update { it.copy(probingServers = true) }
        viewModelScope.launch {
            coroutineScope {
                servers.distinctBy { it.url }.map { server ->
                    async {
                        val health = container.serverHealthRepository.probe(server.url)
                        _state.update { state ->
                            state.copy(serverHealth = state.serverHealth + (server.url to health))
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(probingServers = false) }
        }
    }

    fun switchServer(url: String) {
        if (_state.value.switchingServer != null) return
        if (_state.value.session?.url == url) return
        _state.update { it.copy(switchingServer = url, message = null) }
        viewModelScope.launch {
            runCatching { container.serverSession.connect(url) }
                .onSuccess {
                    container.serverRepository.remember(url)
                    container.serverRepository.saveLastUrl(url)
                    _state.update { state ->
                        state.copy(source = WorkflowSource.LOCAL, message = AppStrings.get(R.string.wf_server_switched))
                    }
                    refreshServerHealth()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            switchingServer = null,
                            message = AppStrings.get(R.string.wf_switch_failed, error.message ?: "error"),
                        )
                    }
                }
        }
    }

    fun switchToLeastBusy() {
        val candidate = _state.value.servers
            .mapNotNull { server -> _state.value.serverHealth[server.url]?.takeIf { it.online } }
            .minWithOrNull(compareBy<ServerHealth> { it.loadScore + (_state.value.appActiveByServer[it.url] ?: 0) * 100 }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE })
        if (candidate == null) {
            _state.update { it.copy(message = AppStrings.get(R.string.wf_no_online_server)) }
        } else switchServer(candidate.url)
    }

    private fun loadInternal(force: Boolean) {
        if (!container.serverSession.connected || (_state.value.loading && !force)) return
        val requestedUrl = container.serverSession.url
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { container.workflowRepository.list() }
                .onSuccess { list ->
                    if (container.serverSession.url != requestedUrl) return@onSuccess
                    _state.update {
                        it.copy(
                            loading = false,
                            workflows = list,
                            recentPaths = container.workflowUsageRepository.recentPaths(),
                            favoritePaths = container.workflowUsageRepository.favorites(),
                        )
                    }
                }
                .onFailure { e ->
                    if (container.serverSession.url != requestedUrl) return@onFailure
                    _state.update {
                        it.copy(
                            loading = false,
                            error = AppStrings.get(R.string.wf_err_list, e.message ?: "error")
                        )
                    }
                }
        }
    }

    fun opened(entry: WorkflowEntry) {
        container.workflowUsageRepository.markOpened(entry.path)
        _state.update { it.copy(recentPaths = container.workflowUsageRepository.recentPaths()) }
    }

    fun toggleFavorite(path: String) {
        _state.update { it.copy(favoritePaths = container.workflowUsageRepository.toggleFavorite(path)) }
    }

    fun preparePreset(preset: PromptPreset): WorkflowEntry? {
        val path = preset.workflowPath ?: return null
        container.promptPresetRepository.markUsed(preset.id)
        container.draftTransferRepository.put(
            DraftTransfer(
                workflowPath = path,
                workflowName = workflowNameFor(path),
                values = preset.values,
                randomSeed = preset.randomSeed ?: true,
            )
        )
        return workflowEntryFor(path)
    }

    fun prepareResult(item: HistoryItem): DraftTransfer? {
        if (item.wfPath.isBlank()) return null
        val transfer = DraftTransfer(
            workflowPath = item.wfPath,
            workflowName = item.wfName,
            values = item.values,
            randomSeed = item.randomSeed,
            sourceFields = item.fields,
            serverUrl = item.serverUrl.takeIf(String::isNotBlank),
        )
        container.draftTransferRepository.put(transfer)
        return transfer
    }

    private fun workflowEntryFor(path: String, fallbackName: String = workflowNameFor(path)): WorkflowEntry =
        _state.value.workflows.firstOrNull { it.path == path }
            ?: _state.value.cloudWorkflows.firstOrNull { it.path == path }
            ?: WorkflowEntry(path, fallbackName, "")

    private fun workflowNameFor(path: String): String =
        _state.value.workflows.firstOrNull { it.path == path }?.name
            ?: _state.value.cloudWorkflows.firstOrNull { it.path == path }?.name
            ?: path.substringAfterLast('/').removeSuffix(".json")

    companion object {
        private fun recentSetups(presets: List<PromptPreset>): List<PromptPreset> = presets
            .filter { it.isWorkflowPreset }
            .sortedWith(compareByDescending<PromptPreset> { it.favorite }.thenByDescending { it.lastUsedAt ?: it.createdAt })
            .take(4)

        private fun recentResults(items: List<HistoryItem>): List<HistoryItem> =
            items.sortedByDescending { it.time }
    }
}

fun workflowType(entry: WorkflowEntry): String = when {
    entry.cloud -> entry.kind.uppercase().ifBlank { "CLOUD" }
    entry.name.contains("video", true) || entry.name.contains("wan", true) -> "VIDEO"
    entry.name.contains("upscale", true) -> "UPSCALE"
    entry.name.contains("inpaint", true) -> "INPAINT"
    entry.name.contains("edit", true) -> "EDIT"
    entry.name.contains("i2i", true) || entry.name.contains("img2img", true) -> "IMG2IMG"
    entry.name.contains("t2i", true) || entry.name.contains("txt2img", true) -> "TXT2IMG"
    else -> "FLOW"
}
