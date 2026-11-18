package com.music.bitchord.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
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

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.startsWith(PREFIX)) return null
            return object : Fetcher {
                override suspend fun fetch(): SourceFetchResult = withContext(Dispatchers.IO) {
                    val bytes = data.removePrefix(PREFIX).split('|').drop(1)
                        .firstNotNullOfOrNull { encoded ->
                            read(options.context, Uri.decode(encoded))?.takeIf { image ->
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                                bounds.outWidth > 0 && bounds.outHeight > 0
                            }
                        } ?: error("No readable local cover")
                    SourceFetchResult(
                        source = ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM),
                        mimeType = null,
                        dataSource = DataSource.DISK,
                    )
                }
            }
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
