package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioJourneyTest {
    @Test fun emptyProjectDoesNotDemandPaidProviderOrAssets() {
        val project = VideoProject(title = "Film", workflowId = "")
        assertEquals(StudioJourney.NextStep.ADD_SHOTS, StudioJourney.inspect(project).next)
    }

    @Test fun readyShotsLeadToTimelineThenReview() {
        val ready = ProjectShot(prompt = "Scene", status = "done", resultPath = "/tmp/clip.mp4")
        val draft = ProjectShot(prompt = "Next")
        val project = VideoProject(title = "Film", workflowId = "", shots = listOf(ready, draft))
        assertEquals(StudioJourney.NextStep.GENERATE, StudioJourney.inspect(project).next)
        val complete = project.copy(shots = listOf(ready))
        assertEquals(StudioJourney.NextStep.ARRANGE, StudioJourney.inspect(complete).next)
        val arranged = complete.copy(
            timeline = Timeline(videoTrack = listOf(TimelineClip(sourcePath = "/tmp/clip.mp4")))
        )
        assertEquals(StudioJourney.NextStep.REVIEW, StudioJourney.inspect(arranged).next)
    }
}
