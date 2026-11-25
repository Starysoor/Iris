package com.comfyremote.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLoraWorkflowTest {
    private fun objectInfo() = JSONObject(
        """
        {
          "CheckpointLoaderSimple": {"input":{"required":{}}},
          "Power Lora Loader (rgthree)": {
            "input": {"required":{}, "optional":{"model":["MODEL"],"clip":["CLIP"]}}
          },
          "LoraLoader": {
            "input":{"required":{"lora_name":[["styles/detail.safetensors","styles/replacement.safetensors"]]}}
          }
        }
        """.trimIndent(),
    )

    @Test
    fun canvasRestoresEveryPowerLoraSlotAndItsModelAndClipLinks() {
        val canvas = """
            {"version":0.4,"nodes":[
              {"id":1,"type":"CheckpointLoaderSimple","widgets_values":[]},
              {"id":8,"type":"Power Lora Loader (rgthree)","title":"Style stack",
               "inputs":[{"name":"model","link":11},{"name":"clip","link":12}],
               "widgets_values":[null,{"type":"PowerLoraLoaderHeaderWidget"},
                 {"on":true,"lora":"detail.safetensors","strength":0.8,"strengthTwo":null},
                 {"on":false,"lora":"old.safetensors","strength":-0.4,"strengthTwo":0.2},
                 null,null]}
            ],"links":[[11,1,0,8,0,"MODEL"],[12,1,1,8,1,"CLIP"]]}
        """.trimIndent()
        val converted = WorkflowConverter.convert(canvas, objectInfo())
        assertEquals(emptyList<String>(), converted.blockingProblems)
        val original = converted.prompt.getJSONObject("8").getJSONObject("inputs")
        assertEquals("1", original.getJSONArray("model").getString(0))
        assertEquals(1, original.getJSONArray("clip").getInt(1))
        assertEquals("detail.safetensors", original.getJSONObject("lora_1").getString("lora"))
        assertFalse(original.getJSONObject("lora_1").has("strengthTwo"))
        assertEquals("old.safetensors", original.getJSONObject("lora_2").getString("lora"))
        assertFalse(original.getJSONObject("lora_2").getBoolean("on"))
        val names = converted.fields.filter { it.input.endsWith(".lora") }.associateBy(PromptField::key)
        assertEquals(setOf("8:lora_1.lora", "8:lora_2.lora"), names.keys)
        assertTrue("styles/replacement.safetensors" in names.getValue("8:lora_2.lora").options)
        assertTrue("old.safetensors" in names.getValue("8:lora_2.lora").options)
        assertEquals(2, names.values.map(PromptField::label).distinct().size)

        val (prompt, _) = WorkflowConverter.buildPrompt(
            converted,
            mapOf(
                "8:lora_1.strength" to "-0.5",
                "8:lora_2.on" to "true",
                "8:lora_2.lora" to "styles/replacement.safetensors",
                "8:lora_2.strengthTwo" to "0.65",
            ),
            randomSeed = false,
        )
        val edited = prompt.getJSONObject("8").getJSONObject("inputs")
        assertEquals(-0.5, edited.getJSONObject("lora_1").getDouble("strength"), 0.0)
        val second = edited.getJSONObject("lora_2")
        assertTrue(second.getBoolean("on"))
        assertEquals("styles/replacement.safetensors", second.getString("lora"))
        assertEquals(-0.4, second.getDouble("strength"), 0.0)
        assertEquals(0.65, second.getDouble("strengthTwo"), 0.0)
        assertFalse(edited.has("lora_2.strengthTwo"))
        assertEquals(-0.4, original.getJSONObject("lora_2").getDouble("strength"), 0.0)
        assertFalse(original.getJSONObject("lora_2").getBoolean("on"))
    }

    @Test
    fun apiPromptEditsSparseSlotsWhenTheServerDoesNotAdvertiseAvailableLoras() {
        val api = """
            {"15":{"class_type":"Power Lora Loader (rgthree)","inputs":{
              "lora_3":{"on":true,"lora":"first.safetensors","strength":0.8},
              "lora_9":{"on":true,"lora":"second.safetensors","strength":0.3,"strengthTwo":0.7}
            }}}
        """.trimIndent()
        val info = objectInfo().apply { remove("LoraLoader") }
        val converted = WorkflowConverter.convert(api, info)
        assertEquals(emptyList<String>(), converted.blockingProblems)
        val names = converted.fields.filter { it.input.endsWith(".lora") }
        assertEquals(setOf("15:lora_3.lora", "15:lora_9.lora"), names.map(PromptField::key).toSet())
        assertTrue(names.all { it.ptype == "string" && it.options.isEmpty() })

        val (prompt, _) = WorkflowConverter.buildPrompt(
            converted, mapOf("15:lora_9.lora" to "folder/alternate.safetensors"), randomSeed = false,
        )
        val inputs = prompt.getJSONObject("15").getJSONObject("inputs")
        assertEquals("first.safetensors", inputs.getJSONObject("lora_3").getString("lora"))
        assertEquals("folder/alternate.safetensors", inputs.getJSONObject("lora_9").getString("lora"))
        assertEquals(0.7, inputs.getJSONObject("lora_9").getDouble("strengthTwo"), 0.0)
        assertFalse(inputs.has("lora_1"))
    }
}
