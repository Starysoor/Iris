package com.comfyremote.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject

/** The exact service ID is retained for every request and stored draft. */
internal data class CloudModelMetadata(
    val id: String,
    val displayName: String = "",
    val description: String = "",
    val kind: String = "",
    val version: String = "",
) {
    fun summary(
        kind: String,
        fallbackName: String = id,
        fallbackDescription: String = "",
        unavailableReason: String = "",
    ): CloudWorkflowSummary = CloudWorkflowSummary(
        id = id,
        name = displayName.ifBlank { fallbackName },
        description = description.ifBlank { fallbackDescription },
        kind = kind,
        priceType = "official_api",
        version = version,
        unavailableReason = unavailableReason,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", displayName)
        .put("description", description)
        .put("kind", kind)
        .put("version", version)

    companion object {
        fun fromJson(value: JSONObject): CloudModelMetadata? {
            val id = value.optString("id").takeIf(String::isNotBlank) ?: return null
            return CloudModelMetadata(id, value.optString("name"), value.optString("description"),
                value.optString("kind"), value.optString("version"))
        }
    }
}

internal fun CloudWorkflowSpec.withMetadata(model: CloudModelMetadata): CloudWorkflowSpec = copy(
    name = model.displayName.ifBlank { name },
    description = model.description.ifBlank { description },
    version = model.version.ifBlank { version },
)

internal fun providerModelDirectory(data: JSONArray?, idKey: String = "id"): List<CloudModelMetadata> =
    if (data == null) emptyList() else (0 until data.length()).mapNotNull { index ->
        val row = data.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString(idKey).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        providerModelMetadata(id, row)
    }

internal fun providerModelMetadata(id: String, row: JSONObject): CloudModelMetadata {
    fun firstString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            (row.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
    val modalities = row.optJSONArray("output_modalities")
        ?: row.optJSONArray("outputModalities")
        ?: row.optJSONObject("architecture")?.optJSONArray("output_modalities")
    val kind = descriptorKind(firstString("output_type", "outputType", "kind", "modality", "type"))
        .ifBlank {
            val types = if (modalities == null) emptyList()
            else (0 until modalities.length()).map { descriptorKind(modalities.optString(it)) }
            listOf("video", "image", "audio", "text").firstOrNull { it in types }.orEmpty()
        }
    return CloudModelMetadata(
        id = id,
        displayName = firstString("display_name", "displayName", "title", "label", "name"),
        description = firstString("description", "summary"),
        kind = kind,
        version = firstString("version", "modelVersion"),
    )
}

private fun descriptorKind(value: String): String = when (value.lowercase().replace('_', '-')) {
    "video", "text-to-video", "image-to-video", "video-to-video", "video-generation" -> "video"
    "image", "text-to-image", "image-to-image", "image-generation" -> "image"
    "audio", "speech", "text-to-speech", "text-to-audio", "audio-generation" -> "audio"
    "text", "chat", "embedding", "rerank", "llm" -> "text"
    else -> ""
}

/** Catalogue updates preserve explicit selections and remove retired discovered entries. */
internal suspend fun modelDirectory(
    settings: CloudProviderSettingsStore,
    account: CloudProviderAccount,
    force: Boolean,
    discover: suspend () -> List<CloudModelMetadata>,
): List<CloudModelMetadata> {
    if (settings.hasSavedModels(account.providerId) && (!force || account.models.isEmpty())) {
        return settings.selectedModelMetadata(account)
    }
    val found = discover().distinctBy { it.id }
    return settings.cacheModelDirectory(account, found, updateSelection = true)
}

internal suspend fun discoverModelIds(
    settings: CloudProviderSettingsStore,
    account: CloudProviderAccount,
    discover: suspend () -> List<CloudModelMetadata>,
): List<String> {
    val found = discover().distinctBy { it.id }
    settings.cacheModelDirectory(account, found, updateSelection = false)
    return found.map { it.id }
}
