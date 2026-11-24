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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source

internal object LocalArtwork {
    private const val PREFIX = "localcover:"
    private const val MAX_BYTES = 16 * 1024 * 1024
    internal val coverBaseNames = listOf("cover", "folder", "album")
    internal val coverExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val artworkDispatcher = Dispatchers.IO.limitedParallelism(2)
    private const val THUMB_PX = 256
    private const val THUMB_FETCH_MAX_PX = 768
    private const val THUMB_CACHE_BYTES = 256L * 1024 * 1024
    private const val THUMB_CACHE_DIR = "local-thumbnails"
    private const val TRASH_SUFFIX = ".trash-"

    @Volatile private var sharedDiskCache: DiskCache? = null
    private val cacheLock = Any()

    private fun thumbnailCache(context: Context): DiskCache {
        sharedDiskCache?.let { return it }
        return synchronized(cacheLock) {
            sharedDiskCache ?: DiskCache.Builder()
                .directory(File(context.applicationContext.cacheDir, THUMB_CACHE_DIR).toOkioPath())
                .maxSizeBytes(THUMB_CACHE_BYTES)
                .build()
                .also { sharedDiskCache = it }
        }
    }

    data class SidecarCover(
        /** `"file:/path/to/cover.jpg"` or `"content://..."` */
        val source: String,
        /** Revision token combining mtime and byte length so replaced covers invalidate cache. */
        val signature: String,
    )

