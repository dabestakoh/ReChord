/*
 * Copyright (C) 2026 BitChord Project
 *
 * Adapted from Mornye (https://github.com/mornyeapp/Mornye),
 * android/app/src/main/kotlin/com/zarz/mornye/UsbDirectPlayback.kt,
 * Copyright 2026 zarzet.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.music.bitchord.playback.audio.bitperfect.BitPerfectDriver
import com.music.bitchord.playback.audio.bitperfect.BitPerfectTransport
import com.music.bitchord.playback.audio.bitperfect.BitPerfectTransportException
import java.nio.ByteBuffer

/** JNI over `usb_transport.cpp`. Handles are opaque ids, never pointers. */
internal object UsbDirectNative {
    init {
        System.loadLibrary("bitchord_usb")
    }

    /** [candidates] holds [CANDIDATE_INTS] values per candidate, in preference order. */
    external fun open(fd: Int, candidates: IntArray): Long
    external fun chosen(handle: Long): Int
    external fun capacity(handle: Long): Int
    external fun write(handle: Long, data: ByteBuffer, offset: Int, count: Int): Int
    external fun setPlaying(handle: Long, playing: Boolean)
    external fun flush(handle: Long)
    external fun frames(handle: Long): Long
    external fun control(handle: Long, input: Boolean, request: Int, value: Int, index: Int, data: ByteArray): ByteArray
    external fun close(handle: Long)

    const val CANDIDATE_INTS = 17

    fun encode(plans: List<UsbDescriptors.Plan>): IntArray = plans.flatMap { plan ->
        val a = plan.alternate
        val f = plan.format
        listOf(
            a.control, a.interface_, a.alternate, if (a.uac2) 1 else 0, a.clock,
            plan.data.address, plan.data.attributes, plan.data.maxPacket, plan.data.interval,
            if (plan.data.rateControl) 1 else 0,
            plan.feedback?.address ?: -1, plan.feedback?.maxPacket ?: 0,
            f.sampleRate, f.channels, f.subslot, f.bits, f.encoding.code,
        )
    }.toIntArray()
}

/**
 * The direct USB driver: Android grants permission and opens the device, and
 * a native worker drives its isochronous endpoint with libusb, detaching
 * Android's own USB audio driver for as long as the stream is open.
 *
 * Volume is the DAC's Feature Unit, never the samples. Before the first
 * transfer every controlled channel is brought down to at most -40 dB, and a
 * level the listener chose earlier on the same DAC is put back only as far as
 * the DAC's own knob now allows. On release the DAC is returned to the level
 * it had before the stream opened. A DAC with no usable volume control plays
 * only if the listener has explicitly allowed fixed-volume output.
 *
 * A failure never redirects to the phone speaker from here: the transport
 * stops, and the sink's owner decides what plays next.
 */
