package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test fun pausedWhilePreparingMustStayPaused() {
        assertFalse(PlaybackResumePolicy.shouldResume(wasPlaying = true, currentlyRequested = false))
    }

    @Test fun alreadyPausedMustStayPaused() {
        assertFalse(PlaybackResumePolicy.shouldResume(wasPlaying = false, currentlyRequested = false))
    }

    @Test fun activePlaybackCanContinueThroughVersionSwap() {
        assertTrue(PlaybackResumePolicy.shouldResume(wasPlaying = true, currentlyRequested = true))
    }

    @Test fun stalePausedSnapshotNeverInitiatesPlayback() {
        assertFalse(PlaybackResumePolicy.shouldResume(wasPlaying = false, currentlyRequested = true))
    }
}
