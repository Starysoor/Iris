package com.comfyremote.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSkillMergeRegressionTest {
    @Test
    fun builtinUpgradePreservesEditedBuiltinAndCustomSkills() {
        val editedBuiltin = AiSkillDef(AiSkillDef.DEFAULT_NATURAL_ID, "我的扩写", "edited system prompt", true)
        val custom = AiSkillDef("custom_keep", "My custom", "custom system prompt", false)

        val merged = AiSkillDef.mergeBuiltins(listOf(editedBuiltin, custom))

        assertEquals("edited system prompt", merged.first { it.id == AiSkillDef.DEFAULT_NATURAL_ID }.systemPrompt)
        assertTrue(merged.any { it.id == "custom_keep" && it.systemPrompt == "custom system prompt" && !it.builtin })
        assertTrue(merged.none { it.id == AiSkillDef.DEFAULT_EN_ID })
        assertTrue(merged.any { it.id == AiSkillDef.DEFAULT_NEG_ID })
        assertTrue(merged.none { it.id == AiSkillDef.DEFAULT_TRANSLATE_ID })
    }

    @Test
    fun nsfwSkillsAreMergedForExistingUsers() {
        // 老用户升级后应自动获得两个 NSFW 技能，无需手动添加
        val merged = AiSkillDef.mergeBuiltins(emptyList())

        val expand = requireNotNull(merged.firstOrNull { it.id == AiSkillDef.DEFAULT_NSFW_EXPAND_ID })
        val tags = requireNotNull(merged.firstOrNull { it.id == AiSkillDef.DEFAULT_NSFW_TAGS_ID })

        assertTrue(expand.builtin && expand.label == "NSFW扩写")
        assertTrue(tags.builtin && tags.label == "NSFW标签")
        assertTrue(expand.systemPrompt.contains("存档标注员"))
        assertTrue(tags.systemPrompt.contains("Danbooru"))
    }
    @Test
    fun h3SkillAutoInstallsForOldSavedListsWithoutOverwritingUserEdits() {
        val oldEdited = AiSkillDef(
            AiSkillDef.DEFAULT_NATURAL_ID, "自然语言（自定义）", "user edit", true,
        )
        val old = listOf(
            oldEdited,
            AiSkillDef("my_skill", "我的技能", "my custom prompt", false),
            AiSkillDef(AiSkillDef.DEFAULT_EN_ID, "retired", "old", true),
        )
        val upgraded = AiSkillDef.mergeBuiltins(old)
        assertEquals(1, upgraded.count { it.id == AiSkillDef.DEFAULT_H3_VIDEO_ID })
        assertEquals("user edit",
            upgraded.first { it.id == AiSkillDef.DEFAULT_NATURAL_ID }.systemPrompt)
        assertTrue(upgraded.any { it.id == "my_skill" && it.systemPrompt == "my custom prompt" })
        assertTrue(upgraded.none { it.id == AiSkillDef.DEFAULT_EN_ID })
        assertTrue(upgraded.none { it.id == AiSkillDef.DEFAULT_TRANSLATE_ID })
        val twice = AiSkillDef.mergeBuiltins(upgraded)
        assertEquals(upgraded.size, twice.size)
    }
}

