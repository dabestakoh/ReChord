package com.music.bitchord.playback

/** The Now Playing version button's label and click action come from one choice. */
internal enum class VersionDisplayAction {
    SHOW_PICTURE,
    HIDE_PICTURE,
    PLAY_AUDIO_VERSION;

    companion object {
        fun forPlayback(isVideo: Boolean, hasCompanionPicture: Boolean): VersionDisplayAction = when {
            hasCompanionPicture -> HIDE_PICTURE
            isVideo -> PLAY_AUDIO_VERSION
            else -> SHOW_PICTURE
        }
    }
}
