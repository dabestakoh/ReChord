package com.music.bitchord.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalTagOverride
import com.music.bitchord.download.Mp4Tagger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure-Kotlin, atomic in-place tag writer for local audio files:
 * - **FLAC**: rewrites `VORBIS_COMMENT` metadata block while preserving `STREAMINFO`,
 *   `PICTURE` artwork, `SEEKTABLE`, and audio frames verbatim.
 * - **MP3**: rewrites ID3v2.3 frames (`TIT2`, `TPE1`, `TALB`, `TPE2`, `TRCK`, `TPOS`,
 *   `TCON`, `TYER`) while preserving `APIC` artwork, unknown frames, and MPEG audio frames.
 * - **M4A/MP4**: delegates to [Mp4Tagger] (preserving embedded artwork via retriever).
 * - **WAV / AIFF / WV / APE / DSF / OGG / OPUS**: saved as an app-only override in
 *   [AppSettings.localMusicTagOverrides] (never touches the file).
 */
object LocalTagWriter {

    data class EditableTags(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val trackNumber: Int?,
        val discNumber: Int?,
        val genre: String,
        val year: String,
    )

    sealed class SaveResult {
        data class Saved(val updatedSong: Song, val appOnly: Boolean) : SaveResult()
        data class NeedsWritePermission(val mediaUri: Uri) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    private val DIRECT_WRITE_EXTS = setOf("flac", "mp3", "m4a", "mp4")
    private const val MAX_MP4_BYTES = 64L * 1024 * 1024

    fun supportsDirectFileWrite(song: Song): Boolean {
        val ext = songExtension(song) ?: return false
        return ext in DIRECT_WRITE_EXTS
    }

    fun songExtension(song: Song): String? =
        song.localPath?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: song.localFormat?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

    suspend fun saveTags(
        context: Context,
        song: Song,
        tags: EditableTags,
        afterSystemGrant: Boolean = false,
    ): SaveResult = withContext(Dispatchers.IO) {
        val updatedSong = song.copy(
            title = tags.title.trim().ifBlank { song.title },
            artist = tags.artist.trim().ifBlank { "Unknown Artist" },
            albumName = tags.album.trim().ifBlank { null },
            localAlbumArtist = tags.albumArtist.trim().ifBlank { null },
            trackNumber = tags.trackNumber,
            localDiscNumber = tags.discNumber,
        )
        val ext = songExtension(song)
        val file = song.localPath?.let(::File)?.takeIf { it.exists() && it.isFile }
        val directSupported = ext in DIRECT_WRITE_EXTS &&
            (ext !in setOf("m4a", "mp4") || (file != null && file.length() <= MAX_MP4_BYTES))

        if (!directSupported) {
            return@withContext saveAppOnly(context, updatedSong, tags)
        }

        val canDirectFileWrite = file != null && file.canWrite() && LocalFileOps.hasAllFilesAccess()
        if (canDirectFileWrite && file != null) {
            val ok = runCatching {
                when (ext) {
                    "flac" -> writeFlacFile(file, tags)
                    "mp3" -> writeMp3File(file, tags)
                    "m4a", "mp4" -> writeMp4File(file, tags)
                    else -> false
                }
            }.getOrDefault(false)
            if (ok) {
                MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), null, null)
                val key = updatedSong.localUri ?: updatedSong.videoId
                AppSettings.removeLocalTagOverrides(listOf(key, updatedSong.videoId))
                LocalLibraryIndex.updateSongInPlace(context, updatedSong, newLastModifiedMs = file.lastModified())
                return@withContext SaveResult.Saved(updatedSong, appOnly = false)
            }
        }

        // Scoped storage fallback (Android 11+ without All-Files access): use content:// write
        val contentUri = LocalFileOps.resolveMediaStoreUri(context, song)
        if (contentUri != null) {
            if (!afterSystemGrant && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !LocalFileOps.hasAllFilesAccess()
            ) {
                return@withContext SaveResult.NeedsWritePermission(contentUri)
            }
            val ok = runCatching { writeViaContentUri(context, contentUri, ext!!, tags) }.getOrDefault(false)
            if (ok) {
                val key = updatedSong.localUri ?: updatedSong.videoId
                AppSettings.removeLocalTagOverrides(listOf(key, updatedSong.videoId))
                LocalLibraryIndex.updateSongInPlace(context, updatedSong, newLastModifiedMs = System.currentTimeMillis())
                return@withContext SaveResult.Saved(updatedSong, appOnly = false)
            }
        }

