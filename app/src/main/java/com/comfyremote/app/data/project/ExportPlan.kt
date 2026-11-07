package com.comfyremote.app.data.project

/**
 * Pure export planning: converts the timeline's video track into the ordered
 * list of items an exporter should render. Deliberately free of any media3
 * types so the math is unit-testable on the JVM; [VideoExporter] maps these
 * items onto Transformer inputs.
 */
object ExportPlanBuilder {

    data class Item(
        val clipId: String,
        val sourcePath: String,
        val inMs: Long,
        val outMs: Long,
        val speed: Float,
        val volume: Float,
        val muted: Boolean,
    ) {
        /** Export duration of this item after speed. */
        val durationMs: Long get() = if (speed > 0f) ((outMs - inMs) / speed).toLong() else 0L
    }

    data class Plan(
        val items: List<Item>,
        /** Clips dropped because their media file is gone; surfaced by the UI. */
        val skippedMissing: List<String>,
        val expectedDurationMs: Long,
        /** Out-of-bounds trim windows; caller must refuse to export these clips. */
        val invalidTrims: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    /**
     * Builds the export plan from the video track. [existingPaths] is the set
     * of media paths that exist on disk (checked by the caller so the plan
     * stays free of file I/O); clips whose source vanished are skipped and
     * reported to the caller. Missing or invalid clips must block the export, not
     * produce a deceptively successful incomplete movie.
     */
    fun build(
        timeline: Timeline,
        existingPaths: Set<String>,
        sourceDurationsMs: Map<String, Long> = emptyMap(),
    ): Plan {
        val items = mutableListOf<Item>()
        val skipped = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        timeline.videoTrack.forEach { clip ->
            if (clip.sourcePath !in existingPaths) {
                skipped += clip.sourceLabel.ifBlank { clip.sourcePath.substringAfterLast('/') }
                return@forEach
            }
            val inMs = clip.sourceInMs.coerceAtLeast(0L)
            val outMs = clip.sourceOutMs.coerceAtLeast(inMs + 1)
            val durationMs = sourceDurationsMs[clip.sourcePath]
            if (durationMs != null && (inMs >= durationMs || outMs > durationMs)) {
                invalid += clip.sourceLabel.ifBlank { clip.sourcePath.substringAfterLast('/') }
                return@forEach
            }
            items += Item(
                clipId = clip.id,
                sourcePath = clip.sourcePath,
                inMs = inMs,
                outMs = outMs,
                speed = clip.speed.coerceIn(0.25f, 4f),
                volume = if (clip.muted) 0f else clip.volume.coerceIn(0f, 2f),
                muted = clip.muted,
            )
        }
        return Plan(
            items = items,
            skippedMissing = skipped,
            expectedDurationMs = items.sumOf { it.durationMs },
            invalidTrims = invalid,
        )
    }
}
