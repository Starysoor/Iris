package com.comfyremote.app.data.project

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.comfyremote.app.ComfyRemoteApp
import com.comfyremote.app.MainActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioForegroundExportInstrumentedTest {
    @Test
    fun exportsAReopenedProjectWithAudioWhileActivityIsInBackground() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val smoke = arguments.getString("studioExportSmoke") == "true"
        val shotCount = if (smoke) 3 else 12
        val clipDurationMs = if (smoke) 1_000L else 10_000L
        val totalDurationMs = shotCount * clipDurationMs
        val title = if (smoke) "QA foreground export smoke" else "QA 120-second export"
        val timeoutSeconds = arguments.getString("studioExportTimeoutSeconds")?.toLongOrNull() ?: 300L
        val app = instrumentation.targetContext.applicationContext as ComfyRemoteApp
        val container = app.container
        val store = container.projectStore
        val soundtrack = File(app.filesDir, "qa-studio-audio.wav")
        val sources = listOf("qa-studio-a.mp4", "qa-studio-b.mp4").map { File(app.filesDir, it) }
        assertTrue((sources + soundtrack).all(File::isFile))

        val project = store.projects.value.firstOrNull {
            val timeline = it.timeline ?: return@firstOrNull false
            it.title == title && it.shots.size == shotCount &&
                timeline.videoTrack.size == shotCount && timeline.videoDurationMs == totalDurationMs &&
                timeline.audioTrack.singleOrNull()?.sourcePath == soundtrack.absolutePath &&
                timeline.videoTrack.all { clip -> sources.any { source -> source.absolutePath == clip.sourcePath } }
        } ?: prepareProject(store, sources, soundtrack, title, shotCount, clipDurationMs)

        val reopened = checkNotNull(ProjectStore(app).project(project.id))
        assertEquals(shotCount + minOf(shotCount, 4), reopened.shots.sumOf { it.takes.size })
        assertEquals(totalDurationMs, reopened.timeline?.videoDurationMs)
        assertEquals("qa-clip-1", reopened.timeline?.videoTrack?.first()?.id)

        withContext(Dispatchers.Main) {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val activity = withTimeout(120_000L) {
            var resumed: MainActivity? = null
            while (resumed == null) {
                resumed = withContext(Dispatchers.Main) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                if (resumed == null) delay(250)
            }
            resumed
        }
        val controller = container.projectExportController
        withContext(Dispatchers.Main) {
            assertTrue(controller.requestStart(project.id))
            val active = controller.state.value as ProjectExportController.State.Running
            controller.cancel("stale-notification")
            assertEquals(active.sessionId, (controller.state.value as ProjectExportController.State.Running).sessionId)
        }
        // Move the task to the background while the export continues in the service.
        withContext(Dispatchers.Main) { assertTrue(activity.moveTaskToBack(true)) }
        val complete = withTimeout(timeoutSeconds * 1_000L) {
            controller.state.first { it.projectId == project.id && it !is ProjectExportController.State.Running }
        }
        assertTrue("Unexpected foreground export result: $complete", complete is ProjectExportController.State.Succeeded)
        val result = File((complete as ProjectExportController.State.Succeeded).outputPath)
        assertTrue(container.historyRepository.load().any { result.absolutePath in it.images })
        assertNull(ProjectExportJournal(File(app.filesDir, "project-export-session.json")).interrupted())

        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(result.absolutePath)
            assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val actual = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            assertTrue("Expected ${totalDurationMs}ms, received ${actual}ms",
                actual in (totalDurationMs - 500L)..(totalDurationMs + 500L))
        } finally {
            metadata.release()
        }
        result.copyTo(File(app.filesDir, "qa-studio-export.mp4"), overwrite = true)
        assertTrue(store.delete(project.id))
        withContext(Dispatchers.Main) { activity.finish() }
    }

    private fun prepareProject(
        store: ProjectStore,
        sources: List<File>,
        soundtrack: File,
        title: String,
        shotCount: Int,
        clipDurationMs: Long,
    ): VideoProject {
        val project = requireNotNull(store.create(title = title, workflowId = "", chainFrames = false))
        repeat(shotCount) { index ->
            val shot = requireNotNull(store.addShot(project.id, "Shot ${index + 1}"))
            val takes = if (index < 4) 2 else 1
            repeat(takes) { attempt ->
                val source = sources[(index + attempt) % sources.size]
                val model = if (source == sources.first()) "local-a" else "local-b"
                assertTrue(store.attachTask(project.id, shot.id, "qa-${project.id}-$index-$attempt",
                    ShotTake(providerId = model, workflowPath = "$model:video")))
                assertTrue(store.markDone(project.id, shot.id, source.absolutePath, null))
            }
            val saved = requireNotNull(store.project(project.id)).shots.first { it.id == shot.id }
            assertEquals(takes, saved.takes.size)
            if (takes == 2) assertTrue(store.selectTake(project.id, shot.id, saved.takes.first().id))
            val selected = requireNotNull(store.project(project.id)).shots.first { it.id == shot.id }
            assertTrue(store.addClip(project.id, TrackKind.VIDEO, TimelineClip(
                id = "qa-clip-$index", sourcePath = requireNotNull(selected.resultPath), sourceOutMs = clipDurationMs,
            )))
        }
        assertTrue(store.moveClip(project.id, TrackKind.VIDEO, "qa-clip-1", -1))
        assertTrue(store.addClip(project.id, TrackKind.AUDIO, TimelineClip(
            sourcePath = soundtrack.absolutePath, sourceOutMs = shotCount * clipDurationMs,
        )))
        return project
    }
}
