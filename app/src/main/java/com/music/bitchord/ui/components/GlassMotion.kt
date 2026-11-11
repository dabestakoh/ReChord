/*
 * Navigation glass capture clock adapted from Auris by OreoMuncher45.
 * Original concept: Auris auris-2.3, GlassMotion.kt.
 * Copyright (C) 2026 BitChord / Auris contributors
 */
package com.music.bitchord.ui.components

/**
 * A monotonic, unobservable clock for a short-lived glass freeze.
 * Draw nodes poll this clock without triggering Compose recompositions.
 * The deadline prevents a canceled transition from freezing indefinitely.
 */
class GlassMotionClock(
    private val freezeWindowNs: Long = 500_000_000L,
    private val now: () -> Long = System::nanoTime,
) {
    @Volatile private var lastMotionAtNs: Long = Long.MIN_VALUE

    fun moved() {
        lastMotionAtNs = now()
    }

    fun settled() {
        lastMotionAtNs = Long.MIN_VALUE
    }

    fun frozen(): Boolean {
        val stamped = lastMotionAtNs
        return stamped != Long.MIN_VALUE &&
            now() - stamped in 0 until freezeWindowNs
    }
}

/** Only navigation glass is optimized; existing player sheet behavior is retained. */
object GlassMotion {
    val nav = GlassMotionClock()
}
