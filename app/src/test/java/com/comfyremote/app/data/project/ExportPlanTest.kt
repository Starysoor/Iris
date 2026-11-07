package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanTest {
    private val timeline = Timeline(
        id = "tl-1",
        videoTrack = listOf(
            TimelineClip(id = "tlc:a", sourcePath = "/m/a.mp4", sourceInMs = 500, sourceOutMs = 2500, speed = 2f),
            TimelineClip(id = "tlc:b", sourcePath = "/m/missing.mp4", sourceOutMs = 1000, sourceLabel = "丢失"),
            TimelineClip(id = "tlc:c", sourcePath = "/m/c.mp4", sourceOutMs = 4000, muted = true),
            TimelineClip(id = "tlc:d", sourcePath = "/m/d.mp4", sourceInMs = 1000, sourceOutMs = 2000, volume = 0.5f),
        ),
        audioTrack = listOf(TimelineClip(id = "tlc:m", sourcePath = "/m/bgm.mp3", sourceOutMs = 60_000L)),
    )

    private val existing = setOf("/m/a.mp4", "/m/c.mp4", "/m/d.mp4", "/m/bgm.mp3")

    @Test
    fun buildKeepsOnlyExistingVideoClipsInOrder() {
        val plan = ExportPlanBuilder.build(timeline, existing)

        assertEquals(listOf("tlc:a", "tlc:c", "tlc:d"), plan.items.map { it.clipId })
        assertEquals(listOf("丢失"), plan.skippedMissing)
        assertTrue(plan.audioTrackIsIgnored())
    }

    @Test
    fun expectedDurationDividesBySpeed() {
        val plan = ExportPlanBuilder.build(timeline, existing)

        // a: 2000/2 = 1000, c: 4000, d: 1000 → 6000ms total.
        assertEquals(6000L, plan.expectedDurationMs)
    }

    @Test
    fun mutedClipsExportAtZeroVolumeAndTrimIsClamped() {
        val plan = ExportPlanBuilder.build(timeline, existing)

        val muted = plan.items.first { it.clipId == "tlc:c" }
        assertEquals(0f, muted.volume)
        assertTrue(muted.muted)

        val gain = plan.items.first { it.clipId == "tlc:d" }
        assertEquals(0.5f, gain.volume)

        // An inverted or zero trim in the source timeline is clamped to in+1.
        val inverted = ExportPlanBuilder.build(
            Timeline(videoTrack = listOf(TimelineClip(id = "x", sourcePath = "/m/a.mp4", sourceInMs = 800, sourceOutMs = 200))),
            existing,
        )
        val item = inverted.items.single()
        assertEquals(801L, item.outMs)
        assertEquals(1L, item.durationMs)
    }


    @Test
    fun invalidTrimIsReportedWhenActualMediaDurationIsKnown() {
        val source = "/m/a.mp4"
        val plan = ExportPlanBuilder.build(
            Timeline(videoTrack = listOf(
                TimelineClip(id = "valid", sourcePath = source, sourceInMs = 0, sourceOutMs = 1000),
                TimelineClip(id = "over", sourcePath = source, sourceInMs = 500, sourceOutMs = 2000, sourceLabel = "too-long"),
            )),
            existingPaths = setOf(source),
            sourceDurationsMs = mapOf(source to 1500L),
        )
        assertEquals(listOf("valid"), plan.items.map { it.clipId })
        assertEquals(listOf("too-long"), plan.invalidTrims)
    }

    @Test
    fun emptyTimelineProducesAnEmptyPlan() {
        val plan = ExportPlanBuilder.build(Timeline(), existing)

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.expectedDurationMs)
    }
}

private fun ExportPlanBuilder.Plan.audioTrackIsIgnored(): Boolean = items.none { it.sourcePath == "/m/bgm.mp3" }
