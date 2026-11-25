package com.music.bitchord

import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalScanFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanFolderTest {

    @Test
    fun deduplicatesOverlappingChildFoldersWhenParentIncludesSubfolders() {
        val parent = LocalScanFolder("/storage/emulated/0/Music", isTreeUri = false, label = "Music", includeSubfolders = true)
        val child = LocalScanFolder("/storage/emulated/0/Music/FLAC", isTreeUri = false, label = "FLAC", includeSubfolders = true)
        val other = LocalScanFolder("/storage/1234-5678/HiRes", isTreeUri = false, label = "HiRes", includeSubfolders = false)

        val normalized = LocalMediaRepository.normalizeScanFolders(listOf(parent, child, other))
        assertEquals(listOf(parent, other), normalized)
    }

    @Test
    fun keepsChildFolderWhenParentDoesNotIncludeSubfolders() {
        val parent = LocalScanFolder("/storage/emulated/0/Music", isTreeUri = false, label = "Music", includeSubfolders = false)
        val child = LocalScanFolder("/storage/emulated/0/Music/FLAC", isTreeUri = false, label = "FLAC", includeSubfolders = true)

        val normalized = LocalMediaRepository.normalizeScanFolders(listOf(parent, child))
        assertEquals(listOf(parent, child), normalized)
    }

    @Test
    fun matchesDirectChildrenOnlyWhenIncludeSubfoldersIsFalse() {
        val folder = LocalScanFolder("/storage/emulated/0/Music", isTreeUri = false, label = "Music", includeSubfolders = false)
        assertTrue(LocalMediaRepository.isPathInFolder("/storage/emulated/0/Music/song.flac", folder))
        assertFalse(LocalMediaRepository.isPathInFolder("/storage/emulated/0/Music/Sub/song.flac", folder))
    }

    @Test
    fun matchesExcludedFoldersProperly() {
        val excluded = listOf(
            LocalScanFolder("/storage/emulated/0/Audiobooks", isTreeUri = false, label = "Audiobooks", includeSubfolders = true),
        )
        assertTrue(LocalMediaRepository.isPathExcluded("/storage/emulated/0/Audiobooks/Book1/ch1.mp3", excluded))
        assertFalse(LocalMediaRepository.isPathExcluded("/storage/emulated/0/Music/Album/track.flac", excluded))
    }

    @Test
    fun normalizesMinDurationToleranceToFiveSecondSteps() {
        assertEquals(0, AppSettings.normalizeLocalMinDurationSec(-10))
        assertEquals(0, AppSettings.normalizeLocalMinDurationSec(0))
        assertEquals(15, AppSettings.normalizeLocalMinDurationSec(14))
        assertEquals(30, AppSettings.normalizeLocalMinDurationSec(30))
        assertEquals(60, AppSettings.normalizeLocalMinDurationSec(90))
    }

    @Test
    fun songScopeDropsRemovedFoldersAndKeepsOverlappingFolders() {
        val songA = com.music.bitchord.data.model.Song(
            videoId = "file:///storage/emulated/0/Music/FLAC/a.flac",
            title = "A",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "file:///storage/emulated/0/Music/FLAC/a.flac",
            localPath = "/storage/emulated/0/Music/FLAC/a.flac",
        )
        val songB = songA.copy(
            videoId = "file:///storage/emulated/0/Downloads/b.flac",
            localUri = "file:///storage/emulated/0/Downloads/b.flac",
            localPath = "/storage/emulated/0/Downloads/b.flac",
        )
        val keptFolder = LocalScanFolder("/storage/emulated/0/Music", isTreeUri = false, label = "Music", includeSubfolders = true)
        val excluded = listOf(LocalScanFolder("/storage/emulated/0/Music/FLAC", isTreeUri = false, label = "FLAC", includeSubfolders = true))

        assertTrue(LocalMediaRepository.isSongInScope(songA, com.music.bitchord.data.settings.LocalMusicScanMode.SPECIFIC_FOLDERS, listOf(keptFolder), emptyList()))
        assertFalse(LocalMediaRepository.isSongInScope(songB, com.music.bitchord.data.settings.LocalMusicScanMode.SPECIFIC_FOLDERS, listOf(keptFolder), emptyList()))
        assertFalse(LocalMediaRepository.isSongInScope(songA, com.music.bitchord.data.settings.LocalMusicScanMode.ALL_FOLDERS, emptyList(), excluded))
    }

    @Test
    fun filterIgnoredHidesOnlyIgnoredSongs() {
        val s1 = com.music.bitchord.data.model.Song(videoId = "file:///a.flac", title = "A", artist = "X", thumbnailUrl = null, localUri = "file:///a.flac")
        val s2 = com.music.bitchord.data.model.Song(videoId = "file:///b.flac", title = "B", artist = "X", thumbnailUrl = null, localUri = "file:///b.flac")
        val visible = com.music.bitchord.data.LocalLibraryIndex.filterIgnored(listOf(s1, s2), setOf("file:///a.flac"))
        assertEquals(listOf(s2), visible)
    }

    @Test
    fun localAlphabetFastAsciiAndUnicodeScriptLabelsMatch() {
        val alpha = com.music.bitchord.data.LocalAlphabet(java.util.Locale.ENGLISH, ignoreThe = true)
        assertEquals("B", alpha.label("The Beatles"))
        assertEquals("A", alpha.label("aespa"))
        assertEquals("#", alpha.label("'Di Na Muli"))
        assertEquals("한", alpha.label("한국어 노래"))
        assertEquals("あ", alpha.label("あいうえお"))
        assertEquals("漢", alpha.label("漢字"))
    }

    @Test
    fun localGroupsCachesGroupByIdForInstantSongsOfLookup() {
        val s1 = com.music.bitchord.data.model.Song(
            videoId = "1",
            title = "Track 1",
            artist = "Artist A",
            thumbnailUrl = "localcover:1",
            albumName = "Album X",
            localUri = "file:///music/1.flac",
            localPath = "/music/1.flac",
            trackNumber = 1,
        )
        val s2 = s1.copy(videoId = "2", title = "Track 2", localUri = "file:///music/2.flac", localPath = "/music/2.flac", trackNumber = 2)
        val songs = listOf(s2, s1)
        val groups = com.music.bitchord.data.LocalGroups.getOrComputeTabGroups(songs, "test-albums") {
            com.music.bitchord.data.LocalGroups.albumsOf("local:all", songs, emptyList())
        }
        assertEquals(1, groups.size)
        val browseId = groups.first().browseId
        val lookedUp = com.music.bitchord.data.LocalGroups.songsOf(browseId, songs)
        assertEquals(listOf(s1, s2), lookedUp)
    }
}
