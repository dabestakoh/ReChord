package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMotionClockTest {
    @Test fun navigationFreezeExpiresWithoutASettledCallback() {
        var time = 100L
        val clock = GlassMotionClock(freezeWindowNs = 50L, now = { time })
        assertFalse(clock.frozen())
        clock.moved()
        assertTrue(clock.frozen())
        time = 149L
        assertTrue(clock.frozen())
        time = 150L
        assertFalse(clock.frozen())
    }

    @Test fun repeatedMotionExtendsFreezeAndSettlingThawsImmediately() {
        var time = 1L
        val clock = GlassMotionClock(freezeWindowNs = 50L, now = { time })
        clock.moved()
        time = 40L
        clock.moved()
        time = 60L
        assertTrue(clock.frozen())
        clock.settled()
        assertFalse(clock.frozen())
    }

    @Test fun clockRegressionCannotKeepGlassFrozen() {
        var time = 100L
        val clock = GlassMotionClock(freezeWindowNs = 50L, now = { time })
        clock.moved()
        time = 99L
        assertFalse(clock.frozen())
    }
}
