package com.comfyremote.app

import android.app.Application
import android.content.SharedPreferences
import com.comfyremote.app.data.cloud.AutoDlModelRepository
import com.comfyremote.app.data.cloud.AutoDlRepository
import com.comfyremote.app.data.cloud.AutoDlSettingsStore
import com.comfyremote.app.data.cloud.CloudInputStore
import com.comfyremote.app.data.cloud.CloudProviderSettingsStore
import com.comfyremote.app.data.cloud.CloudSpendStore
import com.comfyremote.app.data.cloud.CustomOpenAiRepository
import com.comfyremote.app.data.cloud.CustomTemplateRepository
import com.comfyremote.app.data.cloud.GeminiImageRepository
import com.comfyremote.app.data.cloud.OpenAiCompatibleImageRepository
import com.comfyremote.app.data.cloud.ReplicateRepository
import com.comfyremote.app.data.cloud.SiliconFlowRepository
import com.comfyremote.app.data.discovery.LanServerDiscovery
import com.comfyremote.app.data.discovery.TailscaleDeviceDirectory
import com.comfyremote.app.data.discovery.TailscalePeerSettingsStore
import com.comfyremote.app.data.generation.CloudSubmissionGate
import com.comfyremote.app.data.generation.GenerationManager
import com.comfyremote.app.data.generation.GenerationTaskStore
import com.comfyremote.app.data.project.ProjectStore
import com.comfyremote.app.data.media.GalleryExporter
import com.comfyremote.app.data.media.IncomingMediaRepository
import com.comfyremote.app.data.repository.AiRepository
import com.comfyremote.app.data.repository.AiSettingsStore
import com.comfyremote.app.data.repository.AppSettingsStore
import com.comfyremote.app.data.repository.AiSkillBindingStore
import com.comfyremote.app.data.repository.AiSkillStore
import com.comfyremote.app.data.repository.DraftTransferRepository
import com.comfyremote.app.data.repository.HistoryRepository
import com.comfyremote.app.data.repository.NavigationRequestRepository
import com.comfyremote.app.data.repository.ObjectInfoCache
import com.comfyremote.app.data.repository.PromptPresetRepository
import com.comfyremote.app.data.repository.ServerDiscoveryRepository
import com.comfyremote.app.data.repository.ServerHealthRepository
import com.comfyremote.app.data.repository.ServerRepository
import com.comfyremote.app.data.repository.ServerSessionRepository
import com.comfyremote.app.data.repository.UpdateRepository
import com.comfyremote.app.data.repository.UpdateSettingsStore
import com.comfyremote.app.data.repository.WorkflowDraftStore
import com.comfyremote.app.data.repository.WorkflowRepository
import com.comfyremote.app.data.repository.WorkflowUsageRepository
import com.comfyremote.app.data.security.SecretStore
import com.comfyremote.app.data.storyboard.StoryboardStore

/** Lightweight application-scoped dependency container. */
class AppContainer(val app: Application) {
    private val prefs: SharedPreferences = app.getSharedPreferences("comfyremote", Application.MODE_PRIVATE)

    // Migrates the legacy storyboard index to the Studio project index before
    // any store is constructed; the legacy file stays frozen as rollback anchor.
    private val projectMigration =
        com.comfyremote.app.data.project.ProjectMigration.migrateIfNeeded(app.filesDir)

    val appSettingsStore = AppSettingsStore(prefs)
    private val secretStore = SecretStore(prefs)

