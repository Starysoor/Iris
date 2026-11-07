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
    fun buildRetainsVideoOrderAndAddsParallelAudio() {
        val plan = ExportPlanBuilder.build(timeline, existing)

        assertEquals(listOf("tlc:a", "tlc:c", "tlc:d"), plan.items.map { it.clipId })
        assertEquals(listOf("丢失"), plan.skippedMissing)
        assertEquals(listOf("tlc:m"), plan.audioItems.map { it.clipId })
        // Audio begins at t=0 alongside the video and is capped at cut length.
        assertEquals(6000L, plan.audioItems.single().outMs)
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
    fun missingAudioFailsClosedInsteadOfSilentlyExportingAVideoOnlyMovie() {
        val plan = ExportPlanBuilder.build(timeline, existing - "/m/bgm.mp3")
        assertTrue(plan.skippedMissing.contains("bgm.mp3"))
        assertTrue(plan.audioItems.isEmpty())
    }

    @Test
    fun audioTrimPastKnownDurationIsReported() {
        val t = Timeline(
            videoTrack = listOf(TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 1000)),
            audioTrack = listOf(TimelineClip(sourcePath = "/m/bgm.mp3", sourceOutMs = 3000, sourceLabel = "broken")),
        )
        val p = ExportPlanBuilder.build(
            t, existingPaths = existing,
            sourceDurationsMs = mapOf("/m/a.mp4" to 1000, "/m/bgm.mp3" to 2000),
        )
        assertEquals(listOf("broken"), p.invalidTrims)
        assertTrue(p.audioItems.isEmpty())
    }

    @Test
    fun audioItemsHaveTheirOwnSpeedVolumeMuteAndSequentialOrder() {
        val t = Timeline(
            videoTrack = listOf(TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 5000)),
            audioTrack = listOf(
                TimelineClip(id = "sfx", sourcePath = "/m/bgm.mp3", sourceInMs = 1000, sourceOutMs = 3000, speed = 2f, volume = 0.4f),
                TimelineClip(id = "voice", sourcePath = "/m/bgm.mp3", sourceOutMs = 5000, muted = true),
            ),
        )
        val p = ExportPlanBuilder.build(t, existing)
        assertEquals(listOf("sfx", "voice"), p.audioItems.map { it.clipId })
        assertEquals(1000L, p.audioItems[0].durationMs)
        assertEquals(0.4f, p.audioItems[0].volume)
        assertEquals(0f, p.audioItems[1].volume)
        assertTrue(p.audioItems[1].muted)
        assertEquals(4000L, p.audioItems[1].durationMs)
        assertEquals(5000L, p.expectedDurationMs)
    }

    @Test
    fun aProjectWithoutAudioStillHasNoOverlay() {
        val p = ExportPlanBuilder.build(
            Timeline(videoTrack = listOf(TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 1000))),
            existing,
        )
        assertTrue(p.audioItems.isEmpty())
    }

    @Test
    fun emptyTimelineProducesAnEmptyPlan() {
        val plan = ExportPlanBuilder.build(Timeline(), existing)

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.expectedDurationMs)
    }
}

