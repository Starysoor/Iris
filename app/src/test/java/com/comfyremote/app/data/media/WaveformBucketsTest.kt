package com.comfyremote.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformBucketsTest {
    @Test fun mapsTimedAmplitudeToBoundedBuckets() {
        val bars = WaveformBuckets(1_000_000L, 3_000_000L, 4)
        bars.add(1_000_000L, 0.3f)
        bars.add(1_400_000L, 0.7f)
        bars.add(1_600_000L, 0.4f)
        bars.add(2_600_000L, 1.5f)
        assertEquals(listOf(0.7f, 0.4f, 0f, 1f), bars.values())
    }

    @Test fun ignoresOutOfRangeAndInvalidSamples() {
        val bars = WaveformBuckets(500L, 1500L, 2)
        bars.add(499L, 0.6f)
        bars.add(1500L, 0.6f)
        bars.add(700L, -0.4f)
        bars.add(800L, Float.NaN)
        assertEquals(listOf(0f, 0f), bars.values())
    }

    @Test fun emptyOrInvertedWindowNeverDividesByZero() {
        val bars = WaveformBuckets(1000L, 1000L, 2)
        bars.add(1000L, 0.8f)
        assertEquals(listOf(0f, 0f), bars.values())
    }
}
