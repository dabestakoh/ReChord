package com.music.bitchord.playback

import kotlin.math.abs

internal class SeamlessUpgradeAlignment {
    private val driftSamples = ArrayDeque<Pair<Long, Long>>()

    // Average across several position updates from each player. A large
    // discontinuity must still invalidate the estimate immediately.
    fun measureDrift(nowMs: Long, rawDriftMs: Long): Long? {
        if (abs(rawDriftMs) > 300L) driftSamples.clear()
        while (driftSamples.isNotEmpty() && nowMs - driftSamples.first().first > 300L) {
            driftSamples.removeFirst()
        }
        driftSamples.addLast(nowMs to rawDriftMs)
        if (nowMs - driftSamples.first().first < 250L) return null
        return driftSamples.sumOf { it.second } / driftSamples.size
    }

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
        driftSamples.clear()
        clearDecision()
    }

    fun clearDecision() {
        driftDirection = 0
        alignedSinceMs = null
    }
}
