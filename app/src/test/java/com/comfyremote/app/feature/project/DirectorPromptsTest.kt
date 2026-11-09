package com.comfyremote.app.feature.project

import com.comfyremote.app.data.project.ProjectShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorPromptsTest {

    @Test
    fun parsePlanReadsTheContractFormat() {
        val raw = """
            1 | 城市清晨，阳光穿过高楼，主角独自走过空旷街道 | 缓慢推近 | 3
            2 | 主角停下脚步回头，风吹起外套 | 横摇跟随 | 4
        """.trimIndent()

        val plan = DirectorPrompts.parsePlan(raw)

        assertEquals(2, plan.size)
        assertEquals("城市清晨，阳光穿过高楼，主角独自走过空旷街道", plan[0].prompt)
        assertEquals("缓慢推近", plan[0].cameraNotes)
        assertEquals(3, plan[0].suggestedSeconds)
        assertEquals("主角停下脚步回头，风吹起外套", plan[1].prompt)
        assertEquals(4, plan[1].suggestedSeconds)
    }

    @Test
    fun parsePlanToleratesMarkdownAndMissingColumns() {
        val raw = """
            ## 镜头计划
            - 1 | 夜色中的霓虹街道，雨后反光 | 固定机位 | 5s
            * 2 | 特写主角眼神变化 | | 3秒
            3 | 无运镜列的行也算
            这不是有效行
        """.trimIndent()

        val plan = DirectorPrompts.parsePlan(raw)

        // The third numbered row (no camera column) is still a valid prompt row.
        assertEquals(3, plan.size)
        assertEquals("夜色中的霓虹街道，雨后反光", plan[0].prompt)
        assertEquals(5, plan[0].suggestedSeconds)
        assertEquals("特写主角眼神变化", plan[1].prompt)
        assertEquals("", plan[1].cameraNotes)
        assertEquals(3, plan[1].suggestedSeconds)
        assertEquals("无运镜列的行也算", plan[2].prompt)
        assertEquals("", plan[2].cameraNotes)
        assertNull(plan[2].suggestedSeconds)
    }

    @Test
    fun parsePlanSkipsShortPromptsAndOutOfRangeSeconds() {
        val raw = """
            1 | ok | push | 2
            2 | 短 | push | 1
            3 | 这行提示词足够长了所以会被保留 | push | 999
            4 | 这行提示词足够长了所以会被保留 | push | abc
        """.trimIndent()

        val plan = DirectorPrompts.parsePlan(raw)

        // Row 1 prompt "ok" too short; row 2 prompt "短" too short; rows 3-4 keep
        // with null seconds (out of range / not numeric).
        assertEquals(2, plan.size)
        assertNull(plan[0].suggestedSeconds)
        assertNull(plan[1].suggestedSeconds)
    }

    @Test
    fun parsePlanHandlesGarbageWithoutThrowing() {
        assertTrue(DirectorPrompts.parsePlan("").isEmpty())
        assertTrue(DirectorPrompts.parsePlan("绝对没有管道的一行").isEmpty())
        assertTrue(DirectorPrompts.parsePlan("|||").isEmpty())
    }

    @Test
    fun directorReceivesProfilesTimelineTakeStatusAndRealConfiguredChoices() {
        val shot = com.comfyremote.app.data.project.ProjectShot(
            prompt = "赤月下的舞会",
            cameraNotes = "从手部特写推到全景",
            plannedDurationSeconds = 10,
            status = "done",
            takes = listOf(com.comfyremote.app.data.project.ShotTake(
                status = "done", workflowPath = "autodl:video",
            )),
        )
        val project = com.comfyremote.app.data.project.VideoProject(
            title = "绯红舞会",
            workflowId = "",
            targetDurationSeconds = 120,
            budget = 30.0,
            profiles = listOf(com.comfyremote.app.data.project.AssetProfile(
                name = "紫裙舞者",
                summary = "脸部冷静，不笑，保持服装一致",
            )),
            shots = listOf(shot),
            timeline = com.comfyremote.app.data.project.Timeline(
                videoTrack = listOf(com.comfyremote.app.data.project.TimelineClip(
                    sourcePath = "/tmp/studio.mp4", sourceOutMs = 10000L,
                )),
            ),
        )
        val context = DirectorPrompts.user(project, listOf("AutoDL · H3 video"))
        assertTrue("紫裙舞者" in context)
        assertTrue("不笑" in context)
        assertTrue("赤月下的舞会" in context)
        assertTrue("完成 Take 1" in context)
        assertTrue("视频片段 1 个 / 10 秒" in context)
        assertTrue("30.0" in context)
        assertTrue("AutoDL · H3 video" in context)
        assertTrue("剩余目标时长 110 秒" in context)
    }

    @Test
    fun versionedJsonPlanRetainsGroundedEvidenceAndRisks() {
        val response = """
            ```json
            {"schemaVersion":1,"shots":[
              {"prompt":"紫裙舞者在血月下旋转，双手高举","cameraNotes":"俯视缓缓下推","durationSeconds":12,
               "referenceAssetIds":["asset-dress"],"workflowPath":"autodl:video",
               "rationale":"参考现有角色档案","risk":"单次视频上限可能是5秒"},
              {"prompt":"切到仪式大厅的红色血管特写","cameraNotes":"固定镜头","durationSeconds":4}
            ]}
            ```
        """.trimIndent()
        val shots = DirectorPrompts.parsePlan(response)
        assertEquals(2, shots.size)
        assertEquals(listOf("asset-dress"), shots[0].referenceAssetIds)
        assertEquals(12, shots[0].suggestedSeconds)
        assertEquals("autodl:video", shots[0].workflowPath)
        assertTrue(shots[0].risk.contains("5秒"))
        assertTrue(shots[0].rationale.contains("档案"))
    }

    @Test
    fun jsonWrongVersionOrMalformedDoesNotFallbackToUnsafeTextRows() {
        val bad = """{"schemaVersion":2,"shots":[{"prompt":"not supported","durationSeconds":5}]}"""
        assertTrue(DirectorPrompts.parsePlan(bad).isEmpty())
        assertTrue(DirectorPrompts.parsePlan("""{"schemaVersion":1,"shots":""").isEmpty())
    }

    @Test
    fun validationRejectsInventedReferencesWithoutChangingExistingAssets() {
        val project = com.comfyremote.app.data.project.VideoProject(
            title = "Fantasy", workflowId = "",
            assets = listOf(
                com.comfyremote.app.data.reference.ProjectAsset(
                    id = "character-view", path = "/tmp/character.png"),
                com.comfyremote.app.data.reference.ProjectAsset(
                    id = "audio-voice", path = "/tmp/voice.wav",
                    kind = com.comfyremote.app.data.reference.AssetKind.AUDIO),
            ),
        )
        val valid = DirectorPrompts.SuggestedShot("Moonlit dance scene", "tracking", 8,
            referenceAssetIds = listOf("character-view"))
        assertTrue(DirectorPrompts.validationIssues(valid, project).isEmpty())
        val invalid = valid.copy(referenceAssetIds = listOf("character-view","missing","audio-voice"))
        val issues = DirectorPrompts.validationIssues(invalid, project)
        assertEquals(2, issues.size)
        assertTrue(issues.any { "missing" in it })
        assertTrue(issues.any { "audio-voice" in it })
    }

    @Test
    fun userPromptCarriesProjectContext() {
        val project = com.comfyremote.app.data.project.VideoProject(
            id = "p1",
            title = "雨夜追逐",
            workflowId = "model",
            synopsis = "一个外卖员在雨夜穿梭送餐",
            stylePrompt = "赛博朋克 霓虹",
            aspectRatio = "9:16",
            targetDurationSeconds = 30,
            shots = listOf(ProjectShot(id = "s1", prompt = "已有镜头")),
        )

        val user = DirectorPrompts.user(project)

        assertTrue("雨夜追逐" in user)
        assertTrue("外卖员" in user)
        assertTrue("赛博朋克" in user)
        assertTrue("9:16" in user)
        assertTrue("30" in user)
        assertTrue("已有镜头" in user)
        // With a 30s target and one existing shot the plan asks for ~5 more.
        assertTrue("5 个新镜头" in user)
    }
}
