package com.comfyremote.app.feature.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamVisionPromptsTest {
    @Test fun separatesPixelHeuristicFromVisualJudgement() {
        val system = SeamVisionPrompts.system()
        val user = SeamVisionPrompts.user("Shot A", "Shot B", 0.252)
        assertTrue(system.contains("光照变化"))
        assertTrue(system.contains("不能替代"))
        assertTrue(user.contains("Shot A"))
        assertTrue(user.contains("Shot B"))
        assertTrue(user.contains("0.252"))
    }

    @Test fun longProviderOutputIsBoundedBeforeDisplay() {
        assertEquals(1400, SeamVisionPrompts.presentation("x".repeat(5000)).length)
        assertEquals("ok", SeamVisionPrompts.presentation("  ok  "))
    }
}
