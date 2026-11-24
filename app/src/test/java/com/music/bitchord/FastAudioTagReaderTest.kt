package com.music.bitchord

import com.music.bitchord.data.FastAudioTagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FastAudioTagReaderTest {

    @Test
    fun parsesFlacStreamInfoAndVorbisComment() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // Block 0: STREAMINFO (not last), length = 34
        val streamInfo = ByteArray(34)
        // Sample rate = 44100 (0x0AC44), 20 bits at byte 10..12 upper nibble
        val sr = 44100
        streamInfo[10] = ((sr ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sr ushr 4) and 0xFF).toByte()
        streamInfo[12] = ((sr and 0x0F) shl 4).toByte()
        // Total samples = 44100 * 120 = 5_292_000 (120 seconds)
        val totalSamples = 44100L * 120L
        streamInfo[13] = ((totalSamples ushr 32) and 0x0F).toByte()
        streamInfo[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        streamInfo[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        streamInfo[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        streamInfo[17] = (totalSamples and 0xFF).toByte()

        out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        out.write(streamInfo)

        // Block 4: VORBIS_COMMENT (last = 0x84)
        val vc = ByteArrayOutputStream()
        fun writeLe32(v: Int) {
            vc.write(v and 0xFF)
            vc.write((v ushr 8) and 0xFF)
            vc.write((v ushr 16) and 0xFF)
            vc.write((v ushr 24) and 0xFF)
        }
        val vendor = "ReChordTest".toByteArray(Charsets.UTF_8)
        writeLe32(vendor.size)
        vc.write(vendor)
        val comments = listOf(
            "TITLE=Time",
            "ARTIST=Pink Floyd",
            "ALBUM=The Dark Side of the Moon",
            "ALBUMARTIST=Pink Floyd",
            "TRACKNUMBER=4/10",
            "DISCNUMBER=1",
        )
        writeLe32(comments.size)
        for (c in comments) {
            val b = c.toByteArray(Charsets.UTF_8)
            writeLe32(b.size)
            vc.write(b)
        }
        val vcBytes = vc.toByteArray()
        out.write(
            byteArrayOf(
                0x84.toByte(),
                ((vcBytes.size ushr 16) and 0xFF).toByte(),
                ((vcBytes.size ushr 8) and 0xFF).toByte(),
                (vcBytes.size and 0xFF).toByte(),
            ),
        )
        out.write(vcBytes)

        val tags = FastAudioTagReader.readStream(ByteArrayInputStream(out.toByteArray()), "flac")
        assertNotNull(tags)
        assertEquals("Time", tags!!.title)
        assertEquals("Pink Floyd", tags.artist)
        assertEquals("The Dark Side of the Moon", tags.album)
        assertEquals("Pink Floyd", tags.albumArtist)
        assertEquals(120_000L, tags.durationMs)
        assertEquals(4, tags.trackNumber)
        assertEquals(1, tags.discNumber)
    }

    @Test
    fun parsesId3v23Frames() {
        val frames = ByteArrayOutputStream()
        fun writeFrame(id: String, text: String) {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val size = 1 + textBytes.size
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(byteArrayOf(0, 0, ((size ushr 8) and 0xFF).toByte(), (size and 0xFF).toByte()))
            frames.write(byteArrayOf(0, 0)) // flags
            frames.write(3) // UTF-8 encoding
            frames.write(textBytes)
        }
        writeFrame("TIT2", "Hotel California")
        writeFrame("TPE1", "Eagles")
        writeFrame("TALB", "Hotel California")
        writeFrame("TRCK", "1/9")

        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0)) // v2.3.0, flags=0
        val sz = body.size
        out.write(
            byteArrayOf(
                ((sz ushr 21) and 0x7F).toByte(),
                ((sz ushr 14) and 0x7F).toByte(),
                ((sz ushr 7) and 0x7F).toByte(),
                (sz and 0x7F).toByte(),
            ),
        )
        out.write(body)

        val tags = FastAudioTagReader.readStream(ByteArrayInputStream(out.toByteArray()), "mp3")
        assertNotNull(tags)
        assertEquals("Hotel California", tags!!.title)
        assertEquals("Eagles", tags.artist)
        assertEquals("Hotel California", tags.album)
        assertEquals(1, tags.trackNumber)
    }

    @Test
    fun parsesWavDurationFromFmtAndDataChunks() {
        val out = ByteArrayOutputStream()
        fun writeLe32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        writeLe32(1000)
        out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
        out.write("fmt ".toByteArray(Charsets.ISO_8859_1))
        writeLe32(16)
        val fmt = ByteArray(16)
        // byteRate = 176400 (44.1kHz * 16-bit * stereo) at offset 8
        val byteRate = 176400
        fmt[8] = (byteRate and 0xFF).toByte()
        fmt[9] = ((byteRate ushr 8) and 0xFF).toByte()
        fmt[10] = ((byteRate ushr 16) and 0xFF).toByte()
        fmt[11] = ((byteRate ushr 24) and 0xFF).toByte()
        out.write(fmt)
        out.write("data".toByteArray(Charsets.ISO_8859_1))
        writeLe32(byteRate * 45) // 45 seconds

        val tags = FastAudioTagReader.readStream(ByteArrayInputStream(out.toByteArray()), "wav")
        assertNotNull(tags)
        assertEquals(45_000L, tags!!.durationMs)
    }
}
