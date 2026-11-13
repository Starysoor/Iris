package com.comfyremote.app.feature.project

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDragDirectionTest {
    @Test fun horizontalOrSmallMotionDoesNotReorder() {
        assertEquals(0, timelineDragDirection(0f, 52f))
        assertEquals(0, timelineDragDirection(51.9f, 52f))
        assertEquals(0, timelineDragDirection(-51.9f, 52f))
    }

    @Test fun adjacentVerticalThresholdProducesOneStepDirection() {
        assertEquals(1, timelineDragDirection(52f, 52f))
        assertEquals(-1, timelineDragDirection(-52f, 52f))
        assertEquals(1, timelineDragDirection(250f, 52f))
        assertEquals(-1, timelineDragDirection(-250f, 52f))
    }

    @Test fun invalidThresholdNeverReorders() {
        assertEquals(0, timelineDragDirection(55f, 0f))
        assertEquals(0, timelineDragDirection(-55f, -1f))
    }
}
