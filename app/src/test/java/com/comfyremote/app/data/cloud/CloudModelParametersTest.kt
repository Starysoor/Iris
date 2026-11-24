package com.comfyremote.app.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelParametersTest {
    @Test
    fun autoDlUsesOneFamilyDecisionForNamespacedIdsAndUnsupportedMedia() {
        assertEquals("h3", autoDlModelFamily("MiniMaxAI/MiniMax-H3"))
        assertEquals("h3", autoDlModelFamily("vendor/MINIMAX_H3"))
        assertEquals("wan", autoDlModelFamily("Wan-AI/Wan2.7-I2V"))
        assertEquals("image", autoDlModelFamily("vendor/MiniMax-H3-Image", "image"))
        assertEquals("", autoDlModelFamily("vendor/veo-3", "video"))
    }

    @Test
    fun autoDlQwenImageUsesTheDocumentedSizeOptions() {
        assertEquals(listOf("1328x1328", "1664x928"), autoDlImageSizes("Qwen-Image"))
        assertEquals(listOf("1024x1024"), autoDlImageSizes("Z-Image-Turbo"))
        assertTrue(autoDlImageSizes("Qwen-Image-Edit").isEmpty())
    }

    @Test
    fun autoDlWan27KeepsTheModelIdAndSendsBothFramesAndNegativePrompt() {
        val model = "Wan-AI/Wan2.7-I2V"
        val body = JSONObject()
            .put("first_frame_image", "https://example.test/start.png")
            .put("last_frame_image", "https://example.test/end.png")
            .put("negative_prompt", "flickering")
            .put("duration", 6)

        val payload = buildAutoDlWanPayload(model, body)
        val input = payload.getJSONObject("input")
        val frames = input.getJSONArray("media")
        assertEquals(model, payload.getString("model"))
        assertEquals("first_frame", frames.getJSONObject(0).getString("type"))
        assertEquals("last_frame", frames.getJSONObject(1).getString("type"))
        assertEquals("https://example.test/end.png", frames.getJSONObject(1).getString("url"))
        assertEquals("flickering", input.getString("negative_prompt"))
        assertEquals(6, payload.getJSONObject("parameters").getInt("duration"))
    }

    @Test
    fun gptImageModelsExposeAReferenceImageAndRealQualityOptions() {
        val rules = CloudModelParameters.openAiRules("gpt-image-2.5-sunburst")
        val image = rules.firstOrNull { it.name == "image" }
        val quality = rules.firstOrNull { it.name == "quality" }

        assertTrue(image?.isImage == true)
        assertEquals(4, rules.count { it.isImage })
        assertTrue(rules.any { it.name == "image4" && it.isImage })
        assertTrue(quality?.options.orEmpty().contains("xhigh"))
        assertTrue(quality?.options.orEmpty().contains("max"))
    }

    @Test
    fun dalleThreeDoesNotPretendToAcceptAReferenceImage() {
        val rules = CloudModelParameters.openAiRules("dall-e-3")

        assertTrue(rules.none { it.isImage })
        assertTrue(rules.any { it.name == "style" })
    }

    @Test
    fun geminiImageModelsExposeInlineReferenceImages() {
        val flash = CloudModelParameters.geminiRules("gemini-3.1-flash-image")
        val pro = CloudModelParameters.geminiRules("gemini-3-pro-image")
        val lite = CloudModelParameters.geminiRules("gemini-3.1-flash-lite-image")

        assertEquals(4, flash.count { it.isImage })
        assertTrue(flash.any { it.name == "image3" && it.isImage })
        assertEquals(
            listOf("0.5K", "1K", "2K", "4K"),
            flash.first { it.name == "image_size" }.options,
        )
        assertTrue(flash.first { it.name == "aspect_ratio" }.options.contains("8:1"))
        assertTrue(pro.any { it.isImage })
        assertEquals(listOf("1K", "2K", "4K"), pro.first { it.name == "image_size" }.options)
        assertFalse(pro.first { it.name == "aspect_ratio" }.options.contains("8:1"))
        assertEquals(listOf("0.5K", "1K"), lite.first { it.name == "image_size" }.options)
    }

    @Test
    fun siliconFlowEditModelsRequireAnImageWhileTextToImageDoesNot() {
        val edit = CloudModelParameters.siliconFlowRules("Qwen/Qwen-Image-Edit-2509")
        val singleEdit = CloudModelParameters.siliconFlowRules("Qwen/Qwen-Image-Edit")
        val text = CloudModelParameters.siliconFlowRules("Qwen/Qwen-Image")

        assertEquals(3, edit.count { it.isImage })
        assertTrue(edit.first { it.name == "image" }.required)
        assertFalse(edit.first { it.name == "image2" }.required)
        assertTrue(edit.none { it.name == "image_size" })
        assertTrue(edit.none { it.name == "guidance_scale" })
        assertEquals(20.0, edit.first { it.name == "num_inference_steps" }.max!!, 0.0)
        assertEquals(1, singleEdit.count { it.isImage })
        assertTrue(singleEdit.none { it.name == "image_size" })
        assertTrue(text.none { it.isImage })
        assertEquals(9_999_999_999.0, edit.first { it.name == "seed" }.max!!, 0.0)
        assertEquals(9_999_999_999.0, text.first { it.name == "seed" }.max!!, 0.0)
    }

    @Test
    fun siliconFlowMiniMaxH3IsAVideoModelWithKeyframes() {
        assertEquals("video", CloudModelParameters.kindOf("MiniMaxAI/MiniMax-H3"))
        val rules = CloudModelParameters.siliconFlowRules("MiniMaxAI/MiniMax-H3")

        assertEquals(5.0, rules.first { it.name == "duration" }.min!!, 0.0)
        assertEquals(15.0, rules.first { it.name == "duration" }.max!!, 0.0)
        assertEquals(listOf("480p", "768p"), rules.first { it.name == "resolution" }.options)
        assertEquals(
            listOf("1:1", "3:4", "4:3", "9:16", "16:9", "21:9"),
            rules.first { it.name == "ratio" }.options,
        )
        assertTrue(rules.any { it.name == "first_frame_image" && it.isImage })
        assertTrue(rules.any { it.name == "last_frame_image" && it.isImage })
        assertTrue(rules.none { it.name == "image_size" })
    }

    @Test
    fun referenceImageInputsAreOrderedAndIgnoreBlankSlots() {
        val body = org.json.JSONObject()
            .put("image3", "three")
            .put("image", "one")
            .put("image2", "")
            .put("prompt", "ignored")

        assertEquals(
            listOf("image" to "one", "image3" to "three"),
            CloudModelParameters.referenceImageInputs(body),
        )
    }

    @Test
    fun mediaFilterDropsTextModelsButKeepsImageAndVideoModels() {
        assertFalse(CloudModelParameters.looksLikeMediaModel("claude-fable-5"))
        assertFalse(CloudModelParameters.looksLikeMediaModel("DeepSeek-V4-Pro"))
        assertFalse(CloudModelParameters.looksLikeMediaModel("MiniMax-M3.1-Flash"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("gpt-image-2"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("dall-e-3"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("Qwen-Image"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("nano-banana-2"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("MiniMax-H3"))
        assertTrue(CloudModelParameters.looksLikeMediaModel("Wan-AI/Wan2.2-I2V-A14B"))
    }

    @Test
    fun capabilityTagsFollowTheRealInputContract() {
        val editTags = CloudModelCapabilities.of("gpt-image-2.5-flare")
        val seedreamTags = CloudModelCapabilities.of("doubao-seedream-5-0-flash")
        val plainTags = CloudModelCapabilities.of("Z-Image-Turbo")

        assertTrue(CloudModelCapabilities.EDIT in editTags)
        assertTrue(CloudModelCapabilities.EDIT in seedreamTags)
        assertFalse(CloudModelCapabilities.EDIT in plainTags)
    }
}