internal class UsbDirectTransport private constructor(
    private val connection: UsbDeviceConnection,
    @Volatile private var handle: Long,
    val usbDeviceId: Int,
    override val deviceName: String,
    private val format: UsbDescriptors.OutputFormat,
    private val volumeControl: UsbVolume.Volume?,
    val softwareVolume: Boolean,
    initialLevel: Float,
) : BitPerfectTransport {

    override val driver = BitPerfectDriver.USB_DIRECT
    override val sampleRate = format.sampleRate
    override val channelCount = format.channels

    /** Samples are written left-aligned in the endpoint's container. */
    override val outputBits = format.subslot * 8
    override val validBits = format.bits
    override val bufferSizeFrames: Long = UsbDirectNative.capacity(handle).toLong()

    @Volatile private var failure: String? = null
    @Volatile private var detached = false
    @Volatile private var quiesced = true
    @Volatile private var lastFrames = 0L

    fun accepts(rate: Int, channels: Int, sourceBits: Int): Boolean =
        failure == null && sampleRate == rate && channelCount == channels && validBits >= sourceBits

    private val control: UsbControl = nativeControl(handle)

    val volume: UsbVolume.Snapshot? get() = volumeControl?.snapshot

    /** Called off the playback thread; the native worker serializes it with streaming. */
    fun setVolume(db: Double): UsbVolume.Snapshot {
        val volume = volumeControl ?: throw IllegalStateException("USB hardware volume unavailable")
        return synchronized(volume) {
            check(handle != 0L && !detached) { "USB output is closed" }
            volume.set(control, db)
        }
    }

    fun markDetached() {
        detached = true
        failure = "USB disconnected"
    }

    @Volatile private var softwareGain: Double = computeGain(initialLevel)

    @Volatile var softwareLevel: Float = initialLevel
        set(value) {
            field = value
            softwareGain = computeGain(value)
        }

    private val scaled by lazy { ByteBuffer.allocateDirect(MAX_WRITE) }

    override fun write(buffer: ByteBuffer): Int {
        val bytes = minOf(buffer.remaining(), MAX_WRITE)
        val input = if (softwareVolume) {
            val sampleBytes = outputBits / 8
            val frameBytes = sampleBytes * channelCount
            scaled.clear()
            val gain = softwareGain
            val end = buffer.position() + bytes - bytes % frameBytes
            for (offset in buffer.position() until end step sampleBytes) {
                var sample = buffer.get(offset + sampleBytes - 1).toInt()
                for (byte in sampleBytes - 2 downTo 0) {
                    sample = (sample shl 8) or (buffer.get(offset + byte).toInt() and 255)
                }
                val padding = outputBits - validBits
                val value = (((sample shr padding) * gain).toInt()) shl padding
                for (byte in 0 until sampleBytes) scaled.put((value shr (byte * 8)).toByte())
            }
            scaled.flip()
            scaled
        } else buffer
        val count = call { UsbDirectNative.write(handle, input, input.position(), minOf(input.remaining(), bytes)) }
        if (count > 0) quiesced = false
        buffer.position(buffer.position() + count)
        return count
    }

    private val keepAlive = SilentKeepAlive()

    override fun play() {
        keepAlive.start()
        call { UsbDirectNative.setPlaying(handle, true) }
        quiesced = false
    }

    override fun pause() {
        keepAlive.pause()
        call { UsbDirectNative.setPlaying(handle, false) }
    }

    override fun flush() {
        keepAlive.pause()
        if (!quiesced) call { UsbDirectNative.flush(handle) }
        quiesced = true
        lastFrames = 0
    }

    /** The queue runs dry and the worker pads with silence that is not counted as played. */
    override fun stop() = Unit

    override fun playedFrames(): Long {
        if (handle == 0L || failure != null) return lastFrames
        lastFrames = maxOf(lastFrames, runCatching { UsbDirectNative.frames(handle) }.getOrDefault(lastFrames))
        return lastFrames
    }

    override fun release() {
        keepAlive.release()
        val closing = handle
        if (closing == 0L) return
        try {
            val volume = volumeControl
            if (volume == null) {
                handle = 0
                UsbDirectNative.close(closing)
            } else synchronized(volume) {
                // Stop sending audio before restoring the pre-Direct hardware level.
                val restored = !detached && runCatching {
                    if (!quiesced) flush()
                    volume.restore(control)
                }.isSuccess
                handle = 0
                UsbDirectNative.close(closing)
                if (!restored && !detached) {
                    // The Java descriptor is still open after libusb releases its interfaces.
                    runCatching {
                        volume.restore { input, request, value, index, data ->
                            val transferred = connection.controlTransfer(
                                if (input) 0xA1 else 0x21, request, value, index, data, data.size, 250,
                            )
                            check(transferred == data.size) { "USB volume control transfer failed" }
                        }
                    }.onFailure {
                        com.music.bitchord.data.AppDiagnostics.record("USB volume handoff failed", it)
                    }
                }
            }
        } finally {
            connection.close()
        }
    }

    private inline fun <T> call(block: () -> T): T {
        failure?.let { throw BitPerfectTransportException(it) }
        return try {
            block()
        } catch (error: IllegalStateException) {
            failure = error.message ?: "USB output failed"
            throw BitPerfectTransportException(failure!!, error)
        }
    }

    companion object {
        private const val MAX_WRITE = 256 * 1024

        private fun computeGain(level: Float): Double =
            if (level == 0f) 0.0 else Math.pow(10.0, (level - 1.0) * 4.0)

        private fun nativeControl(nativeHandle: Long): UsbControl = UsbControl { input, request, value, index, data ->
            val reply = UsbDirectNative.control(nativeHandle, input, request, value, index, data)
            if (input) reply.copyInto(data)
        }

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            rate: Int,
            channels: Int,
            sourceBits: Int,
            convert: Boolean,
            allowFixedVolume: Boolean,
            rememberedDb: Double?,
            softwareVolume: Boolean,
            softwareLevel: Float,
        ): UsbDirectTransport {
            val connection = manager.openDevice(device) ?: throw BitPerfectTransportException("cannot_open_usb")
            var handle = 0L
            try {
                val raw = connection.rawDescriptors ?: throw BitPerfectTransportException("no_descriptors")
                val plans = plans(raw, rate, channels, sourceBits, convert)
                if (plans.isEmpty()) throw BitPerfectTransportException("format")
                handle = try {
                    UsbDirectNative.open(connection.fileDescriptor, UsbDirectNative.encode(plans))
                } catch (error: IllegalStateException) {
                    throw BitPerfectTransportException("unsupported", error)
                }
                val plan = plans[UsbDirectNative.chosen(handle)]
                val control = nativeControl(handle)
                // Startup attenuation happens inside Volume.open, before any audio.
                val volume = UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, channels)
                    ?.let { feature -> runCatching { UsbVolume.Volume.open(control, feature) }.getOrNull() }
                if (volume == null && !allowFixedVolume && !softwareVolume) throw BitPerfectTransportException("volume_unavailable")
                if (volume != null && rememberedDb != null) {
                    runCatching { volume.set(control, minOf(rememberedDb, volume.snapshot.restoreLimitDb)) }
                }
                return UsbDirectTransport(
                    connection = connection,
                    handle = handle,
                    usbDeviceId = device.deviceId,
                    deviceName = device.productName?.takeIf { it.isNotBlank() } ?: "USB audio",
                    format = plan.format,
                    volumeControl = volume,
                    softwareVolume = volume == null && softwareVolume,
                    initialLevel = softwareLevel,
                )
            } catch (error: Exception) {
                if (handle != 0L) UsbDirectNative.close(handle)
                runCatching { connection.close() }
                throw error as? BitPerfectTransportException ?: BitPerfectTransportException("unsupported", error)
            }
        }

        /** Every way the attached DAC could carry this stream, best first; only unchanged without [convert]. */
        fun plans(raw: ByteArray, rate: Int, channels: Int, sourceBits: Int, convert: Boolean): List<UsbDescriptors.Plan> {
            val device = try {
                UsbDescriptors.parse(raw)
            } catch (_: UsbDescriptors.MalformedDescriptorException) {
                return emptyList()
            }
            return UsbDescriptors.dacFormats(device, rate, channels, sourceBits, convert)
                .mapNotNull { (alternate, format) -> UsbDescriptors.plan(alternate, format) }
                .take(32)
        }
    }
}

