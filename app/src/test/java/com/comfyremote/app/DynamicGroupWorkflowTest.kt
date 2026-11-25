package com.comfyremote.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COMFY_DYNAMICGROUP_V3 inputs (ComfyUI core "Load LoRA (Model)" /
 * "Load LoRA (Text Encoder)") take a stack of rows. The canvas stores a row
 * counter followed by each row's widget values; the API prompt flattens them
 * to `<group>.<index>.<field>` keys, and the server rejects the prompt when
 * fewer than `min` rows arrive.
 */
class DynamicGroupWorkflowTest {

    private fun loraStackSchema(min: Int = 1, max: Int = 20): JSONObject = JSONObject("""
        {
          "UNETLoader": {"input": {"required": {
            "unet_name": [["unet.safetensors"]], "weight_dtype": [["default"]]
          }}},
          "LoadLoraModel": {
            "display_name": "Load LoRA (Model)",
            "input_order": {"required": ["model", "loras"]},
            "input": {"required": {
              "model": ["MODEL", {}],
              "loras": ["COMFY_DYNAMICGROUP_V3", {
                "tooltip": "Each row applies one LoRA to the model.",
                "template": {"required": {
                  "lora_name": ["COMBO", {"options": ["a.safetensors", "b.safetensors"]}],
                  "strength": ["FLOAT", {"default": 1.0, "min": -100.0, "max": 100.0}],
                  "enabled": ["BOOLEAN", {"default": true}]
                }},
                "min": $min, "max": $max, "group_name": "LoRA"
              }]
            }}
          }
        }
    """.trimIndent())

    /** One canvas row socket per template field, as the ComfyUI frontend lists them. */
    private fun rowSockets(rows: IntRange = 0..0, strengthLink: Int? = null, seed: Boolean = false): String =
        rows.joinToString { row ->
            listOf(
                """{"name":"loras.$row.lora_name","type":"COMBO","link":null,"widget":{"name":"loras.$row.lora_name"}}""",
                """{"name":"loras.$row.strength","type":"FLOAT","link":$strengthLink,"widget":{"name":"loras.$row.strength"}}""",
                """{"name":"loras.$row.enabled","type":"BOOLEAN","link":null,"widget":{"name":"loras.$row.enabled"}}""",
            ).plus(
                if (seed) listOf(
                    """{"name":"loras.$row.seed","type":"INT","link":null,"widget":{"name":"loras.$row.seed"}}""",
                ) else emptyList(),
            ).joinToString(",")
        }

    /** The canvas node exactly as saved by the ComfyUI frontend. */
    private fun loraStackNode(
        widgets: String,
        sockets: String = rowSockets(),
        title: String = "多 LoRA 节点（点 ➕ 加行堆叠）",
        extraNodes: String = "",
        extraLinks: String = "",
    ): String = """
        {
          "version":1,
          "nodes":[
            {"id":1,"type":"UNETLoader","widgets_values":["unet.safetensors","default"],"outputs":[{"name":"MODEL","type":"MODEL","links":[1]}]},
            {"id":2,"type":"LoadLoraModel","title":"$title","mode":0,
             "inputs":[{"name":"model","type":"MODEL","link":1},$sockets],
             "outputs":[{"name":"MODEL","type":"MODEL","links":[]}],
             "widgets_values":$widgets}$extraNodes
          ],
          "links":[[1,1,0,2,0,"MODEL"]$extraLinks]
        }
    """.trimIndent()

    private fun convert(workflow: String, schema: JSONObject = loraStackSchema()): ConvertedWorkflow {
        val converted = WorkflowConverter.convert(workflow, schema)
        assertTrue("blocking: ${converted.blockingProblems}", converted.blockingProblems.isEmpty())
        return converted
    }

    private fun nodeInputs(converted: ConvertedWorkflow, id: String = "2"): JSONObject =
        converted.prompt.getJSONObject(id).getJSONObject("inputs")

    private fun keys(converted: ConvertedWorkflow, nodeId: String = "2") =
        converted.fields.filter { it.nodeId == nodeId }.associateBy { it.input }

