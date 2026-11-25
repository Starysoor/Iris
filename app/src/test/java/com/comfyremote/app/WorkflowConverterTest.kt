package com.comfyremote.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the UI->API converter against real workflow fixtures fetched from the
 * live ComfyUI install (node tools/fetch_fixtures.mjs refreshes them).
 */
class WorkflowConverterTest {

    private fun readRes(p: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(p)
        if (stream == null) {
            val files = java.io.File("app/src/test/resources/fixtures").list()?.joinToString(",") ?: "DIR-MISSING"
            throw AssertionError("resource missing: $p | loader=${javaClass.classLoader} | fixtures dir=[$files] | cwd=${java.io.File(".").absolutePath}")
        }
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun info(): JSONObject = JSONObject(readRes("fixtures/object_info_trimmed.json"))

    private fun fixtureNames(): List<String> = listOf(
        "moss_a.json",
        "moss_b.json",
        "moss_layout.json",
        "qwen_turbo.json",
        "qwen_i2i.json",
        "qwen_edit.json",
        "qwen_t2i_all.json",
        "sdxl_anime.json",
        "sdxl_hires.json",
        "anima_aesthetic.json",
        "anima_turbo.json",
    )

    @Test
    fun allFixturesConvertWithoutWarnings() {
        val info = info()
        fixtureNames().forEach { name ->
            val conv = WorkflowConverter.convert(readRes("fixtures/$name"), info)
            assertTrue("$name: warnings ${conv.warnings}", conv.warnings.isEmpty())
            assertTrue("$name: no api nodes", conv.prompt.length() > 0)
            assertTrue("$name: no text fields", conv.fields.any { it.kind == FieldKind.TEXT })
            assertTrue("$name: no seed fields", conv.fields.any { it.kind == FieldKind.SEED })
        }
    }

    @Test
    fun sdxlStructureMatchesExpected() {
        val conv = WorkflowConverter.convert(readRes("fixtures/sdxl_anime.json"), info())
        val texts = conv.fields.filter { it.kind == FieldKind.TEXT }
        val seeds = conv.fields.filter { it.kind == FieldKind.SEED }
        val params = conv.fields.filter { it.kind == FieldKind.PARAM }
        assertEquals(3, texts.size)   // positive, negative, filename_prefix
        assertEquals(1, seeds.size)   // KSampler seed
        assertEquals(1, conv.notes.size) // MarkdownNote captured
        val ks = conv.prompt.optJSONObject("5")!!.optJSONObject("inputs")!!
        for (k in listOf("seed", "steps", "cfg", "sampler_name", "scheduler", "denoise"))
            assertTrue("KSampler missing $k", ks.has(k))
        assertTrue("combo param options not populated", params.any { it.ptype == "combo" && it.options.isNotEmpty() })
        val sampler = params.first { it.input == "sampler_name" }
        assertTrue(sampler.options.contains("euler"))
    }

    @Test
    fun img2imgDiscoversImageField() {
        val conv = WorkflowConverter.convert(readRes("fixtures/qwen_i2i.json"), info())
        val images = conv.fields.filter { it.kind == FieldKind.IMAGE }
        assertEquals(1, images.size)
        assertEquals("example.png", images[0].value)
    }

    @Test
    fun apiFormatExposesWidgetParamsAndSkipsLinks() {
        fun spec(type: Any, cfg: JSONObject? = null): org.json.JSONArray =
            org.json.JSONArray().put(type).put(cfg ?: JSONObject())
        val required = JSONObject()
            .put("seed", spec("INT", JSONObject().put("control_after_generate", true)))
            .put("steps", spec("INT", JSONObject().put("min", 1).put("max", 100)))
            .put("cfg", spec("FLOAT", JSONObject().put("min", 0.0).put("max", 20.0)))
            .put("denoise", spec("FLOAT"))
            .put("sampler_name", org.json.JSONArray().put(org.json.JSONArray().put("euler").put("dpmpp_2m")))
            .put("text", spec("STRING"))
            .put("image", spec("STRING", JSONObject().put("image_upload", true)))
        val info = JSONObject().put("KSampler", JSONObject().put("input", JSONObject().put("required", required)))
        val api = JSONObject().put(
            "5",
            JSONObject().put("class_type", "KSampler").put(
                "inputs",
                JSONObject()
                    .put("seed", 42)
                    .put("steps", 20)
                    .put("cfg", 7.5)
                    .put("denoise", 0.5)
                    .put("sampler_name", "euler")
                    .put("text", org.json.JSONArray().put("12").put(0))
                    .put("image", "ref.png"),
            ),
        )

        val fields = WorkflowConverter.convert(api.toString(), info).fields

        assertEquals(FieldKind.SEED, fields.first { it.input == "seed" }.kind)
        assertEquals("int", fields.first { it.input == "steps" }.ptype)
        assertEquals("float", fields.first { it.input == "cfg" }.ptype)
        assertEquals("float", fields.first { it.input == "denoise" }.ptype)
        val sampler = fields.first { it.input == "sampler_name" }
        assertEquals("combo", sampler.ptype)
        assertTrue(sampler.options.contains("euler"))
        assertEquals(FieldKind.IMAGE, fields.first { it.input == "image" }.kind)
        assertTrue("linked text input must not become a widget", fields.none { it.input == "text" })
    }

    @Test
    fun hiresFixHasTwoSeeds() {
        val conv = WorkflowConverter.convert(readRes("fixtures/sdxl_hires.json"), info())
        assertEquals(2, conv.fields.count { it.kind == FieldKind.SEED })
    }

    @Test
    fun buildPromptOverridesTextAndRandomizesSeed() {
        val conv = WorkflowConverter.convert(readRes("fixtures/sdxl_anime.json"), info())
        val f = conv.fields.first { it.kind == FieldKind.TEXT && it.input == "text" && it.nodeId == "2" }
        val values = mapOf(f.key to "1girl, test override")
        val (random1, seeds1) = WorkflowConverter.buildPrompt(conv, values, randomSeed = true)
        assertEquals("1girl, test override", random1.optJSONObject("2")!!.optJSONObject("inputs")!!.optString("text"))
        val seedStr = seeds1.values.first().toLong()
        assertTrue(seedStr in 0 until WorkflowConverter.SEED_MAX)

        val (fixed, seeds2) = WorkflowConverter.buildPrompt(conv, seeds1, randomSeed = false)
        val ks = fixed.optJSONObject("5")!!.optJSONObject("inputs")!!
        assertEquals(seeds1.values.first().toLong(), ks.optLong("seed"))
        assertEquals(seeds1["5:seed"], seeds2["5:seed"])
    }

    @Test
    fun buildPromptAppliesIntAndComboParams() {
        val conv = WorkflowConverter.convert(readRes("fixtures/sdxl_anime.json"), info())
        val steps = conv.fields.first { it.input == "steps" }
        val sampler = conv.fields.first { it.input == "sampler_name" }
        val values = mapOf(steps.key to "10", sampler.key to "euler")
        val (prompt, _) = WorkflowConverter.buildPrompt(conv, values, randomSeed = true)
        val ks = prompt.optJSONObject("5")!!.optJSONObject("inputs")!!
        assertEquals(10L, ks.optLong("steps"))
        assertEquals("euler", ks.optString("sampler_name"))
    }

    @Test
    fun nestedSubgraphsKeepObjectLinksAndTheHostWidgetValue() {
        val schema = JSONObject("""
            {
              "Text":{"input":{"required":{"text":["STRING",{}]}}},
              "Output":{"input":{"required":{"text":["STRING",{"forceInput":true}]}}}
            }
        """)
        val workflow = """
            {
              "version":1,
              "nodes":[
                {"id":3,"type":"outer","inputs":[{"name":"text","widget":{"name":"text"}}],"widgets_values":["host value"]},
                {"id":9,"type":"Output","inputs":[{"name":"text","link":6}]}
              ],
              "links":[{"id":6,"origin_id":3,"origin_slot":0,"target_id":9,"target_slot":0}],
              "definitions":{"subgraphs":[
                {
                  "id":"outer", "inputNode":{"id":-10},"outputNode":{"id":-20},
                  "inputs":[{"name":"text","linkIds":[1]}],"outputs":[{"name":"out","linkIds":[2]}],
                  "nodes":[{"id":2,"type":"inner","inputs":[{"name":"text","link":1}]}],
                  "links":[{"id":1,"origin_id":-10,"origin_slot":0,"target_id":2,"target_slot":0},
                           {"id":2,"origin_id":2,"origin_slot":0,"target_id":-20,"target_slot":0}]
                },
                {
                  "id":"inner", "inputNode":{"id":-10},"outputNode":{"id":-20},
                  "inputs":[{"name":"text","linkIds":[1]}],"outputs":[{"name":"out","linkIds":[2]}],
                  "nodes":[{"id":1,"type":"Text","inputs":[{"name":"text","link":1,"widget":{"name":"text"}}],"widgets_values":["old"]}],
                  "links":[{"id":1,"origin_id":-10,"origin_slot":0,"target_id":1,"target_slot":0},
                           {"id":2,"origin_id":1,"origin_slot":0,"target_id":-20,"target_slot":0}]
                }
              ]}
            }
        """.trimIndent()
        val converted = WorkflowConverter.convert(workflow, schema)
        assertTrue(converted.blockingProblems.toString(), converted.blockingProblems.isEmpty())
        assertEquals("3:2:1", converted.prompt.getJSONObject("9").getJSONObject("inputs").getJSONArray("text").getString(0))
        assertEquals("host value", converted.prompt.getJSONObject("3:2:1").getJSONObject("inputs").getString("text"))
        assertEquals("host value", converted.fields.single().value)
    }

    @Test
    fun brokenCanvasLinkIdentifiesTheDestinationField() {
        val workflow = """{"version":1,"nodes":[{"id":9,"type":"Output","inputs":[{"name":"text","link":6}]}],"links":[]}"""
        val schema = JSONObject("""{"Output":{"input":{"required":{"text":["STRING",{"forceInput":true}]}}}}""")
        val converted = WorkflowConverter.convert(workflow, schema)
        assertTrue(converted.blockingProblems.single().contains("#9 · text"))
    }

    @Test
    fun nullLoraTitlesFallBackToClassNameAndDuplicateLabelsKeepTheNodeId() {
        val schema = JSONObject("""
            {
              "LoraLoader": {
                "display_name": null,
                "input": {"required": {"lora_name": [["one.safetensors", "two.safetensors"]]}}
              }
            }
        """)
        val prompt = """
            {
              "1":{"class_type":"LoraLoader","_meta":{"title":null},"inputs":{"lora_name":"one.safetensors"}},
              "2":{"class_type":"LoraLoader","inputs":{"lora_name":"two.safetensors"}}
            }
        """.trimIndent()

        val fields = WorkflowConverter.convert(prompt, schema).fields
        assertEquals(setOf("1:lora_name", "2:lora_name"), fields.map { it.key }.toSet())
        assertTrue(fields.all { it.label.contains("LoraLoader") && it.label.endsWith("#${it.nodeId}") })
    }

    @Test
    fun mergeBuiltinsAppendsMissingDefaultsAndKeepsEdits() {
        val edited = AiSkillDef(AiSkillDef.DEFAULT_NATURAL_ID, "自定义扩写", "my prompt", true)
        val stored = listOf(edited)
        val merged = AiSkillDef.mergeBuiltins(stored)
        assertEquals("my prompt", merged.first { it.id == AiSkillDef.DEFAULT_NATURAL_ID }.systemPrompt)
        assertTrue(merged.none { it.id == AiSkillDef.DEFAULT_EN_ID })
        assertTrue(merged.any { it.id == AiSkillDef.DEFAULT_NEG_ID })
        assertTrue(merged.none { it.id == AiSkillDef.DEFAULT_TRANSLATE_ID })
        assertTrue(merged.any { it.id == AiSkillDef.DEFAULT_NSFW_EXPAND_ID })
        assertTrue(merged.any { it.id == AiSkillDef.DEFAULT_NSFW_TAGS_ID })
    }

    @Test
    fun convertedPromptStillValidatesAgainstObjectInfo() {
        // every converted node must exist in object_info and only use known inputs
        val info = info()
        fixtureNames().forEach { name ->
            val conv = WorkflowConverter.convert(readRes("fixtures/$name"), info)
            conv.prompt.keys().forEach { id ->
                val node = conv.prompt.optJSONObject(id)!!
                val type = node.optString("class_type")
                assertTrue("$name: node $id type $type not in object_info", info.has(type))
                val def = info.optJSONObject(type)!!
                val schema = mutableSetOf<String>()
                listOf("required", "optional").forEach { sec ->
                    def.optJSONObject("input")?.optJSONObject(sec)?.let {
                        it.keys().forEach { k -> schema.add(k) }
                    }
                }
                node.optJSONObject("inputs")!!.keys().forEach { inputName ->
                    // dynamic AUTOGROW inputs (e.g. images.image_1) are exempt
                    val known = inputName in schema || inputName.contains(".")
                    assertTrue("$name: node $id unknown input $inputName", known)
                }
            }
        }
    }
}