        // If direct write failed (e.g. read-only SD card), never lose the user's edit — save app-only.
        saveAppOnly(context, updatedSong, tags)
    }

    private suspend fun saveAppOnly(context: Context, updatedSong: Song, tags: EditableTags): SaveResult {
        val key = updatedSong.localUri ?: updatedSong.videoId
        AppSettings.setLocalTagOverride(
            key,
            LocalTagOverride(
                title = updatedSong.title,
                artist = updatedSong.artist,
                albumName = updatedSong.albumName,
                albumArtist = updatedSong.localAlbumArtist,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
            ),
        )
        LocalLibraryIndex.updateSongInPlace(context, updatedSong)
        return SaveResult.Saved(updatedSong, appOnly = true)
    }

    private fun writeViaContentUri(context: Context, uri: Uri, ext: String, tags: EditableTags): Boolean {
        val tmpIn = File.createTempFile("rechord-tag-in-", ".$ext", context.cacheDir)
        val tmpOut = File.createTempFile("rechord-tag-out-", ".$ext", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpIn).use { output -> input.copyTo(output) }
            } ?: return false
            val ok = when (ext) {
                "flac" -> FileInputStream(tmpIn).use { i -> FileOutputStream(tmpOut).use { o -> rewriteFlacStream(i, o, tags) } }
                "mp3" -> FileInputStream(tmpIn).use { i -> FileOutputStream(tmpOut).use { o -> rewriteMp3Stream(i, o, tags) } }
                "m4a", "mp4" -> {
                    val rewritten = rewriteMp4Bytes(tmpIn, tags) ?: return false
                    tmpOut.writeBytes(rewritten)
                    true
                }
                else -> false
            }
            if (!ok) return false
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(tmpOut).use { input -> input.copyTo(out) }
            } ?: return false
            true
        } finally {
            runCatching { tmpIn.delete() }
            runCatching { tmpOut.delete() }
        }
    }

    // ── FLAC VORBIS_COMMENT atomic writer ─────────────────────────────────

    internal fun writeFlacFile(file: File, tags: EditableTags): Boolean {
        val tmp = File(file.parentFile ?: return false, ".${file.name}.rechord.tmp")
        return try {
            val ok = BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { output ->
                    rewriteFlacStream(input, output, tags)
                }
            }
            if (!ok) {
                tmp.delete()
                return false
            }
            replaceAtomically(tmp, file)
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    internal fun rewriteFlacStream(input: InputStream, output: OutputStream, tags: EditableTags): Boolean {
        val magic = readExact(input, 4) ?: return false
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return false

        val blocks = ArrayList<Pair<Int, ByteArray>>()
        var vendor = "ReChord"
        val existingComments = LinkedHashMap<String, String>()

        while (true) {
            val hdr = readExact(input, 4) ?: return false
            val isLast = (hdr[0].toInt() and 0x80) != 0
            val type = hdr[0].toInt() and 0x7F
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
            val payload = readExact(input, len) ?: return false
            if (type == 4) {
                parseVorbisCommentsPreserving(payload, existingComments)?.let { vendor = it }
            } else {
                blocks.add(type to payload)
            }
            if (isLast) break
        }

        // Update edited fields while preserving any extra Vorbis comments (ReplayGain, MusicBrainz, etc.)
        fun putOrRemove(key: String, value: String?) {
            existingComments.keys.removeAll { it.equals(key, ignoreCase = true) }
            if (!value.isNullOrBlank()) existingComments[key] = value.trim()
        }
        putOrRemove("TITLE", tags.title)
        putOrRemove("ARTIST", tags.artist)
        putOrRemove("ALBUM", tags.album)
        putOrRemove("ALBUMARTIST", tags.albumArtist)
        existingComments.keys.removeAll { it.equals("ALBUM ARTIST", ignoreCase = true) }
        putOrRemove("TRACKNUMBER", tags.trackNumber?.toString())
        putOrRemove("DISCNUMBER", tags.discNumber?.toString())
        putOrRemove("GENRE", tags.genre)
        putOrRemove("DATE", tags.year)

        val vorbisPayload = encodeVorbisComments(vendor, existingComments)
        // Insert VORBIS_COMMENT right after STREAMINFO (index 1)
        val insertIdx = if (blocks.isNotEmpty() && blocks[0].first == 0) 1 else 0
        blocks.add(insertIdx, 4 to vorbisPayload)

        output.write(magic)
        for (i in blocks.indices) {
            val (type, payload) = blocks[i]
            val lastBit = if (i == blocks.lastIndex) 0x80 else 0
            val len = payload.size
            output.write(lastBit or (type and 0x7F))
            output.write((len ushr 16) and 0xFF)
            output.write((len ushr 8) and 0xFF)
            output.write(len and 0xFF)
            output.write(payload)
        }
        input.copyTo(output, bufferSize = 64 * 1024)
        output.flush()
        return true
    }

    private fun parseVorbisCommentsPreserving(data: ByteArray, out: MutableMap<String, String>): String? {
        if (data.size < 8) return null
        var off = 0
        val vendorLen = le32(data, off)
        off += 4
        if (vendorLen < 0 || off + vendorLen + 4 > data.size) return null
        val vendor = String(data, off, vendorLen, Charsets.UTF_8)
        off += vendorLen
        val count = le32(data, off)
        off += 4
        for (i in 0 until count.coerceIn(0, 1024)) {
            if (off + 4 > data.size) break
            val len = le32(data, off)
            off += 4
            if (len < 0 || off + len > data.size) break
            val entry = String(data, off, len, Charsets.UTF_8)
            off += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val k = entry.substring(0, eq).uppercase(Locale.ROOT)
                val v = entry.substring(eq + 1)
                out[k] = v
            }
        }
        return vendor
    }

    private fun encodeVorbisComments(vendor: String, comments: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeLe32(out, vendorBytes.size)
        out.write(vendorBytes)
        writeLe32(out, comments.size)
        for ((k, v) in comments) {
            val bytes = "$k=$v".toByteArray(Charsets.UTF_8)
            writeLe32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // ── MP3 ID3v2.3 atomic writer ─────────────────────────────────────────

    internal fun writeMp3File(file: File, tags: EditableTags): Boolean {
        val tmp = File(file.parentFile ?: return false, ".${file.name}.rechord.tmp")
        return try {
            val ok = BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { output ->
                    rewriteMp3Stream(input, output, tags)
                }
            }
            if (!ok) {
                tmp.delete()
                return false
            }
            replaceAtomically(tmp, file)
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    private val REPLACED_ID3_FRAMES = setOf("TIT2", "TPE1", "TALB", "TPE2", "TRCK", "TPOS", "TCON", "TYER", "TDRC")

    internal fun rewriteMp3Stream(input: InputStream, output: OutputStream, tags: EditableTags): Boolean {
        val preservedFrames = ArrayList<Pair<String, ByteArray>>()
        val prefix = ByteArray(10)
        val firstRead = readUpTo(input, prefix)
        var pendingPrefix: ByteArray? = null

        if (firstRead == 10 && prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()) {
            val major = prefix[3].toInt() and 0xFF
            val tagSize = ((prefix[6].toInt() and 0x7F) shl 21) or
                ((prefix[7].toInt() and 0x7F) shl 14) or
                ((prefix[8].toInt() and 0x7F) shl 7) or
                (prefix[9].toInt() and 0x7F)
            val tagBody = readExact(input, tagSize) ?: return false
            if (major == 3 || major == 4) {
                var pos = 0
                while (pos + 10 <= tagBody.size) {
                    if (tagBody[pos] == 0.toByte()) break
                    val id = String(tagBody, pos, 4, Charsets.ISO_8859_1)
                    val frameSize = if (major == 4) {
                        ((tagBody[pos + 4].toInt() and 0x7F) shl 21) or
                            ((tagBody[pos + 5].toInt() and 0x7F) shl 14) or
                            ((tagBody[pos + 6].toInt() and 0x7F) shl 7) or
                            (tagBody[pos + 7].toInt() and 0x7F)
                    } else {
                        ((tagBody[pos + 4].toInt() and 0xFF) shl 24) or
                            ((tagBody[pos + 5].toInt() and 0xFF) shl 16) or
                            ((tagBody[pos + 6].toInt() and 0xFF) shl 8) or
                            (tagBody[pos + 7].toInt() and 0xFF)
                    }
                    pos += 10
                    if (frameSize <= 0 || pos + frameSize > tagBody.size) break
                    if (id !in REPLACED_ID3_FRAMES) {
                        preservedFrames.add(id to tagBody.copyOfRange(pos, pos + frameSize))
                    }
                    pos += frameSize
                }
            }
        } else if (firstRead > 0) {
            pendingPrefix = prefix.copyOf(firstRead)
        }

        val framesOut = ByteArrayOutputStream()
        fun writeId3Frame(id: String, payload: ByteArray) {
            framesOut.write(id.toByteArray(Charsets.ISO_8859_1))
            val sz = payload.size
            framesOut.write((sz ushr 24) and 0xFF)
            framesOut.write((sz ushr 16) and 0xFF)
            framesOut.write((sz ushr 8) and 0xFF)
            framesOut.write(sz and 0xFF)
            framesOut.write(0)
            framesOut.write(0)
            framesOut.write(payload)
        }
        fun writeTextFrame(id: String, value: String?) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            // ID3v2.3 UTF-16 with BOM (encoding byte = 1) is universally supported by Android & desktop players.
            val encoded = v.toByteArray(Charsets.UTF_16)
            val payload = ByteArray(1 + encoded.size)
            payload[0] = 1
            System.arraycopy(encoded, 0, payload, 1, encoded.size)
            writeId3Frame(id, payload)
        }

        writeTextFrame("TIT2", tags.title)
        writeTextFrame("TPE1", tags.artist)
        writeTextFrame("TALB", tags.album)
        writeTextFrame("TPE2", tags.albumArtist)
        writeTextFrame("TRCK", tags.trackNumber?.toString())
        writeTextFrame("TPOS", tags.discNumber?.toString())
        writeTextFrame("TCON", tags.genre)
        writeTextFrame("TYER", tags.year)
        for ((id, payload) in preservedFrames) {
            writeId3Frame(id, payload)
        }

        val bodyBytes = framesOut.toByteArray()
        val sz = bodyBytes.size
        output.write(
            byteArrayOf(
                'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
                3, 0, 0, // ID3v2.3.0, flags=0
                ((sz ushr 21) and 0x7F).toByte(),
                ((sz ushr 14) and 0x7F).toByte(),
                ((sz ushr 7) and 0x7F).toByte(),
                (sz and 0x7F).toByte(),
            ),
        )
        output.write(bodyBytes)
        if (pendingPrefix != null) output.write(pendingPrefix)
        input.copyTo(output, bufferSize = 64 * 1024)
        output.flush()
        return true
    }

    // ── M4A/MP4 writer (via existing Mp4Tagger) ───────────────────────────

    private fun writeMp4File(file: File, tags: EditableTags): Boolean {
        val rewritten = rewriteMp4Bytes(file, tags) ?: return false
        val tmp = File(file.parentFile ?: return false, ".${file.name}.rechord.tmp")
        return try {
            tmp.writeBytes(rewritten)
            replaceAtomically(tmp, file)
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun rewriteMp4Bytes(file: File, tags: EditableTags): ByteArray? {
        if (file.length() > MAX_MP4_BYTES) return null
        val existingCover = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
        val isPng = existingCover != null && existingCover.size >= 4 &&
            existingCover[0] == 0x89.toByte() && existingCover[1] == 0x50.toByte()
        return Mp4Tagger.tag(
            bytes = file.readBytes(),
            title = tags.title,
            artist = tags.artist,
            album = tags.album.ifBlank { null },
            lyrics = null,
            cover = existingCover,
            coverIsPng = isPng,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun replaceAtomically(tmp: File, target: File): Boolean {
        if (tmp.renameTo(target)) return true
        // Cross-filesystem or Android FUSE fallback: copy bytes in place then delete temp.
        return try {
            FileInputStream(tmp).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output, 64 * 1024) }
            }
            tmp.delete()
            true
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun readExact(stream: InputStream, count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = stream.read(buf, off, count - off)
            if (n <= 0) return null
            off += n
        }
        return buf
    }

    private fun readUpTo(stream: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
