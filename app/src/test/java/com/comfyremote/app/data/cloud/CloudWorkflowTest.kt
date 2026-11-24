package com.comfyremote.app.data.cloud

import com.comfyremote.app.PromptValidationException
import com.comfyremote.app.data.repository.WorkflowCapability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudWorkflowTest {

    private fun spec() = CloudWorkflowSpec(
        id = "minimax_h3_lightx2v_v5",
        name = "H3 多图参考生视频",
        kind = "video",
        description = "test",
        rules = listOf(
            CloudInputRule("prompt", "prompt", required = true),
            CloudInputRule("duration", "integer", required = false, defaultValue = "5", min = 1.0, max = 10.0),
            CloudInputRule(
                "resolution", "enum", required = false, defaultValue = "768p竖",
                options = listOf("480p竖", "768p竖"),
            ),
            CloudInputRule("seed", "integer", required = false, defaultValue = "42", min = 1.0, max = 999999999999999.0),
            CloudInputRule(
                "ref_image_0", "image", required = true,
                defaultValue = "default_local_path:/blank.png;https://example.com/blank.png",
            ),
        ),
    )

    @Test
    fun workflowEntriesCarryProviderPrefixAndLabel() {
        val summary = CloudWorkflowSummary(
            id = "wf-1",
            name = "Cloud workflow",
            description = "desc",
            kind = "video",
            priceType = "video_sec_cost",
        )

        val entry = summary.toEntry("provider:", "Provider B")

        assertEquals("provider:wf-1", entry.path)
        assertEquals("Provider B", entry.providerLabel)
        assertEquals(true, entry.cloud)
    }

    @Test
    fun fieldsMatchAutoDlRulesAndHideBlankAssetDefaults() {
        val fields = CloudWorkflowMapping.fields(spec())
        assertEquals(5, fields.size)
        assertEquals("", fields.first { it.key == "ref_image_0" }.value)
        assertTrue(fields.first { it.key == "ref_image_0" }.required)
        assertEquals("768p竖", fields.first { it.key == "resolution" }.value)
        assertEquals(listOf("480p竖", "768p竖"), fields.first { it.key == "resolution" }.options)
        assertEquals("5", fields.first { it.key == "duration" }.value)
    }

    @Test
    fun requiredEmptyImageBlocksAndOptionalMediaOnlyWarns() {
        val insight = CloudWorkflowMapping.analyze(
            spec(),
            CloudWorkflowMapping.fields(spec()),
            mapOf("prompt" to "a cat"),
        )
        assertTrue(!insight.compatible)
        assertTrue(insight.blockingIssues.any { it.message.contains("参考图片") })
        assertTrue(WorkflowCapability.IMG2VIDEO in insight.capabilities)
    }

    @Test
    fun builderMapsTypesAndResolvesLocalImage() = kotlinx.coroutines.runBlocking {
        val s = spec()
        val fields = CloudWorkflowMapping.fields(s)
        val (body, seeds) = CloudPromptBuilder.build(
            spec = s,
            fields = fields,
            values = mapOf(
                "prompt" to "a cat walking",
                "duration" to "7",
                "resolution" to "480p竖",
                "seed" to "12345",
                "ref_image_0" to "local:/tmp/ref.jpg",
            ),
            randomSeed = false,
            resolveImage = { value -> if (value == "local:/tmp/ref.jpg") "data:image/jpeg;base64,AA==" else null },
        )

        assertEquals("a cat walking", body.getString("prompt"))
        assertEquals(7L, body.getLong("duration"))
        assertEquals("480p竖", body.getString("resolution"))
        assertEquals(12345L, body.getLong("seed"))
        assertEquals("data:image/jpeg;base64,AA==", body.getString("ref_image_0"))
        assertEquals("12345", seeds["seed"])
    }

    @Test
    fun builderRandomizesSeedAndRejectsMissingRequiredImage() = kotlinx.coroutines.runBlocking {
        val s = spec()
        val fields = CloudWorkflowMapping.fields(s)
        val (body, seeds) = CloudPromptBuilder.build(
            s, fields,
            mapOf("prompt" to "x", "ref_image_0" to "local:/tmp/ref.jpg"),
            randomSeed = true,
            resolveImage = { "data:image/png;base64,AA==" },
        )
        assertTrue(body.getLong("seed") >= 1L)
        assertEquals(body.getLong("seed").toString(), seeds["seed"])

        val error = runCatching {
            CloudPromptBuilder.build(
                s, fields,
                mapOf("prompt" to "x"),
                randomSeed = true,
                resolveImage = { null },
            )
        }.exceptionOrNull()
        assertTrue(error is PromptValidationException)
    }

    @Test
    fun audioInputsMapToMediaFieldsAndDataUrls() = kotlinx.coroutines.runBlocking {
        val audioSpec = CloudWorkflowSpec(
            id = "audio-video",
            name = "audio video",
            kind = "video",
            description = "",
            rules = listOf(
                CloudInputRule("prompt", "prompt", required = true),
                CloudInputRule("ref_audio_0", "audio", required = true),
            ),
        )
        val fields = CloudWorkflowMapping.fields(audioSpec)
        val audio = fields.first { it.input == "ref_audio_0" }
        assertEquals(com.comfyremote.app.FieldKind.AUDIO, audio.kind)
        val (body, _) = CloudPromptBuilder.build(
            audioSpec,
            fields,
            mapOf("prompt" to "x", "ref_audio_0" to "local:/tmp/a.wav"),
            randomSeed = true,
            resolveImage = { "data:audio/wav;base64,AA==" },
        )
        assertEquals("data:audio/wav;base64,AA==", body.getString("ref_audio_0"))
    }

    @Test
    fun declaredMinMaxIsEnforcedBeforeSubmit() = kotlinx.coroutines.runBlocking {
        val s = spec()
        val fields = CloudWorkflowMapping.fields(s)
        val error = runCatching {
            CloudPromptBuilder.build(
                s,
                fields,
                mapOf("prompt" to "x", "duration" to "99", "ref_image_0" to "local:/tmp/a.jpg"),
                randomSeed = true,
                resolveImage = { "data:image/png;base64,AA==" },
            )
        }.exceptionOrNull()
        assertTrue(error is PromptValidationException)
        assertTrue(error!!.message!!.contains("10"))
    }

    @Test
    fun requiredVideoWorkflowIsRejectedBeforeSpendingCloudCredits() = kotlinx.coroutines.runBlocking {
        val videoSpec = CloudWorkflowSpec(
            id = "video-video",
            name = "video video",
            kind = "video",
            description = "",
            rules = listOf(
                CloudInputRule("prompt", "prompt", required = true),
                CloudInputRule("ref_video_0", "video", required = true),
            ),
        )
        val error = runCatching {
            CloudPromptBuilder.build(
                videoSpec,
                CloudWorkflowMapping.fields(videoSpec),
                mapOf("prompt" to "x"),
                randomSeed = true,
                resolveImage = { null },
            )
        }.exceptionOrNull()
        assertTrue(error is PromptValidationException)
    }

    @Test
    fun videoInputsKeepOfficialFormatsAcrossSnapshotAndSubmitUrlOrTypedInlineMedia() = kotlinx.coroutines.runBlocking {
        val data = JSONObject("""{
            "data":{"uuid":"motion-transfer","name":"Motion transfer","price_type":"video_sec_cost",
            "input_rules":{"ref_video":{"type":"video","required":true,"accept_types":["video/mp4","video/webm"]}}}
        }""")
        val parsed = CloudWorkflowParser.detail(data)
        val snapshot = requireNotNull(CloudWorkflowSpec.fromJson(parsed.toJson()))
        val fields = CloudWorkflowMapping.fields(snapshot)
        assertEquals(com.comfyremote.app.FieldKind.VIDEO, fields.single().kind)
        assertEquals(listOf("video/mp4", "video/webm"), snapshot.rule("ref_video")!!.acceptedMediaTypes)
        val url = "https://example.test/reference.mp4?signature=abc"
        val (remote, _) = CloudPromptBuilder.build(snapshot, fields, mapOf("ref_video" to url), false) { null }
        assertEquals(url, remote.getString("ref_video"))
        val webm = "data:video/webm;base64,AA=="
        val (local, _) = CloudPromptBuilder.build(snapshot, fields, mapOf("ref_video" to "local:/tmp/ref.webm"), false) { webm }
        assertEquals(webm, local.getString("ref_video"))
        val wrongFormat = runCatching {
            CloudPromptBuilder.build(snapshot, fields, mapOf("ref_video" to "local:/tmp/ref.mov"), false) {
                "data:video/quicktime;base64,AA=="
            }
        }.exceptionOrNull()
        assertTrue(wrongFormat is PromptValidationException)
    }

    @Test
    fun pricingTiersAndPerSecondMinimumAreParsedAndEstimated() {
        val detail = JSONObject().put("data", JSONObject()
            .put("uuid", "v2")
            .put("name", "H3 视频")
            .put("price_type", "minimax_h3")
            .put("billing_config", JSONObject().put("minimax_h3", JSONArray()
                .put(JSONObject().put("resolution", "480p").put("price", 40).put("off_peak_price", 30))
                .put(JSONObject().put("resolution", "768p").put("price", 50).put("off_peak_price", 40))))
            .put("input_rules", JSONObject().put("prompt", JSONObject().put("type", "prompt").put("required", true))))
        val spec = CloudWorkflowParser.detail(detail)
        assertEquals("per_call", spec.pricing!!.mode)
        assertEquals(40, spec.pricing!!.estimate("480p竖", 5))
        assertEquals(50, spec.pricing!!.estimate("768p横", 10))
        assertTrue(spec.pricing!!.label.contains("40") && spec.pricing!!.label.contains("50"))

        val audio = JSONObject().put("data", JSONObject()
            .put("uuid", "a2")
            .put("name", "tts")
            .put("price_type", "audio_sec_cost")
            .put("billing_config", JSONObject().put("audio_sec_cost", JSONObject()
                .put("price", 1).put("minimum_price", 10)))
            .put("input_rules", JSONObject().put("prompt_text", JSONObject().put("type", "string").put("required", true))))
        val audioSpec = CloudWorkflowParser.detail(audio)
        assertEquals("per_second", audioSpec.pricing!!.mode)
        assertEquals(10, audioSpec.pricing!!.estimate(null, 3))
        assertEquals(20, audioSpec.pricing!!.estimate(null, 20))
    }

    @Test
    fun parserClassifiesCatalogueAndTaskResults() {
        val catalog = JSONObject()
            .put("data", JSONObject()
                .put("max_page", 1)
                .put("list", JSONArray()
                    .put(JSONObject().put("uuid", "v1").put("name", "H3 文生视频").put("price_type", "minimax_h3"))
                    .put(JSONObject().put("uuid", "a1").put("name", "indextts2").put("price_type", "audio_sec_cost"))
                ))
        val (list, maxPage) = CloudWorkflowParser.list(catalog)
        assertEquals(1, maxPage)
        assertEquals("video", list.first { it.id == "v1" }.kind)
        assertEquals("audio", list.first { it.id == "a1" }.kind)

        val detail = JSONObject().put("data", JSONObject()
            .put("uuid", "v1")
            .put("name", "H3 文生视频")
            .put("price_type", "minimax_h3")
            .put("input_rules", JSONObject()
                .put("prompt", JSONObject().put("type", "prompt").put("required", true))
                .put("duration", JSONObject().put("type", "integer").put("default", 5).put("min", 1).put("max", 15))
                .put("resolution", JSONObject().put("type", "enum").put("default", "768p竖")
                    .put("options", JSONArray().put(JSONObject().put("label", "480p竖")).put(JSONObject().put("label", "768p竖"))))
            ))
        val parsed = CloudWorkflowParser.detail(detail)
        assertEquals("video", parsed.kind)
        assertEquals(3, parsed.rules.size)
        assertEquals(1.0, parsed.rule("duration")!!.min)
        assertEquals(listOf("480p竖", "768p竖"), parsed.rules.first { it.name == "resolution" }.options)

        val result = CloudWorkflowParser.taskResult(JSONObject().put("data", JSONObject()
            .put("task_id", "t1")
            .put("status", "SUCCESS")
            .put("results", JSONArray().put(JSONObject()
                .put("url", "https://cdn/x.mp4").put("type", "video").put("file_type", "mp4").put("output_type", "output")))
        ))
        assertEquals("SUCCESS", result.status)
        assertEquals("video", result.results.single().type)
        assertEquals("mp4", result.results.single().fileType)
        assertEquals("output", result.results.single().outputType)
    }
}
