package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun archiveRoundTripKeepsMediaAndTimelineButDetachesPaidTasks() {
        val store = ProjectStore(File(temp.root, "projects.json"))
        val media = File(temp.root, "storyboards/assets").apply { mkdirs() }
        val original = File(media, "scene.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val take = ShotTake(id = "take-old", status = "running", taskId = "paid-old",
            resultPath = original.absolutePath, referenceAssetIds = listOf("asset-old"))
        val shot = ProjectShot(id = "shot-old", status = "running", taskId = "paid-old",
            referencePath = original.absolutePath, resultPath = original.absolutePath,
            selectedTakeId = take.id, takes = listOf(take),
            referenceAssetIds = listOf("asset-old"))
        val project = VideoProject(id = "project-old", title = "Scene", workflowId = "",
            assets = listOf(ProjectAsset(id = "asset-old", path = original.absolutePath,
                kind = AssetKind.VIDEO)), shots = listOf(shot),
            timeline = Timeline(videoTrack = listOf(TimelineClip(
                sourcePath = original.absolutePath, sourceOutMs = 3000))))
        assertTrue(store.insertImported(project))
        val archive = ProjectArchive(store, media, listOf(temp.root))
        val output = ByteArrayOutputStream()
        val exported = archive.export(project.id, output)
        assertEquals(1, exported.mediaFiles)
        assertEquals(4L, exported.bytes)
        val restored = archive.importSnapshot(ByteArrayInputStream(output.toByteArray()))
        assertNotEquals(project.id, restored.id)
        assertEquals(2, store.projects.value.size)
        assertNotEquals(project.shots.single().id, restored.shots.single().id)
        assertNull(restored.shots.single().taskId)
        assertEquals("failed", restored.shots.single().status)
        assertNull(restored.shots.single().takes.single().taskId)
        assertEquals("failed", restored.shots.single().takes.single().status)
        assertEquals(restored.shots.single().takes.single().id, restored.shots.single().selectedTakeId)
        assertEquals(restored.assets.single().id, restored.shots.single().referenceAssetIds.single())
        assertEquals(restored.assets.single().path, restored.timeline!!.videoTrack.single().sourcePath)
        assertEquals(original.readBytes().toList(),
            File(restored.assets.single().path).readBytes().toList())
        assertEquals(2, ProjectStore(File(temp.root, "projects.json")).projects.value.size)
    }

    @Test fun missingMediaRejectsExportWithoutChangingIndex() {
        val store = ProjectStore(File(temp.root, "projects.json"))
        val project = VideoProject(id = "missing", title = "Missing", workflowId = "",
            assets = listOf(ProjectAsset(path = File(temp.root, "not-here.mp4").absolutePath)))
        assertTrue(store.insertImported(project))
        val archive = ProjectArchive(store, File(temp.root, "assets"), listOf(temp.root))
        assertTrue(runCatching { archive.export(project.id, ByteArrayOutputStream()) }.isFailure)
        assertEquals(1, store.projects.value.size)
    }

    @Test fun pathTraversalAndUnknownEntriesRejectWithoutWritingMedia() {
        val store = ProjectStore(File(temp.root, "projects.json"))
        val target = File(temp.root, "assets")
        val packageBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"iris-project","version":1,"media":[{"path":"/unsafe","entry":"../bad"}]}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(ProjectCodec.encode(listOf(VideoProject(id = "x", title = "x", workflowId = ""))).toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val archive = ProjectArchive(store, target, listOf(temp.root))
        assertTrue(runCatching { archive.importSnapshot(ByteArrayInputStream(packageBytes)) }.isFailure)
        assertTrue(store.projects.value.isEmpty())
        assertFalse(File(temp.root, "bad").exists())
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }
}
