package com.music.bitchord.ui.screens

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.sortedLocalGroups
import com.music.bitchord.data.sortedForLibrary
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.recentlyAddedTracks
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import com.music.bitchord.data.LocalAlphabet
import java.util.Locale

class LocalLibraryBrowsingTest {
    @Test fun sortChoiceIsIndependentForEveryLocalView() {
        val state = LocalFolderState()
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.SONGS))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.ARTISTS))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.ALBUMS))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.HI_RES))
        assertEquals(LocalMusicSort.DATE_ADDED, state.sortFor(LocalFolderTab.RECENTLY_ADDED))

        state.groupSorts = mapOf(
            LocalFolderTab.SONGS to LocalMusicSort.TITLE_DESC,
            LocalFolderTab.ALBUMS to LocalMusicSort.DATE_ADDED_OLDEST,
            LocalFolderTab.RECENTLY_ADDED to LocalMusicSort.TITLE_ASC,
        )
        assertEquals(LocalMusicSort.TITLE_DESC, state.sortFor(LocalFolderTab.SONGS))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.ARTISTS))
        assertEquals(LocalMusicSort.DATE_ADDED_OLDEST, state.sortFor(LocalFolderTab.ALBUMS))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.RECENTLY_ADDED))
        assertEquals(LocalMusicSort.TITLE_ASC, state.sortFor(LocalFolderTab.HI_RES))
        // An existing Songs preference remains effective until this view picks its own sort.
        assertEquals(LocalMusicSort.DATE_ADDED, LocalFolderState().sortFor(
            LocalFolderTab.SONGS, LocalMusicSort.DATE_ADDED,
        ))
    }

    @Test fun changingAViewSortReordersTheSameInMemoryRows() {
        val songs = listOf(
            Song("a", "Alpha", "Artist", null, albumName = "Alpha", localDateAddedSeconds = 10),
            Song("z", "Zeta", "Artist", null, albumName = "Zeta", localDateAddedSeconds = 30),
        )
        val alphabet = LocalAlphabet(Locale.ENGLISH, ignoreThe = true)
        val base = { LocalGroups.albumsOf("local:all", songs, emptyList()) }
        fun order(sort: LocalMusicSort) = LocalGroups.getOrComputeSortedTabGroups(
            songs, "sort-test-albums", sort, alphabet, base,
        ).map { it.title }
        assertEquals(listOf("Alpha", "Zeta"), order(LocalMusicSort.TITLE_ASC))
        assertEquals(listOf("Zeta", "Alpha"), order(LocalMusicSort.TITLE_DESC))
        assertEquals(listOf("Zeta", "Alpha"), order(LocalMusicSort.DATE_ADDED))
        assertEquals(listOf("Alpha", "Zeta"), order(LocalMusicSort.TITLE_ASC))
    }

    @Test fun groupedSortUsesDatesAndKeepsDescendingRailAligned() {
        fun track(id: String, album: String, added: Long?) =
            Song(id, id, "Artist", null, albumName = album, localDateAddedSeconds = added)
        val groups = LocalGroups.albumsOf("local:all", listOf(
            track("one", "Alpha", null), track("two", "Beta", 20), track("three", "Zeta", 10),
        ), emptyList())
        val alphabet = LocalAlphabet(Locale.ENGLISH, ignoreThe = false)
        val byTitle = groups.sortedLocalGroups(LocalMusicSort.TITLE_DESC, alphabet).map { it.title }
        val byDate = groups.sortedLocalGroups(LocalMusicSort.DATE_ADDED, alphabet).map { it.title }
        assertEquals(listOf("Zeta", "Beta", "Alpha"), byTitle)
        assertEquals(listOf("Z", "B", "A"), localLetterPositions(byTitle, 0, 2, alphabet).keys.toList())
        assertEquals(listOf("Beta", "Zeta", "Alpha"), byDate)
    }

    @Test fun addedDateDirectionsKeepUnknownDatesLast() {
        val songs = listOf(
            Song("old", "Zeta", "Artist", null, localDateAddedSeconds = 10),
            Song("unknown", "Alpha", "Artist", null),
            Song("new", "Beta", "Artist", null, localDateAddedSeconds = 30),
        )
        assertEquals(listOf("new", "old", "unknown"),
            songs.sortedForLibrary(LocalMusicSort.DATE_ADDED).map { it.videoId })
        assertEquals(listOf("old", "new", "unknown"),
            songs.sortedForLibrary(LocalMusicSort.DATE_ADDED_OLDEST).map { it.videoId })
        val groups = LocalGroups.albumsOf("local:all", songs.map { it.copy(albumName = it.title) }, emptyList())
        val alphabet = LocalAlphabet(Locale.ENGLISH, ignoreThe = false)
        assertEquals(listOf("Zeta", "Beta", "Alpha"),
            groups.sortedLocalGroups(LocalMusicSort.DATE_ADDED_OLDEST, alphabet).map { it.title })
    }

    @Test fun letterJumpsTargetTheFirstMatchingGridRow() {
        assertEquals(
            mapOf("A" to 3, "B" to 4, "#" to 5),
            localLetterPositions(listOf("Álbum", "Another", "Beta", "Bravo", "1989"), 3, 2),
        )
    }

    @Test fun artistSortAndRailShareTheSameArticleRules() {
        val alphabet = LocalAlphabet(Locale.ENGLISH, ignoreThe = true)
        val titles = listOf("The Beatles", "A Tribe Called Quest", "Theatre")
            .sortedWith(alphabet::compare)
        assertEquals(listOf("A Tribe Called Quest", "The Beatles", "Theatre"), titles)
        assertEquals(mapOf("A" to 0, "B" to 1, "T" to 2), localLetterPositions(titles, 0, 1, alphabet))
    }

    @Test fun railKeepsSwedishLettersAndLabelsOtherScripts() {
        val alphabet = LocalAlphabet(Locale.forLanguageTag("sv"), ignoreThe = false)
        assertEquals("Å", alphabet.label("A\u030Ar"))
        assertEquals("Ä", alphabet.label("Ängel"))
        assertEquals("Ö", alphabet.label("Öga"))
        assertEquals("漢", alphabet.label("東京"))
        assertEquals("한", alphabet.label("한국"))
        assertEquals("#", alphabet.label("1989"))
    }

    @Test fun recentlyAddedSelectsOnlyTheNewestHundredDatedTracks() {
        val songs = (1..105).map { id ->
            Song("$id", "Title $id", "Artist", null, localDateAddedSeconds = id.toLong())
        } + Song("unknown", "Untitled", "Artist", null)
        assertEquals((105 downTo 6).map(Int::toString), recentlyAddedTracks(songs).map { it.videoId })
        assertEquals(99, recentlyAddedTracks(songs.take(99)).size)
    }

    @Test fun multiDiscAlbumsStayTogetherButSeparateDirectoriesDoNotMerge() {
        fun track(id: String, directory: String, disc: Int) = Song(
            videoId = id, title = id, artist = id, thumbnailUrl = null,
            albumName = "Collection", localAlbumArtist = "Various Artists",
            localPath = "$directory/$id.flac", localDiscNumber = disc, trackNumber = 1,
        )
        val tracks = listOf(
            track("second", "/Music/Release/CD2", 2),
            track("first", "/Music/Release/CD1", 1),
            track("different", "/Music/Reissue", 1),
        )
        val albums = LocalGroups.albumsOf("local:all", tracks, emptyList())
        assertEquals(2, albums.size)
        val release = albums.first { it.songs.size == 2 }
        assertEquals(listOf("first", "second"), LocalGroups.songsOf(release.browseId, tracks).map { it.videoId })
        assertEquals(1, LocalGroups.artistsOf("local:all", tracks, albumArtists = true).size)
    }
}
