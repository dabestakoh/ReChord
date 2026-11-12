package com.music.bitchord.playback

import kotlin.math.abs

internal class SeamlessUpgradeAlignment {
    private var alignedSinceMs: Long? = null
    private var initialPositionMs = 0L

    fun sample(nowMs: Long, positionMs: Long, driftMs: Long, ready: Boolean): Boolean {
        if (!ready || abs(driftMs) > 55L) {
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

    fun reset() {
        alignedSinceMs = null
    }
}
