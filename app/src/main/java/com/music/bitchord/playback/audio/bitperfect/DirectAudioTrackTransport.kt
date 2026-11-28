/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio.bitperfect

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Exact-rate, mixer-free PCM through Android's Direct AudioTrack path
 * (`AUDIO_OUTPUT_FLAG_DIRECT`), used on Android 10+ DAPs (HiBy DTA, FiiO,
 * Shanling, iBasso, etc.) whose HAL exposes `direct_pcm` up to 384 kHz /
 * 24- or 32-bit but does not implement AAudio MMAP `SharingMode::Exclusive`.
 *
 * Regular phones without a `direct_pcm` HAL profile return false from
 * [isDirectSupported] and cleanly fall back to standard output with all
 * effects unlocked.
 */
@RequiresApi(29)
internal class DirectAudioTrackTransport private constructor(
    private val device: AudioDeviceInfo,
    private val track: AudioTrack,
    override val sampleRate: Int,
    override val channelCount: Int,
    override val outputBits: Int,
    override val bufferSizeFrames: Long,
) : BitPerfectTransport {

    override val driver = BitPerfectDriver.DIRECT_PCM
    override val deviceName: String =
        device.productName?.toString().orEmpty().ifBlank { "Wired Hi-Res DAC" }

    @Volatile private var failure: String? = null
    private var previousHead = 0L
    private var headWrap = 0L
    private var zeroWriteCount = 0
    private val frameBytes = (outputBits / 8) * channelCount

    private val routing = AudioRouting.OnRoutingChangedListener {
        val routed = track.routedDevice
        if (routed != null && !ExclusiveTransport.isWiredHiResDevice(routed)) {
            failure = "Wired Hi-Res route changed"
        }
    }

    init {
        track.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
    }

    override fun write(buffer: ByteBuffer): Int {
        failure?.let { throw BitPerfectTransportException(it) }
        val bytes = (buffer.remaining() / frameBytes) * frameBytes
        if (bytes == 0) return 0
        val written = track.write(buffer, bytes, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) throw BitPerfectTransportException("Direct PCM write failed: $written")
        if (written == 0 && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            if (++zeroWriteCount >= MAX_STALLED_WRITES) {
                throw BitPerfectTransportException("Direct PCM stalled (dead track)")
            }
        } else {
            zeroWriteCount = 0
        }
        return written
    }

    override fun play() {
        failure?.let { throw BitPerfectTransportException(it) }
        track.play()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            throw BitPerfectTransportException("Direct PCM start failed")
        }
    }

    override fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    override fun flush() {
        pause()
        track.flush()
        previousHead = 0L
        headWrap = 0L
    }

    override fun stop() {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
    }

    override fun playedFrames(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < previousHead) headWrap += 1L shl 32
        previousHead = head
        return headWrap + head
    }

    override fun release() {
        runCatching { track.removeOnRoutingChangedListener(routing) }
        runCatching { track.release() }
    }

    companion object {
        private const val TAG = "DirectAudioTrack"
        private const val MAX_RATE_ATTEMPTS = 3
        private const val MAX_STALLED_WRITES = 120

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun open(
            manager: AudioManager,
            rate: Int,
            channels: Int,
            sourceBits: Int,
            convert: Boolean,
        ): DirectAudioTrackTransport {
            val wired = ExclusiveTransport.wiredHiResDevice(manager)
                ?: throw BitPerfectTransportException("exclusive_unavailable")
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val offered = wired.sampleRates.takeIf { it.isNotEmpty() }?.toList()
                ?: BitPerfectPcm.STANDARD_RATES
            val rates = if (convert) {
                BitPerfectPcm.rankRates(rate, offered + rate).take(MAX_RATE_ATTEMPTS)
            } else {
                listOf(rate)
            }
            val candidateDepths = buildList {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(24)
                    add(32)
                }
                add(16)
            }

            var lastError: Throwable? = null
            for (candidateRate in rates) {
                val supportedDepths = candidateDepths.filter { bits ->
                    val format = buildAudioFormat(candidateRate, mask, bits) ?: return@filter false
                    isDirectSupported(format)
                }
                if (supportedDepths.isEmpty()) continue
                val chosenBits = (if (convert) {
                    BitPerfectPcm.dacBits(sourceBits, supportedDepths)
                } else {
                    BitPerfectPcm.outputBits(sourceBits, supportedDepths)
                }) ?: continue
                val encoding = encodingForBits(chosenBits) ?: continue
                val audioFormat = buildAudioFormat(candidateRate, mask, chosenBits) ?: continue

                var track: AudioTrack? = null
                try {
                    val minimum = AudioTrack.getMinBufferSize(candidateRate, mask, encoding)
                    if (minimum <= 0) continue
                    val frameBytes = channels * (chosenBits / 8)
                    val bufferBytes = maxOf(minimum * 2, (candidateRate / 5) * frameBytes)
                        .let { (it / frameBytes) * frameBytes }
                        .coerceAtLeast(frameBytes)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(ATTRIBUTES)
                        .setAudioFormat(audioFormat)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                    if (track.state != AudioTrack.STATE_INITIALIZED ||
                        track.sampleRate != candidateRate ||
                        track.audioFormat != encoding ||
                        track.channelCount != channels
                    ) {
                        track.release()
                        continue
                    }
                    // Calling setPreferredDevice() unconditionally on an already-routed
                    // DIRECT AudioTrack can invalidate its IAudioTrack (status -32) on
                    // Android 12 DAP HALs such as HiBy DTA; only reroute if needed.
                    val routed = track.routedDevice
                    if (routed != null && routed.id != wired.id && !track.setPreferredDevice(wired)) {
                        track.release()
                        continue
                    }
                    track.setVolume(1f)
                    Log.i(
                        TAG,
                        "Opened Direct Hi-Res PCM on ${wired.productName}: ${candidateRate}Hz/${chosenBits}-bit (source ${rate}Hz/${sourceBits}-bit)",
                    )
                    return DirectAudioTrackTransport(
                        device = wired,
                        track = track,
                        sampleRate = candidateRate,
                        channelCount = channels,
                        outputBits = chosenBits,
                        bufferSizeFrames = track.bufferSizeInFrames.toLong(),
                    )
                } catch (error: Exception) {
                    runCatching { track?.release() }
                    lastError = error
                }
            }
            throw BitPerfectTransportException("exclusive_unavailable", lastError)
        }

        private fun encodingForBits(bits: Int): Int? = when (bits) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_24BIT_PACKED else null
            32 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_32BIT else null
            else -> null
        }

        private fun buildAudioFormat(rate: Int, mask: Int, bits: Int): AudioFormat? {
            val encoding = encodingForBits(bits) ?: return null
            return runCatching {
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
            }.getOrNull()
        }

        private fun isDirectSupported(format: AudioFormat): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AudioManager.getDirectPlaybackSupport(format, ATTRIBUTES) !=
                    AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            } else {
                @Suppress("DEPRECATION")
                AudioTrack.isDirectPlaybackSupported(format, ATTRIBUTES)
            }
        }.getOrDefault(false)
    }
}
