/*
 * Fidelity scoring adapted from Auris by OreoMuncher45 (auris-2.3).
 * Copyright (C) 2026 BitChord Project and Auris contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting

/**
 * A single 0–100 number for how much of the source survives the playing path,
 * together with every point it deducted and why.
 *
 * This is a score for *this app's path*, not for the music or the DAC. It
 * starts at 100 and subtracts each stage that demonstrably alters or fails to
 * carry the decoded samples: the platform mixer, a lossy re-encode downstream,
 * an output encoding narrower than the source, a rate change, and each DSP
 * stage that is actually running. Every deduction is returned, so the readout
 * can say "87 because the system mixer is in the path and the EQ is on"
 * instead of showing a number nobody can act on.
 *
 * Inputs are taken from ReChord's live output and DSP state. Scores are
 * heuristic, not laboratory measurements or proof of device-level bit accuracy.
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
        /** Null means not enough source metadata to assert end-to-end fidelity. */
        val sourceLossless: Boolean? = null,
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
    )

    private const val MIXED_PATH_POINTS = 12
    private const val DIRECT_PATH_POINTS = 6
    private const val PHONE_PATH_POINTS = 32
    private const val BLUETOOTH_CODEC_POINTS = 12
    private const val NARROW_ENCODING_POINTS = 12
    private const val RESAMPLE_POINTS = 8
    private const val DSD_CONVERSION_POINTS = 6
    private const val UNVERIFIED_POINTS = 6
    private const val LOSSY_SOURCE_POINTS = 10

    private const val LOUDNESS_POINTS = 4
    private const val EQUALIZER_POINTS = 8
    private const val SPATIAL_POINTS = 8
    private const val VOCAL_CUT_POINTS = 8
    private const val JAMES_DSP_POINTS = 8

    fun assess(inputs: Inputs): Assessment {
        val deductions = mutableListOf<Deduction>()

        val verifiedPath = inputs.verifiedTransportActive &&
            inputs.verifiedTransportExact && !inputs.dacLimited

        when {
            inputs.verifiedTransportActive -> {
                if (!verifiedPath) {
                    deductions += Deduction(
                        if (inputs.dacLimited) "DAC required a format conversion" else "Output stream was not exact",
                        NARROW_ENCODING_POINTS,
                    )
                }
            }
            inputs.directPlaybackActual -> deductions +=
                Deduction("Direct AudioTrack path (device exactness unverified)", DIRECT_PATH_POINTS)
            else -> deductions += Deduction("System mixer is in the path", MIXED_PATH_POINTS)
        }

        // Routing from an idle AudioTrack snapshot must never override an active
        // dedicated driver, particularly when Android no longer owns the USB DAC.
        if (!inputs.verifiedTransportActive) {
            if (inputs.routeKind == AudioRouting.Kind.PHONE) {
                deductions += Deduction("Built-in speaker path", PHONE_PATH_POINTS)
            }
            if (inputs.routeKind == AudioRouting.Kind.BLUETOOTH) {
                deductions += Deduction("Bluetooth re-encodes the stream", BLUETOOTH_CODEC_POINTS)
            }
            if (inputs.dspAvailable && !inputs.outputExact) {
                deductions += Deduction("Output cannot carry decoded PCM exactly", NARROW_ENCODING_POINTS)
            }
        }

        if (inputs.verifiedTransportActive) {
            val inputBits = inputs.sourceBits?.takeIf { it > 0 }
            val sinkBits = inputs.outputBits?.takeIf { it > 0 }
            if (inputBits == null || sinkBits == null) {
                deductions += Deduction("USB/DAC sample precision not reported", UNVERIFIED_POINTS)
            } else if (sinkBits < inputBits && !inputs.dacLimited) {
                deductions += Deduction("DAC output precision is narrower than source", NARROW_ENCODING_POINTS)
            }
        }

        when (inputs.sourceLossless) {
            false -> deductions += Deduction("Lossy source encoding", LOSSY_SOURCE_POINTS)
            null -> deductions += Deduction("Source losslessness not confirmed", UNVERIFIED_POINTS)
            true -> Unit
        }

        if (inputs.dsd) {
            deductions += Deduction("DSD converted to PCM", DSD_CONVERSION_POINTS)
        } else {
            val source = inputs.sourceRateHz?.takeIf { it > 0 }
            val output = inputs.outputRateHz?.takeIf { it > 0 }
            if (source == null || output == null) {
                deductions += Deduction("Source or DAC sample rate not reported", UNVERIFIED_POINTS)
            } else if (source != output) {
                deductions += Deduction(
                    "Resampled ${rateText(source)} -> ${rateText(output)}",
                    RESAMPLE_POINTS,
                )
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

        val lost = deductions.sumOf { it.points }
        // Never label unverified normal AudioTrack as bit-perfect even if its
        // reported PCM encoding is exact at the app boundary.
        val exactVerified = verifiedPath && deductions.isEmpty()
        val score = (100 - lost).coerceIn(0, if (exactVerified) 100 else 99)
        return Assessment(
            score = score,
            grade = if (exactVerified) "Bit-perfect" else gradeOf(score),
            activeEffects = running.map { it.first },
            effectsAvailable = inputs.dspAvailable,
            effectsBypassed = inputs.dspBypassed,
            deductions = deductions,
            bitPerfectVerified = exactVerified,
        )
    }

    private fun gradeOf(score: Int): String = when {
        score >= 98 -> "Reference (unverified)"
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
