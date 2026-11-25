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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source

internal fun localArtworkRevisionToken(
    modifiedMs: Long,
    sizeBytes: Long,
    sidecarSignatures: List<String>,
): String = "r2:$modifiedMs:$sizeBytes:${sidecarSignatures.joinToString()}"

internal object LocalArtwork {
    internal const val REVISION_VERSION = 2
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
    private val missingCovers = android.util.LruCache<String, Long>(512)
    private const val MISSING_COVER_TTL_MS = 60_000L

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
        return decorateOne(song, sidecars, mediaRevision = null)
    }

    internal fun artworkRevisionToken(
        modifiedMs: Long,
        sizeBytes: Long,
        sidecars: List<SidecarCover>,
    ): String = localArtworkRevisionToken(modifiedMs, sizeBytes, sidecars.map { it.signature })

    fun decorateOne(
        song: Song,
        sidecars: List<SidecarCover>,
        mediaRevision: FileRevisionToken?,
    ): Song {
        val media = song.localUri ?: return song
        val embedded = if (song.localPath != null) "embedded:$media|path:${song.localPath}" else "embedded:$media"
        val sidecarSources = sidecars.map { it.source }
        val fallback = song.thumbnailUrl?.takeUnless { it.startsWith(PREFIX) }
        val localFile = song.localPath?.let(::File)
        val revision = mediaRevision?.let {
            artworkRevisionToken(it.modifiedMs, it.sizeBytes, sidecars)
        } ?: artworkRevisionToken(
            modifiedMs = localFile?.let { runCatching { it.lastModified() }.getOrDefault(0L) }
                ?.takeIf { it > 0L }
                ?: (song.localDateModifiedSeconds ?: 0L) * 1_000L,
            sizeBytes = localFile?.let { runCatching { it.length() }.getOrDefault(0L) } ?: 0L,
            sidecars = sidecars,
        )
        fun url(vararg sources: String?) = PREFIX +
            (listOf(revision) + sources.filterNotNull()).joinToString("|") { Uri.encode(it) }
        return song.copy(
            thumbnailUrl = url(embedded, *sidecarSources.toTypedArray(), fallback),
            localAlbumArtwork = url(*sidecarSources.toTypedArray(), embedded, fallback),
        )
    }

    data class FileRevisionToken(val modifiedMs: Long, val sizeBytes: Long)

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
            missingCovers.evictAll()
            runCatching { File(context.cacheDir, THUMB_CACHE_DIR).deleteRecursively() }
            runCatching { File(context.cacheDir, "embedded_art").deleteRecursively() }
        }
    }

    private fun knownMissing(url: String): Boolean =
        (missingCovers.get(url) ?: 0L) > android.os.SystemClock.elapsedRealtime()

    private const val MAX_IDLE_THUMBNAILS = 96
    private const val PREWARM_BATCH = 12
    private val prewarmDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val thumbnailLocks = Array(32) { Mutex() }
    @Volatile private var userScrollingUntilMs: Long = 0L

    private fun thumbnailLock(url: String): Mutex =
        thumbnailLocks[(url.hashCode() and Int.MAX_VALUE) % thumbnailLocks.size]

    internal fun warmKeys(songs: List<Song>): List<String> = songs.asSequence()
        .flatMap { sequenceOf(it.localAlbumArtwork, it.thumbnailUrl) }
        .filterNotNull()
        .filter { it.startsWith(PREFIX) }
        .distinct()
        .take(MAX_IDLE_THUMBNAILS)
        .toList()

    /** Pauses background thumbnail pre-warming while the user is actively scrolling Local Music. */
    fun notifyUserScrolling(durationMs: Long = 350L) {
        userScrollingUntilMs = android.os.SystemClock.elapsedRealtime() + durationMs
    }

    private fun isLowBatteryOrPowerSave(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isPowerSaveMode == true) return@runCatching true
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging == true
        !charging && pct in 1..15
    }.getOrDefault(false)

    private fun canWarmArtwork(context: Context): Boolean {
        if (isLowBatteryOrPowerSave(context)) return false
        val runtime = Runtime.getRuntime()
        val limit = runtime.maxMemory()
        if (runtime.totalMemory() - runtime.freeMemory() >= limit - limit / 4) return false
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return android.os.Build.VERSION.SDK_INT < 29 ||
            power == null || power.currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_MODERATE
    }

    /** Warm a bounded set of covers after a scan. The visible rows load their own covers on demand. */
    suspend fun prewarmThumbnails(
        context: Context,
        keys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = withContext(prewarmDispatcher) {
        if (keys.isEmpty()) return@withContext true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        if (!canWarmArtwork(context)) return@withContext false

        suspend fun awaitIdle() {
            do {
                lifecycle.first { it.isAtLeast(Lifecycle.State.STARTED) }
                val remaining = userScrollingUntilMs - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
            } while (userScrollingUntilMs > android.os.SystemClock.elapsedRealtime() ||
                !lifecycle.value.isAtLeast(Lifecycle.State.STARTED))
        }

        awaitIdle()
        val cache = runCatching { thumbnailCache(context) }.getOrNull() ?: return@withContext false
        val missing = keys.filter { !knownMissing(it) && thumbnailIsMissing(cache, it) }
        if (missing.isEmpty()) return@withContext true
        onProgress(0, missing.size)
        var complete = true
        for ((index, url) in missing.withIndex()) {
            yield()
            if (index > 0 && index % PREWARM_BATCH == 0) delay(200L)
            awaitIdle()
            if (index % PREWARM_BATCH == 0 && !canWarmArtwork(context)) return@withContext false
            thumbnailLock(url).withLock {
                if (!knownMissing(url) && thumbnailIsMissing(cache, url)) {
                    val bytes = decodeArtwork(context, url, cache)
                    val handled = if (bytes != null) saveThumbnail(cache, url, bytes) else knownMissing(url)
                    complete = complete && handled
                }
            }
            val done = index + 1
            if (done == missing.size || done % PREWARM_BATCH == 0) onProgress(done, missing.size)
        }
        complete
    }

    private fun thumbnailIsMissing(cache: DiskCache, url: String): Boolean =
        runCatching { cache.openSnapshot(url)?.use { false } ?: true }.getOrDefault(false)

    private fun cachedThumbnail(cache: DiskCache, url: String): SourceFetchResult? =
        runCatching { cache.openSnapshot(url) }.getOrNull()?.let { snapshot ->
            SourceFetchResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = cache.fileSystem,
                    diskCacheKey = url,
                    closeable = snapshot,
                ),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }

    private fun saveThumbnail(cache: DiskCache, url: String, bytes: ByteArray): Boolean = synchronized(cacheLock) {
        if (cache !== sharedDiskCache) return@synchronized false
        runCatching {
            val editor = cache.openEditor(url) ?: return@runCatching !thumbnailIsMissing(cache, url)
            try {
                cache.fileSystem.write(editor.metadata) {}
                cache.fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
                true
            } catch (error: Exception) {
                editor.abort()
                throw error
            }
        }.getOrDefault(false)
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith(PREFIX)) return null
            return object : Fetcher {
                override suspend fun fetch(): SourceFetchResult {
                    val thumbnails = thumbnailCache(context)
                    val width = options.size.width as? Dimension.Pixels
                    val height = options.size.height as? Dimension.Pixels
                    val small = width != null && height != null && width.px <= THUMB_FETCH_MAX_PX && height.px <= THUMB_FETCH_MAX_PX
                    if (small) {
                        cachedThumbnail(thumbnails, url)?.let { return it }
                    }
                    return withContext(artworkDispatcher) {
                        if (small) {
                            thumbnailLock(url).withLock {
                                cachedThumbnail(thumbnails, url) ?: readArtwork(options.context, url, thumbnails)
                            }
                        } else {
                            readArtwork(options.context, url)
                        }
                    }
                }
            }
        }
    }

    private fun decodeArtwork(context: Context, url: String, thumbnails: DiskCache?): ByteArray? {
        if (thumbnails != null && knownMissing(url)) return null
        var confirmedAbsent = true
        val sources = url.removePrefix(PREFIX).split('|').drop(1)
        for (encoded in sources) {
            val source = Uri.decode(encoded)
            val result = runCatching { read(context, source) }
            val bytes = result.getOrNull()
            if (result.isFailure || bytes != null) confirmedAbsent = false
            if (bytes != null) decodeCover(bytes, thumbnails != null)?.let { return it }
        }
        if (confirmedAbsent && thumbnails != null && sources.isNotEmpty()) {
            synchronized(cacheLock) {
                if (thumbnails === sharedDiskCache) {
                    missingCovers.put(url, android.os.SystemClock.elapsedRealtime() + MISSING_COVER_TTL_MS)
                }
            }
        }
        return null
    }

    private fun readArtwork(context: Context, url: String, thumbnails: DiskCache? = null): SourceFetchResult {
        val bytes = decodeArtwork(context, url, thumbnails) ?: error("No readable local cover")
        if (thumbnails != null) saveThumbnail(thumbnails, url, bytes)
        return SourceFetchResult(
            source = ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
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

    private fun read(context: Context, source: String): ByteArray? = when {
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
        source.startsWith("file:") -> {
            val file = File(source.removePrefix("file:"))
            if (!file.isFile || !file.canRead()) throw java.io.IOException("Artwork file is unavailable")
            file.takeIf { it.length() in 1..MAX_BYTES.toLong() }?.readBytes()
        }
        else -> checkNotNull(context.contentResolver.openInputStream(Uri.parse(source))).use { stream ->
            stream.source().buffer().use { input ->
                if (input.request(MAX_BYTES.toLong() + 1)) null else input.readByteArray()
            }
        }
    }
}
