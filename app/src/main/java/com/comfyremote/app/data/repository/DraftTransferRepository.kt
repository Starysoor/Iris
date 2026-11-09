package com.comfyremote.app.data.repository

import com.comfyremote.app.PromptField

/** One-shot handoff used when History/Task Center reopens a workflow for editing. */
data class DraftTransfer(
    val workflowPath: String,
    val workflowName: String,
    val values: Map<String, String>,
    val randomSeed: Boolean,
    /** Source field metadata for semantic cross-workflow remapping; empty for legacy records. */
    val sourceFields: List<PromptField> = emptyList(),
    /** Original server for cross-server reuse/continuation. Blank legacy records keep the current server. */
    val serverUrl: String? = null,
    /** Local generated image to upload into the first compatible image input on reopen. */
    val referenceLocalPath: String? = null,
    /** Set when this draft was opened from a storyboard shot. */
    val storyboardProjectId: String? = null,
    val storyboardShotId: String? = null,
    /** True when the user chose "pick a workflow" instead of returning to the original one. */
    val reuseAcrossWorkflows: Boolean = false,
    /** Multiple local reference images for consecutive image inputs. */
    val referenceLocalPaths: List<String> = emptyList(),
    /** Exact input name -> explicit project media path, never inferred by position. */
    val boundMediaFieldPaths: Map<String, String> = emptyMap(),
)

class DraftTransferRepository {
    private val lock = Any()
    private var pending: DraftTransfer? = null

    fun put(transfer: DraftTransfer) = synchronized(lock) { pending = transfer }

    fun peek(): DraftTransfer? = synchronized(lock) { pending }

    fun consume(workflowPath: String): DraftTransfer? = synchronized(lock) {
        pending?.takeIf {
            it.workflowPath == workflowPath || it.reuseAcrossWorkflows
        }?.also { pending = null }
    }

    fun clear() = synchronized(lock) { pending = null }
}
