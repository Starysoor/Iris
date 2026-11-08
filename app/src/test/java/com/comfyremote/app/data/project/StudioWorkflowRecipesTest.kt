package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioWorkflowRecipesTest {
    @Test fun recipesAreExplicitUserOperatedAndHaveNoPaidSideEffect() {
        assertEquals(3, StudioWorkflowRecipes.options.size)
        assertEquals(StudioWorkflowRecipes.Step.VIDEO,
            StudioWorkflowRecipes.resolve(StudioWorkflowRecipes.TEXT_TO_VIDEO).steps[1])
        val advanced = StudioWorkflowRecipes.resolve(StudioWorkflowRecipes.KEYFRAMES_AND_AUDIO)
        assertEquals(listOf("image", "video", "audio"),
            advanced.steps.mapNotNull { it.providerKind })
        assertTrue(StudioWorkflowRecipes.supports(advanced.id))
        assertFalse(StudioWorkflowRecipes.supports("unknown"))
    }

    @Test fun unknownRecipeFallsBackToTextOnlyWithoutChangingStoredShotModel() {
        val project = VideoProject(title = "Film", workflowId = "default-model",
            stageWorkflowPaths = mapOf("video" to "autodl:approved-video"))
        assertEquals("default-model", project.workflowId)
        assertEquals("autodl:approved-video", project.stageWorkflowPaths["video"])
        assertEquals(StudioWorkflowRecipes.TEXT_TO_VIDEO,
            StudioWorkflowRecipes.resolve("anything").id)
    }
}
