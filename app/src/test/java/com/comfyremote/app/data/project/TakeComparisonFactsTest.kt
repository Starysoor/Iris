package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeComparisonFactsTest {
    @Test fun detectRelevantDifferencesWithoutChangingOriginalTakes() {
        val first = ShotTake(
            id = "A", providerId = "p1", workflowPath = "p1:model", seed = "42",
            costEstimate = 3.0, referenceAssetIds = listOf("actor", "scene"),
            values = mapOf("duration" to "5"), prompt = "Close-up",
        )
        val second = first.copy(
            id = "B", providerId = "p2", workflowPath = "p2:model", seed = "88",
            costEstimate = 4.0, values = mapOf("duration" to "10"),
        )
        val result = TakeComparisonFacts.compare(first, second)
        assertTrue(result.provider.differs)
        assertTrue(result.model.differs)
        assertTrue(result.seed.differs)
        assertTrue(result.cost.differs)
        assertTrue(result.parameters.differs)
        assertFalse(result.references.differs)
        assertFalse(result.prompt.differs)
        assertEquals(5, result.differenceCount)
        assertEquals(listOf("actor", "scene"), first.referenceAssetIds)
    }

    @Test fun identicalSnapshotsHaveNoDifferences() {
        val take = ShotTake(id = "t", prompt = "A", seed = "1", values = mapOf("b" to "2", "a" to "1"))
        assertEquals(0, TakeComparisonFacts.compare(take, take.copy(id = "u")).differenceCount)
    }
}
