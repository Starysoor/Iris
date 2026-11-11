package com.comfyremote.app.data.project

/** Pure, typed comparison values; never overwrites or mutates either Take. */
object TakeComparisonFacts {
    data class ValuePair(val left: String, val right: String) {
        val differs: Boolean get() = left != right
    }

    data class Comparison(
        val provider: ValuePair,
        val model: ValuePair,
        val seed: ValuePair,
        val cost: ValuePair,
        val references: ValuePair,
        val parameters: ValuePair,
        val prompt: ValuePair,
    ) {
        val differenceCount: Int get() = listOf(
            provider, model, seed, cost, references, parameters, prompt,
        ).count { it.differs }
    }

    fun compare(a: ShotTake, b: ShotTake): Comparison {
        fun pair(f: (ShotTake) -> String) = ValuePair(f(a), f(b))
        return Comparison(
            provider = pair { it.providerId.ifBlank { "—" } },
            model = pair { it.workflowPath.ifBlank { "—" } },
            seed = pair { it.seed.orEmpty().ifBlank { "—" } },
            cost = pair { it.costEstimate?.toString() ?: "?" },
            references = pair { it.referenceAssetIds.joinToString(",").ifBlank { "—" } },
            parameters = pair { it.values.toSortedMap().toString() },
            prompt = pair { it.prompt },
        )
    }
}
