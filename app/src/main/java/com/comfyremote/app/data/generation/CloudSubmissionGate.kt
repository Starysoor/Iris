package com.comfyremote.app.data.generation

import com.comfyremote.app.R
import com.comfyremote.app.data.repository.AppSettingsStore
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Confirmation payload shown before any paid cloud submission. */
data class CloudConfirmation(
    val workflowName: String,
    val providerLabel: String,
    val count: Int,
    val priceLabel: String,
    val perRunCost: Int?,
    val totalCost: Int?,
    val duration: String,
    val resolution: String,
)

/** Pure policy used by every cloud submission path. */
object CloudSubmissionPolicy {

    fun needsConfirmation(requests: List<GenerationRequest>, confirmCloudSubmit: Boolean): Boolean =
        confirmCloudSubmit && requests.any { it.provider != "comfyui" }

    fun confirmation(requests: List<GenerationRequest>, priceLabel: String?): CloudConfirmation {
        val cloud = requests.filter { it.provider != "comfyui" }
        val costs = cloud.map { it.estimatedCloudCost }
        val perRun = if (cloud.size == 1) costs.singleOrNull() else costs.distinct().singleOrNull()
        val total = if (costs.isNotEmpty() && costs.all { it != null }) costs.filterNotNull().sum() else null
        val first = cloud.firstOrNull()
        return CloudConfirmation(
            workflowName = cloud.map { it.workflowName }
                .filter { it.isNotBlank() }
                .distinct()
                .singleOrNull()
                .orEmpty(),
            providerLabel = cloud.map { it.providerLabel }
                .filter { it.isNotBlank() }
                .distinct()
                .singleOrNull()
                .orEmpty(),
            count = cloud.size,
            priceLabel = priceLabel ?: AppStrings.get(R.string.price_platform),
            perRunCost = perRun,
            totalCost = total,
            duration = first?.let { fieldValue(it, "duration", "audio_duration") }.orEmpty(),
            resolution = first?.let { fieldValue(it, "resolution") }.orEmpty(),
        )
    }

    private fun fieldValue(request: GenerationRequest, vararg inputs: String): String? =
        request.fields.firstOrNull { it.input in inputs }
            ?.let { request.fieldValues[it.key] ?: it.value }
            ?.takeIf { it.isNotBlank() }
}

/**
 * The single gate for paid cloud submissions. Every caller awaits [submit]; the app
 * shows one confirmation dialog driven by [pending]. Local submissions pass through.
 */
class CloudSubmissionGate(
    private val settings: AppSettingsStore,
    private val manager: GenerationManager,
    private val validate: (List<GenerationRequest>) -> Unit,
) {
    data class Pending(
        val confirmation: CloudConfirmation,
        internal val decision: CompletableDeferred<Boolean>,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private val lock = Mutex()

    suspend fun submit(
        requests: List<GenerationRequest>,
        localQueue: Boolean,
        priceLabel: String? = null,
    ): List<String> = lock.withLock {
        if (requests.isEmpty()) return@withLock emptyList()
        validate(requests)
        if (CloudSubmissionPolicy.needsConfirmation(requests, settings.settings.value.confirmCloudSubmit)) {
            val decision = CompletableDeferred<Boolean>()
            _pending.value = Pending(CloudSubmissionPolicy.confirmation(requests, priceLabel), decision)
            val approved = try {
                decision.await()
            } finally {
                _pending.value = null
            }
            if (!approved) return@withLock emptyList()
            validate(requests)
        }
        if (requests.size == 1) {
            listOf(manager.submit(requests.single()))
        } else {
            manager.submitBatch(requests, localQueue)
        }
    }

    fun confirm() {
        _pending.value?.decision?.complete(true)
    }

    fun dismiss() {
        _pending.value?.decision?.complete(false)
    }
}
