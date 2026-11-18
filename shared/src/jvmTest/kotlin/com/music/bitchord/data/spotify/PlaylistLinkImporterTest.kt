package com.music.bitchord.data.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistLinkImporterTest {
    @Test
    fun applePageKeepsOrderAndRepeatedTracksAndFlagsPartialResults() {
        val html = """
            <script id="serialized-server-data">[{"data":{"sections":[
              {"itemKind":"containerDetailHeaderLockup","items":[{"title":"Mix","trackCount":4}]},
              {"itemKind":"trackLockup","items":[
                {"title":"First","subtitleLinks":[{"title":"Artist"}]},
                {"title":"Second","artistName":"Another Artist"},
                {"title":"First","artistName":"Artist"}
              ]}
            ]}}]</script>
        """.trimIndent()
        val (title, tracks, partial) = PlaylistLinkImporter.parseApplePage(html)
        assertEquals("Mix", title)
        assertEquals(listOf("First", "Second", "First"), tracks.map { it.title })
        assertEquals("Artist", tracks.first().artist)
        assertTrue(partial)
    }
}
