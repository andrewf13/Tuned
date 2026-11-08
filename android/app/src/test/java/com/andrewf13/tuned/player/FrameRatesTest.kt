package com.andrewf13.tuned.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    // Roughly what a Shield at 4K reports.
    private val modes = listOf(
        ModeSpec(1, 3840, 2160, 23.976f),
        ModeSpec(2, 3840, 2160, 24f),
        ModeSpec(3, 3840, 2160, 25f),
        ModeSpec(4, 3840, 2160, 29.97f),
        ModeSpec(5, 3840, 2160, 50f),
        ModeSpec(6, 3840, 2160, 59.94f),
        ModeSpec(7, 3840, 2160, 60f),
        ModeSpec(8, 1920, 1080, 50f),
    )
    private val at60 = modes.first { it.id == 7 }

    @Test fun filmGetsExactMode() {
        assertEquals(1, FrameRates.choose(modes, at60, FrameRates.snap(23.97f))?.id)
        assertEquals(2, FrameRates.choose(modes, at60, 24f)?.id)
    }

    @Test fun pal25PrefersFiftyHz() = assertEquals(5, FrameRates.choose(modes, at60, 25f)?.id)

    @Test fun ntscPrefersDoubled() = assertEquals(6, FrameRates.choose(modes, at60, FrameRates.snap(29.97f))?.id)

    @Test fun sixtyStaysOnCurrent() = assertEquals(7, FrameRates.choose(modes, at60, 60f)?.id)

    @Test fun filmFallsBackTo24WhenNo23976() {
        val no23 = modes.filter { it.id != 1 }
        assertEquals(2, FrameRates.choose(no23, at60, 24000f / 1001f)?.id)
    }

    @Test fun nothingSuitableReturnsNull() {
        val only60 = listOf(at60)
        assertNull(FrameRates.choose(only60, at60, 25f))
    }

    @Test fun measuresFromTimestamps() {
        val pal = (0 until 48).map { it * 40_000L }
        assertEquals(25f, FrameRates.fromTimestamps(pal), 0.001f)
        val film = (0 until 48).map { (it * 41_708.333).toLong() }
        assertEquals(24000f / 1001f, FrameRates.fromTimestamps(film), 0.001f)
    }
}
