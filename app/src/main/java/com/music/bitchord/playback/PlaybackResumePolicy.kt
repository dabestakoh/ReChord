package com.music.bitchord.playback

/** Do not override a newer pause while an alternate audio version is being prepared. */
internal object PlaybackResumePolicy {
    fun shouldResume(wasPlaying: Boolean, currentlyRequested: Boolean): Boolean =
        wasPlaying && currentlyRequested
}
