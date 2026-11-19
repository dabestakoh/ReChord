package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionDisplayActionTest {
    @Test
    fun committedAudioCanOnlyShowOrHideItsCompanionPicture() {
        assertEquals(
            VersionDisplayAction.SHOW_PICTURE,
            VersionDisplayAction.forPlayback(isVideo = false, hasCompanionPicture = false),
        )
        assertEquals(
            VersionDisplayAction.HIDE_PICTURE,
            VersionDisplayAction.forPlayback(isVideo = false, hasCompanionPicture = true),
        )
    }

    @Test
    fun onlyANativeVideoWithoutACompanionOffersAnAudioSwap() {
        assertEquals(
            VersionDisplayAction.PLAY_AUDIO_VERSION,
            VersionDisplayAction.forPlayback(isVideo = true, hasCompanionPicture = false),
        )
        assertEquals(
            VersionDisplayAction.HIDE_PICTURE,
            VersionDisplayAction.forPlayback(isVideo = true, hasCompanionPicture = true),
        )
    }
}
