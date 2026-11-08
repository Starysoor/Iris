package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole

/**
 * One intentionally chosen *project asset* for one verified provider field.
 * Every binding is scoped to the complete provider workflow path.
 */
data class StudioStageMediaBinding(
    val workflowPath: String,
    val fieldName: String,
    val assetId: String,
)

object StudioStageMediaBindings {
    fun accepts(input: StudioStagePreflight.Input, asset: ProjectAsset): Boolean {
        val kind = when (input.type) {
            "image" -> AssetKind.IMAGE
            "audio" -> AssetKind.AUDIO
            else -> return false
        }
        if (asset.kind != kind || asset.path.isBlank()) return false
        return kind != AssetKind.IMAGE ||
            input.role in setOf(ReferenceRole.AUTO, ReferenceRole.GENERIC_REFERENCE) ||
            asset.role == input.role ||
            asset.role == ReferenceRole.GENERIC_REFERENCE
    }

    /** Unknown, stale or changed fields are *rejected*, not silently discarded. */
    fun resolve(
        workflowPath: String,
        report: StudioStagePreflight.Report,
        bindings: List<StudioStageMediaBinding>,
        assets: List<ProjectAsset>,
        readable: (String) -> Boolean,
    ): Map<String, String>? {
        val values = linkedMapOf<String, String>()
        for (binding in bindings.filter { it.workflowPath == workflowPath }) {
            val field = report.inputs.firstOrNull { it.field == binding.fieldName } ?: return null
            val asset = assets.firstOrNull { it.id == binding.assetId } ?: return null
            if (!accepts(field, asset) || !readable(asset.path)) return null
            if (values.put(field.field, asset.path) != null) return null
        }
        return values
    }
}
