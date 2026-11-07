package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = ProjectStore(File(temp.root, "projects.json"))

    @Test
    fun timelineRoundTripsWithClipsAndDefaults() {
        val clip = TimelineClip(
            id = "tlc:c1",
            sourcePath = "/m/take-a.mp4",
            sourceLabel = "镜头 1",
            sourceInMs = 500L,
            sourceOutMs = 2500L,
            speed = 2f,
            volume = 0.5f,
            muted = false,
        )
        val timeline = Timeline(
            id = "tl-1",
            videoTrack = listOf(clip),
            audioTrack = listOf(TimelineClip(id = "tlc:a1", sourcePath = "/m/bgm.mp3", sourceOutMs = 10_000L)),
        )
        val project = VideoProject(id = "p1", title = "T", workflowId = "m", timeline = timeline)

        val restored = ProjectCodec.decode(ProjectCodec.encode(listOf(project))).single()

        assertEquals(timeline, restored.timeline)
        assertEquals(1000L, clip.durationMs) // (2500-500)/2
        assertEquals(10_000L, restored.timeline!!.totalDurationMs) // overlapping tracks, use longest
        assertEquals(1000L, restored.timeline!!.videoDurationMs)
        assertEquals(10_000L, restored.timeline!!.audioDurationMs)
    }

    @Test
    fun legacyProjectsDecodeWithoutTimeline() {
        val raw = """{"version":2,"projects":[{"id":"p1","title":"Old","workflowId":"m"}]}"""

        assertNull(ProjectCodec.decode(raw).single().timeline)
    }

    @Test
    fun damagedClipRowsAreDroppedOrClamped() {
        val raw = """{"version":2,"projects":[{"id":"p1","title":"P","workflowId":"m",
          "timeline":{"id":"tl-1",
            "videoTrack":[
              {"id":"tlc:ok","sourcePath":"/m/a.mp4","sourceInMs":100,"sourceOutMs":900},
              {"sourcePath":""},
              {"id":"tlc:inverted","sourcePath":"/m/b.mp4","sourceInMs":800,"sourceOutMs":200}
            ],
            "audioTrack":["not-an-object"]
          }}]}"""

        val timeline = ProjectCodec.decode(raw).single().timeline!!

        assertEquals(2, timeline.videoTrack.size)
        assertTrue(timeline.audioTrack.isEmpty())
        val inverted = timeline.videoTrack.first { it.id == "tlc:inverted" }
        assertEquals(801L, inverted.sourceOutMs) // clamped to in+1
        assertEquals(1L, inverted.durationMs)
        assertTrue(timeline.videoTrack.first().id.startsWith("tlc:"))
    }

    @Test
    fun ensureTimelineIsIdempotentAndTrackOpsRespectOrder() {
        val store = store()
        val project = store.create("Studio", "wf")!!

        assertTrue(store.ensureTimeline(project.id))
        assertFalse(store.ensureTimeline(project.id))
        val clipA = TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 1000L)
        val clipB = TimelineClip(sourcePath = "/m/b.mp4", sourceOutMs = 1000L)
        assertTrue(store.addClip(project.id, TrackKind.VIDEO, clipA))
        assertTrue(store.addClip(project.id, TrackKind.VIDEO, clipB))
        assertTrue(store.addClip(project.id, TrackKind.AUDIO, TimelineClip(sourcePath = "/m/bgm.mp3", sourceOutMs = 5000L)))

        assertEquals(listOf(clipA.id, clipB.id), store.project(project.id)!!.timeline!!.videoTrack.map { it.id })

        assertTrue(store.moveClip(project.id, TrackKind.VIDEO, clipB.id, -1))
        assertEquals(listOf(clipB.id, clipA.id), store.project(project.id)!!.timeline!!.videoTrack.map { it.id })

        // Out-of-range moves are no-ops.
        assertFalse(store.moveClip(project.id, TrackKind.VIDEO, clipB.id, -5))
        // Unknown clips cannot be removed or updated.
        assertFalse(store.removeClip(project.id, TrackKind.VIDEO, "tlc:missing"))
        assertFalse(store.updateClip(project.id, TrackKind.VIDEO, "tlc:missing") { it.copy(muted = true) })

        assertTrue(store.removeClip(project.id, TrackKind.VIDEO, clipA.id))
        assertEquals(listOf(clipB.id), store.project(project.id)!!.timeline!!.videoTrack.map { it.id })
    }

    @Test
    fun clipsUpdateTrimSpeedAndMute() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        store.ensureTimeline(project.id)
        val clip = TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 4000L)
        store.addClip(project.id, TrackKind.VIDEO, clip)

        assertTrue(
            store.updateClip(project.id, TrackKind.VIDEO, clip.id) {
                it.copy(sourceInMs = 1000L, sourceOutMs = 3000L, speed = 2f, volume = 0.25f, muted = true)
            },
        )

        val updated = store.project(project.id)!!.timeline!!.videoTrack.single()
        assertEquals(1000L, updated.sourceInMs)
        assertEquals(3000L, updated.sourceOutMs)
        assertEquals(2f, updated.speed)
        assertEquals(0.25f, updated.volume)
        assertTrue(updated.muted)
        assertEquals(1000L, updated.durationMs)
    }

    @Test
    fun timelineReferencesKeepMediaAliveWhenTakesAreDeleted() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        val media = temp.newFile("result.mp4").apply { writeText("result") }
        store.attachTask(project.id, shot.id, "task-1")
        store.markDone(project.id, shot.id, media.absolutePath, null)
        val takeId = store.project(project.id)!!.shots.single().selectedTakeId!!
        val clip = TimelineClip(sourcePath = media.absolutePath, sourceOutMs = 1000L)
        store.ensureTimeline(project.id)
        store.addClip(project.id, TrackKind.VIDEO, clip)

        assertTrue(media.absolutePath in store.referencedMediaPaths())

        // Deleting the take must NOT delete the file: the timeline still uses it.
        assertTrue(store.deleteTake(project.id, shot.id, takeId))
        assertTrue(media.exists())
        assertTrue(media.absolutePath in store.referencedMediaPaths())

        // Once the clip is gone too, the file becomes collectable.
        assertTrue(store.removeClip(project.id, TrackKind.VIDEO, clip.id))
        assertFalse(media.absolutePath in store.referencedMediaPaths())
    }

    @Test
    fun theSameMediaCanBeReferencedBySeveralClips() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        store.ensureTimeline(project.id)
        val clipA = TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 1000L)
        val clipB = TimelineClip(sourcePath = "/m/a.mp4", sourceOutMs = 2000L)
        store.addClip(project.id, TrackKind.VIDEO, clipA)
        store.addClip(project.id, TrackKind.VIDEO, clipB)

        assertTrue(store.removeClip(project.id, TrackKind.VIDEO, clipA.id))
        assertEquals("/m/a.mp4", store.project(project.id)!!.timeline!!.videoTrack.single().sourcePath)
    }

    @Test
    fun assetsInProfilesAndTimelineAreSeparateConcerns() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val asset = ProjectAsset(id = "asset-1", role = ReferenceRole.GENERIC_REFERENCE, path = "/tmp/clip.mp4")
        store.addAsset(project.id, asset)
        store.ensureTimeline(project.id)
        store.addClip(project.id, TrackKind.VIDEO, TimelineClip(sourcePath = asset.path, sourceOutMs = 100L))

        // Removing the asset must not delete a file the timeline references.
        assertTrue(store.removeAsset(project.id, "asset-1"))
        assertTrue("/tmp/clip.mp4" in store.referencedMediaPaths())
    }
}
