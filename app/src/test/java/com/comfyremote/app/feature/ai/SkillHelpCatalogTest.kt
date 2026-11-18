package com.comfyremote.app.feature.ai

import com.comfyremote.app.SkillExecution
import com.comfyremote.app.SkillInputSource
import com.comfyremote.app.SkillOutput
import com.comfyremote.app.SkillScope
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillHelpCatalogTest {
    @Test fun everyUsageTagHasLocalizedDocumentation() {
        SkillScope.entries.forEach { assertTrue(it.name, SkillHelpCatalog.scopeDescription(it) != 0) }
        SkillInputSource.entries.forEach { assertTrue(it.name, SkillHelpCatalog.inputDescription(it) != 0) }
        SkillExecution.entries.forEach { assertTrue(it.name, SkillHelpCatalog.executionDescription(it) != 0) }
        SkillOutput.entries.forEach { assertTrue(it.name, SkillHelpCatalog.outputDescription(it) != 0) }
    }
}
