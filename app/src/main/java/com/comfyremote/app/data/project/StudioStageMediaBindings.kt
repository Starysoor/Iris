package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.media.MediaTypeResolver

/**
 * One intentionally chosen *project asset* for one verified provider field.
 * Every binding is scoped to the complete provider workflow path.
 */
data class StudioStageMediaBinding(
    val workflowPath: String,
    val fieldName: String,
    val assetId: String,
    val shotId: String? = null,
)

object StudioStageMediaBindings {
    fun effective(
        bindings: List<StudioStageMediaBinding>,
        workflowPath: String,
        shotId: String?,
    ): List<StudioStageMediaBinding> {
        val matching = bindings.filter { it.workflowPath == workflowPath }
        val defaults = matching.filter { it.shotId == null }
        if (shotId == null) return defaults
        val overrides = matching.filter { it.shotId == shotId }
        val overriddenFields = overrides.mapTo(hashSetOf(), StudioStageMediaBinding::fieldName)
        return defaults.filterNot { it.fieldName in overriddenFields } + overrides
    }

    fun accepts(input: StudioStagePreflight.Input, asset: ProjectAsset): Boolean {
        val kind = when (input.type) {
            "image" -> AssetKind.IMAGE
            "audio" -> AssetKind.AUDIO
            "video" -> AssetKind.VIDEO
            else -> return false
        }
        if (asset.kind != kind || asset.path.isBlank()) return false
        val extension = asset.path.substringAfterLast('.', "").lowercase()
        val supportedExtensions = when (kind) {
            AssetKind.IMAGE -> setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif")
            AssetKind.AUDIO -> setOf("wav", "mp3", "m4a", "ogg", "flac", "aac")
            AssetKind.VIDEO -> setOf("mp4", "webm", "mov", "mkv", "m4v", "avi", "3gp")
            else -> return false
        }
        if (extension !in supportedExtensions) return false
        if (input.acceptedMediaTypes.isNotEmpty()) {
            val fileMime = MediaTypeResolver.mimeType(asset.path)
            val supported = input.acceptedMediaTypes.any { mediaType ->
                mediaType.equals(fileMime, true) || mediaType.equals("${input.type}/*", true) ||
                    (kind == AssetKind.IMAGE && mediaType.equals("image/jpeg", true))
            }
            if (!supported) return false
        }
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
        val scoped = bindings.filter { it.workflowPath == workflowPath }
        val assetsById = assets.associateBy(ProjectAsset::id)
        for (binding in scoped) {
            val field = report.inputs.firstOrNull { it.field == binding.fieldName } ?: return null
            val asset = assetsById[binding.assetId] ?: return null
            if (!accepts(field, asset)) return null
        }
        return localPaths(scoped, assets, readable)
    }

    fun localPaths(
        bindings: List<StudioStageMediaBinding>,
        assets: List<ProjectAsset>,
        readable: (String) -> Boolean,
    ): Map<String, String>? {
        val assetsById = assets.associateBy(ProjectAsset::id)
        val values = linkedMapOf<String, String>()
        for (binding in bindings) {
            val path = assetsById[binding.assetId]?.path ?: return null
            if (!readable(path) || values.put(binding.fieldName, path) != null) return null
        }
        return values
    }
}
