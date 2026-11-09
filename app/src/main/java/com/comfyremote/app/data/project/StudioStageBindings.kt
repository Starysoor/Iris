package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole

/**
 * A saved, explicit association from a provider's exact input name to a project
 * asset ID. Never guesses field order or silently maps an asset to another model.
 */
object StudioStageBindings {
    fun key(workflowPath: String, field: String): String = "$workflowPath::$field"

    fun matches(input: StudioStagePreflight.Input, asset: ProjectAsset): Boolean {
        val kind = when (input.type) {
            "image" -> AssetKind.IMAGE
            "audio" -> AssetKind.AUDIO
            else -> return false
        }
        if (asset.kind != kind || asset.path.isBlank()) return false
        if (kind != AssetKind.IMAGE) return true
        return input.role == ReferenceRole.AUTO ||
            input.role == ReferenceRole.GENERIC_REFERENCE ||
            asset.role == input.role ||
            asset.role == ReferenceRole.GENERIC_REFERENCE
    }

    fun candidates(input: StudioStagePreflight.Input, assets: List<ProjectAsset>): List<ProjectAsset> =
        assets.filter { matches(input, it) }.distinctBy { it.path }

    data class Resolution(
        val fieldPaths: Map<String, String>,
        val errors: List<String>,
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    /**
     * Revalidates persisted ID bindings against the *current* provider contract
     * and project assets. A missing asset or changed model field is an error;
     * it can never silently fall back to a random first image/audio field.
     */
    fun resolve(
        project: VideoProject,
        workflowPath: String,
        report: StudioStagePreflight.Report,
    ): Resolution {
        if (report.workflowId.isBlank()) return Resolution(emptyMap(), listOf("Missing provider workflow id"))
        val prefix = "$workflowPath::"
        val saved = project.stageMediaBindings.filterKeys { it.startsWith(prefix) }
        val outputs = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()
        saved.forEach { (key, assetId) ->
            val fieldName = key.removePrefix(prefix)
            val input = report.inputs.firstOrNull { it.field == fieldName }
            val asset = project.assets.firstOrNull { it.id == assetId }
            when {
                input == null -> errors += "Input no longer exists: $fieldName"
                asset == null -> errors += "Missing project asset for: $fieldName"
                !matches(input, asset) -> errors += "Wrong media type or reference role: $fieldName"
                else -> outputs[fieldName] = asset.path
            }
        }
        return Resolution(outputs, errors)
    }
}
