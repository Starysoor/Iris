package com.comfyremote.app.data.generation

import android.graphics.Bitmap
import com.comfyremote.app.ComfyException
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import com.comfyremote.app.PromptField
import org.json.JSONObject
import java.util.UUID

/** Immutable snapshot of what the user asked for. Editing after submit cannot affect it. */
data class GenerationRequest(
    val id: String = UUID.randomUUID().toString(),
    /** "comfyui" for a direct server, "autodl" for AutoDL's hosted workflow API. */
    val provider: String = "comfyui",
    /** Human-readable provider name, so errors and billing notes never name the wrong vendor. */
    val providerLabel: String = "",
    val serverUrl: String,
    val workflowPath: String,
    val workflowName: String,
    val fieldValues: Map<String, String>,
    val randomSeed: Boolean,
    val nodeTitles: Map<String, String>,
    val fields: List<PromptField>,
    val convertedPrompt: JSONObject,
    val createdAt: Long = System.currentTimeMillis(),
    /** Best-effort platform price estimate captured at submit time. */
    val estimatedCloudCost: Int? = null,
    /** Set when this task belongs to a storyboard shot. */
    val storyboardProjectId: String? = null,
    val storyboardShotId: String? = null,
    /** Preparation stages stay associated with the shot and produce their own media. */
    val studioStageKind: String? = null,
) {
    val isPrimaryStudioShot: Boolean
        get() = storyboardShotId != null && (studioStageKind == null || studioStageKind == "video")
}

enum class GenerationPhase { PREPARING, QUEUEING, QUEUED, RUNNING, CANCELLING, CANCELLED, SUCCEEDED, FAILED }

data class ResultImage(
    val id: String,
    val localPath: String,
    val width: Int? = null,
    val height: Int? = null,
)

enum class ErrorKind { CONNECTION, PROMPT_VALIDATION, EXECUTION, TIMEOUT, DOWNLOAD, CANCELLED, UNKNOWN }

data class GenerationError(
    val kind: ErrorKind,
    val message: String,
    val nodeId: String? = null,
    val nodeType: String? = null,
    val nodeTitle: String? = null,
)

class GenerationTimeoutException(message: String) : ComfyException(message)

enum class CancelAction { LOCAL_PENDING, CANCEL_JOB, DELETE_QUEUE, INTERRUPT_SERVER, NOT_CANCELLABLE }

/** Local QUEUED/RUNNING cancels need an explicit server action; cloud cancels can only stop tracking. */
fun cancelActionFor(phase: GenerationPhase, cloud: Boolean, localPending: Boolean): CancelAction = when {
    localPending -> CancelAction.LOCAL_PENDING
    phase == GenerationPhase.PREPARING || phase == GenerationPhase.QUEUEING -> CancelAction.CANCEL_JOB
    phase == GenerationPhase.QUEUED -> if (cloud) CancelAction.CANCEL_JOB else CancelAction.DELETE_QUEUE
    phase == GenerationPhase.RUNNING -> if (cloud) CancelAction.CANCEL_JOB else CancelAction.INTERRUPT_SERVER
    else -> CancelAction.NOT_CANCELLABLE
}

data class CancelOutcome(val taskId: String, val cancelled: Boolean, val error: String? = null)

/** Atomically applicable only while a task is still pending locally. */
internal fun GenerationTask.cancelIfStillLocalPending(now: Long): GenerationTask? =
    if (!localPending || !isActive) null else copy(
        localPending = false,
        phase = GenerationPhase.CANCELLED,
        completedAt = now,
        recoveryNote = "Removed from local queue",
        queuePosition = null,
        progress = null,
        preview = null,
    )


data class GenerationTask(
    val taskId: String,
    val request: GenerationRequest,
    val phase: GenerationPhase = GenerationPhase.PREPARING,
    val promptId: String? = null,
    val clientId: String? = null,
    val queueRemaining: Int? = null,
    /** 1-based position among pending prompts when the server can report it. */
    val queuePosition: Int? = null,
    val nodeId: String? = null,
    val nodeTitle: String? = null,
    val progress: Float? = null,
    val preview: Bitmap? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val results: List<ResultImage> = emptyList(),
    val error: GenerationError? = null,
    val seedInfo: String = "",
    /** Actual seed values used by the submitted prompt, persisted before completion for recovery. */
    val seedValues: Map<String, String> = emptyMap(),
    /** True when this runtime task was restored from disk after process recreation. */
    val recovered: Boolean = false,
    val recoveryNote: String? = null,
    /** True while the request is intentionally waiting in the app-side queue and has not reached ComfyUI yet. */
    val localPending: Boolean = false,
    /** True once this task produced a cloud provider task id and was counted in the local spend ledger. */
    val spendRecorded: Boolean = false,
    /** Optional batch identity shared by variation jobs created together. */
    val batchId: String? = null,
    /** 1-based position within a batch. */
    val batchIndex: Int? = null,
    val batchSize: Int? = null,
) {
    val isActive: Boolean
        get() = phase == GenerationPhase.PREPARING || phase == GenerationPhase.QUEUEING ||
            phase == GenerationPhase.QUEUED || phase == GenerationPhase.RUNNING || phase == GenerationPhase.CANCELLING

    val isTerminal: Boolean
        get() = phase == GenerationPhase.CANCELLED || phase == GenerationPhase.SUCCEEDED || phase == GenerationPhase.FAILED

    val isRemoteQueued: Boolean
        get() = !localPending && (phase == GenerationPhase.QUEUEING || phase == GenerationPhase.QUEUED)

    /** A cloud job is billable only after the provider returned a remote task id. */
    val shouldRecordCloudSpend: Boolean
        get() = request.provider != "comfyui" && !spendRecorded && !promptId.isNullOrBlank()

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        val start = startedAt ?: createdAt
        val end = completedAt ?: now
        return (end - start).coerceAtLeast(0L)
    }
}

fun GenerationTask.label(): String {
    if (localPending) return AppStrings.get(R.string.phase_local_pending)
    return when (phase) {
        GenerationPhase.PREPARING -> AppStrings.get(R.string.phase_preparing)
        GenerationPhase.QUEUEING, GenerationPhase.QUEUED -> AppStrings.get(R.string.phase_queued)
        GenerationPhase.RUNNING -> AppStrings.get(R.string.phase_running)
        GenerationPhase.CANCELLING -> AppStrings.get(R.string.phase_cancelling)
        GenerationPhase.CANCELLED -> AppStrings.get(R.string.phase_cancelled)
        GenerationPhase.SUCCEEDED -> AppStrings.get(R.string.phase_succeeded)
        GenerationPhase.FAILED -> AppStrings.get(R.string.phase_failed)
    }
}

fun GenerationError.uiLabel(): String = when (kind) {
    ErrorKind.EXECUTION -> AppStrings.get(R.string.error_execution)
    ErrorKind.CONNECTION -> AppStrings.get(R.string.error_connection)
    ErrorKind.PROMPT_VALIDATION -> AppStrings.get(R.string.error_validation)
    ErrorKind.TIMEOUT -> AppStrings.get(R.string.error_timeout)
    ErrorKind.DOWNLOAD -> AppStrings.get(R.string.error_download)
    ErrorKind.CANCELLED -> AppStrings.get(R.string.error_cancelled)
    ErrorKind.UNKNOWN -> AppStrings.get(R.string.error_unknown)
}
