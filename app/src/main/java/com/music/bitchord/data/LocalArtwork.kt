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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    internal val coverBaseNames = listOf("cover", "folder", "front", "albumart", "album", "artwork")
    internal val coverExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val discouragedArtworkNames = listOf(
        "back", "rear", "booklet", "inside", "disc", "disk", "cd", "logo", "artist", "banner",
    )
    private val artworkDispatcher = Dispatchers.IO.limitedParallelism(2)
    private const val THUMB_PX = 256
    private const val THUMB_FETCH_MAX_PX = 768
    private const val THUMB_CACHE_BYTES = 256L * 1024 * 1024
    private const val THUMB_CACHE_DIR = "local-thumbnails"
    private const val TRASH_SUFFIX = ".trash-"

    @Volatile private var sharedDiskCache: DiskCache? = null
    private val cacheLock = Any()
    private const val ABSENT_COVERS_FILE = "absent-covers.txt"
    private const val MAX_ABSENT_COVERS = 50_000
    @Volatile private var absentLoaded = false
    @Volatile private var absentDirty = false
    private val absentCovers = LinkedHashSet<String>()

    private fun absentCoversFile(context: Context): File =
        File(File(context.applicationContext.cacheDir, THUMB_CACHE_DIR), ABSENT_COVERS_FILE)

    private fun ensureAbsentLoaded(context: Context) {
        if (absentLoaded) return
        val file = absentCoversFile(context)
        val lines = runCatching {
            if (file.isFile) file.readLines() else emptyList()
        }.getOrDefault(emptyList())
        synchronized(cacheLock) {
            if (!absentLoaded) {
                lines.forEach { line ->
                    if (line.isNotBlank()) absentCovers.add(line)
                }
                while (absentCovers.size > MAX_ABSENT_COVERS) {
                    val it = absentCovers.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                        absentDirty = true
                    } else break
                }
                absentLoaded = true
            }
        }
    }

    private fun recordAbsentCover(context: Context, url: String) {
        ensureAbsentLoaded(context)
        synchronized(cacheLock) {
            if (absentCovers.add(url)) {
                while (absentCovers.size > MAX_ABSENT_COVERS) {
                    val it = absentCovers.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    } else break
                }
                absentDirty = true
            }
        }
    }

    private fun flushAbsentCovers(context: Context) {
        val snapshot: List<String>
        synchronized(cacheLock) {
            if (!absentDirty) return
            snapshot = absentCovers.toList()
            absentDirty = false
        }
        runCatching {
            val file = absentCoversFile(context)
            file.parentFile?.mkdirs()
            file.writeText(snapshot.joinToString("\n"))
        }
    }

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
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return ext in coverExtensions
    }

    fun coverSortOrder(name: String): Int {
        val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        val preferred = coverBaseNames.indexOf(base)
        if (preferred >= 0) return preferred
        return if (discouragedArtworkNames.any { token -> base.contains(token) }) 1_000 else 100
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
        val preferredSidecars = sidecars.filter { coverSortOrder(it.signature.substringBefore(':')) < 100 }
        val otherSidecars = sidecars - preferredSidecars.toSet()
        val preferredSources = preferredSidecars.map { it.source }
        val otherSources = otherSidecars.map { it.source }
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
            // A row and Now Playing belong to one exact file, so its embedded
            // sleeve wins. Album/folder tiles prefer an explicitly named shared
            // cover, then the representative track's embedded sleeve. Arbitrary
            // directory images are a last usable fallback on both surfaces.
            thumbnailUrl = url(
                embedded,
                *preferredSources.toTypedArray(),
                *otherSources.toTypedArray(),
                fallback,
            ),
            localAlbumArtwork = url(
                *preferredSources.toTypedArray(),
                embedded,
                *otherSources.toTypedArray(),
                fallback,
            ),
        )
    }

    data class FileRevisionToken(val modifiedMs: Long, val sizeBytes: Long)

    fun scanDirectoryCovers(directory: File): List<SidecarCover> = runCatching {
        if (!directory.isDirectory || !directory.canRead()) return emptyList()
        directory.listFiles()?.filter {
            it.isFile && isCoverFileName(it.name)
        }?.sortedWith(
            compareBy<File> { coverSortOrder(it.name) }
                .thenByDescending { it.length() }
                .thenBy { it.name.lowercase(Locale.ROOT) },
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
            absentCovers.clear()
            absentLoaded = false
            absentDirty = false
            runCatching { File(context.cacheDir, THUMB_CACHE_DIR).deleteRecursively() }
            runCatching { File(context.cacheDir, "embedded_art").deleteRecursively() }
        }
    }

    private fun knownMissing(context: Context, url: String): Boolean {
        ensureAbsentLoaded(context)
        return synchronized(cacheLock) { url in absentCovers }
    }

    private const val PREWARM_BATCH = 12
    private const val PREWARM_CONCURRENCY = 3
    private val prewarmDispatcher = Dispatchers.IO.limitedParallelism(PREWARM_CONCURRENCY)
    private val thumbnailLocks = Array(32) { Mutex() }
    @Volatile private var userScrollingUntilMs: Long = 0L

    private fun thumbnailLock(url: String): Mutex =
        thumbnailLocks[(url.hashCode() and Int.MAX_VALUE) % thumbnailLocks.size]

    internal fun warmKeys(songs: List<Song>): List<String> = songs.asSequence()
        .flatMap { sequenceOf(it.localAlbumArtwork, it.thumbnailUrl) }
        .filterNotNull()
        .filter { it.startsWith(PREFIX) }
        .distinct()
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

    /** Warm every missing local thumbnail in bounded, scroll-friendly batches (continues screen-off). */
    suspend fun prewarmThumbnails(
        context: Context,
        keys: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = withContext(prewarmDispatcher) {
        if (keys.isEmpty()) return@withContext true
        if (!canWarmArtwork(context)) return@withContext false

        suspend fun awaitScrollIdle() {
            while (true) {
                val remaining = userScrollingUntilMs - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                delay(remaining)
            }
        }

        awaitScrollIdle()
        ensureAbsentLoaded(context)
        val cache = runCatching { thumbnailCache(context) }.getOrNull() ?: return@withContext false
        val missing = keys.filter { !knownMissing(context, it) && thumbnailIsMissing(cache, it) }
        if (missing.isEmpty()) return@withContext true
        onProgress(0, missing.size)
        val complete = AtomicBoolean(true)
        val aborted = AtomicBoolean(false)
        val doneCount = AtomicInteger(0)
        val permits = Semaphore(PREWARM_CONCURRENCY)
        try {
            coroutineScope {
                missing.mapIndexed { index, url ->
                    async {
                        permits.withPermit {
                            if (aborted.get()) return@withPermit
                            yield()
                            awaitScrollIdle()
                            if (index % PREWARM_BATCH == 0 && !canWarmArtwork(context)) {
                                aborted.set(true)
                                return@withPermit
                            }
                            thumbnailLock(url).withLock {
                                if (!knownMissing(context, url) && thumbnailIsMissing(cache, url)) {
                                    val bytes = decodeArtwork(context, url, cache)
                                    val handled = if (bytes != null) saveThumbnail(cache, url, bytes) else knownMissing(context, url)
                                    if (!handled) complete.set(false)
                                }
                            }
                            val done = doneCount.incrementAndGet()
                            if (done == missing.size || done % PREWARM_BATCH == 0) {
                                onProgress(done, missing.size)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            flushAbsentCovers(context)
        }
        !aborted.get() && complete.get()
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
        if (thumbnails != null && knownMissing(context, url)) return null
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
            recordAbsentCover(context, url)
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