    fun isCoverFileName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val base = name.substring(0, dot).lowercase(Locale.ROOT)
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return base in coverBaseNames && ext in coverExtensions
    }

    fun coverSortOrder(name: String): Int {
        val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return coverBaseNames.indexOf(base).let { if (it < 0) coverBaseNames.size else it }
    }

    fun decorate(
        songs: List<Song>,
        prediscoveredCovers: Map<String, List<SidecarCover>> = emptyMap(),
    ): List<Song> {
        val directories = songs.groupBy { it.localPath?.let { path -> File(path).parentFile?.path } }
        val covers: Map<String?, List<SidecarCover>> = directories.mapValues { (dirPath, tracks) ->
            if (dirPath == null || tracks.map { it.albumName to it.localAlbumArtist }.distinct().size > 1) {
                emptyList()
            } else {
                prediscoveredCovers[dirPath] ?: scanDirectoryCovers(File(dirPath))
            }
        }
        return songs.map { song ->
            decorateOne(song, covers[song.localPath?.let { File(it).parentFile?.path }].orEmpty())
        }
    }

    fun decorateOne(song: Song, sidecars: List<SidecarCover>): Song {
        val media = song.localUri ?: return song
        val embedded = if (song.localPath != null) "embedded:$media|path:${song.localPath}" else "embedded:$media"
        val sidecarSources = sidecars.map { it.source }
        val fallback = song.thumbnailUrl?.takeUnless { it.startsWith(PREFIX) }
        val revision = "${song.localDateModifiedSeconds}:${sidecars.joinToString { it.signature }}"
        fun url(vararg sources: String?) = PREFIX +
            (listOf(revision) + sources.filterNotNull()).joinToString("|") { Uri.encode(it) }
        return song.copy(
            thumbnailUrl = url(embedded, *sidecarSources.toTypedArray(), fallback),
            localAlbumArtwork = url(*sidecarSources.toTypedArray(), embedded, fallback),
        )
    }

    fun scanDirectoryCovers(directory: File): List<SidecarCover> = runCatching {
        if (!directory.isDirectory || !directory.canRead()) return emptyList()
        directory.listFiles()?.filter {
            it.isFile && isCoverFileName(it.name)
        }?.sortedWith(
            compareBy<File> { coverSortOrder(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) },
        )?.map { file ->
            SidecarCover(
                source = "file:${file.path}",
                signature = "${file.name}:${file.lastModified()}:${file.length()}",
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun albumCover(tracks: List<Song>): String? =
        tracks.firstNotNullOfOrNull { it.localAlbumArtwork?.takeIf { u -> u.startsWith(PREFIX) } }
            ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl }

    fun clearThumbnailCache(context: Context) {
        synchronized(cacheLock) {
            runCatching { sharedDiskCache?.clear() }
            sharedDiskCache = null
        }
        runCatching {
            File(context.cacheDir, THUMB_CACHE_DIR).deleteRecursively()
        }
        runCatching {
            File(context.cacheDir, "embedded_art").deleteRecursively()
        }
    }

    private val prewarmDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun isLowBatteryOrPowerSave(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isPowerSaveMode == true) return@runCatching true
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging == true
        !charging && pct in 1..15
    }.getOrDefault(false)

    /**
     * Low-priority (`THREAD_PRIORITY_BACKGROUND`) background caching for EVERY
     * unique song thumbnail and album cover in the local library so scrolling
     * 5,000+ songs and albums hits the shared 256 MB DiskCache in `< 1 ms`.
     * Uses at most 2 workers, skips already-cached covers, and yields/stops
     * when battery saver is active or battery is critically low.
     */
    suspend fun prewarmThumbnails(
        context: Context,
        songs: List<Song>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(prewarmDispatcher) {
        if (isLowBatteryOrPowerSave(context)) return@withContext
        val cache = runCatching { thumbnailCache(context) }.getOrNull() ?: return@withContext
        val urls = LinkedHashSet<String>(songs.size * 2)
        for (song in songs) {
            song.thumbnailUrl?.takeIf { it.startsWith(PREFIX) }?.let { urls.add(it) }
            song.localAlbumArtwork?.takeIf { it.startsWith(PREFIX) }?.let { urls.add(it) }
        }
        val missing = urls.filter { url ->
            !runCatching { cache.openSnapshot(url)?.use { true } ?: false }.getOrDefault(false)
        }
        if (missing.isEmpty()) return@withContext
        val total = missing.size
        val nextIdx = java.util.concurrent.atomic.AtomicInteger(0)
        val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, total)
        coroutineScope {
            List(2) {
                async(prewarmDispatcher) {
                        val previousPriority = runCatching {
                            android.os.Process.getThreadPriority(android.os.Process.myTid())
                        }.getOrNull()
                        runCatching {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        }
                        try {
                            while (true) {
                                kotlinx.coroutines.yield()
                                val i = nextIdx.getAndIncrement()
                                if (i >= total) break
                                if (i % 40 == 0 && isLowBatteryOrPowerSave(context)) break
                                val url = missing[i]
                                val bytes = url.removePrefix(PREFIX).split('|').drop(1)
                                    .firstNotNullOfOrNull { encoded ->
                                        read(context, Uri.decode(encoded))?.let { image -> decodeCover(image, thumbnail = true) }
                                    }
                                if (bytes != null) {
                                    runCatching {
                                        cache.openEditor(url)?.let { editor ->
                                            try {
                                                cache.fileSystem.write(editor.metadata) {}
                                                cache.fileSystem.write(editor.data) { write(bytes) }
                                                editor.commit()
                                            } catch (e: Exception) {
                                                editor.abort()
                                                throw e
                                            }
                                        }
                                    }
                                }
                                val done = doneCounter.incrementAndGet()
                                if (done == total || done % 20 == 0) onProgress(done, total)
                            }
                        } finally {
                            if (previousPriority != null) {
                                runCatching { android.os.Process.setThreadPriority(previousPriority) }
                            }
                        }
                    }
            }.forEach { it.await() }
        }
        onProgress(total, total)
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith(PREFIX)) return null
            return object : Fetcher {
                override suspend fun fetch(): SourceFetchResult = withContext(artworkDispatcher) {
                    val thumbnails = thumbnailCache(context)
                    val width = options.size.width as? Dimension.Pixels
                    val height = options.size.height as? Dimension.Pixels
                    val small = width != null && height != null && width.px <= THUMB_FETCH_MAX_PX && height.px <= THUMB_FETCH_MAX_PX
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
            if (resized.compress(format, 80, output)) output.toByteArray() else null
        } finally {
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
        }
    }

    private fun read(context: Context, source: String): ByteArray? = runCatching {
        when {
            source.startsWith("embedded:") -> {
                val raw = source.removePrefix("embedded:")
                val mediaUri = raw.substringBefore("|path:")
                val filePath = raw.substringAfter("|path:", "").takeIf { it.isNotBlank() }
                // Fast pure-Kotlin binary extraction for FLAC/MP3 avoids MediaMetadataRetriever IPC.
                val fastPicture = when {
                    filePath != null -> FastAudioTagReader.readEmbeddedPictureFile(File(filePath), MAX_BYTES)
                    mediaUri.startsWith("file://") -> Uri.parse(mediaUri).path?.let {
                        FastAudioTagReader.readEmbeddedPictureFile(File(it), MAX_BYTES)
                    }
                    else -> null
                }
                if (fastPicture != null && fastPicture.isNotEmpty()) {
                    fastPicture
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(mediaUri))
                        retriever.embeddedPicture?.takeIf { it.size <= MAX_BYTES }
                    } finally {
                        retriever.release()
                    }
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
