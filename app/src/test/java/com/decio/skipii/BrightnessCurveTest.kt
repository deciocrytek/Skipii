package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fullscreen brightness drag on Android's slider curve (FullscreenSliders.kt). */
class BrightnessCurveTest {
    @Test
    fun ends() {
        assertEquals(0f, toSlider(0f), 0.001f)
        assertEquals(1f, toSlider(1f), 0.001f)
        assertEquals(0f, fromSlider(0f), 0.001f)
        assertEquals(1f, fromSlider(1f), 0.001f)
    }

    @Test
    fun roundTrip() {
        for (i in 0..100) {
            val slider = i / 100f
            assertEquals(slider, toSlider(fromSlider(slider)), 0.002f)
        }
    }

    @Test
    fun emulatorDefault() {
        // skipii_qa's default brightness (0.3976 linear) sits at ~82% of the system slider.
        assertEquals(0.82f, toSlider(0.3976f), 0.01f)
    }
}