    @Test
    fun savedLoraStackWorkflowExposesEveryRow() {
        val converted = WorkflowConverter.convert(
            javaClass.classLoader!!.getResourceAsStream("fixtures/krea2_anime.json")!!
                .readBytes().toString(Charsets.UTF_8),
            JSONObject(javaClass.classLoader!!.getResourceAsStream("fixtures/object_info_trimmed.json")!!
                .readBytes().toString(Charsets.UTF_8)),
        )
        assertTrue("blocking: ${converted.blockingProblems}", converted.blockingProblems.isEmpty())

        val inputs = converted.prompt.getJSONObject("2").getJSONObject("inputs")
        assertEquals("""["1",0]""", inputs.get("model").toString())
        assertEquals("Krea2\\krea2_retroanime.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals(0.8, inputs.getDouble("loras.0.strength"), 1e-9)
        assertTrue(inputs.getBoolean("loras.0.enabled"))
        assertFalse("the row counter is not part of the prompt", inputs.has("loras"))

        val lora = converted.fields.single { it.key == "2:loras.0.lora_name" }
        assertEquals(FieldKind.PARAM, lora.kind)
        assertEquals("combo", lora.ptype)
        assertEquals("Krea2\\krea2_retroanime.safetensors", lora.value)
        assertTrue(lora.options.contains("Krea2\\krea2_retroanime.safetensors"))
        assertEquals("多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · lora_name", lora.label)
        assertTrue(converted.fields.any { it.key == "2:loras.0.strength" && it.ptype == "float" })
        assertTrue(converted.fields.any { it.key == "2:loras.0.enabled" && it.ptype == "bool" })
    }

    @Test
    fun rowsBecomeFlatPromptInputsAndEditableFields() {
        val converted = convert(loraStackNode("""[1,"b.safetensors",0.8,false]"""))

        val inputs = nodeInputs(converted)
        assertEquals("""["1",0]""", inputs.get("model").toString())
        assertEquals("b.safetensors", inputs.get("loras.0.lora_name"))
        // The server rejects a strength that arrives as text, so it must be a real number.
        assertEquals(0.8, inputs.get("loras.0.strength"))
        assertFalse(inputs.getBoolean("loras.0.enabled"))
        assertFalse("the row counter is not part of the prompt", inputs.has("loras"))

        val fields = keys(converted)
        assertEquals(setOf("loras.0.lora_name", "loras.0.strength", "loras.0.enabled"), fields.keys)
        assertEquals(FieldKind.PARAM, fields.getValue("loras.0.lora_name").kind)
        assertEquals("combo", fields.getValue("loras.0.lora_name").ptype)
        assertEquals(listOf("a.safetensors", "b.safetensors"), fields.getValue("loras.0.lora_name").options)
        assertEquals("float", fields.getValue("loras.0.strength").ptype)
        assertEquals("bool", fields.getValue("loras.0.enabled").ptype)
        assertEquals("多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · lora_name", fields.getValue("loras.0.lora_name").label)
        assertEquals("多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · enabled", fields.getValue("loras.0.enabled").label)
    }

    @Test
    fun everyRowIsReadAndTheNextWidgetKeepsItsOwnValue() {
        val widgets = """[3,"a.safetensors",1.0,true,"b.safetensors",0.5,false,"a.safetensors",2.0,true]"""
        val converted = convert(loraStackNode(widgets, rowSockets(0..2), title = "LoRA stack"))

        val inputs = nodeInputs(converted)
        assertEquals("a.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(0.5, inputs.getDouble("loras.1.strength"), 1e-9)
        assertEquals("a.safetensors", inputs.get("loras.2.lora_name"))
        assertEquals(2.0, inputs.getDouble("loras.2.strength"), 1e-9)
        assertTrue(inputs.getBoolean("loras.2.enabled"))

        val rows = converted.fields.filter { it.nodeId == "2" && it.input.endsWith(".lora_name") }
        assertEquals(
            listOf("LoRA stack · LoRA 1 · lora_name", "LoRA stack · LoRA 2 · lora_name", "LoRA stack · LoRA 3 · lora_name"),
            rows.map { it.label },
        )
    }

    @Test
    fun aWidgetAfterTheGroupKeepsItsValue() {
        val schema = loraStackSchema()
        schema.getJSONObject("LoadLoraModel").getJSONObject("input")
            .put("optional", JSONObject().put("notes", JSONArray().put("STRING").put(JSONObject().put("default", ""))))
        val workflow = loraStackNode("""[2,"a.safetensors",1.0,true,"b.safetensors",0.4,false,"kept"]""", rowSockets(0..1))

        val inputs = nodeInputs(convert(workflow, schema))

        assertEquals("kept", inputs.get("notes"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
    }

    @Test
    fun namedWidgetValuesResolveRowsByTheirFieldName() {
        val workflow = loraStackNode(
            """{"loras":2,"loras.0.lora_name":"a.safetensors","loras.0.strength":0.7,"loras.0.enabled":false,
                "loras.1.lora_name":"b.safetensors","loras.1.strength":1.2,"loras.1.enabled":true}""",
        )

        val inputs = nodeInputs(convert(workflow))

        assertEquals(0.7, inputs.getDouble("loras.0.strength"), 1e-9)
        assertFalse(inputs.getBoolean("loras.0.enabled"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(1.2, inputs.getDouble("loras.1.strength"), 1e-9)
    }

    @Test
    fun aLinkedRowFieldResolvesToItsSourceInsteadOfTheStaleWidget() {
        val schema = loraStackSchema().put(
            "FloatNode",
            JSONObject().put("output", JSONArray().put("FLOAT")).put(
                "input",
                JSONObject().put("required", JSONObject().put("value", JSONArray().put("FLOAT").put(JSONObject().put("forceInput", true)))),
            ),
        )
        val workflow = loraStackNode(
            """[1,"a.safetensors",0.25,true]""",
            sockets = rowSockets(strengthLink = 7),
            extraNodes = """,
                {"id":3,"type":"FloatNode","widgets_values":[0.9],
                 "inputs":[{"name":"value","type":"FLOAT","link":null}],
                 "outputs":[{"name":"FLOAT","type":"FLOAT","links":[7]}]}""",
            extraLinks = """,[7,3,0,2,2,"FLOAT"]""",
        )

        val converted = convert(workflow, schema)
        val inputs = nodeInputs(converted)
        assertEquals("""["3",0]""", inputs.get("loras.0.strength").toString())
        // The placeholder still occupies its slot so later fields stay aligned.
        assertEquals("a.safetensors", inputs.get("loras.0.lora_name"))
        assertTrue(inputs.getBoolean("loras.0.enabled"))
        assertTrue("a linked row field is not an editable widget", keys(converted)["loras.0.strength"] == null)
    }

    @Test
    fun aSeedRowFieldConsumesItsControlWidget() {
        val schema = loraStackSchema()
        val loras = schema.getJSONObject("LoadLoraModel").getJSONObject("input").getJSONObject("required").getJSONArray("loras")
        loras.getJSONObject(1).getJSONObject("template").getJSONObject("required")
            .put("seed", JSONArray().put("INT").put(JSONObject().put("control_after_generate", true).put("default", 0)))
        val workflow = loraStackNode(
            """[2,"a.safetensors",1.0,true,11,"fixed","b.safetensors",0.3,false,22,"randomize"]""",
            rowSockets(0..1, seed = true),
        )

        val converted = convert(workflow, schema)
        val inputs = nodeInputs(converted)

        assertEquals(11, inputs.get("loras.0.seed"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(22, inputs.get("loras.1.seed"))
        val seeds = converted.fields.filter { it.nodeId == "2" && it.kind == FieldKind.SEED }
        assertEquals(listOf("loras.0.seed", "loras.1.seed"), seeds.map { it.input })
    }

    @Test
    fun tooFewOrTooManyRowsIsReportedBeforeSubmission() {
        val none = WorkflowConverter.convert(loraStackNode("[0]"), loraStackSchema(min = 1))
        assertTrue(none.blockingProblems.single().contains("loras"))
        assertTrue(none.blockingProblems.single().contains("1"))

        val tooMany = WorkflowConverter.convert(loraStackNode("[3]"), loraStackSchema(min = 1, max = 2))
        assertTrue(tooMany.blockingProblems.single().contains("loras"))
        assertTrue(tooMany.blockingProblems.single().contains("2"))

        // A damaged counter never expands into a huge stack.
        val absurd = WorkflowConverter.convert(loraStackNode("""[1000000000]"""), loraStackSchema())
        assertTrue(absurd.blockingProblems.single().contains("上限 20"))
        assertEquals(20, absurd.dynamicGroups.values.single().rows)

        // min = 0 groups are valid without any row and simply emit nothing.
        val empty = WorkflowConverter.convert(loraStackNode("[0]"), loraStackSchema(min = 0))
        assertTrue("blocking: ${empty.blockingProblems}", empty.blockingProblems.isEmpty())
        assertFalse(nodeInputs(empty).has("loras.0.lora_name"))
    }

    @Test
    fun aMissingCounterFallsBackToTheRowsTheCanvasStillLists() {
        // The counter slot is unreadable but still present, so the rows after it stay aligned.
        val widgets = """[null,"a.safetensors",0.8,true,"b.safetensors",0.5,false]"""

        val inputs = nodeInputs(convert(loraStackNode(widgets, rowSockets(0..1))))

        assertEquals("a.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(0.5, inputs.getDouble("loras.1.strength"), 1e-9)
        assertFalse(inputs.getBoolean("loras.1.enabled"))
    }

    @Test
    fun rowValuesOfTheWrongKindAreReportedInsteadOfBeingSent() {
        // The counter slot was lost, so every row value sits one field too early
        // and the strength ends up holding the "enabled" flag.
        val converted = WorkflowConverter.convert(loraStackNode("""["a.safetensors",0.8,true]"""), loraStackSchema())

        val problem = converted.blockingProblems.single()
        assertTrue(problem, problem.contains("#2 · loras.0.strength"))
        assertTrue(problem, problem.contains("重新打开并保存"))
    }

    @Test
    fun apiFormatRowsBecomeFieldsIncludingSparseRows() {
        val api = JSONObject().put(
            "1", JSONObject().put("class_type", "UNETLoader")
                .put("inputs", JSONObject().put("unet_name", "unet.safetensors").put("weight_dtype", "default")),
        ).put(
            "2",
            JSONObject().put("class_type", "LoadLoraModel").put("_meta", JSONObject().put("title", "stack")).put(
                "inputs",
                JSONObject()
                    .put("model", JSONArray().put("1").put(0))
                    .put("loras.0.lora_name", "a.safetensors")
                    .put("loras.0.strength", 0.6)
                    .put("loras.0.enabled", true)
                    // Row 1 was deleted on the canvas: the server keeps gaps as they are.
                    .put("loras.2.lora_name", "b.safetensors")
                    .put("loras.2.strength", 1.5)
                    .put("loras.2.enabled", false),
            ),
        )

        val converted = WorkflowConverter.convert(api.toString(), loraStackSchema())

        assertTrue("blocking: ${converted.blockingProblems}", converted.blockingProblems.isEmpty())
        val fields = keys(converted)
        assertEquals(
            setOf("loras.0.lora_name", "loras.0.strength", "loras.0.enabled", "loras.2.lora_name", "loras.2.strength", "loras.2.enabled"),
            fields.keys,
        )
        assertEquals("stack · LoRA 3 · lora_name", fields.getValue("loras.2.lora_name").label)
        assertEquals("b.safetensors", fields.getValue("loras.2.lora_name").value)
        assertEquals("false", fields.getValue("loras.2.enabled").value)
        assertEquals(listOf("a.safetensors", "b.safetensors"), fields.getValue("loras.2.lora_name").options)
        assertEquals(api.getJSONObject("2").getJSONObject("inputs").toString(), nodeInputs(converted).toString())
    }

    @Test
    fun apiFormatKeepsMalformedGroupKeysForTheServerToJudge() {
        val api = JSONObject().put(
            "1", JSONObject().put("class_type", "UNETLoader")
                .put("inputs", JSONObject().put("unet_name", "unet.safetensors").put("weight_dtype", "default")),
        ).put(
            "2",
            JSONObject().put("class_type", "LoadLoraModel").put(
                "inputs",
                JSONObject()
                    .put("loras.0.lora_name", "a.safetensors")
                    .put("loras.0.strength", 1.0)
                    .put("loras.0.enabled", true)
                    .put("loras.01.lora_name", "typo-row.safetensors"),
            ),
        )

        val converted = WorkflowConverter.convert(api.toString(), loraStackSchema())

        assertEquals("typo-row.safetensors", nodeInputs(converted).get("loras.01.lora_name"))
        assertEquals(listOf("loras.0.lora_name", "loras.0.strength", "loras.0.enabled"), keys(converted).keys.toList())
    }

    @Test
    fun addingARowStartsItFromTheTemplateDefaults() {
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,false]"""))
        val group = converted.dynamicGroups.values.single()
        assertEquals("2:loras", group.key)
        assertEquals(1, group.rows)
        assertEquals(1, group.savedRows)
        assertTrue(group.canAddRows)

        val grown = WorkflowConverter.withGroupRows(converted, group.key, 3)!!

        val inputs = nodeInputs(grown)
        // The row saved in the workflow file is untouched.
        assertEquals("a.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals(0.8, inputs.getDouble("loras.0.strength"), 1e-9)
        assertFalse(inputs.getBoolean("loras.0.enabled"))
        // New rows start from the template, like a row added in the ComfyUI canvas.
        assertEquals("a.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(1.0, inputs.getDouble("loras.1.strength"), 1e-9)
        assertTrue(inputs.getBoolean("loras.2.enabled"))
        assertEquals(JSONArray().put("1").put(0).toString(), inputs.get("model").toString())

        val labels = grown.fields.filter { it.nodeId == "2" }.map { it.label }
        assertEquals(
            listOf(
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · lora_name",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · strength",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 1 · enabled",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 2 · lora_name",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 2 · strength",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 2 · enabled",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 3 · lora_name",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 3 · strength",
                "多 LoRA 节点（点 ➕ 加行堆叠） · LoRA 3 · enabled",
            ),
            labels,
        )
        val grownGroup = grown.dynamicGroups.getValue(group.key)
        assertEquals(3, grownGroup.rows)
        assertEquals(1, grownGroup.savedRows)
        // The conversion it started from is left alone.
        assertEquals(1, converted.dynamicGroups.getValue(group.key).rows)
        assertFalse(nodeInputs(converted).has("loras.1.lora_name"))
    }

    @Test
    fun removingRowsDropsTheLastOnesAndKeepsTheRest() {
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""))
        val key = converted.dynamicGroups.values.single().key
        val grown = WorkflowConverter.withGroupRows(converted, key, 3)!!

        val shrunk = WorkflowConverter.withGroupRows(grown, key, 2)!!

        val inputs = nodeInputs(shrunk)
        assertTrue(inputs.has("loras.1.lora_name"))
        assertFalse(inputs.has("loras.2.lora_name"))
        assertFalse(shrunk.fields.any { it.key == "2:loras.2.lora_name" })
        assertEquals(2, shrunk.dynamicGroups.getValue(key).rows)
        assertEquals(1, shrunk.dynamicGroups.getValue(key).savedRows)
    }

    @Test
    fun rowsStayInsideTheServerLimits() {
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""))
        val key = converted.dynamicGroups.values.single().key

        assertSame(converted, WorkflowConverter.withGroupRows(converted, key, 0))
        assertSame(converted, WorkflowConverter.withGroupRows(converted, key, 21))
        assertSame(converted, WorkflowConverter.withGroupRows(converted, key, 1))
        assertSame(converted, WorkflowConverter.withGroupRows(converted, "2:missing", 2))

        val narrow = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""), loraStackSchema(min = 1, max = 2))
        assertSame(narrow, WorkflowConverter.withGroupRows(narrow, key, 3))
    }

    @Test
    fun aRowIsNotAddedWhenTheServerOffersNothingToPick() {
        val schema = loraStackSchema()
        schema.getJSONObject("LoadLoraModel").getJSONObject("input").getJSONObject("required")
            .getJSONArray("loras").getJSONObject(1).getJSONObject("template").getJSONObject("required")
            .put("lora_name", JSONArray().put("COMBO").put(JSONObject().put("options", JSONArray())))
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""), schema)
        val group = converted.dynamicGroups.values.single()

        assertFalse(group.canAddRows)
        assertSame(converted, WorkflowConverter.withGroupRows(converted, group.key, 2))
    }

    @Test
    fun theRememberedRowCountRebuildsRowsAddedOnThePhone() {
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""))
        val countKey = WorkflowConverter.groupRowsKey("2:loras")

        val restored = WorkflowConverter.restoreGroupRows(converted, mapOf(countKey to "3"))!!

        assertTrue(nodeInputs(restored).has("loras.2.lora_name"))
        assertEquals(3, restored.dynamicGroups.getValue("2:loras").rows)
        // A stale count at or below the saved rows, or outside the limits, changes nothing.
        assertSame(converted, WorkflowConverter.restoreGroupRows(converted, mapOf(countKey to "1")))
        assertSame(converted, WorkflowConverter.restoreGroupRows(converted, mapOf(countKey to "99")))
        assertSame(converted, WorkflowConverter.restoreGroupRows(converted, mapOf(countKey to "many")))
        assertSame(converted, WorkflowConverter.restoreGroupRows(converted, emptyMap()))
    }

    @Test
    fun buildPromptAppliesEditsToRowsAddedOnThePhone() {
        val converted = convert(loraStackNode("""[1,"a.safetensors",0.8,true]"""))
        val grown = WorkflowConverter.withGroupRows(converted, "2:loras", 2)!!
        val fields = grown.fields.associateBy { it.key }

        val (prompt, _) = WorkflowConverter.buildPrompt(
            grown,
            mapOf(
                fields.getValue("2:loras.1.lora_name").key to "b.safetensors",
                fields.getValue("2:loras.1.strength").key to "0.5",
                fields.getValue("2:loras.1.enabled").key to "false",
            ),
            randomSeed = false,
        )
        val inputs = prompt.getJSONObject("2").getJSONObject("inputs")

        assertEquals("a.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertEquals(0.5, inputs.getDouble("loras.1.strength"), 1e-9)
        assertFalse(inputs.getBoolean("loras.1.enabled"))
    }

    @Test
    fun buildPromptWritesEditedRowsBackIntoTheFlatKeys() {
        val converted = convert(loraStackNode("""[2,"a.safetensors",1.0,true,"b.safetensors",1.0,true]"""))
        val fields = keys(converted)

        val (prompt, _) = WorkflowConverter.buildPrompt(
            converted,
            mapOf(
                fields.getValue("loras.0.lora_name").key to "b.safetensors",
                fields.getValue("loras.0.strength").key to "0.35",
                fields.getValue("loras.1.enabled").key to "false",
            ),
            randomSeed = false,
        )
        val inputs = prompt.getJSONObject("2").getJSONObject("inputs")

        assertEquals("b.safetensors", inputs.get("loras.0.lora_name"))
        assertEquals(0.35, inputs.getDouble("loras.0.strength"), 1e-9)
        assertTrue(inputs.getBoolean("loras.0.enabled"))
        assertEquals("b.safetensors", inputs.get("loras.1.lora_name"))
        assertFalse(inputs.getBoolean("loras.1.enabled"))
        assertEquals("""["1",0]""", inputs.get("model").toString())
    }
}