/**
 * Tiny muted looping static AudioTrack active only while Direct USB is playing.
 * Keeps Android/OEM AudioFlinger aware that media playback is active so OEM
 * schedulers (e.g., Vivo OriginOS) do not throttle the process during app
 * switches or screen-off transitions.
 */
private class SilentKeepAlive {
    private var track: AudioTrack? = null

    @Synchronized
    fun start() {
        runCatching {
            val active = track ?: createTrack().also { track = it } ?: return
            if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
        }.onFailure {
            Log.w("UsbDirectTransport", "SilentKeepAlive start failed", it)
        }
    }

    @Synchronized
    fun pause() {
        runCatching {
            track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause()
        }
    }

    @Synchronized
    fun release() {
        runCatching {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        }
        track = null
    }

    private fun createTrack(): AudioTrack? {
        val sampleRate = 48_000
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        val rawBytes = maxOf(minBytes, (sampleRate / 5) * 2)
        val bytes = (rawBytes + 1) and 1.inv()
        val frames = bytes / 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (t.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.w("UsbDirectTransport", "SilentKeepAlive track uninitialized (bytes=$bytes)")
            t.release()
            return null
        }
        t.write(ByteArray(bytes), 0, bytes)
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w("UsbDirectTransport", "SilentKeepAlive track failed after write (state=${t.state}, bytes=$bytes)")
            t.release()
            return null
        }
        val loopStatus = t.setLoopPoints(0, frames, -1)
        if (loopStatus != AudioTrack.SUCCESS) {
            Log.w("UsbDirectTransport", "SilentKeepAlive setLoopPoints failed: $loopStatus")
        }
        t.setVolume(0f)
        Log.i("UsbDirectTransport", "SilentKeepAlive initialized (frames=$frames, bytes=$bytes)")
        return t
    }
}
