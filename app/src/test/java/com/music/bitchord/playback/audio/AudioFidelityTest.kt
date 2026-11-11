package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFidelityTest {
    private fun input(
        route: AudioRouting.Kind = AudioRouting.Kind.USB,
        verified: Boolean = false,
        exact: Boolean = true,
        bypass: Boolean = false,
        direct: Boolean = false,
        equalizer: Boolean = false,
        spatial: Boolean = false,
        dsd: Boolean = false,
        rate: Int? = 96_000,
        outputRate: Int? = 96_000,
        bits: Int? = 24,
        outputBits: Int? = 24,
        dacLimited: Boolean = false,
    ) = AudioFidelity.Inputs(
        routeKind = route,
        verifiedTransportActive = verified,
        verifiedTransportExact = exact,
        dspBypassed = bypass,
        directPlaybackActual = direct,
        outputExact = exact,
        dacLimited = dacLimited,
        sourceBits = bits,
        outputBits = outputBits,
        equalizerActive = equalizer,
        spatialActive = spatial,
        dsd = dsd,
        sourceRateHz = rate,
        outputRateHz = outputRate,
    )

    @Test fun verifiedDirectUsbIs100() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true))
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.VERIFIED, a.verification)
        assertEquals("Bit-perfect", a.grade)
        assertTrue(a.bitPerfectVerified)
    }

    @Test fun androidBitPerfectMixerAlsoGets100WithoutPenalty() {
        val a = AudioFidelity.assess(
            input(verified = true, bypass = true).copy(transportDescription = "Android bit-perfect mixer")
        )
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.VERIFIED, a.verification)
    }

    @Test fun ordinaryMixerCanBe100ButUnverified() {
        val a = AudioFidelity.assess(input(route = AudioRouting.Kind.USB))
        assertEquals(100, a.score)
        assertEquals("No known changes", a.grade)
        assertEquals(AudioFidelity.Verification.UNVERIFIED, a.verification)
        assertFalse(a.bitPerfectVerified)
        assertTrue(a.deductions.isEmpty())
    }

    @Test fun directAudioTrackCanBe100ButUnverified() {
        val a = AudioFidelity.assess(input(direct = true))
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.UNVERIFIED, a.verification)
    }

    @Test fun stalePhoneRouteDoesNotPenalizeVerifiedUsbDriver() {
        val a = AudioFidelity.assess(input(route = AudioRouting.Kind.PHONE, verified = true, bypass = true))
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.VERIFIED, a.verification)
    }

    @Test fun nonExactUsbOutputIsModified() {
        val a = AudioFidelity.assess(input(verified = true, exact = false, bypass = true))
        assertTrue(a.score < 100)
        assertEquals(AudioFidelity.Verification.MODIFIED, a.verification)
    }

    @Test fun dacLimitedPrecisionConvertsAndLowersScore() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, exact = false,
            dacLimited = true, outputBits = 16))
        assertTrue(a.score < 100)
        assertEquals(AudioFidelity.Verification.MODIFIED, a.verification)
    }

    @Test fun resamplingLowersScore() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, outputRate = 48_000))
        assertTrue(a.deductions.any { it.label.startsWith("Resampled") })
        assertEquals(AudioFidelity.Verification.MODIFIED, a.verification)
    }

    @Test fun rateUnknownIsNotPenalizedButCannotBeVerified() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, rate = null))
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.UNVERIFIED, a.verification)
    }

    @Test fun widenedContainerPreservingSamplesIsValid() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, bits = 16, outputBits = 24))
        assertEquals(100, a.score)
        assertEquals(AudioFidelity.Verification.VERIFIED, a.verification)
    }

    @Test fun narrowingPrecisionModifiesSamples() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, bits = 24, outputBits = 16))
        assertTrue(a.score < 100)
    }

    @Test fun activeEqualizerModifiesPcm() {
        val a = AudioFidelity.assess(input(equalizer = true))
        assertTrue(a.score < 100)
        assertEquals(AudioFidelity.Verification.MODIFIED, a.verification)
    }

    @Test fun bitPerfectDriverBypassesEnabledButInactiveEffects() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, spatial = true))
        assertEquals(100, a.score)
        assertTrue(a.activeEffects.isEmpty())
    }

    @Test fun dsdConversionIsReportedAsModification() {
        val a = AudioFidelity.assess(input(verified = true, bypass = true, dsd = true))
        assertEquals(AudioFidelity.Verification.MODIFIED, a.verification)
    }

    @Test fun lossyCodecNeverAppearsInScoringInputs() {
        // This API has deliberately no codec, source bitrate or lossless flag.
        val a = AudioFidelity.assess(input(verified = true, bypass = true))
        assertEquals(100, a.score)
    }
}
