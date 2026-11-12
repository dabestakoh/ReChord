package com.music.bitchord.playback

import kotlin.math.abs

internal class SeamlessUpgradeAlignment {
    private var alignedSinceMs: Long? = null
    private var initialPositionMs = 0L
    private var driftSinceMs = 0L
    private var driftDirection = 0

    fun sample(nowMs: Long, positionMs: Long, driftMs: Long, ready: Boolean): Boolean {
        if (!ready) {
            reset()
            return false
        }
        if (abs(driftMs) > alignmentToleranceMs) {
            alignedSinceMs = null
            val direction = driftMs.compareTo(0L)
            if (direction != driftDirection) {
                driftDirection = direction
                driftSinceMs = nowMs
            }
            return false
        }
        driftDirection = 0
        val since = alignedSinceMs
        if (since == null) {
            alignedSinceMs = nowMs
            initialPositionMs = positionMs
            return false
        }
        return nowMs - since >= 350L && positionMs > initialPositionMs
    }

    fun correctionTarget(nowMs: Long, activePositionMs: Long): Long? =
        activePositionMs.takeIf { driftDirection != 0 && nowMs - driftSinceMs >= 350L }

    private val alignmentToleranceMs = 55L

    fun reset() {
        driftDirection = 0
        alignedSinceMs = null
    }
}
