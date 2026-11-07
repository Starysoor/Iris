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

class ProjectStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = ProjectStore(File(temp.root, "projects.json"))

    @Test
    fun createDerivesAutoDlPathsAndKeepsProviderIdentity() {
        val store = store()

        val legacy = store.create("Studio", "minimax_h3_lightx2v_v5")!!
        val cloud = store.create(
            "Cloud",
            workflowId = "",
            workflowPath = "replicate:owner/model",
            providerId = "replicate",
        )!!

        assertEquals("autodl:minimax_h3_lightx2v_v5", legacy.workflowPath)
        assertEquals("autodl", legacy.providerId)
        assertEquals("replicate:owner/model", cloud.workflowPath)
        assertEquals("replicate", cloud.providerId)
    }

    @Test
    fun nextShotWaitsForAnEarlierRunningShot() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val first = store.addShot(project.id, "first")!!
        val second = store.addShot(project.id, "second")!!

        store.attachTask(project.id, first.id, "task-1")

        assertNull(store.nextShot(project.id))
        store.markDone(project.id, first.id, "/tmp/r1.mp4", "/tmp/f1.png")
        assertEquals(second.id, store.nextShot(project.id)?.id)
    }

    @Test
    fun attachTaskRecordsATakeSnapshotAndNeverOverwritesARunningShot() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene one")!!

        val seed = ShotTake(
            providerId = "replicate",
            workflowPath = "replicate:owner/model",
            prompt = "scene one",
            seed = "42",
            values = mapOf("duration" to "5"),
            costEstimate = 0.2,
        )
        assertTrue(store.attachTask(project.id, shot.id, "task-1", seed))
        val running = store.project(project.id)!!.shots.single()
        assertEquals("running", running.status)
        assertEquals("task-1", running.taskId)
        val take = running.takes.single()
        assertEquals("task-1", take.taskId)
        assertEquals("running", take.status)
        assertEquals("replicate:owner/model", take.workflowPath)
        assertEquals("scene one", take.prompt)
        assertEquals("42", take.seed)
        assertEquals(0.2, take.costEstimate!!, 1e-9)
        assertEquals(take.id, running.selectedTakeId)

        // A second attach while running must be rejected and must not add a take.
        assertFalse(store.attachTask(project.id, shot.id, "task-2"))
        assertEquals(1, store.project(project.id)!!.shots.single().takes.size)
    }

    @Test
    fun attachTaskWithoutSeedDerivesTheTakeFromShotAndProjectDefaults() {
        val store = store()
        val project = store.create(
            "Studio",
            workflowId = "",
            workflowPath = "autodl:wf-model",
            providerId = "autodl",
        )!!
        val shot = store.addShot(project.id, "scene")!!

        assertTrue(store.attachTask(project.id, shot.id, "task-1"))

        val take = store.project(project.id)!!.shots.single().takes.single()
        assertEquals("autodl:wf-model", take.workflowPath)
        assertEquals("autodl", take.providerId)
        assertEquals("scene", take.prompt)
    }

    @Test
    fun markDoneClosesTheRunningTakeWithTheResult() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-1")

        assertTrue(store.markDone(project.id, shot.id, "/tmp/result.mp4", "/tmp/last.png"))

        val done = store.project(project.id)!!.shots.single()
        assertEquals("done", done.status)
        assertEquals("/tmp/result.mp4", done.resultPath)
        val take = done.takes.single()
        assertEquals("done", take.status)
        assertEquals("/tmp/result.mp4", take.resultPath)
        assertNull(take.error)
        assertEquals(take.id, done.selectedTakeId)
    }

    @Test
    fun markFailedClosesTheRunningTakeWithTheError() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-1")

        assertTrue(store.markFailed(project.id, shot.id, "provider 500"))

        val failed = store.project(project.id)!!.shots.single()
        assertEquals("failed", failed.status)
        assertEquals("provider 500", failed.error)
        assertEquals("failed", failed.takes.single().status)
        assertEquals("provider 500", failed.takes.single().error)
    }

    @Test
    fun markTaskCancelledReturnsTheShotToDraftAndClosesTheTake() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-1")

        assertTrue(store.markTaskCancelled(project.id, shot.id, "task-1"))

        val cancelled = store.project(project.id)!!.shots.single()
        assertEquals("draft", cancelled.status)
        assertNull(cancelled.taskId)
        assertEquals("cancelled", cancelled.takes.single().status)
        // A stale cancel for an older task id must not touch the current state.
        store.attachTask(project.id, shot.id, "task-2")
        assertFalse(store.markTaskCancelled(project.id, shot.id, "task-1"))
        assertEquals("running", store.project(project.id)!!.shots.single().status)
    }

    @Test
    fun duplicatingAShotResetsGenerationHistoryButKeepsTheSetup() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene", cameraNotes = "low angle")!!
        store.attachTask(project.id, shot.id, "task-1")
        store.markDone(project.id, shot.id, "/tmp/r.mp4", "/tmp/f.png")

        val copy = store.duplicateShot(project.id, shot.id)!!

        assertEquals("low angle", copy.cameraNotes)
        assertEquals("draft", copy.status)
        assertNull(copy.taskId)
        assertNull(copy.resultPath)
        assertNull(copy.lastFramePath)
        assertTrue(copy.takes.isEmpty())
        assertNull(copy.selectedTakeId)
    }

    @Test
    fun deletingAProjectCleansOnlyUnreferencedExpiredFrames() {
        val store = store()
        val mediaDir = File(temp.root, "storyboards").apply { mkdirs() }
        val referenced = File(mediaDir, "kept.png").apply { writeText("kept") }
        val orphan = File(mediaDir, "stale.png").apply { writeText("stale") }
        orphan.setLastModified(System.currentTimeMillis() - 15 * 60_000L)
        val freshOrphan = File(mediaDir, "fresh.png").apply { writeText("fresh") }
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-media")
        store.markDone(project.id, shot.id, referenced.absolutePath, null)

        assertTrue(store.delete(project.id))

        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        // Recent files stay inside the grace window even when unreferenced.
        assertTrue(freshOrphan.exists())
    }

    @Test
    fun legacyStoryboardRollbackAnchorKeepsReferencedMediaAfterNewProjectDeletion() {
        val mediaDir = File(temp.root, "storyboards").apply { mkdirs() }
        val preserved = File(mediaDir, "old-reference.png").apply {
            writeText("rollback media")
            setLastModified(System.currentTimeMillis() - 60 * 60_000L)
        }
        val legacyProject = com.comfyremote.app.data.storyboard.StoryboardProject(
            id = "legacy-project", title = "Legacy", workflowId = "workflow",
            assets = listOf(ProjectAsset(
                id = "legacy-asset", path = preserved.absolutePath,
                role = ReferenceRole.CHARACTER_REFERENCE,
            )),
        )
        File(temp.root, "storyboards.json").writeText(
            com.comfyremote.app.data.storyboard.StoryboardCodec.encode(listOf(legacyProject)),
        )
        val store = store()
        val newProject = store.create("New", "wf")!!
        assertTrue(store.delete(newProject.id))
        assertTrue(preserved.exists())
    }

    @Test
    fun takeResultsCountAsReferencedMedia() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.attachTask(project.id, shot.id, "task-1")

        val running = store.project(project.id)!!.shots.single()
        val take = running.takes.single()
        val takes = listOf(take.copy(resultPath = "/tmp/take-result.mp4"))
        store.updateShot(project.id, shot.id) { it.copy(takes = takes) }

        assertTrue("/tmp/take-result.mp4" in store.referencedMediaPaths())
    }

    @Test
    fun projectAssetsPersistAndCountAsReferencedMedia() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val asset = ProjectAsset(
            id = "asset-1",
            role = ReferenceRole.CHARACTER_REFERENCE,
            path = "/tmp/character.png",
            label = "hero",
        )

        assertTrue(store.addAsset(project.id, asset))
        assertEquals("hero", store.project(project.id)?.assets?.single()?.label)
        assertTrue("/tmp/character.png" in store.referencedMediaPaths())

        assertTrue(store.removeAsset(project.id, asset.id))
        assertTrue(store.project(project.id)?.assets?.isEmpty() == true)
        assertFalse("/tmp/character.png" in store.referencedMediaPaths())
    }

    @Test
    fun aFailedWriteKeepsTheProjectAndDoesNotDeleteAssetMedia() {
        val index = File(temp.root, "projects.json")
        val store = ProjectStore(index)
        val project = store.create("Studio", "wf")!!
        val assetFile = File(temp.root, "asset.png").apply { writeText("asset") }
        val asset = ProjectAsset(
            id = "asset-file",
            role = ReferenceRole.GENERIC_REFERENCE,
            path = assetFile.absolutePath,
        )
        store.addAsset(project.id, asset)
        index.delete()
        index.mkdirs()

        val removed = store.removeAsset(project.id, asset.id)

        assertFalse(removed)
        assertTrue(assetFile.exists())
        assertTrue(store.project(project.id)!!.assets.any { it.id == asset.id })
        assertTrue(store.persistenceError.value != null)
    }

    @Test
    fun corruptProjectIndexCannotBeOverwrittenWithAnEmptyProject() {
        val file = File(temp.root, "projects.json").apply { writeText("{broken") }
        val store = ProjectStore(file)

        assertNull(store.create("New", "workflow"))

        assertTrue(store.persistenceError.value != null)
        assertEquals("{broken", file.readText())
    }

    @Test
    fun unreadableProjectRowsCannotBeSilentlyDroppedOnSave() {
        val file = File(temp.root, "projects.json")
            .apply { writeText("""{"version":2,"projects":[{"title":"missing id"}]}""") }
        val store = ProjectStore(file)

        assertNull(store.create("New", "workflow"))

        assertTrue(store.persistenceError.value != null)
        assertTrue(file.readText().contains("missing id"))
    }

    @Test
    fun runningShotWithDeletedTaskCanBeUnblockedWithoutResubmission() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        assertTrue(store.attachTask(project.id, shot.id, "removed-task"))
        val running = store.project(project.id)!!.shots.single()

        assertTrue(isOrphanedRunningShot(running, emptySet()))
        assertFalse(isOrphanedRunningShot(running, setOf("removed-task")))

        assertTrue(store.markFailed(project.id, shot.id, "Check the provider before retrying"))
        assertEquals(shot.id, store.nextShot(project.id)?.id)
        assertEquals("failed", store.project(project.id)!!.shots.single().status)
    }

    @Test
    fun projectSettingsPreserveExplicitShotWorkflowOverrides() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "scene")!!
        store.updateShot(project.id, shot.id) {
            it.copy(workflowPath = "siliconflow:old/model", providerId = "siliconflow", values = mapOf("k" to "v"))
        }

        assertTrue(
            store.updateSettings(
                project.id,
                title = "Renamed",
                synopsis = "a short",
                workflowPath = "replicate:new/model",
                providerId = "replicate",
                aspectRatio = "9:16",
                defaultValues = mapOf("resolution" to "768p"),
                targetDurationSeconds = 120,
                budget = 9.9,
            ),
        )

        val updated = store.project(project.id)!!
        assertEquals("Renamed", updated.title)
        assertEquals("a short", updated.synopsis)
        assertEquals("replicate:new/model", updated.workflowPath)
        assertEquals(120, updated.targetDurationSeconds)
        assertEquals(9.9, updated.budget!!, 1e-9)
        val overridden = updated.shots.single()
        assertEquals("siliconflow:old/model", overridden.workflowPath)
        assertEquals("siliconflow", overridden.providerId)
        assertEquals(mapOf("k" to "v"), overridden.values)
    }


    @Test
    fun projectSettingsPreserveExistingBudgetUnlessExplicitlyEdited() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        assertTrue(store.setBudget(project.id, 20.0))
        assertTrue(store.updateSettings(
            project.id, "Renamed", "", "autodl:other", "autodl", "16:9", emptyMap(),
        ))
        assertEquals(20.0, store.project(project.id)!!.budget!!, 1e-9)
        assertTrue(store.updateSettings(
            project.id, "Renamed", "", "autodl:other", "autodl", "16:9", emptyMap(),
            budget = null, updateBudget = true,
        ))
        assertNull(store.project(project.id)!!.budget)
        assertTrue(store.setBudget(project.id, Double.POSITIVE_INFINITY))
        assertNull(store.project(project.id)!!.budget)
    }

    @Test
    fun changingProjectDefaultRetainsOverridesButClearsInheritedValues() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val inherited = store.addShot(project.id, "inherited")!!
        val overridden = store.addShot(project.id, "override")!!
        store.updateShot(project.id, inherited.id) { it.copy(values = mapOf("legacy" to "1")) }
        store.updateShot(project.id, overridden.id) {
            it.copy(workflowPath = "replicate:fixed", values = mapOf("prompt" to "saved"))
        }
        assertTrue(store.updateSettings(project.id, "Studio", "", "autodl:new", "autodl", "", emptyMap()))
        val shots = store.project(project.id)!!.shots
        assertTrue(shots.first { it.id == inherited.id }.values.isEmpty())
        assertEquals("replicate:fixed", shots.first { it.id == overridden.id }.workflowPath)
        assertEquals(mapOf("prompt" to "saved"), shots.first { it.id == overridden.id }.values)
    }

    @Test
    fun storeRejectsDeletingRunningShotsOrProjectsWithoutFalseSuccess() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val shot = store.addShot(project.id, "paying shot")!!
        assertTrue(store.attachTask(project.id, shot.id, "active-task"))
        assertFalse(store.deleteShot(project.id, shot.id))
        assertFalse(store.delete(project.id))
        assertEquals("active-task", store.project(project.id)!!.shots.single().taskId)
        assertTrue(store.markTaskCancelled(project.id, shot.id, "active-task"))
        assertTrue(store.deleteShot(project.id, shot.id))
        assertTrue(store.delete(project.id))
    }

    @Test
    fun chainFrameToggleClearsOnlyAutomaticReferences() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val first = store.addShot(project.id, "first")!!
        val second = store.addShot(project.id, "second")!!
        store.attachTask(project.id, first.id, "task-chain")
        store.markDone(project.id, first.id, "/tmp/r.mp4", "/tmp/last.png")

        val relayed = store.project(project.id)!!.shots[1]
        assertEquals("/tmp/last.png", relayed.referencePath)
        assertFalse(relayed.referenceManual)

        assertTrue(store.setChainFrames(project.id, false))
        val cleared = store.project(project.id)!!.shots[1]
        assertNull(cleared.referencePath)
        assertFalse(cleared.referenceManual)
    }

    @Test
    fun profilesUpsertAndDeletingAnAssetCleansProfileReferences() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        val asset = ProjectAsset(id = "asset-hero", role = ReferenceRole.CHARACTER_REFERENCE, path = "/tmp/hero.png")

        assertTrue(
            store.addProfile(
                project.id,
                AssetProfile(id = "profile-1", name = "女主", assetIds = listOf("asset-hero", "asset-gone")),
            ),
        )
        // Upsert by id: a second add with the same id replaces the profile.
        assertTrue(
            store.addProfile(
                project.id,
                AssetProfile(id = "profile-1", name = "女主 v2", assetIds = listOf("asset-hero")),
            ),
        )
        assertEquals("女主 v2", store.project(project.id)!!.profiles.single().name)

        assertTrue(store.updateProfile(project.id, "profile-1") { it.copy(summary = "银发") })
        assertEquals("银发", store.project(project.id)!!.profiles.single().summary)

        store.addAsset(project.id, asset)
        assertTrue(store.removeAsset(project.id, "asset-hero"))
        assertTrue(store.project(project.id)!!.profiles.single().assetIds.isEmpty())

        // Deleting a profile never touches assets.
        store.addAsset(project.id, asset)
        assertTrue(store.deleteProfile(project.id, "profile-1"))
        assertFalse(store.deleteProfile(project.id, "profile-1"))
        assertEquals("asset-hero", store.project(project.id)!!.assets.single().id)
        assertTrue(store.project(project.id)!!.profiles.isEmpty())
    }

    @Test
    fun updateProfileOnMissingProfileIsRejected() {
        val store = store()
        val project = store.create("Studio", "wf")!!
        assertFalse(store.updateProfile(project.id, "missing") { it.copy(name = "x") })
        assertTrue(store.project(project.id)!!.profiles.isEmpty())
    }
}
