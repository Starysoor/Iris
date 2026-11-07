package com.comfyremote.app.data.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeWorkshopTest {
    @get:Rule
    val temp = TemporaryFolder()

    private data class Seeded(val store: ProjectStore, val projectId: String, val shotId: String)

    private fun shot(seeded: Seeded): ProjectShot =
        seeded.store.project(seeded.projectId)!!.shots.single()

    private fun store() = ProjectStore(File(temp.root, "projects.json"))

    /** One shot holding two done takes: "take-a" (primary) and "take-b". */
    private fun twoDoneTakes(): Seeded {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-1")
        store.markDone(project.id, shot.id, "/m/take-a.mp4", "/m/f1.png")
        val takeA = store.project(project.id)!!.shots.single().takes.single().copy(id = "take-a")
        store.updateShot(project.id, shot.id) {
            it.copy(
                selectedTakeId = "take-a",
                takes = listOf(takeA) + ShotTake(
                    id = "take-b",
                    status = "done",
                    taskId = "task-2",
                    resultPath = "/m/take-b.mp4",
                    seed = "7",
                    costEstimate = 0.5,
                ),
            )
        }
        return Seeded(store, project.id, shot.id)
    }

    @Test
    fun selectTakePromotesOnlyDoneTakesAndCopiesTheLiveResult() {
        val seeded = twoDoneTakes()
        seeded.store.updateShot(seeded.projectId, seeded.shotId) {
            it.copy(takes = it.takes + ShotTake(id = "take-running", status = "running", taskId = "task-3"))
        }

        // A running take can never be promoted.
        assertFalse(seeded.store.selectTake(seeded.projectId, seeded.shotId, "take-running"))
        assertEquals("take-a", shot(seeded).selectedTakeId)

        assertTrue(seeded.store.selectTake(seeded.projectId, seeded.shotId, "take-b"))
        val promoted = shot(seeded)
        assertEquals("take-b", promoted.selectedTakeId)
        assertEquals("/m/take-b.mp4", promoted.resultPath)
        assertEquals("done", promoted.status)
        // The promoted result must re-enter tail-frame artifacts.
        assertFalse(promoted.tailFrameStored)
    }

    @Test
    fun favoriteAndDiscardAreRecordLevelAndKeepTheLiveResult() {
        val seeded = twoDoneTakes()

        assertTrue(seeded.store.setTakeFavorite(seeded.projectId, seeded.shotId, "take-b", true))
        assertTrue(shot(seeded).takes.first { it.id == "take-b" }.favorite)

        assertTrue(seeded.store.discardTake(seeded.projectId, seeded.shotId, "take-b"))
        assertEquals("discarded", shot(seeded).takes.first { it.id == "take-b" }.status)
        // Discarding only marks the record; the live result is untouched.
        assertEquals("/m/take-a.mp4", shot(seeded).resultPath)
        assertEquals("done", shot(seeded).status)
    }

    @Test
    fun deletingThePrimaryTakePromotesTheNewestRemainingDoneTake() {
        val seeded = twoDoneTakes()

        assertTrue(seeded.store.deleteTake(seeded.projectId, seeded.shotId, "take-a"))

        val after = shot(seeded)
        assertEquals("take-b", after.selectedTakeId)
        assertEquals("/m/take-b.mp4", after.resultPath)
        assertEquals("done", after.status)
        assertFalse(after.tailFrameStored)
    }

    @Test
    fun deletingTheOnlyDoneTakePreservesHistoryOwnedFile() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        val seeded = Seeded(store, project.id, shot.id)
        store.attachTask(project.id, shot.id, "task-1")
        val media = temp.newFile("r1.mp4").apply { writeText("result") }
        store.markDone(project.id, shot.id, media.absolutePath, null)
        val takeId = shot(seeded).selectedTakeId!!

        assertTrue(store.deleteTake(project.id, shot.id, takeId))

        val after = shot(seeded)
        assertEquals("draft", after.status)
        assertNull(after.resultPath)
        assertNull(after.selectedTakeId)
        assertTrue(after.takes.isEmpty())
        // This path may be owned by the independent Results/History library.
        assertTrue(media.exists())
    }

    @Test
    fun deletingANonPrimaryTakeKeepsTheLiveResult() {
        val seeded = twoDoneTakes()

        assertTrue(seeded.store.deleteTake(seeded.projectId, seeded.shotId, "take-b"))

        val after = shot(seeded)
        assertEquals("/m/take-a.mp4", after.resultPath)
        assertEquals(1, after.takes.size)
        assertEquals("take-a", after.selectedTakeId)
    }

    @Test
    fun deletingAnAlreadyDeletedTakeIsANoOp() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        val seeded = Seeded(store, project.id, shot.id)
        val media = temp.newFile("r.mp4").apply { writeText("result") }
        store.attachTask(project.id, shot.id, "task-1")
        store.markDone(project.id, shot.id, media.absolutePath, null)
        val takeId = shot(seeded).selectedTakeId!!

        assertTrue(store.deleteTake(project.id, shot.id, takeId))
        assertFalse(store.deleteTake(project.id, shot.id, takeId))
    }


    @Test
    fun cannotDeleteAnActiveTakeAndNeverLoseTaskIdentity() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        assertTrue(store.attachTask(project.id, shot.id, "paid-remote-task"))
        val takeId = store.project(project.id)!!.shots.single().takes.single().id
        assertFalse(store.deleteTake(project.id, shot.id, takeId))
        assertFalse(store.selectTake(project.id, shot.id, takeId))
        assertEquals("paid-remote-task", store.project(project.id)!!.shots.single().taskId)
        assertEquals("running", store.project(project.id)!!.shots.single().takes.single().status)
    }

    @Test
    fun selectingFinishedTakeDetachesStaleTaskAndClearsOldTail() {
        val seeded = twoDoneTakes()
        assertTrue(seeded.store.selectTake(seeded.projectId, seeded.shotId, "take-b"))
        val selected = shot(seeded)
        assertNull(selected.taskId)
        assertNull(selected.lastFramePath)
        assertFalse(selected.tailFrameStored)
        assertFalse(seeded.store.markDone(seeded.projectId, seeded.shotId, "/m/stale.mp4", null))
        assertEquals("/m/take-b.mp4", shot(seeded).resultPath)
    }

    @Test
    fun takeSeedSnapshotCarriesProviderSeedAndCost() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!

        store.attachTask(
            project.id,
            shot.id,
            "task-9",
            takeSeed = ShotTake(
                providerId = "replicate",
                workflowPath = "replicate:owner/model",
                seed = "42",
                costEstimate = 0.35,
            ),
        )

        val take = store.project(project.id)!!.shots.single().takes.single()
        assertEquals("replicate", take.providerId)
        assertEquals("replicate:owner/model", take.workflowPath)
        assertEquals("42", take.seed)
        assertEquals(0.35, take.costEstimate!!, 1e-9)
        assertEquals("running", take.status)
    }

    @Test
    fun favoriteSurvivesTheCodecRoundTrip() {
        val take = ShotTake(id = "t1", status = "done", resultPath = "/m/r.mp4", favorite = true)
        val shot = ProjectShot(id = "s1", prompt = "p", takes = listOf(take), selectedTakeId = "t1")
        val project = VideoProject(id = "p1", title = "T", workflowId = "m", shots = listOf(shot))

        val restored = ProjectCodec.decode(ProjectCodec.encode(listOf(project))).single()

        assertTrue(restored.shots.single().takes.single().favorite)
    }
}
