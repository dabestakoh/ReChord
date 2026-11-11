package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the display-only, Auris-inspired fidelity estimate. */
class AudioFidelityTest {
    private fun input(
        route: AudioRouting.Kind = AudioRouting.Kind.PHONE,
        exclusive: Boolean = false,
        direct: Boolean = false,
        exact: Boolean = true,
        bypass: Boolean = false,
        spatial: Boolean = false,
        equalizer: Boolean = false,
        sourceRate: Int? = 48_000,
        outputRate: Int? = 48_000,
    ) = AudioFidelity.Inputs(
        routeKind = route,
        exclusiveUsbActive = exclusive,
        directPlaybackActual = direct,
        outputExact = exact,
        dspBypassed = bypass,
        spatialActive = spatial,
        equalizerActive = equalizer,
        sourceRateHz = sourceRate,
        outputRateHz = outputRate,
    )

    @Test fun phoneMixerAndSpatialDeductionsMatchAurisExample() {
        val value = AudioFidelity.assess(input(exact = true, spatial = true))
        assertEquals(48, value.score)
        assertEquals("Processed", value.grade)
        assertEquals(3, value.deductions.size)
    }

    @Test fun exactExclusiveUsbWithBypassedDspHasNoDeductions() {
        val value = AudioFidelity.assess(input(exclusive = true, bypass = true, spatial = true))
        assertEquals(100, value.score)
        assertEquals("Bit-perfect", value.grade)
        assertTrue(value.activeEffects.isEmpty())
        assertTrue(value.deductions.isEmpty())
    }

    @Test fun resamplingAndNarrowEncodingBothCount() {
        val value = AudioFidelity.assess(input(exact = false, outputRate = 44_100))
        assertTrue(value.deductions.any { it.label.startsWith("Resampled") })
        assertTrue(value.deductions.any { it.label.contains("encoding") })
    }

    @Test fun bypassedEffectsAreNeverDeducted() {
        val value = AudioFidelity.assess(input(bypass = true, spatial = true, equalizer = true))
        assertFalse(value.deductions.any { it.label == "Spatial audio" || it.label == "Equalizer" })
    }

    @Test fun unknownRatesDoNotClaimResampling() {
        val value = AudioFidelity.assess(input(sourceRate = null, outputRate = null))
        assertFalse(value.deductions.any { it.label.startsWith("Resampled") })
    }
}
