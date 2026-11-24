package com.music.bitchord.data

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Pure-Kotlin, zero-IPC binary header tag reader for high-speed indexing of
 * large audiophile libraries (FLAC, MP3/ID3v2, DSF, WAV, AIFF).
 *
 * Reading the first few kilobytes directly avoids spawning Android's
 * `MediaMetadataRetriever` binder IPC per file (~0.2 ms vs 15–40 ms per file).
 * Callers fall back to `MediaMetadataRetriever` when this returns null or
 * misses required fields (e.g. M4A/MP4/WV/APE or video-track verification).
 */
object FastAudioTagReader {

    data class FastTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val durationMs: Long? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
    )

    private const val MAX_HEADER_BYTES = 512 * 1024

    fun readFile(file: File): FastTags? {
        val ext = file.extension.lowercase(Locale.ROOT)
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis, 16 * 1024).use { bis ->
                    readStream(bis, ext, file.length())
                }
            }
        }.getOrNull()
    }

    fun readStream(stream: InputStream, extension: String, fileSize: Long = -1L): FastTags? {
        val ext = extension.lowercase(Locale.ROOT)
        return when (ext) {
            "flac" -> readFlac(stream)
            "mp3" -> readId3v2(stream)
            "dsf" -> readDsf(stream, fileSize)
            "wav" -> readWav(stream)
            "aiff", "aif" -> readAiff(stream)
            else -> null
        }
    }

    // ── FLAC (STREAMINFO + VORBIS_COMMENT) ────────────────────────────────

    internal fun readFlac(stream: InputStream): FastTags? {
        val magic = readExact(stream, 4) ?: return null
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) return null

        var durationMs: Long? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null

        var bytesRead = 4
        while (bytesRead < MAX_HEADER_BYTES) {
            val blockHeader = readExact(stream, 4) ?: break
            bytesRead += 4
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)

            if (blockType == 0 && length >= 34) {
                // STREAMINFO: bytes 10..17 pack 20-bit sampleRate, 3-bit channels, 5-bit bps, 36-bit totalSamples.
                val payload = readExact(stream, length) ?: break
                bytesRead += length
                val sampleRate = ((payload[10].toInt() and 0xFF) shl 12) or
                    ((payload[11].toInt() and 0xFF) shl 4) or
                    ((payload[12].toInt() and 0xF0) ushr 4)
                val totalSamples = ((payload[13].toLong() and 0x0F) shl 32) or
                    ((payload[14].toLong() and 0xFF) shl 24) or
                    ((payload[15].toLong() and 0xFF) shl 16) or
                    ((payload[16].toLong() and 0xFF) shl 8) or
                    (payload[17].toLong() and 0xFF)
                if (sampleRate > 0 && totalSamples > 0) {
                    durationMs = (totalSamples * 1_000L) / sampleRate
                }
            } else if (blockType == 4 && length in 8..MAX_HEADER_BYTES) {
                // VORBIS_COMMENT
                val payload = readExact(stream, length) ?: break
                bytesRead += length
                val comments = parseVorbisComment(payload)
                title = comments["TITLE"] ?: title
                artist = comments["ARTIST"] ?: artist
                album = comments["ALBUM"] ?: album
                albumArtist = comments["ALBUMARTIST"] ?: comments["ALBUM ARTIST"] ?: albumArtist
                trackNumber = parseNumber(comments["TRACKNUMBER"]) ?: trackNumber
                discNumber = parseNumber(comments["DISCNUMBER"]) ?: discNumber
                if (durationMs != null) break // Got both STREAMINFO and VORBIS_COMMENT.
            } else {
                // Skip PADDING, PICTURE, SEEKTABLE, etc. without allocating huge buffers.
                if (!skipExact(stream, length.toLong())) break
                bytesRead += length
            }
            if (isLast) break
        }

        if (title == null && artist == null && album == null && durationMs == null) return null
        return FastTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            durationMs = durationMs,
            trackNumber = trackNumber,
            discNumber = discNumber,
        )
    }

    private fun parseVorbisComment(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var offset = 0
        if (data.size < 8) return result
        val vendorLen = le32(data, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > data.size) return result
        offset += vendorLen
        val count = le32(data, offset)
        offset += 4
        if (count <= 0 || count > 2048) return result
        for (i in 0 until count) {
            if (offset + 4 > data.size) break
            val len = le32(data, offset)
            offset += 4
            if (len < 0 || offset + len > data.size) break
            val entry = String(data, offset, len, Charsets.UTF_8)
            offset += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase(Locale.ROOT)
                val value = entry.substring(eq + 1).trim()
                if (value.isNotEmpty() && key !in result) {
                    result[key] = value
                }
            }
        }
        return result
    }

    // ── DSF (DSD Stream File: DSD chunk + fmt chunk + trailing ID3v2) ─────

    internal fun readDsf(stream: InputStream, fileSize: Long): FastTags? {
        val dsdHeader = readExact(stream, 28) ?: return null
        if (dsdHeader[0] != 'D'.code.toByte() || dsdHeader[1] != 'S'.code.toByte() ||
            dsdHeader[2] != 'D'.code.toByte() || dsdHeader[3] != ' '.code.toByte()
        ) return null

        val metadataOffset = le64(dsdHeader, 20)
        val fmtChunk = readExact(stream, 52) ?: return null
        var durationMs: Long? = null
        if (fmtChunk[0] == 'f'.code.toByte() && fmtChunk[1] == 'm'.code.toByte() &&
            fmtChunk[2] == 't'.code.toByte() && fmtChunk[3] == ' '.code.toByte()
        ) {
            val sampleFreq = le32(fmtChunk, 28)
            val sampleCount = le64(fmtChunk, 36)
            if (sampleFreq > 0 && sampleCount > 0) {
                durationMs = (sampleCount * 1_000L) / sampleFreq
            }
        }

        // If metadataOffset points to a trailing ID3v2 block and fileSize is known,
        // skip to it when small or let caller handle via File seek if huge.
        var id3: FastTags? = null
        if (metadataOffset > 80 && (fileSize <= 0 || metadataOffset < fileSize)) {
            val toSkip = metadataOffset - 80L
            if (toSkip in 0..(128L * 1024 * 1024) && skipExact(stream, toSkip)) {
                id3 = readId3v2(stream)
            }
        }

        if (id3 == null && durationMs == null) return null
        return (id3 ?: FastTags()).copy(durationMs = durationMs ?: id3?.durationMs)
    }

    // ── ID3v2.3 / ID3v2.4 (MP3, DSF trailing tag) ─────────────────────────

    internal fun readId3v2(stream: InputStream): FastTags? {
        val header = readExact(stream, 10) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion !in 3..4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = syncSafe32(header, 6)
        if (tagSize <= 0) return null

        val readLimit = tagSize.coerceAtMost(MAX_HEADER_BYTES)
        val body = readExact(stream, readLimit) ?: return null

        var offset = 0
        // Skip extended header if present.
        if ((flags and 0x40) != 0 && offset + 4 <= body.size) {
            val extSize = if (majorVersion == 4) syncSafe32(body, offset) else be32(body, offset)
            offset += if (majorVersion == 4) extSize else extSize + 4
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null

        while (offset + 10 <= body.size) {
            if (body[offset] == 0.toByte()) break // Padding reached.
            val frameId = String(body, offset, 4, Charsets.ISO_8859_1)
            val frameSize = if (majorVersion == 4) syncSafe32(body, offset + 4) else be32(body, offset + 4)
            offset += 10
            if (frameSize <= 0 || offset + frameSize > body.size) break

            when (frameId) {
                "TIT2" -> decodeId3Text(body, offset, frameSize)?.let { title = it }
                "TPE1" -> decodeId3Text(body, offset, frameSize)?.let { artist = it }
                "TALB" -> decodeId3Text(body, offset, frameSize)?.let { album = it }
                "TPE2" -> decodeId3Text(body, offset, frameSize)?.let { albumArtist = it }
                "TRCK" -> parseNumber(decodeId3Text(body, offset, frameSize))?.let { trackNumber = it }
                "TPOS" -> parseNumber(decodeId3Text(body, offset, frameSize))?.let { discNumber = it }
            }
            offset += frameSize
        }

        if (title == null && artist == null && album == null && albumArtist == null) return null
        return FastTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
        )
    }

    private fun decodeId3Text(data: ByteArray, offset: Int, length: Int): String? {
        if (length <= 1) return null
        val encoding = data[offset].toInt() and 0xFF
        val textOffset = offset + 1
        val textLen = length - 1
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
        return runCatching {
            String(data, textOffset, textLen, charset)
                .trim { it == '\u0000' || it.isWhitespace() }
                .takeIf { it.isNotEmpty() && it != "<unknown>" }
        }.getOrNull()
    }

    // ── WAV (RIFF fmt + data + LIST/INFO) ─────────────────────────────────

    internal fun readWav(stream: InputStream): FastTags? {
        val riff = readExact(stream, 12) ?: return null
        if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
            String(riff, 8, 4, Charsets.ISO_8859_1) != "WAVE"
        ) return null

        var byteRate = 0
        var dataSize = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null

        var scanned = 12
        while (scanned < MAX_HEADER_BYTES) {
            val chunkHeader = readExact(stream, 8) ?: break
            scanned += 8
            val chunkId = String(chunkHeader, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = le32(chunkHeader, 4).toLong() and 0xFFFFFFFFL
            val paddedSize = chunkSize + (chunkSize and 1L)

            when (chunkId) {
                "fmt " -> if (chunkSize >= 16 && chunkSize <= 4096) {
                    val fmt = readExact(stream, chunkSize.toInt()) ?: break
                    byteRate = le32(fmt, 8)
                    if (paddedSize > chunkSize) skipExact(stream, paddedSize - chunkSize)
                    scanned += paddedSize.toInt()
                } else {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
                "data" -> {
                    dataSize = chunkSize
                    break // Audio payload reached; duration can be computed.
                }
                "LIST" -> if (chunkSize in 4..65536) {
                    val listData = readExact(stream, chunkSize.toInt()) ?: break
                    if (paddedSize > chunkSize) skipExact(stream, paddedSize - chunkSize)
                    scanned += paddedSize.toInt()
                    if (String(listData, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        var pos = 4
                        while (pos + 8 <= listData.size) {
                            val id = String(listData, pos, 4, Charsets.ISO_8859_1)
                            val sz = le32(listData, pos + 4)
                            pos += 8
                            if (sz < 0 || pos + sz > listData.size) break
                            val value = String(listData, pos, sz, Charsets.UTF_8)
                                .trim { it == '\u0000' || it.isWhitespace() }
                                .takeIf { it.isNotEmpty() }
                            when (id) {
                                "INAM" -> title = value ?: title
                                "IART" -> artist = value ?: artist
                                "IPRD" -> album = value ?: album
                                "ITRK" -> trackNumber = parseNumber(value) ?: trackNumber
                            }
                            pos += sz + (sz and 1)
                        }
                    }
                } else {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
                else -> {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
            }
        }

        val durationMs = if (byteRate > 0 && dataSize > 0) (dataSize * 1_000L) / byteRate else null
        if (durationMs == null && title == null && artist == null && album == null) return null
        return FastTags(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            trackNumber = trackNumber,
        )
    }

    // ── AIFF (FORM COMM duration) ─────────────────────────────────────────

    internal fun readAiff(stream: InputStream): FastTags? {
        val form = readExact(stream, 12) ?: return null
        val magic = String(form, 0, 4, Charsets.ISO_8859_1)
        val formType = String(form, 8, 4, Charsets.ISO_8859_1)
        if (magic != "FORM" || (formType != "AIFF" && formType != "AIFC")) return null

        var durationMs: Long? = null
        var title: String? = null
        var artist: String? = null
        var scanned = 12
        while (scanned < MAX_HEADER_BYTES) {
            val chunkHeader = readExact(stream, 8) ?: break
            scanned += 8
            val chunkId = String(chunkHeader, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = be32(chunkHeader, 4).toLong() and 0xFFFFFFFFL
            val paddedSize = chunkSize + (chunkSize and 1L)
            when (chunkId) {
                "COMM" -> if (chunkSize >= 18 && chunkSize <= 4096) {
                    val comm = readExact(stream, chunkSize.toInt()) ?: break
                    val sampleFrames = be32(comm, 2).toLong() and 0xFFFFFFFFL
                    val sampleRate = parseExtended80(comm, 8)
                    if (sampleFrames > 0 && sampleRate > 0.0) {
                        durationMs = ((sampleFrames.toDouble() / sampleRate) * 1_000.0).toLong()
                    }
                    if (paddedSize > chunkSize) skipExact(stream, paddedSize - chunkSize)
                    scanned += paddedSize.toInt()
                } else {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
                "NAME" -> if (chunkSize in 1..4096) {
                    val raw = readExact(stream, chunkSize.toInt()) ?: break
                    title = String(raw, Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }.ifEmpty { null }
                    if (paddedSize > chunkSize) skipExact(stream, paddedSize - chunkSize)
                    scanned += paddedSize.toInt()
                } else {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
                "AUTH" -> if (chunkSize in 1..4096) {
                    val raw = readExact(stream, chunkSize.toInt()) ?: break
                    artist = String(raw, Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }.ifEmpty { null }
                    if (paddedSize > chunkSize) skipExact(stream, paddedSize - chunkSize)
                    scanned += paddedSize.toInt()
                } else {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
                "SSND" -> break
                else -> {
                    if (!skipExact(stream, paddedSize)) break
                    scanned += paddedSize.toInt()
                }
            }
        }
        if (durationMs == null && title == null && artist == null) return null
        return FastTags(title = title, artist = artist, durationMs = durationMs)
    }

    // ── Byte helpers ──────────────────────────────────────────────────────

    private fun parseExtended80(data: ByteArray, offset: Int): Double {
        if (offset + 10 > data.size) return 0.0
        val exp = ((data[offset].toInt() and 0x7F) shl 8) or (data[offset + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2..9) {
            mantissa = (mantissa shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        if (exp == 0 && mantissa == 0L) return 0.0
        val unbiased = exp - 16383 - 63
        // Unsigned 64-bit mantissa to Double.
        val mantissaDouble = if (mantissa >= 0) mantissa.toDouble()
        else (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L).toDouble()
        return mantissaDouble * Math.pow(2.0, unbiased.toDouble())
    }

    private fun parseNumber(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24) or
            ((b[o + 4].toLong() and 0xFF) shl 32) or
            ((b[o + 5].toLong() and 0xFF) shl 40) or
            ((b[o + 6].toLong() and 0xFF) shl 48) or
            ((b[o + 7].toLong() and 0xFF) shl 56)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun syncSafe32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or
            ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or
            (b[o + 3].toInt() and 0x7F)

    private fun readExact(stream: InputStream, count: Int): ByteArray? {
        if (count < 0) return null
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buf, read, count - read)
            if (n <= 0) return null
            read += n
        }
        return buf
    }

    private fun skipExact(stream: InputStream, count: Long): Boolean {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val n = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (n <= 0) return false
                remaining -= n
            }
        }
        return true
    }
}
