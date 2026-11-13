package com.comfyremote.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientTest {

    @Test
    fun parseChatCompletionExtractsContent() {
        val body = """{"choices":[{"message":{"role":"assistant","content":" 1girl, cafe, sunset "}}]}"""
        assertEquals("1girl, cafe, sunset", AiClient.parseChatCompletion(body))
    }

    @Test
    fun parseChatCompletionSurfacesProviderError() {
        val body = """{"error":{"message":"Incorrect API key"}}"""
        try {
            AiClient.parseChatCompletion(body)
            throw AssertionError("expected ComfyException")
        } catch (e: ComfyException) {
            assertTrue(e.message!!.contains("Incorrect API key"))
        }
    }

    @Test(expected = ComfyException::class)
    fun parseChatCompletionFailsOnEmptyContent() {
        AiClient.parseChatCompletion("""{"choices":[]}""")
    }

    @Test
    fun skillRecommendationFollowsWorkflowType() {
        val skills = AiSkillDef.defaults()
        assertEquals(AiSkillDef.DEFAULT_TAGS_ID, AiSkillDef.recommendedOrder(skills, "SDXL-动漫文生图").first().id)
        assertEquals(AiSkillDef.DEFAULT_TAGS_ID, AiSkillDef.recommendedOrder(skills, "Anima-Turbo极速文生图").first().id)
        assertEquals(AiSkillDef.DEFAULT_NATURAL_ID, AiSkillDef.recommendedOrder(skills, "MOSS-A-加速文生图").first().id)
        assertEquals(AiSkillDef.DEFAULT_NATURAL_ID, AiSkillDef.recommendedOrder(skills, "Qwen-2.1-图生图").first().id)
        // custom skills follow the recommended built-in
        val withCustom = skills + AiSkillDef("custom_1", "我的风格", "prompt", false)
        val ordered = AiSkillDef.recommendedOrder(withCustom, "SDXL-动漫文生图")
        assertEquals(AiSkillDef.DEFAULT_TAGS_ID, ordered.first().id)
        assertEquals("custom_1", ordered.last().id)
    }

    @Test
    fun buildChatBodyDefaultsOmitTunables() {
        val body = AiClient.buildChatBody("m", "sys", "user text", AiTunables())
        assertEquals("m", body.optString("model"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_tokens"))
        assertEquals(2, body.optJSONArray("messages")!!.length())
        assertEquals("sys", body.optJSONArray("messages")!!.optJSONObject(0)!!.optString("content"))
        assertEquals("user text", body.optJSONArray("messages")!!.optJSONObject(1)!!.optString("content"))
    }

    @Test
    fun buildChatBodyAppliesTunablesAndExtraOverrides() {
        val extra = JSONObject().put("reasoning_effort", "high").put("top_p", 0.9)
        val body = AiClient.buildChatBody(
            "m", "sys", "u",
            AiTunables(temperature = 1.2, maxTokens = 2048, extraJson = extra),
        )
        assertEquals(1.2, body.optDouble("temperature"), 1e-9)
        assertEquals(2048, body.optInt("max_tokens"))
        assertEquals("high", body.optString("reasoning_effort"))
        assertEquals(0.9, body.optDouble("top_p"), 1e-9)
    }

    @Test
    fun buildVisionChatBodyUsesOpenAiImageUrlContent() {
        val body = AiClient.buildVisionChatBody(
            model = "vision-model",
            systemPrompt = "look",
            userText = "rewrite",
            imageDataUrl = "data:image/jpeg;base64,AA==",
            t = AiTunables(),
        )
        val messages = body.getJSONArray("messages")
        assertEquals("look", messages.getJSONObject(0).getString("content"))
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("rewrite", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AA==",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun multiImageVisionBodyCarriesRoleAndOrder() {
        val body = AiClient.buildVisionChatBody(
            model = "vision-model",
            systemPrompt = "look",
            userText = "animate",
            images = listOf(
                com.comfyremote.app.AiImageInput(
                    dataUrl = "data:image/png;base64,AA==",
                    role = com.comfyremote.app.data.reference.ReferenceRole.START_FRAME,
                    order = 1,
                    label = "start",
                ),
                com.comfyremote.app.AiImageInput(
                    dataUrl = "data:image/png;base64,BB==",
                    role = com.comfyremote.app.data.reference.ReferenceRole.END_FRAME,
                    order = 2,
                    label = "end",
                ),
            ),
            t = AiTunables(),
        )
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(content.toString().contains("start frame"))
        assertTrue(content.toString().contains("end frame"))
        assertEquals(2, (0 until content.length()).count { content.getJSONObject(it).getString("type") == "image_url" })
    }

    @Test
    fun tagProfilesResolveAndAddQualityConventions() {
        assertEquals(TagProfile.ILLUSTRIOUS, TagProfile.AUTO.resolve("Anima-Turbo极速文生图"))
        assertEquals(TagProfile.ILLUSTRIOUS, TagProfile.AUTO.resolve("SDXL-动漫文生图"))
        assertEquals(TagProfile.PONY, TagProfile.AUTO.resolve("Pony-V6"))
        assertEquals(TagProfile.AUTO, TagProfile.AUTO.resolve("MOSS-A-加速文生图"))
        assertTrue(tagProfileInstruction(TagProfile.PONY, "anything").contains("score_9"))
        assertTrue(tagProfileInstruction(TagProfile.DANBOORU, "anything").contains("masterpiece"))
        assertTrue(tagProfileInstruction(TagProfile.AUTO, "MOSS").isEmpty())
    }

    @Test
    fun applyThinkingModeWritesExpectedFields() {
        // mirrors MainViewModel.applyThinkingMode mapping via the same JSON contract
        val extra = JSONObject()
        extra.put("thinking", JSONObject().put("type", "disabled"))
        assertTrue(extra.optJSONObject("thinking")!!.optString("type") == "disabled")
        extra.remove("thinking")
        extra.put("reasoning_effort", "medium")
        assertEquals("medium", extra.optString("reasoning_effort"))
        assertNull(extra.optJSONObject("thinking"))
    }
    @Test
    fun minimaxH3RecommendedByNameAndPathWithoutChangingImageRecommendations() {
        val skills = AiSkillDef.defaults()
        val expected = AiSkillDef.DEFAULT_H3_VIDEO_ID
        listOf(
            "minimax_h3_lightx2v_v5",
            "MiniMax-H3",
            "siliconflow:MiniMax-H3",
            "普通工作流 autodl-model:minimax-h3-i2v",
            "无说明 autodl:/api/h3/video",
        ).forEach { workflow ->
            assertEquals(workflow, expected, AiSkillDef.recommendedOrder(skills, workflow).first().id)
        }
        assertEquals(
            AiSkillDef.DEFAULT_TAGS_ID,
            AiSkillDef.recommendedOrder(skills, "Anima-Turbo anime").first().id,
        )
        assertEquals(
            AiSkillDef.DEFAULT_NATURAL_ID,
            AiSkillDef.recommendedOrder(skills, "Qwen image model").first().id,
        )
        val custom = AiSkillDef("custom-h3", "我的技巧", "local", false)
        val ordered = AiSkillDef.recommendedOrder(skills + custom, "autodl:minimax-h3")
        assertEquals(expected, ordered.first().id)
        assertEquals(custom, ordered.last())
        assertEquals(skills.size + 1, ordered.size)
    }

    @Test
    fun h3PromptContractCoversAudioFrameModesDialogueAndSafety() {
        val skill = AiSkillDef.H3_VIDEO_DEFAULT
        val prompt = skill.systemPrompt
        assertEquals("builtin_h3_video", skill.id)
        assertTrue(skill.builtin)
        assertTrue(skill.description.contains("huggingface.co/MiniMaxAI/MiniMax-H3/"))
        assertTrue(skill.scopes.contains(SkillScope.TXT2VIDEO))
        assertTrue(skill.scopes.contains(SkillScope.IMG2VIDEO))
        assertTrue(skill.scopes.contains(SkillScope.START_END_VIDEO))
        assertEquals(SkillExecution.TEXT_MODEL, skill.execution)
        assertEquals(SkillOutput.REPLACE_FIELD, skill.output)
        val fields = listOf(
            "integrated_multimodal_description:",
            "overall_soundscape:",
            "non_diegetic_music:",
        )
        fields.forEach { assertTrue("Missing H3 core field: $it", prompt.contains(it)) }
        assertTrue(fields.map { prompt.indexOf(it) }.zipWithNext().all { (a, b) -> a < b })
        listOf("T2VA", "I2VA", "FL2VA", "L2VA", "[Shot 1]", "Picture 1",
            "Picture 2", "0.00", "S.SS", "(S1)", "(S2)", "<d>[Chinese]",
            "N/A", "Do not repeat dialogue", "No soundtrack",
            "one continuous", "original", "camera movement", "do not fabricate",
        ).forEach { term ->
            assertTrue("H3 prompt lost guidance: $term", prompt.contains(term, ignoreCase = true))
        }
    }

}
