package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end diagnostic score for ReChord's active PCM output path. */
class AudioFidelityTest {
    private fun input(
        route: AudioRouting.Kind = AudioRouting.Kind.USB,
        verified: Boolean = false,
        exact: Boolean = true,
        dacLimited: Boolean = false,
        sourceLossless: Boolean? = true,
        bypass: Boolean = false,
        direct: Boolean = false,
        spatial: Boolean = false,
        equalizer: Boolean = false,
        sourceRate: Int? = 96_000,
        outputRate: Int? = 96_000,
        sourceBits: Int? = 24,
        outputBits: Int? = 24,
    ) = AudioFidelity.Inputs(
        routeKind = route,
        verifiedTransportActive = verified,
        verifiedTransportExact = exact,
        dacLimited = dacLimited,
        sourceLossless = sourceLossless,
        sourceBits = sourceBits,
        outputBits = outputBits,
        directPlaybackActual = direct,
        outputExact = exact,
        dspBypassed = bypass,
        spatialActive = spatial,
        equalizerActive = equalizer,
        sourceRateHz = sourceRate,
        outputRateHz = outputRate,
    )

    @Test fun exactDirectUsbAndBypassedDspEarns100() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, spatial = true))
        assertEquals(100, a.score)
        assertEquals("Bit-perfect", a.grade)
        assertTrue(a.bitPerfectVerified)
        assertTrue(a.deductions.isEmpty())
        assertTrue(a.activeEffects.isEmpty())
    }

    @Test fun androidVerifiedBitPerfectMixerEarns100WithoutArbitraryMixerPenalty() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true))
        assertEquals(100, a.score)
        assertTrue(a.bitPerfectVerified)
    }

    @Test fun stalePhoneRouteMustNotPenalizeActiveUsbTransport() {
        val a = AudioFidelity.assess(input(route = AudioRouting.Kind.PHONE, verified = true, bypass = true))
        assertEquals(100, a.score)
    }

    @Test fun ordinaryAudioTrackCannotClaimVerifiedBitPerfect() {
        val a = AudioFidelity.assess(input(exact = true))
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.score < 100)
        assertEquals("System mixer is in the path", a.deductions.first().label)
    }

    @Test fun directAudioTrackCannotClaimVerifiedBitPerfectWithoutDriverConfirmation() {
        val a = AudioFidelity.assess(input(direct = true))
        assertEquals(94, a.score)
        assertFalse(a.bitPerfectVerified)
    }

    @Test fun limitedDacConversionPrevents100AndIsExplained() {
        val a = AudioFidelity.assess(
            input(verified = true, bypass = true, exact = false, dacLimited = true, outputBits = 16)
        )
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label.contains("DAC") })
    }

    @Test fun verifiedTransportWithChangedSampleRateIsNotBitPerfect() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, outputRate = 48_000))
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label.startsWith("Resampled") })
    }

    @Test fun unknownSourceOrOutputRatePrevents100() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, sourceRate = null))
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label.contains("not reported") })
    }

    @Test fun lossySourceNeverGets100EvenWithExactPcmTransport() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, sourceLossless = false))
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label.contains("Lossy source") })
    }

    @Test fun unconfirmedSourceCannotGet100() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, sourceLossless = null))
        assertFalse(a.bitPerfectVerified)
    }

    @Test fun activeEqualizerPrevents100WhenNotBypassed() {
        val a = AudioFidelity.assess(input(verified = true, equalizer = true))
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label == "Equalizer" })
    }

    @Test fun convertedDsdMustNotBeLabeledBitPerfect() {
        val a = AudioFidelity.assess(
            input(verified = true, bypass = true).copy(dsd = true)
        )
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.any { it.label == "DSD converted to PCM" })
    }
}
