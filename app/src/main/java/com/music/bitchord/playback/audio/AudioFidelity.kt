/*
 * Fidelity scoring adapted from Auris by OreoMuncher45 (auris-2.3).
 * Copyright (C) 2026 BitChord Project and Auris contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting

/**
 * Diagnostic estimate of modifications between decoded PCM and the DAC.
 *
 * Codec, source compression bitrate and original recording quality never
 * affect this score. Unknown downstream Android behavior is reported as
 * Unverified, not as known sample damage.
 *
 * A 100/100 score means no *known* alteration. Bit-perfect is only claimed
 * when the active transport provides sufficient positive evidence.
 */
object AudioFidelity {

    /** Everything the score is allowed to look at. No inferred values. */
    data class Inputs(
        val routeKind: AudioRouting.Kind,
        /** The dedicated bit-perfect transport (USB direct, Android USB mixer or exclusive AAudio) owns playback. */
        val verifiedTransportActive: Boolean = false,
        /** Transport's own verdict, not a possibly stale normal AudioTrack snapshot. */
        val verifiedTransportExact: Boolean = false,
        val transportDescription: String? = null,
        val dacLimited: Boolean = false,
        val sourceBits: Int? = null,
        val outputBits: Int? = null,
        val directPlaybackActual: Boolean = false,

        /** Whether the output encoding carries the decoder's samples intact. */
        val outputExact: Boolean = false,
        /** False when the stream is a compressed bitstream the DSP never sees. */
        val dspAvailable: Boolean = true,
        /** True when the listener deliberately took every DSP stage out. */
        val dspBypassed: Boolean = false,
        val loudnessActive: Boolean = false,
        val equalizerActive: Boolean = false,
        val spatialActive: Boolean = false,
        val vocalCutActive: Boolean = false,
        val jamesDspActive: Boolean = false,
        val simAtmosActive: Boolean = false,
        /** DSD has no native output path here, so it is always converted to PCM. */
        val dsd: Boolean = false,
        val sourceRateHz: Int? = null,
        val outputRateHz: Int? = null,
    )

    data class Deduction(val label: String, val points: Int)

    data class Assessment(
        val score: Int,
        val grade: String,
        /** Names of the DSP stages genuinely running, in chain order. */
        val activeEffects: List<String>,
        /** False for a bitstream the DSP chain never sees. */
        val effectsAvailable: Boolean,
        /** True when effects are out of the path by request, not by being off. */
        val effectsBypassed: Boolean,
        val deductions: List<Deduction>,
        /** Only true for an exact active transport with verified source format and no processing. */
        val bitPerfectVerified: Boolean,
        val verification: Verification,
        val verificationDetail: String,
    )

    enum class Verification { VERIFIED, UNVERIFIED, MODIFIED }

    private const val BLUETOOTH_CODEC_POINTS = 12
    private const val NARROW_ENCODING_POINTS = 12
    private const val RESAMPLE_POINTS = 8
    private const val DSD_CONVERSION_POINTS = 6

    private const val LOUDNESS_POINTS = 4
    private const val EQUALIZER_POINTS = 8
    private const val SPATIAL_POINTS = 8
    private const val VOCAL_CUT_POINTS = 8
    private const val JAMES_DSP_POINTS = 8

