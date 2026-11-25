package com.comfyremote.app.data.project

import com.comfyremote.app.ConvertedWorkflow
import com.comfyremote.app.FieldKind
import com.comfyremote.app.data.cloud.CloudInputRule
import com.comfyremote.app.data.cloud.CloudWorkflowMapping
import com.comfyremote.app.data.cloud.CloudWorkflowSpec
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole

/**
 * Read-only preflight based exclusively on the *provider's full input contract*.
 *
 * An asset in the project library is a candidate, never an implicitly uploaded or
 * assigned input. These results do not replace Create's final validation.
 */
object StudioStagePreflight {
    enum class Support { CONFIGURABLE, REQUIRES_UNSUPPORTED_INPUT, UNKNOWN_CONTRACT }

    data class Input(
        val field: String,
        val type: String,
        val required: Boolean,
        val defaultProvided: Boolean,
        val role: ReferenceRole,
        /** An inventory hint, not evidence that the asset was uploaded or mapped. */
        val candidateAssets: Int,
        val min: Double?,
        val max: Double?,
        val options: List<String>,
        val acceptedMediaTypes: List<String> = emptyList(),
    )

    data class Report(
        val workflowId: String,
        val modelName: String,
        val expectedKind: String,
        val providerKind: String,
        val support: Support,
        val inputs: List<Input>,
        val unsupportedRequired: List<String>,
        val imageInputs: Int,
        val audioInputs: Int,
        val requiredImageInputs: Int,
        val requiredAudioInputs: Int,
        /** Required slots not simultaneously satisfiable from distinct *listed* files. */
        val potentialMissingMediaSlots: List<String>,
        /** The provider's own display string; pricing may be unknown. */
        val providerPrice: String?,
        val videoInputs: Int = 0,
        val requiredVideoInputs: Int = 0,
        val description: String = "",
    )

    /**
     * Maximum bipartite matching: one distinct project file per required field.
     * This is only a conservative inventory check; uploading still needs a user action.
     */
    private fun potentialMissingSlots(
        rules: List<CloudInputRule>,
        assets: List<ProjectAsset>,
    ): List<String> {
        val required = rules.filter {
            it.required && it.isMedia &&
                it.effectiveDefault.isNullOrBlank()
        }
        val uniqueFiles = assets.filter { it.path.isNotBlank() }.distinctBy { it.path }
        val candidates = required.map { field ->
            val kind = when (field.type) {
                "image" -> AssetKind.IMAGE
                "audio" -> AssetKind.AUDIO
                else -> AssetKind.VIDEO
            }
            uniqueFiles.indices.filter { index ->
                val asset = uniqueFiles[index]
                asset.kind == kind && (kind != AssetKind.IMAGE ||
                    field.referenceRole in setOf(ReferenceRole.AUTO, ReferenceRole.GENERIC_REFERENCE) ||
                    asset.role == field.referenceRole ||
                    asset.role == ReferenceRole.GENERIC_REFERENCE)
            }
        }
        val claimedBy = mutableMapOf<Int, Int>()
        fun match(slot: Int, visited: MutableSet<Int>): Boolean {
            for (asset in candidates[slot]) {
                if (!visited.add(asset)) continue
                val owner = claimedBy[asset]
                if (owner == null || match(owner, visited)) {
                    claimedBy[asset] = slot
                    return true
                }
            }
            return false
        }
        return required.indices.filterNot { match(it, mutableSetOf()) }
            .map { required[it].name }
    }

    fun inspect(
        spec: CloudWorkflowSpec,
        expectedKind: String,
        projectAssets: List<ProjectAsset>,
    ): Report {
        val inputs = spec.rules.map { rule ->
            val kind = when (rule.type) {
                "image" -> AssetKind.IMAGE
                "audio" -> AssetKind.AUDIO
                "video" -> AssetKind.VIDEO
                else -> null
            }
            val candidates = if (kind == null) 0 else projectAssets
                .filter { it.path.isNotBlank() }
                .distinctBy { it.path }
                .count { asset ->
                asset.kind == kind && (kind != AssetKind.IMAGE ||
                    rule.referenceRole == ReferenceRole.AUTO ||
                    rule.referenceRole == ReferenceRole.GENERIC_REFERENCE ||
                    asset.role == rule.referenceRole ||
                    asset.role == ReferenceRole.GENERIC_REFERENCE)
            }
            Input(
                field = rule.name,
                type = rule.type,
                required = rule.required,
                defaultProvided = !rule.effectiveDefault.isNullOrBlank(),
                role = rule.referenceRole,
                candidateAssets = candidates,
                min = rule.min,
                max = rule.max,
                options = rule.options,
                acceptedMediaTypes = rule.acceptedMediaTypes,
            )
        }
        val unsupported = listOfNotNull(spec.unavailableReason.takeIf(String::isNotBlank)) + spec.rules.filter { rule ->
            rule.required && !CloudWorkflowMapping.supports(rule)
        }.map(CloudInputRule::name)
        val state = when {
            spec.kind != expectedKind -> Support.REQUIRES_UNSUPPORTED_INPUT
            unsupported.isNotEmpty() -> Support.REQUIRES_UNSUPPORTED_INPUT
            spec.rules.isEmpty() -> Support.UNKNOWN_CONTRACT
            else -> Support.CONFIGURABLE
        }
        return Report(
            workflowId = spec.id,
            modelName = spec.name,
            expectedKind = expectedKind,
            providerKind = spec.kind,
            support = state,
            inputs = inputs,
            unsupportedRequired = unsupported,
            imageInputs = inputs.count { it.type == "image" },
            audioInputs = inputs.count { it.type == "audio" },
            requiredImageInputs = inputs.count { it.type == "image" && it.required },
            requiredAudioInputs = inputs.count { it.type == "audio" && it.required },
            potentialMissingMediaSlots = potentialMissingSlots(spec.rules, projectAssets),
            providerPrice = spec.pricing?.label,
            videoInputs = inputs.count { it.type == "video" },
            requiredVideoInputs = inputs.count { it.type == "video" && it.required },
            description = spec.description,
        )
    }

    fun inspectLocal(
        workflowId: String,
        name: String,
        expectedKind: String,
        workflow: ConvertedWorkflow,
        projectAssets: List<ProjectAsset>,
    ): Report {
        val spec = CloudWorkflowSpec(
            id = workflowId,
            name = name,
            kind = expectedKind.takeIf { it in workflow.outputKinds } ?: workflow.outputKinds.joinToString("/"),
            description = "",
            unavailableReason = workflow.blockingProblems.joinToString("\n"),
            rules = workflow.fields.map { field ->
                val type = field.kind.mediaType ?: when (field.kind) {
                    FieldKind.TEXT -> if (field.multiline) "prompt" else "string"
                    FieldKind.SEED -> "integer"
                    else -> when (field.ptype) {
                        "int" -> "integer"
                        "float" -> "number"
                        "bool" -> "boolean"
                        else -> "enum"
                    }
                }
                CloudInputRule(
                    name = field.key, type = type, required = field.required,
                    defaultValue = field.value, min = field.min?.toDoubleOrNull(),
                    max = field.max?.toDoubleOrNull(), options = field.options,
                    referenceRole = field.referenceRole, label = field.label,
                )
            },
        )
        val report = inspect(spec, expectedKind, projectAssets)
        return if (report.support == Support.UNKNOWN_CONTRACT && expectedKind in workflow.outputKinds) {
            report.copy(support = Support.CONFIGURABLE)
        } else report
    }
}
