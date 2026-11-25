package com.music.bitchord

import com.music.bitchord.data.LocalMediaRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaFilterTest {

    @Test
    fun rejectsShortAudio() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 29_999,
                displayName = "effect.mp3",
                path = "/storage/emulated/0/Music/effect.mp3",
            ),
        )
    }

    @Test
    fun rejectsWaveAndRecorderFiles() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "recording.wav",
                path = "/storage/emulated/0/Recordings/recording.wav",
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "voice-note.mp3",
                path = "/storage/emulated/0/Voice Recorder/voice-note.mp3",
            ),
        )
    }

    @Test
    fun keepsSupportedMusicFiles() {
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 50_000,
                displayName = "Song.flac",
                path = "/storage/emulated/0/Music/Artist/Song.flac",
            ),
        )
    }

    @Test
    fun keepsAudioOnlyMp4AndDsd() {
        for (name in listOf("Song.mp4", "Song.dsf", "Song.dff")) {
            assertTrue(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/$name",
                ),
            )
        }
    }

    @Test
    fun keepsTracksTheScannerCouldNotTime() {
        // Fragmented-MP4 ALAC scans with duration 0, high-rate DSD with NULL
        // (read back as 0): an unknown length, not a short clip.
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 0,
                displayName = "Starboy.m4a",
                path = "/storage/emulated/0/Music/Starboy.m4a",
            ),
        )
    }

    @Test
    fun keepsAudiophileFormatsInMusicFolder() {
        for (name in listOf("Track.wav", "Track.aiff", "Track.aif", "Track.wv", "Track.ape")) {
            assertTrue(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/HiRes/$name",
                ),
            )
        }
    }

    @Test
    fun alwaysRejectsVideoFilesEvenWhenFilterDisabled() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "concert.mp4",
                path = "/storage/emulated/0/Music/concert.mp4",
                filterNonMusic = false,
                hasVideo = true,
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "clip.mp4",
                path = "/storage/emulated/0/Movies/clip.mp4",
                filterNonMusic = false,
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "camera.mp4",
                path = "/storage/emulated/0/DCIM/Camera/camera.mp4",
                filterNonMusic = false,
            ),
        )
    }

    @Test
    fun respectsConfigurableShortAudioThreshold() {
        // 15s interlude: accepted when threshold is 0s or 15s, rejected at 30s or 60s.
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 15_000,
                displayName = "Interlude.flac",
                path = "/storage/emulated/0/Music/Interlude.flac",
                minDurationMs = 0L,
            ),
        )
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 15_000,
                displayName = "Interlude.flac",
                path = "/storage/emulated/0/Music/Interlude.flac",
                minDurationMs = 15_000L,
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 15_000,
                displayName = "Interlude.flac",
                path = "/storage/emulated/0/Music/Interlude.flac",
                minDurationMs = 30_000L,
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 59_000,
                displayName = "Skit.flac",
                path = "/storage/emulated/0/Music/Skit.flac",
                minDurationMs = 60_000L,
            ),
        )
    }
}