    fun assess(inputs: Inputs): Assessment {
        val deductions = mutableListOf<Deduction>()

        val verifiedPath = inputs.verifiedTransportActive &&
            inputs.verifiedTransportExact && !inputs.dacLimited

        if (inputs.verifiedTransportActive) {
            if (!verifiedPath) {
                deductions += Deduction(
                    if (inputs.dacLimited) "DAC required format conversion"
                    else "Transport reports altered PCM samples",
                    NARROW_ENCODING_POINTS,
                )
            }
            val inputBits = inputs.sourceBits?.takeIf { it > 0 }
            val sinkBits = inputs.outputBits?.takeIf { it > 0 }
            if (inputBits != null && sinkBits != null && sinkBits < inputBits && !inputs.dacLimited) {
                deductions += Deduction("DAC output precision is narrower than decoded PCM", NARROW_ENCODING_POINTS)
            }
        } else {
            // Do not deduct for an ordinary Android mixer just because its
            // downstream behavior cannot be observed by the app.
            if (inputs.dspAvailable && !inputs.outputExact) {
                deductions += Deduction("Output cannot preserve decoded PCM precision", NARROW_ENCODING_POINTS)
            }
            if (inputs.routeKind == AudioRouting.Kind.BLUETOOTH) {
                deductions += Deduction("Bluetooth re-encodes decoded PCM", BLUETOOTH_CODEC_POINTS)
            }
        }

        // DSD-to-PCM is a processing step and cannot be raw DSD bit-perfect.
        if (inputs.dsd) {
            deductions += Deduction("DSD converted to PCM", DSD_CONVERSION_POINTS)
        } else {
            val source = inputs.sourceRateHz?.takeIf { it > 0 }
            val output = inputs.outputRateHz?.takeIf { it > 0 }
            if (source != null && output != null && source != output) {
                deductions += Deduction("Resampled ${rateText(source)} -> ${rateText(output)}", RESAMPLE_POINTS)
            }
        }

        // A bypassed chain runs nothing, whatever the switches still say.
        val running = if (!inputs.dspAvailable || inputs.dspBypassed) {
            emptyList()
        } else {
            buildList {
                if (inputs.loudnessActive) {
                    add("Loudness normalization" to LOUDNESS_POINTS)
                }
                if (inputs.equalizerActive) {
                    add("Equalizer" to EQUALIZER_POINTS)
                }
                if (inputs.spatialActive) {
                    add("Spatial audio" to SPATIAL_POINTS)
                }
                if (inputs.vocalCutActive) {
                    add("Vocal cut" to VOCAL_CUT_POINTS)
                }
                if (inputs.jamesDspActive) {
                    add("JamesDSP" to JAMES_DSP_POINTS)
                }
                if (inputs.simAtmosActive) {
                    add("Simulated Dolby Atmos" to SPATIAL_POINTS)
                }
            }
        }
        running.forEach { (label, points) -> deductions += Deduction(label, points) }

        val score = (100 - deductions.sumOf { it.points }).coerceIn(0, 100)
        val knownPcmFormat = inputs.sourceRateHz?.let { it > 0 } == true &&
            inputs.outputRateHz?.let { it > 0 } == true &&
            inputs.sourceBits?.let { it > 0 } == true &&
            inputs.outputBits?.let { it > 0 } == true
        val verified = verifiedPath && knownPcmFormat && deductions.isEmpty() &&
            (!inputs.dspAvailable || inputs.dspBypassed || running.isEmpty())
        val verification = when {
            deductions.isNotEmpty() -> Verification.MODIFIED
            verified -> Verification.VERIFIED
            else -> Verification.UNVERIFIED
        }
        val detail = when (verification) {
            Verification.MODIFIED -> "Known PCM alteration in playback path"
            Verification.VERIFIED -> "Active dedicated output reports exact PCM to DAC"
            Verification.UNVERIFIED -> when {
                !inputs.verifiedTransportActive ->
                    "Android output may modify PCM downstream; no known change detected"
                !knownPcmFormat -> "DAC format telemetry incomplete"
                else -> "End-to-end PCM exactness cannot be confirmed"
            }
        }
        return Assessment(
            score = score,
            grade = if (verified) "Bit-perfect" else if (score == 100) "No known changes" else gradeOf(score),
            activeEffects = running.map { it.first },
            effectsAvailable = inputs.dspAvailable,
            effectsBypassed = inputs.dspBypassed,
            deductions = deductions,
            bitPerfectVerified = verified,
            verification = verification,
            verificationDetail = detail,
        )
    }

    private fun gradeOf(score: Int): String = when {
        score >= 98 -> "Reference"
        score >= 90 -> "Reference"
        score >= 72 -> "High fidelity"
        score >= 55 -> "Mixed path"
        score >= 35 -> "Processed"
        else -> "Heavily processed"
    }

    private fun rateText(hz: Int): String = when {
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> "%.1f kHz".format(java.util.Locale.ROOT, hz / 1000.0)
    }
}
