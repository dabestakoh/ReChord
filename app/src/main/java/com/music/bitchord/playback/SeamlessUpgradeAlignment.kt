package com.music.bitchord.playback

import kotlin.math.abs

internal class SeamlessUpgradeAlignment {
    private var alignedSinceMs: Long? = null
    private var initialPositionMs = 0L

    fun sample(nowMs: Long, positionMs: Long, driftMs: Long, ready: Boolean): Boolean {
        if (!ready || abs(driftMs) > alignmentToleranceMs) {
            reset()
            return false
        }
        val since = alignedSinceMs
        if (since == null) {
            alignedSinceMs = nowMs
            initialPositionMs = positionMs
            return false
        }
        return nowMs - since >= 350L && positionMs > initialPositionMs
    }

    fun correctionTarget(activePositionMs: Long, standbyPositionMs: Long): Long? =
        activePositionMs.takeIf { abs(it - standbyPositionMs) > alignmentToleranceMs }

    private val alignmentToleranceMs = 55L

    fun reset() {
        alignedSinceMs = null
    }
}
