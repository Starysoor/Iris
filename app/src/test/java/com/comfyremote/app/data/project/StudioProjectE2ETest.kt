package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Paid-API-free model-level E2E: a real 12-shot project with multi-provider Takes,
 * version choice, assets, editing tracks and a process-restart-like store reload.
 *
 * This tests project integrity and export planning; actual codec rendering and
 * physical-device media validation remain release acceptance work (#80).
 */
class StudioProjectE2ETest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun twelveShotMixedProviderProjectSurvivesRestartAndBuildsCompleteExportPlan() {
        val index = File(temp.root, "projects.json")
        val store = ProjectStore(index)
        // An offline-first project can be authored without credentials or model catalogues.
        val project = store.create("Two-minute fantasy film", workflowId = "", workflowPath = "")!!
        assertTrue(store.setBudget(project.id, 30.0))

        val assetFiles = (0 until 6).map { index ->
            val file = temp.newFile("reference-$index.png").apply { writeText("fixture-$index") }
            val role = if (index == 0) ReferenceRole.CHARACTER_REFERENCE else ReferenceRole.SCENE_REFERENCE
            val asset = ProjectAsset(
                id = "reference-$index", path = file.absolutePath,
                label = if (index == 0) "hero 3-view" else "scene-$index",
                role = role, kind = AssetKind.IMAGE,
            )
            assertTrue(store.addAsset(project.id, asset))
            asset
        }
        assertTrue(store.addProfile(
            project.id,
            AssetProfile(id = "hero", name = "Hero", kind = ProfileKind.CHARACTER, assetIds = listOf(assetFiles[0].id)),
        ))
        val sourcePaths = mutableSetOf<String>()
        for (i in 0 until 12) {
            val shot = store.addShot(project.id, "Scene ${i + 1}: cinematic fantasy", "slow pan")!!
            val provider = if (i % 2 == 0) "replicate" else "autodl-model"
            val workflow = if (i % 2 == 0) "replicate:video" else "autodl-model:video"
            assertTrue(store.updateShot(project.id, shot.id) {
                it.copy(workflowPath = workflow, providerId = provider, referenceAssetIds = listOf(assetFiles[0].id))
            })
            val takeCount = if (i < 4) 2 else 1
            for (takeNumber in 0 until takeCount) {
                val taskId = "local-mock-${i}-${takeNumber}"
                assertTrue(store.attachTask(
                    project.id, shot.id, taskId,
                    takeSeed = ShotTake(
                        providerId = provider, workflowPath = workflow,
                        prompt = "Scene ${i + 1}", costEstimate = 0.5,
                    ),
                ))
                val output = "/mock/shot-${i}-take-${takeNumber}.mp4"
                assertTrue(store.markDone(project.id, shot.id, output, null))
                sourcePaths += output
            }
            if (i < 4) {
                val versions = store.project(project.id)!!.shots.first { it.id == shot.id }.takes
                assertEquals(2, versions.size)
                assertTrue(store.selectTake(project.id, shot.id, versions.first().id))
            }
        }

        assertTrue(store.ensureTimeline(project.id))
        val shots = store.project(project.id)!!.shots
        assertEquals(12, shots.size)
        shots.forEachIndexed { index, shot ->
            val clip = TimelineClip(
                id = "tlc:${index}", sourcePath = shot.resultPath!!,
                sourceLabel = "Shot ${index + 1}", sourceOutMs = 10_000L,
            )
            assertTrue(store.addClip(project.id, TrackKind.VIDEO, clip))
        }
        assertTrue(store.addClip(
            project.id, TrackKind.AUDIO,
            TimelineClip(id = "tlc:music", sourcePath = "/mock/score.wav", sourceOutMs = 120_000L),
        ))
        assertTrue(store.updateSettings(
            project.id, title = "Two-minute fantasy film (edited)", synopsis = "first act",
            workflowPath = "", providerId = "", aspectRatio = "16:9", defaultValues = emptyMap(),
            targetDurationSeconds = 120,
        ))

        // Simulates process death and app restart: use only the durable index.
        val restored = ProjectStore(index).project(project.id)!!
        assertEquals("Two-minute fantasy film (edited)", restored.title)
        assertEquals(6, restored.assets.size)
        assertEquals(1, restored.profiles.size)
        assertEquals(12, restored.shots.size)
        assertEquals(16, restored.shots.sumOf { it.takes.size })
        assertEquals(30.0, restored.budget!!, 1e-9)
        assertTrue(restored.shots.all { it.status == "done" })
        assertTrue(restored.shots.all { it.workflowPath?.isNotBlank() == true })
        assertEquals(120_000L, restored.timeline!!.videoDurationMs)
        assertEquals(120_000L, restored.timeline!!.audioDurationMs)
        assertEquals(120_000L, restored.timeline!!.totalDurationMs)

        val plan = ExportPlanBuilder.build(
            restored.timeline!!,
            existingPaths = sourcePaths,
            sourceDurationsMs = sourcePaths.associateWith { 10_000L },
        )
        assertFalse(plan.isEmpty)
        assertEquals(12, plan.items.size)
        assertEquals(1, plan.audioItems.size)
        assertEquals("tlc:music", plan.audioItems.single().clipId)
        assertEquals(120_000L, plan.audioItems.single().durationMs)
        assertTrue(plan.skippedMissing.isEmpty())
        assertTrue(plan.invalidTrims.isEmpty())
        assertEquals(120_000L, plan.expectedDurationMs)
        assertTrue(DirectorChecks.analyze(restored, sourcePaths + assetFiles.map { it.path })
            .none { it.code == "duration_drift" })
    }
}
