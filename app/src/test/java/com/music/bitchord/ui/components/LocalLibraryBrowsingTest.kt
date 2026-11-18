package com.music.bitchord.ui.screens

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.recentlyAddedTracks
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryBrowsingTest {
    @Test fun letterJumpsTargetTheFirstMatchingGridRow() {
        assertEquals(
            mapOf('A' to 3, 'B' to 4, '#' to 5),
            localLetterPositions(listOf("Álbum", "Another", "Beta", "Bravo", "1989"), 3, 2),
        )
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
