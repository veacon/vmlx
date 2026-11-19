package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingTest {
    @Test fun stepperStepsFiveMillisecondsAndClampsToRange() {
        val later = offsetAfterLyricsTimingStep(0L, LyricsTimingStepMs)
        val earlier = offsetAfterLyricsTimingStep(0L, -LyricsTimingStepMs)

        // A stored offset is the inverse of the delay shown to the user.
        assertEquals(-5L, later)
        assertEquals(5L, earlier)
        assertEquals(5L, lyricsTimingDelayMs(later))
        assertEquals(3_000L, offsetAfterLyricsTimingStep(2_998L, -LyricsTimingStepMs))
        assertEquals(-3_000L, offsetAfterLyricsTimingStep(-2_998L, LyricsTimingStepMs))
    }

    @Test fun steppingBackReturnsToZero() {
        val oneStep = offsetAfterLyricsTimingStep(0L, LyricsTimingStepMs)
        assertEquals(0L, offsetAfterLyricsTimingStep(oneStep, -LyricsTimingStepMs))
    }
}
