package com.comfyremote.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityPagingTest {
    @Test fun hundredShotProjectRetainsAtMostSixteenStrips() {
        for (page in 0..6) {
            val window = ContinuityPaging.window(100, page, 16)
            assertTrue(window.count in 1..16)
            assertTrue(window.endExclusive - window.seamStart <= 17)
            assertEquals(7, window.pages)
        }
        assertEquals(4, ContinuityPaging.window(100, 6, 16).count)
    }

    @Test fun previousLastShotIsIncludedForSeamBoundary() {
        val w = ContinuityPaging.window(100, 1, 16)
        assertEquals(16, w.start)
        assertEquals(15, w.seamStart)
        assertEquals(32, w.endExclusive)
    }

    @Test fun emptyAndOutOfRangePageAreHandledSafely() {
        val empty = ContinuityPaging.window(0, 99, 16)
        assertEquals(0, empty.count)
        assertEquals(1, empty.pages)
        assertEquals(0, empty.page)
        assertEquals(0, ContinuityPaging.window(3, -9, 16).page)
        assertEquals(0, ContinuityPaging.window(3, 123, 16).page)
    }
}
