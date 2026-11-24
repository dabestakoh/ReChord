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
}
