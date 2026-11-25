package com.music.bitchord

import com.music.bitchord.data.FastAudioTagReader
import com.music.bitchord.data.LocalLibraryIndex
import com.music.bitchord.data.LocalTagWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LocalTagOverride
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTagWriterTest {

    @Test
    fun flacWriteReadRoundTripPreservesStreamInfoArtworkAndAudioFrames() {
        val original = ByteArrayOutputStream()
        original.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // Block 0: STREAMINFO (34 bytes: 96kHz, 2ch, 24-bit, 96000 samples = 1s)
        val streamInfo = ByteArray(34)
        val sr = 96_000
        streamInfo[10] = ((sr ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sr ushr 4) and 0xFF).toByte()
        streamInfo[12] = (((sr and 0xF) shl 4) or (1 shl 1) or 1).toByte() // ch=2 (1), bps=24 (23 -> top bit 1)
        streamInfo[13] = ((7 shl 4) or 0).toByte() // low 4 bits of 23 = 7
        streamInfo[15] = ((96_000 ushr 16) and 0xFF).toByte()
        streamInfo[16] = ((96_000 ushr 8) and 0xFF).toByte()
        streamInfo[17] = (96_000 and 0xFF).toByte()
        original.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        original.write(streamInfo)

        // Block 6: PICTURE (last=1)
        val fakeArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val pic = ByteArrayOutputStream()
        fun be32(v: Int) {
            pic.write((v ushr 24) and 0xFF); pic.write((v ushr 16) and 0xFF)
            pic.write((v ushr 8) and 0xFF); pic.write(v and 0xFF)
        }
        be32(3); be32(10); pic.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
        be32(0); be32(100); be32(100); be32(24); be32(0); be32(fakeArt.size); pic.write(fakeArt)
        val picBytes = pic.toByteArray()
        original.write(
            byteArrayOf(
                0x86.toByte(),
                ((picBytes.size ushr 16) and 0xFF).toByte(),
                ((picBytes.size ushr 8) and 0xFF).toByte(),
                (picBytes.size and 0xFF).toByte(),
            ),
        )
        original.write(picBytes)
        val audioTail = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        original.write(audioTail)

        val edited = ByteArrayOutputStream()
        val ok = LocalTagWriter.rewriteFlacStream(
            ByteArrayInputStream(original.toByteArray()),
            edited,
            LocalTagWriter.EditableTags(
                title = "New Title",
                artist = "New Artist",
                album = "New Album",
                albumArtist = "Album Artist",
                trackNumber = 5,
                discNumber = 2,
                genre = "Jazz",
                year = "2024",
            ),
        )
        assertTrue(ok)
        val outBytes = edited.toByteArray()

        val parsed = FastAudioTagReader.readStream(ByteArrayInputStream(outBytes), "flac")
        assertNotNull(parsed)
        assertEquals("New Title", parsed!!.title)
        assertEquals("New Artist", parsed.artist)
        assertEquals("New Album", parsed.album)
        assertEquals("Album Artist", parsed.albumArtist)
        assertEquals(5, parsed.trackNumber)
        assertEquals(2, parsed.discNumber)
        assertEquals(96_000, parsed.sampleRateHz)
        assertEquals(24, parsed.bitsPerSample)

        val preservedArt = FastAudioTagReader.readEmbeddedPictureStream(ByteArrayInputStream(outBytes), "flac")
        assertNotNull(preservedArt)
        assertEquals(fakeArt.toList(), preservedArt!!.toList())
        assertEquals(audioTail.toList(), outBytes.takeLast(audioTail.size))
    }

    @Test
    fun mp3WriteReadRoundTripUpdatesTagsAndPreservesAudioTail() {
        val rawAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x12, 0x34)
        val out = ByteArrayOutputStream()
        val ok = LocalTagWriter.rewriteMp3Stream(
            ByteArrayInputStream(rawAudio),
            out,
            LocalTagWriter.EditableTags(
                title = "MP3 Track",
                artist = "MP3 Artist",
                album = "MP3 Album",
                albumArtist = "VA",
                trackNumber = 3,
                discNumber = 1,
                genre = "Rock",
                year = "2025",
            ),
        )
        assertTrue(ok)
        val bytes = out.toByteArray()
        val parsed = FastAudioTagReader.readStream(ByteArrayInputStream(bytes), "mp3")
        assertNotNull(parsed)
        assertEquals("MP3 Track", parsed!!.title)
        assertEquals("MP3 Artist", parsed.artist)
        assertEquals("MP3 Album", parsed.album)
        assertEquals("VA", parsed.albumArtist)
        assertEquals(3, parsed.trackNumber)
        assertEquals(1, parsed.discNumber)
        assertEquals(rawAudio.toList(), bytes.takeLast(rawAudio.size))
    }

    @Test
    fun appOnlyOverrideAppliesToWavSongAcrossRescans() {
        val wav = Song(
            videoId = "file:///Music/track.wav",
            title = "track",
            artist = "Unknown Artist",
            thumbnailUrl = null,
            localUri = "file:///Music/track.wav",
            localPath = "/Music/track.wav",
            localFormat = "WAV",
        )
        assertTrue(!LocalTagWriter.supportsDirectFileWrite(wav))
        val overrides = mapOf(
            "file:///Music/track.wav" to LocalTagOverride(
                title = "Mastered Track",
                artist = "Studio Artist",
                albumName = "Sessions",
                trackNumber = 1,
            ),
        )
        val updated = LocalLibraryIndex.applyTagOverrides(listOf(wav), overrides).single()
        assertEquals("Mastered Track", updated.title)
        assertEquals("Studio Artist", updated.artist)
        assertEquals("Sessions", updated.albumName)
        assertEquals(1, updated.trackNumber)
    }
}
