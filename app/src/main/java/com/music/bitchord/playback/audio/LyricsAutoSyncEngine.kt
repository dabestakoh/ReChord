package com.music.bitchord.playback.audio

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.LyricsAutoSyncState
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One-tap background vocal-alignment engine for Lyrics Auto-Sync.
 *
 * Listens read-only to live vocal-band energy (~300 Hz – 3.4 kHz) via [PosterSpectrumTap]
 * for ~6 seconds without downloading extra audio or touching playback samples, then
 * cross-correlates the heard vocal envelope & onsets against the expected lyric singing
 * envelope across [MIN_LYRICS_OFFSET_MS]..[MAX_LYRICS_OFFSET_MS] to compute and save a
 * per-song timing offset.
 */
class LyricsAutoSyncEngine(
    private val saveOffset: (songId: String, offsetMs: Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<LyricsAutoSyncState>(LyricsAutoSyncState.Idle)
    val state: StateFlow<LyricsAutoSyncState> = _state.asStateFlow()

    fun cancel() {
        job?.cancel()
        job = null
        PosterSpectrumTap.autoSyncActive = false
        _state.value = LyricsAutoSyncState.Idle
    }

    fun start(
        songId: String,
        lines: List<LyricLine>,
        isPlaying: () -> Boolean,
        currentPositionMs: () -> Long,
        previousOffsetMs: Int,
    ) {
        job?.cancel()
        val timed = lines.filter { !it.isGap && (it.timeMs > 0L || it.sungUntilMs != null) }
        if (timed.size < 3) {
            _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = false)
            return
        }
        if (!isPlaying()) {
            _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = true)
            return
        }

        _state.value = LyricsAutoSyncState.Listening(songId, 0f)
        job = scope.launch {
            PosterSpectrumTap.autoSyncActive = true
            try {
                val frameMs = 40L
                val totalFrames = 150 // 6.0 seconds of live vocal observation
                val samplePos = LongArray(totalFrames)
                val heard = FloatArray(totalFrames)
                var validSamples = 0

                for (i in 0 until totalFrames) {
                    delay(frameMs)
                    if (!isPlaying()) {
                        _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = true)
                        return@launch
                    }
                    val pos = currentPositionMs()
                    val vocal = PosterSpectrumTap.readVocalEnergy()
                    samplePos[i] = pos
                    if (vocal >= 0f) {
                        heard[i] = vocal
                        validSamples++
                    } else {
                        heard[i] = 0f
                    }
                    _state.value = LyricsAutoSyncState.Listening(songId, (i + 1).toFloat() / totalFrames)
                }

                if (validSamples < totalFrames / 2) {
                    _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = false)
                    return@launch
                }

                val bestOffset = computeBestOffset(timed, samplePos, heard)
                if (bestOffset == null) {
                    _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = false)
                    return@launch
                }

                val snapped = ((bestOffset / 50f).roundToInt() * 50)
                    .coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
                saveOffset(songId, snapped)
                _state.value = LyricsAutoSyncState.Synced(
                    songId = songId,
                    offsetMs = snapped,
                    previousOffsetMs = previousOffsetMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = LyricsAutoSyncState.Failed(songId, needsPlayback = false)
            } finally {
                PosterSpectrumTap.autoSyncActive = false
            }
        }
    }

    private fun computeBestOffset(
        lines: List<LyricLine>,
        positions: LongArray,
        heardEnergy: FloatArray,
    ): Int? {
        val n = heardEnergy.size
        // Compute vocal onsets (positive energy rises) alongside raw vocal presence.
        val heardOnsets = FloatArray(n) { i ->
            if (i == 0) 0f else max(0f, heardEnergy[i] - heardEnergy[i - 1])
        }
        val normEnergy = zscore(heardEnergy) ?: return null
        val normOnsets = zscore(heardOnsets) ?: FloatArray(n)

        // Build line intervals (start..end) for fast lookup.
        val starts = LongArray(lines.size) { lines[it].timeMs }
        val ends = LongArray(lines.size) { i ->
            val explicit = lines[i].sungUntilMs
            val nextStart = lines.getOrNull(i + 1)?.timeMs
            when {
                explicit != null && explicit > starts[i] -> explicit
                nextStart != null && nextStart > starts[i] -> minOf(nextStart, starts[i] + 5_000L)
                else -> starts[i] + 3_000L
            }
        }

        val expectedPresence = FloatArray(n)
        val expectedOnset = FloatArray(n)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestOffset = 0
        var secondBest = Float.NEGATIVE_INFINITY

        var offset = MIN_LYRICS_OFFSET_MS
        while (offset <= MAX_LYRICS_OFFSET_MS) {
            var activeCount = 0
            for (i in 0 until n) {
                val lyricClockMs = positions[i] + offset
                var singing = 0f
                var onset = 0f
                for (l in starts.indices) {
                    val s = starts[l]
                    val e = ends[l]
                    if (lyricClockMs in s..e) {
                        singing = 1f
                        if (lyricClockMs - s <= 160L) onset = 1f
                        activeCount++
                        break
                    }
                }
                expectedPresence[i] = singing
                expectedOnset[i] = onset
            }

            if (activeCount in (n / 10)..(n * 9 / 10)) {
                val expP = zscore(expectedPresence)
                if (expP != null) {
                    val expO = zscore(expectedOnset)
                    var corr = 0f
                    for (i in 0 until n) {
                        corr += normEnergy[i] * expP[i]
                        if (expO != null) corr += 0.45f * normOnsets[i] * expO[i]
                    }
                    corr /= n
                    if (corr > bestScore) {
                        if (abs(offset - bestOffset) > 400) secondBest = bestScore
                        bestScore = corr
                        bestOffset = offset
                    } else if (corr > secondBest && abs(offset - bestOffset) > 400) {
                        secondBest = corr
                    }
                }
            }
            offset += 25
        }

        // Require a meaningful positive correlation so instrumental sections don't produce random offsets.
        if (bestScore < 0.08f) return null
        return bestOffset
    }

    private fun zscore(values: FloatArray): FloatArray? {
        if (values.isEmpty()) return null
        var sum = 0.0
        for (v in values) sum += v
        val mean = (sum / values.size).toFloat()
        var sq = 0.0
        for (v in values) {
            val d = v - mean
            sq += d * d
        }
        val std = sqrt(sq / values.size).toFloat()
        if (std < 1e-4f) return null
        return FloatArray(values.size) { (values[it] - mean) / std }
    }
}
