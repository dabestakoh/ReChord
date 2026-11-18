package com.music.bitchord.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.size.Dimension
import android.graphics.Bitmap
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.music.bitchord.data.model.Song
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source

internal object LocalArtwork {
    private const val PREFIX = "localcover:"
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val names = listOf("cover", "folder", "album")
    private val artworkDispatcher = Dispatchers.IO.limitedParallelism(2)
    private const val THUMB_PX = 384
    private const val THUMB_CACHE_BYTES = 128L * 1024 * 1024
    private val extensions = setOf("jpg", "jpeg", "png", "webp")

    fun decorate(songs: List<Song>): List<Song> {
        val directories = songs.groupBy { it.localPath?.let { path -> File(path).parentFile } }
        val covers = directories.mapValues { (directory, tracks) ->
            if (directory == null || tracks.map { it.albumName to it.localAlbumArtist }.distinct().size > 1) null
            else directory.listFiles()?.filter {
                it.isFile && it.nameWithoutExtension.lowercase(Locale.ROOT) in names &&
                    it.extension.lowercase(Locale.ROOT) in extensions
            }?.sortedWith(compareBy<File> { names.indexOf(it.nameWithoutExtension.lowercase(Locale.ROOT)) }
                .thenBy { it.name.lowercase(Locale.ROOT) }).orEmpty()
        }
        return songs.map { song ->
            val media = song.localUri ?: return@map song
            val cover = covers[song.localPath?.let { File(it).parentFile }].orEmpty()
            val embedded = "embedded:$media"
            val sidecars = cover.map { "file:${it.path}" }
            val fallback = song.thumbnailUrl
            val revision = "${song.localDateModifiedSeconds}:${cover.joinToString { "${it.lastModified()}:${it.length()}" }}"
            fun url(vararg sources: String?) = PREFIX +
                (listOf(revision) + sources.filterNotNull()).joinToString("|") { Uri.encode(it) }
            song.copy(
                thumbnailUrl = url(embedded, *sidecars.toTypedArray(), fallback),
                localAlbumArtwork = url(*sidecars.toTypedArray(), embedded, fallback),
            )
        }
    }

    fun albumCover(tracks: List<Song>): String? {
        val models = tracks.mapNotNull { it.localAlbumArtwork }.filter { it.startsWith(PREFIX) }
        if (models.isEmpty()) return tracks.firstNotNullOfOrNull { it.thumbnailUrl }
        val parts = models.map { it.removePrefix(PREFIX).split('|') }
        val candidates = parts.flatMap { it.drop(1) }.distinct().sortedBy {
            when {
                Uri.decode(it).startsWith("file:") -> 0
                Uri.decode(it).startsWith("embedded:") -> 1
                else -> 2
            }
        }
        val revision = parts.map { it.first() }.distinct().joinToString(",")
        return PREFIX + (listOf(revision) + candidates).joinToString("|")
    }

    class Factory(context: Context) : Fetcher.Factory<coil3.Uri> {
        private val thumbnails by lazy {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "local-thumbnails").toOkioPath())
                .maxSizeBytes(THUMB_CACHE_BYTES)
                .build()
        }

        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith(PREFIX)) return null
            return object : Fetcher {
                override suspend fun fetch(): SourceFetchResult = withContext(artworkDispatcher) {
                    val width = options.size.width as? Dimension.Pixels
                    val height = options.size.height as? Dimension.Pixels
                    val small = width != null && height != null && width.px <= THUMB_PX && height.px <= THUMB_PX
                    val cached = if (small) runCatching {
                        thumbnails.openSnapshot(url)?.use { thumbnails.fileSystem.read(it.data) { readByteArray() } }
                    }.getOrNull() else null
                    val bytes = cached ?: url.removePrefix(PREFIX).split('|').drop(1)
                        .firstNotNullOfOrNull { encoded ->
                            read(options.context, Uri.decode(encoded))?.let { image ->
                                decodeCover(image, small)
                            }
                        } ?: error("No readable local cover")
                    if (small && cached == null) runCatching {
                        thumbnails.openEditor(url)?.let { editor ->
                            try {
                                thumbnails.fileSystem.write(editor.metadata) {}
                                thumbnails.fileSystem.write(editor.data) { write(bytes) }
                                editor.commit()
                            } catch (error: Exception) {
                                editor.abort()
                                throw error
                            }
                        }
                    }
                    SourceFetchResult(
                        source = ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM),
                        mimeType = null,
                        dataSource = DataSource.DISK,
                    )
                }
            }
        }
    }

    private fun decodeCover(bytes: ByteArray, thumbnail: Boolean): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = Integer.highestOneBit((longest / THUMB_PX).coerceAtLeast(1))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null
        if (!thumbnail) {
            bitmap.recycle()
            return bytes
        }
        val ratio = (THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val resized = if (ratio == 1f) bitmap else Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true,
        )
        return try {
            val output = ByteArrayOutputStream()
            val format = if (resized.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (resized.compress(format, 88, output)) output.toByteArray() else null
        } finally {
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
        }
    }

    private fun read(context: Context, source: String): ByteArray? = runCatching {
        when {
            source.startsWith("embedded:") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(source.removePrefix("embedded:")))
                    retriever.embeddedPicture?.takeIf { it.size <= MAX_BYTES }
                } finally {
                    retriever.release()
                }
            }
            source.startsWith("file:") -> File(source.removePrefix("file:")).takeIf { it.length() in 1..MAX_BYTES.toLong() }?.readBytes()
            else -> context.contentResolver.openInputStream(Uri.parse(source))?.use { stream ->
                stream.source().buffer().use { input ->
                    if (input.request(MAX_BYTES.toLong() + 1)) null else input.readByteArray()
                }
            }
        }
    }.getOrNull()
}
