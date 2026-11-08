package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCodecTest {
    @Test
    fun fullProjectRoundTripPreservesEveryField() {
        val take = ShotTake(
            id = "take-1",
            providerId = "replicate",
            workflowPath = "replicate:owner/model",
            prompt = "wide establishing shot",
            seed = "42",
            values = mapOf("duration" to "5"),
            referenceAssetIds = listOf("asset-hero"),
            status = "done",
            taskId = "task-1",
            resultPath = "/files/result.mp4",
            costEstimate = 0.35,
            createdAt = 111L,
        )
        val shot = ProjectShot(
            id = "shot-1",
            prompt = " establishing wide",
            cameraNotes = "slow push in",
            plannedDurationSeconds = 12,
            referencePath = "/files/ref.png",
            referenceManual = true,
            referenceRole = ReferenceRole.START_FRAME,
            referenceAssetIds = listOf("asset-hero", "asset-scene"),
            firstFrameAssetId = "asset-first",
            lastFrameAssetId = "asset-last",
            workflowPath = "siliconflow:Wan-AI/Wan2.2",
            providerId = "siliconflow",
            workflowId = "Wan-AI/Wan2.2",
            values = mapOf("resolution" to "768p竖"),
            lastFramePath = "/files/last.png",
            tailFrameStored = true,
            status = "done",
            taskId = "task-1",
            resultPath = "/files/result.mp4",
            takes = listOf(take),
            selectedTakeId = "take-1",
            createdAt = 11L,
            updatedAt = 22L,
        )
        val project = VideoProject(
            id = "project-1",
            title = "Sakura 短片",
            synopsis = "a two minute fantasy short",
            aspectRatio = "9:16",
            targetDurationSeconds = 120,
            stylePrompt = "cinematic",
            workflowId = "minimax_h3_lightx2v_v5",
            workflowPath = "autodl:minimax_h3_lightx2v_v5",
            providerId = "autodl",
            defaultValues = mapOf("resolution" to "768p竖"),
            chainFrames = false,
            budget = 12.5,
            shots = listOf(shot),
            assets = listOf(
                ProjectAsset(
                    id = "asset-hero",
                    role = ReferenceRole.CHARACTER_REFERENCE,
                    path = "/files/character.png",
                    label = "hero",
                    order = 1,
                    createdAt = 5L,
                    kind = AssetKind.IMAGE,
                    tags = listOf("主角", "v2"),
                ),
                ProjectAsset(
                    id = "asset-clip",
                    role = ReferenceRole.GENERIC_REFERENCE,
                    path = "/files/clip.mp4",
                    kind = AssetKind.VIDEO,
                ),
            ),
            createdAt = 100L,
            updatedAt = 200L,
        )

        val restored = ProjectCodec.decode(ProjectCodec.encode(listOf(project))).single()

        assertEquals(project, restored)
    }

    @Test
    fun optionalFieldsRoundTripAsAbsent() {
        val project = VideoProject(
            id = "project-plain",
            title = "Plain",
            workflowId = "model",
        )

        val restored = ProjectCodec.decode(ProjectCodec.encode(listOf(project))).single()

        assertNull(restored.targetDurationSeconds)
        assertNull(ProjectCodec.decode(ProjectCodec.encode(listOf(
            project.copy(shots = listOf(ProjectShot(prompt = "scene")))
        ))).single().shots.single().plannedDurationSeconds)
        assertNull(restored.budget)
        assertNull(restored.shots.singleOrNull()?.let { it.cameraNotes })
        assertEquals("Plain", restored.title)
        assertTrue(restored.shots.isEmpty())
        assertTrue(restored.assets.isEmpty())
        assertTrue(restored.chainFrames)
    }

    @Test
    fun assetKindIsInferredFromPathWhenAbsent() {
        val raw = """{"version":2,"projects":[{"id":"p1","title":"P","workflowId":"m",
          "assets":[
            {"id":"a1","role":"CHARACTER_REFERENCE","path":"/x/hero.png","label":"h","order":0,"createdAt":1},
            {"id":"a2","role":"GENERIC_REFERENCE","path":"/x/clip.mp4","label":"c","order":1,"createdAt":2},
            {"id":"a3","role":"GENERIC_REFERENCE","path":"/x/voice.mp3","label":"v","order":2,"createdAt":3}
          ]}]}"""

        val assets = ProjectCodec.decode(raw).single().assets

        assertEquals(AssetKind.IMAGE, assets[0].kind)
        assertEquals(AssetKind.VIDEO, assets[1].kind)
        assertEquals(AssetKind.AUDIO, assets[2].kind)
    }

    @Test
    fun unknownTakeStatusSurvivesDecodeWithLegacyDefault() {
        val raw = """{"version":2,"projects":[{"id":"p1","title":"P","workflowId":"m",
          "shots":[{"id":"s1","prompt":"p","status":"done",
            "takes":[{"id":"t1","status":"weird-new-status"}]}]}]}"""

        val take = ProjectCodec.decode(raw).single().shots.single().takes.single()

        // Unknown take statuses keep their stored value; only missing ones default.
        assertEquals("weird-new-status", take.status)
    }

    @Test
    fun profilesRoundTripAndTolerateDamagedRows() {
        val project = VideoProject(
            id = "project-profiles",
            title = "Profiles",
            workflowId = "m",
            profiles = listOf(
                AssetProfile(
                    id = "profile-1",
                    name = "女主",
                    summary = "银发红瞳 20 岁左右",
                    kind = com.comfyremote.app.data.project.ProfileKind.CHARACTER,
                    assetIds = listOf("a-front", "a-side", "a-back"),
                    createdAt = 7L,
                ),
                AssetProfile(id = "profile-2", name = "旧城", kind = com.comfyremote.app.data.project.ProfileKind.SCENE),
            ),
        )

        val restored = ProjectCodec.decode(ProjectCodec.encode(listOf(project))).single()

        assertEquals(project.profiles, restored.profiles)

        val damaged = """{"version":2,"projects":[{"id":"p1","title":"P","workflowId":"m",
          "profiles":[
            {"id":"ok","name":"A"},
            {"name":"no id"},
            "not-an-object"
          ]}]}"""
        val profiles = ProjectCodec.decode(damaged).single().profiles
        assertEquals(1, profiles.size)
        assertEquals("A", profiles.single().name)
    }

    @Test
    fun legacyProjectsDecodeWithoutProfilesField() {
        val raw = """{"version":2,"projects":[{"id":"p1","title":"Old","workflowId":"m"}]}"""

        val project = ProjectCodec.decode(raw).single()

        assertTrue(project.profiles.isEmpty())
    }

    @Test
    fun malformedJsonDecodesToEmptyListInsteadOfCrashing() {
        assertTrue(ProjectCodec.decode("not json").isEmpty())
        assertTrue(ProjectCodec.decode("""{"version":2}""").isEmpty())
        assertTrue(ProjectCodec.decode("""{"version":2,"projects":["nope"]}""").isEmpty())
    }

    @Test
    fun rowsWithoutIdsAreDroppedAndCounted() {
        val raw = """{"version":2,"projects":[
          {"id":"p1","title":"Keep","workflowId":"m"},
          {"title":"No id","workflowId":"m"}
        ]}"""

        val projects = ProjectCodec.decode(raw)

        assertEquals(1, projects.size)
        assertEquals("Keep", projects.single().title)
        // The corruption guard must still see both rows in the raw file.
        assertEquals(2, ProjectCodec.rawProjectCount(raw))
    }

    @Test
    fun rawProjectCountRejectsUnparseableFiles() {
        assertNull(ProjectCodec.rawProjectCount("definitely not json"))
        assertEquals(0, ProjectCodec.rawProjectCount("""{"version":2,"projects":[]}"""))
        // A bare legacy-style array is not the new envelope and must be treated
        // as invalid so stores refuse to overwrite it.
        assertNull(ProjectCodec.rawProjectCount("""[{"id":"p1"}]"""))
    }
}
