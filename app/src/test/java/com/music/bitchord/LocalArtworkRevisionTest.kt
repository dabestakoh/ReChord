package com.music.bitchord

import com.music.bitchord.data.localArtworkRevisionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalArtworkRevisionTest {

    @Test
    fun embeddedArtworkRevisionIncludesMillisecondsAndFileSize() {
        val original = localArtworkRevisionToken(
            modifiedMs = 1_700_000_000_001L,
            sizeBytes = 4_096L,
            sidecarSignatures = emptyList(),
        )

        assertNotEquals(
            original,
            localArtworkRevisionToken(1_700_000_000_002L, 4_096L, emptyList()),
        )
        assertNotEquals(
            original,
            localArtworkRevisionToken(1_700_000_000_001L, 4_097L, emptyList()),
        )
    }

    @Test
    fun sidecarRevisionChangesTheArtworkKeyWithoutChangingAudio() {
        val original = localArtworkRevisionToken(1_000L, 8_000L, listOf("cover.jpg:10:20"))
        val replaced = localArtworkRevisionToken(1_000L, 8_000L, listOf("cover.jpg:11:21"))

        assertNotEquals(original, replaced)
        assertEquals(original, localArtworkRevisionToken(1_000L, 8_000L, listOf("cover.jpg:10:20")))
    }
}