    val autoDlSettingsStore = AutoDlSettingsStore(secretStore)
    val autoDlRepository = AutoDlRepository(autoDlSettingsStore)
    val cloudProviderSettingsStore = CloudProviderSettingsStore(prefs, secretStore)
    val cloudTaskStore = com.comfyremote.app.data.cloud.CloudProviderTaskStore(app)
    val openAiImageRepository = OpenAiCompatibleImageRepository(cloudProviderSettingsStore, cloudTaskStore)
    val autoDlModelRepository = AutoDlModelRepository(cloudProviderSettingsStore, cloudTaskStore)
    val geminiImageRepository = GeminiImageRepository(cloudProviderSettingsStore, cloudTaskStore)
    val replicateRepository = ReplicateRepository(cloudProviderSettingsStore, cloudTaskStore)
    val siliconFlowRepository = SiliconFlowRepository(cloudProviderSettingsStore, cloudTaskStore)
    val customOpenAiRepository = CustomOpenAiRepository(cloudProviderSettingsStore, cloudTaskStore)
    val customTemplateRepository = CustomTemplateRepository(cloudProviderSettingsStore, cloudTaskStore)
    val cloudProviders = com.comfyremote.app.data.cloud.CloudProviderRegistry(
        providers = listOf(
            autoDlRepository,
            autoDlModelRepository,
            openAiImageRepository,
            geminiImageRepository,
            replicateRepository,
            siliconFlowRepository,
            customOpenAiRepository,
            customTemplateRepository,
        ),
        // An explicit switch wins; otherwise a provider is visible only after it
        // has credentials. This keeps unused providers out of the Cloud tab.
        isProviderEnabled = { provider ->
            cloudProviderSettingsStore.providerEnabledOverride(provider.id) ?: provider.configured()
        },
    )
    val cloudInputStore = CloudInputStore(app)
    val cloudSpendStore = CloudSpendStore(prefs)
    val storyboardStore = StoryboardStore(app)

    // Studio project index; holds the migrated legacy projects from here on.
    // storyboardStore stays readable as the frozen pre-migration snapshot.
    val projectStore = ProjectStore(app)
    val projectAssetStore = com.comfyremote.app.data.project.ProjectAssetStore(app)

    val aiSettingsStore = AiSettingsStore(prefs, secretStore)
    val aiSkillStore = AiSkillStore(prefs)
    val aiSkillBindingStore = AiSkillBindingStore(prefs)
    val aiRepository = AiRepository(aiSettingsStore)

    val serverRepository = ServerRepository(prefs)
    val serverSession = ServerSessionRepository()
    val tailscalePeerSettings = TailscalePeerSettingsStore(secretStore)
    val serverDiscoveryRepository = ServerDiscoveryRepository(
        LanServerDiscovery(
            knownUrls = {
                serverRepository.load().map { it.url } + listOfNotNull(serverRepository.savedLastUrl())
            },
            tailscaleToken = { tailscalePeerSettings.token() },
            tailscaleDirectory = TailscaleDeviceDirectory(),
        )
    )
    val serverHealthRepository = ServerHealthRepository()

    val updateSettingsStore = UpdateSettingsStore(prefs, secretStore)
    val updateRepository = UpdateRepository(app)

    val promptPresetRepository = PromptPresetRepository(prefs)
    val draftTransferRepository = DraftTransferRepository()
    val workflowUsageRepository = WorkflowUsageRepository(prefs)
    val workflowDraftStore = WorkflowDraftStore(prefs)

    val historyRepository = HistoryRepository(
        HistoryStore(app) {
            storyboardStore.referencedMediaPaths() + projectStore.referencedMediaPaths()
        }
    )
    /** Lives at application scope; project exports continue after UI navigation. */
    val projectExportController by lazy {
        com.comfyremote.app.data.project.ProjectExportController(this)
    }
    val objectInfoCache = ObjectInfoCache(app.cacheDir)
    val workflowRepository = WorkflowRepository(serverSession, objectInfoCache)

    val galleryExporter = GalleryExporter(app)
    val incomingMedia = IncomingMediaRepository()
    val navigationRequests = NavigationRequestRepository()

    val generationTaskStore = GenerationTaskStore(app)
    val generationManager = GenerationManager(this, app)
    val cloudSubmissionGate = CloudSubmissionGate(appSettingsStore, generationManager)

    /** Clears unreferenced cloud inputs; queued tasks, drafts and history keep their files. */
    fun clearCloudInputCache() {
        val protected = buildSet {
            fun addValues(values: Map<String, String>) {
                values.values.filter { it.startsWith(CloudInputStore.PREFIX) }.forEach(::add)
            }
            historyRepository.load().forEach { addValues(it.values) }
            promptPresetRepository.load().forEach { addValues(it.values) }
            workflowDraftStore.all().forEach { addValues(it.values) }
            draftTransferRepository.peek()?.let { addValues(it.values) }
            generationManager.tasks.value.forEach { task ->
                addValues(task.request.fieldValues)
                addValues(task.seedValues)
            }
        }
        cloudInputStore.clearAll(protected)
    }
}
