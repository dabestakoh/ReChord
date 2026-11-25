package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal enum class PlaybackBufferRole {
    ACTIVE, STANDBY, QUALITY_CHECK,
}

internal class PlaybackMemoryBudget(heapLimitBytes: Long, lowRamDevice: Boolean) {
    val constrained = lowRamDevice || heapLimitBytes <= 192L * MIB
    val backBufferMs = if (constrained) 5_000 else 10_000
    val browseCacheEntries = if (constrained) 512 else 1_024
    val artworkCacheBytes = (heapLimitBytes / 10).coerceAtMost(if (constrained) 16L * MIB else 48L * MIB)

    private val pressureThresholdBytes = heapLimitBytes - heapLimitBytes / 4

    fun isUnderPressure(usedHeapBytes: Long): Boolean = usedHeapBytes >= pressureThresholdBytes

    fun targetBytes(role: PlaybackBufferRole, underPressure: Boolean): Int {
        val mib = when (role) {
            PlaybackBufferRole.ACTIVE -> if (underPressure) 4 else if (constrained) 5 else 8
            PlaybackBufferRole.STANDBY -> if (underPressure) 2 else if (constrained) 3 else 4
            // The quality check still needs 12 seconds ahead of a moving playhead.
            PlaybackBufferRole.QUALITY_CHECK -> if (underPressure) 12 else if (constrained) 16 else 20
        }
        return mib * MIB
    }

    fun minimumReadAheadUs(role: PlaybackBufferRole): Long = when (role) {
        PlaybackBufferRole.ACTIVE -> 3_000_000L
        PlaybackBufferRole.STANDBY -> 4_000_000L
        PlaybackBufferRole.QUALITY_CHECK -> 16_000_000L
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}

/** Byte targets are soft so high-bitrate input can still make forward progress. */
@UnstableApi
internal class PlaybackBufferControl(
    private val budget: PlaybackMemoryBudget,
    role: PlaybackBufferRole,
    minBufferMs: Int,
    maxBufferMs: Int,
    startBufferMs: Int,
    rebufferMs: Int,
    backBufferMs: Int,
    private val underPressure: () -> Boolean,
    private val allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
) : DefaultLoadControl(
    allocator,
    minBufferMs, minBufferMs,
    maxBufferMs, maxBufferMs,
    startBufferMs, startBufferMs,
    rebufferMs, rebufferMs,
    budget.targetBytes(
        if (role == PlaybackBufferRole.QUALITY_CHECK) role else PlaybackBufferRole.ACTIVE,
        underPressure = false,
    ),
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* prioritizeTimeOverSizeThresholdsForLocalPlayback = */ true,
    backBufferMs,
    /* retainBackBufferFromKeyframe = */ true,
) {
    @Volatile
    var role = role

    val allocatedBytes: Int get() = allocator.totalBytesAllocated

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val currentRole = role
        val targetBytes = budget.targetBytes(currentRole, underPressure())
        allocator.setTargetBufferSize(targetBytes)
        if (parameters.bufferedDurationUs >=
            budget.minimumReadAheadUs(currentRole) * parameters.playbackSpeed.coerceAtLeast(1f) &&
            allocatedBytes >= targetBytes
        ) return false
        return super.shouldContinueLoading(parameters)
    }
}
