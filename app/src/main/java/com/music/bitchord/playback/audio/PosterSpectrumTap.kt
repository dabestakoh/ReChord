package com.music.bitchord.playback.audio

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Read-only, zero-allocation live spectrum observer for the Poster curved rim visualizer.
 *
 * Only runs when [active] is true (Poster visualizer enabled and Poster player visible).
 * Never mutates [AudioBlock.samples], preserving byte-exact DSP bypass and Bit-Perfect output.
 */
object PosterSpectrumTap {
    const val BAND_COUNT = 24
    private const val FFT_SIZE = 512
    private const val STALE_MS = 400L

    @Volatile
    var active: Boolean = false

    @Volatile
    var autoSyncActive: Boolean = false

    private val observing: Boolean get() = active || autoSyncActive

    @Volatile
    private var lastUpdateMs: Long = 0L

    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val bands = FloatArray(BAND_COUNT)
    private val published = FloatArray(BAND_COUNT)

    /** Observes [block] read-only on the audio thread. Returns immediately when neither visualizer nor auto-sync is active. */
    fun observe(block: AudioBlock, sampleRate: Int) {
        if (!observing || block.frameCount <= 0 || sampleRate <= 0) return
        val samples = block.samples
        val ch = block.channelCount.coerceAtLeast(1)
        val frames = block.frameCount
        // Downmix to mono into circular ring buffer without allocating.
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += samples[idx++]
            ring[ringPos] = sum / ch
            ringPos = (ringPos + 1) and (FFT_SIZE - 1)
        }

        // Copy windowed samples into FFT workspace.
        var r = ringPos
        for (i in 0 until FFT_SIZE) {
            real[i] = ring[r] * hann[i]
            imag[i] = 0f
            r = (r + 1) and (FFT_SIZE - 1)
        }

        fft512(real, imag)

        // Map bins 1..(FFT_SIZE/2 - 1) logarithmically from ~45 Hz to ~14 kHz across BAND_COUNT bars.
        val nyquist = sampleRate * 0.5f
        val minHz = 45f
        val maxHz = minOf(14_000f, nyquist * 0.92f).coerceAtLeast(minHz * 2f)
        val ratio = (maxHz / minHz).toDouble()
        val half = FFT_SIZE / 2

        for (b in 0 until BAND_COUNT) {
            val loHz = minHz * ratio.pow(b.toDouble() / BAND_COUNT).toFloat()
            val hiHz = minHz * ratio.pow((b + 1).toDouble() / BAND_COUNT).toFloat()
            val loBin = ((loHz / nyquist) * half).toInt().coerceIn(1, half - 1)
            val hiBin = ((hiHz / nyquist) * half).toInt().coerceIn(loBin, half - 1)
            var peak = 0f
            for (k in loBin..hiBin) {
                val re = real[k]
                val im = imag[k]
                val mag = sqrt(re * re + im * im) / (FFT_SIZE * 0.25f)
                if (mag > peak) peak = mag
            }
            // Perceptual dB-like compression into 0..1 with slight treble lift so upper bands stay lively.
            val lift = 1f + (b.toFloat() / BAND_COUNT) * 0.85f
            val level = (ln(1f + peak * lift * 18f) / ln(19f)).coerceIn(0f, 1f)
            bands[b] = level
        }

        System.arraycopy(bands, 0, published, 0, BAND_COUNT)
        lastUpdateMs = SystemClock.uptimeMillis()
    }

    /** Copies the latest spectrum into [out], returning false if silent/stale/inactive. */
    fun read(out: FloatArray): Boolean {
        if (!observing) return false
        if (SystemClock.uptimeMillis() - lastUpdateMs > STALE_MS) return false
        val count = minOf(out.size, BAND_COUNT)
        System.arraycopy(published, 0, out, 0, count)
        return true
    }

    /** Returns live vocal-band energy (~300 Hz – 3.4 kHz, bands 5..16) in 0..1, or -1f if stale/inactive. */
    fun readVocalEnergy(): Float {
        if (!observing) return -1f
        if (SystemClock.uptimeMillis() - lastUpdateMs > STALE_MS) return -1f
        var sum = 0f
        var count = 0
        for (b in 5..16) {
            sum += published[b]
            count++
        }
        return if (count > 0) (sum / count).coerceIn(0f, 1f) else -1f
    }

    private fun fft512(re: FloatArray, im: FloatArray) {
        val n = FFT_SIZE
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val ang = (-2.0 * PI / len)
            val wLenR = cos(ang).toFloat()
            val wLenI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + halfLen] * wR - im[i + k + halfLen] * wI
                    val vI = re[i + k + halfLen] * wI + im[i + k + halfLen] * wR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + halfLen] = uR - vR
                    im[i + k + halfLen] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
