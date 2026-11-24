package com.music.bitchord.data

import android.content.Context
import android.database.ContentObserver
import android.provider.MediaStore
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalMusicScanMode
import com.music.bitchord.download.Downloads
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object LocalLibraryIndex {

    enum class ScanPhase { IDLE, DISCOVERING, INDEXING, UNAVAILABLE }

    data class ScanProgress(
        val phase: ScanPhase = ScanPhase.IDLE,
        val processed: Int = 0,
        val total: Int = 0,
        val discovered: Int = 0,
        val revokedFolders: List<String> = emptyList(),
    ) {
        val isActive: Boolean get() = phase == ScanPhase.DISCOVERING || phase == ScanPhase.INDEXING
    }

    @Serializable
    private data class CachedEntry(
        val song: Song,
        val lastModifiedMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val coverSignature: String = "",
    )

    @Serializable
    private data class SavedV2(
        val source: String,
        val mediaStoreSignature: String? = null,
        val entries: List<CachedEntry> = emptyList(),
    )

    @Serializable
    private data class SavedV1(
        val source: String,
        val songs: List<Song>,
    )

    private const val INDEX_V2_NAME = "local-music-index-v2.json"
    private const val INDEX_V1_NAME = "local-music-index-v1.json"
    private const val EMIT_BATCH_SIZE = 50
    private const val EMIT_INTERVAL_MS = 250L
    private const val CHECKPOINT_EVERY_NEW = 500

    /** Application-scoped coroutine scope so scans survive navigating out of Local Music. */
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexWorkers = Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    )

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: SavedV2? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var prewarmJob: Job? = null

    private val mutableProgress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = mutableProgress.asStateFlow()

    private val mutableSongs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = mutableSongs.asStateFlow()

    internal fun sourceKey(): String = buildString {
        append(AppSettings.localMusicScanMode.value.name)
        append('|')
        append(AppSettings.filterNonMusicAudio.value)
        append('|')
        append(AppSettings.localMusicMinDurationSec.value)
        append('|')
        append(AppSettings.localLegacyScanMode.value.name)
        append('|')
        AppSettings.localMusicFolders.value.forEach { f ->
            append(f.isTreeUri).append(':').append(f.id).append(':').append(f.includeSubfolders).append(';')
        }
        append('|')
        AppSettings.localMusicExcludedFolders.value.forEach { f ->
            append(f.isTreeUri).append(':').append(f.id).append(':').append(f.includeSubfolders).append(';')
        }
    }

    private fun fileV2(context: Context) = AtomicFile(File(context.filesDir, INDEX_V2_NAME))
    private fun fileV1(context: Context) = File(context.filesDir, INDEX_V1_NAME)

    /**
     * Non-blocking cache read safe for all callers (including Android Auto /
     * `PlaybackService` and Search → Library). Returns empty immediately when
     * Local Music has not been configured yet — never triggers an unsolicited scan.
     */
    suspend fun get(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) {
            return@withContext emptyList()
        }
        val live = mutableSongs.value
        if (live.isNotEmpty()) return@withContext live

        val saved = read(context)
        if (saved != null && saved.entries.isNotEmpty()) {
            val loaded = saved.entries.map { it.song }
            mutableSongs.value = loaded
            return@withContext loaded
        }
        emptyList()
    }

    /**
     * Launches a scan in the app-scoped background scope if Local Music is
     * configured. No-op when `NOT_CONFIGURED` or when a scan is already running.
     */
    fun refreshIfConfigured(context: Context) {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) return
        if (activeJob?.isActive == true) return
        startScan(context.applicationContext, forceFull = false)
    }

    /** Starts (or restarts if [forceFull]) a background scan in the app-scoped coroutine. */
    fun startScan(context: Context, forceFull: Boolean = false): Job {
        val appContext = context.applicationContext
        if (forceFull) {
            activeJob?.cancel()
        } else if (activeJob?.isActive == true) {
            return activeJob!!
        }
        val job = indexScope.launch {
            lock.withLock {
                runScan(appContext, forceFull = forceFull)
            }
        }
        activeJob = job
        return job
    }

    /** Suspends until a refresh completes; used by explicit pull/menu refresh callers. */
    suspend fun refresh(context: Context, forceFull: Boolean = false): List<Song> {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) {
            return emptyList()
        }
        startScan(context.applicationContext, forceFull = forceFull).join()
        return mutableSongs.value
    }

    /** Cancels an active scan while keeping any tracks already checkpointed. */
    fun cancelScan() {
        activeJob?.cancel()
        activeJob = null
        prewarmJob?.cancel()
        prewarmJob = null
        if (mutableProgress.value.isActive) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
        }
    }

    /**
     * Clears the Local Music index, thumbnail cache, persisted SAF URI grants,
     * and scan configuration, resetting back to the first-time opt-in screen.
     * Does NOT delete user audio files or interrupt active playback.
     */
    suspend fun clearLibrary(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        cancelScan()
        val safUris = (AppSettings.localMusicFolders.value + AppSettings.localMusicExcludedFolders.value)
            .filter { it.isTreeUri }
            .map { it.id } + listOfNotNull(AppSettings.localMusicFolderUri.value.takeIf { it.isNotBlank() })
        LocalStorageVolumes.releaseSafPermissions(appContext, safUris)
        lock.withLock {
            cached = null
            runCatching { fileV2(appContext).delete() }
            runCatching { fileV1(appContext).delete() }
            LocalArtwork.clearThumbnailCache(appContext)
            AppSettings.resetLocalMusicConfig()
            mutableSongs.value = emptyList()
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
        }
    }

    fun changes(context: Context) = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        try {
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: SecurityException) {
            resolver.unregisterContentObserver(observer)
            close()
            return@callbackFlow
        }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun read(context: Context): SavedV2? {
        cached?.let { return it }
        val v2 = runCatching {
            val raw = fileV2(context).readFully().decodeToString()
            json.decodeFromString(SavedV2.serializer(), raw)
        }.getOrNull()
        if (v2 != null) {
            cached = v2
            return v2
        }
        // Migrate v1 cache seamlessly so existing users see their library immediately.
        val v1File = fileV1(context)
        if (v1File.exists()) {
            val v1 = runCatching {
                json.decodeFromString(SavedV1.serializer(), v1File.readText())
            }.getOrNull()
            if (v1 != null) {
                val migrated = SavedV2(
                    source = sourceKey(),
                    entries = v1.songs.map { song ->
                        CachedEntry(
                            song = song,
                            lastModifiedMs = (song.localDateModifiedSeconds ?: 0L) * 1_000L,
                        )
                    },
                )
                cached = migrated
                return migrated
            }
        }
        return null
    }

    private fun writeDisk(context: Context, saved: SavedV2) {
        val atomic = fileV2(context)
        runCatching {
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(SavedV2.serializer(), saved).encodeToByteArray())
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        }
        cached = saved
    }

    private suspend fun runScan(context: Context, forceFull: Boolean) {
        val mode = AppSettings.localMusicScanMode.value
        if (mode == LocalMusicScanMode.NOT_CONFIGURED) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            return
        }

        val key = sourceKey()
        val previous = read(context)
        val sameSource = previous != null && previous.source == key
        val oldEntries = if (sameSource) previous!!.entries else emptyList()

        // Seed live list immediately from cache so returning users never see a blank screen.
        if (sameSource && mutableSongs.value.isEmpty() && oldEntries.isNotEmpty()) {
            mutableSongs.value = oldEntries.map { it.song }
        }

        val useLegacy = LocalStorageVolumes.shouldUseLegacyScan(context, AppSettings.localLegacyScanMode.value)
        val hasStoragePerm = LocalMediaRepository.hasStoragePermission(context)
        val hasSafFolders = mode == LocalMusicScanMode.SPECIFIC_FOLDERS &&
            AppSettings.localMusicFolders.value.any { it.isTreeUri }

        if (!hasStoragePerm && !hasSafFolders && !useLegacy) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.UNAVAILABLE)
            return
        }

        // Fast-path: pure MediaStore ALL_FOLDERS with unchanged COUNT/MAX(date_modified)/MAX(_id).
        if (!forceFull && sameSource && mode == LocalMusicScanMode.ALL_FOLDERS && !useLegacy &&
            AppSettings.localMusicExcludedFolders.value.isEmpty()
        ) {
            val currentSig = LocalMediaRepository.mediaStoreSignature(context)
            if (currentSig != null && currentSig == previous?.mediaStoreSignature && oldEntries.isNotEmpty()) {
                mutableSongs.value = oldEntries.map { it.song }
                mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
                return
            }
        }

        mutableProgress.value = ScanProgress(phase = ScanPhase.DISCOVERING, discovered = 0)

        val discovery = try {
            LocalMediaRepository.discoverLocalMusic(context) { count ->
                mutableProgress.value = ScanProgress(
                    phase = ScanPhase.DISCOVERING,
                    discovered = count,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            throw cancelled
        } catch (_: Exception) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.UNAVAILABLE)
            return
        }

        if (sourceKey() != key) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            return
        }

        // Unmount protection: if a previously indexed external volume/folder is currently
        // unreadable, keep cached tracks and report UNAVAILABLE instead of wiping the library.
        val discoveredUris = discovery.files.mapTo(HashSet(discovery.files.size)) { it.uri }
        val unmountedPreviouslyIndexed = discovery.unavailableVolumes || oldEntries.any { entry ->
            entry.song.localUri !in discoveredUris && isPathOnUnmountedExternalVolume(entry.song.localPath)
        }
        if (unmountedPreviouslyIndexed && discovery.files.size < oldEntries.size) {
            mutableProgress.value = ScanProgress(
                phase = ScanPhase.UNAVAILABLE,
                revokedFolders = discovery.revokedSafFolders,
            )
            return
        }

        val total = discovery.files.size
        if (total == 0) {
            val emptySaved = SavedV2(key, discovery.mediaStoreSignature, emptyList())
            writeDisk(context, emptySaved)
            mutableSongs.value = emptyList()
            mutableProgress.value = ScanProgress(
                phase = ScanPhase.IDLE,
                revokedFolders = discovery.revokedSafFolders,
            )
            return
        }

        mutableProgress.value = ScanProgress(
            phase = ScanPhase.INDEXING,
            processed = 0,
            total = total,
            discovered = total,
            revokedFolders = discovery.revokedSafFolders,
        )

        val priorByUri = previous?.entries.orEmpty().associateBy { it.song.localUri }
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val minDurationMs = if (filterNonMusic) AppSettings.localMusicMinDurationSec.value * 1_000L else 0L
        val nowSec = System.currentTimeMillis() / 1_000L

        // Pre-compute directory sidecar covers in parallel for MediaStore directories (direct/SAF already have them).
        val mediaStoreDirs = discovery.files.asSequence()
            .filter { it.isMediaStore }
            .mapNotNull { it.path?.let { p -> File(p).parentFile?.path } }
            .distinct()
            .toList()
        val dirCovers = ConcurrentHashMap<String, List<LocalArtwork.SidecarCover>>()
        if (mediaStoreDirs.isNotEmpty()) {
            coroutineScope {
                mediaStoreDirs.chunked(64).map { chunk ->
                    async(indexWorkers) {
                        for (dirPath in chunk) {
                            dirCovers[dirPath] = LocalArtwork.scanDirectoryCovers(File(dirPath))
                        }
                    }
                }.awaitAll()
            }
        }

        val processedCounter = AtomicInteger(0)
        val newlyParsedCounter = AtomicInteger(0)
        val resultsLock = Any()
        val orderedResults = arrayOfNulls<CachedEntry>(total)
        val liveBuffer = ArrayList<Song>(total)
        var lastEmitTime = System.currentTimeMillis()
        var lastEmitCount = 0
        var lastCheckpointNew = 0

        fun emitSnapshotLocked(processed: Int, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && (processed - lastEmitCount < EMIT_BATCH_SIZE) && (now - lastEmitTime < EMIT_INTERVAL_MS)) {
                return
            }
            lastEmitTime = now
            lastEmitCount = processed
            mutableSongs.value = liveBuffer.toList()
            mutableProgress.value = ScanProgress(
                phase = ScanPhase.INDEXING,
                processed = processed,
                total = total,
                discovered = total,
                revokedFolders = discovery.revokedSafFolders,
            )
        }

        fun maybeCheckpointLocked(newlyParsed: Int) {
            if (newlyParsed - lastCheckpointNew < CHECKPOINT_EVERY_NEW) return
            lastCheckpointNew = newlyParsed
            val partialEntries = orderedResults.filterNotNull()
            if (partialEntries.isNotEmpty()) {
                writeDisk(context, SavedV2(key, discovery.mediaStoreSignature, partialEntries))
            }
        }

        try {
            val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val nextIndex = AtomicInteger(0)
            coroutineScope {
                List(workerCount) {
                    async(indexWorkers) {
                        while (true) {
                            coroutineContext.ensureActive()
                            val idx = nextIndex.getAndIncrement()
                            if (idx >= total) break
                            val item = discovery.files[idx]
                            val sidecars = if (item.isMediaStore) {
                                item.path?.let { dirCovers[File(it).parentFile?.path] }.orEmpty()
                            } else {
                                item.sidecarCovers
                            }
                            val coverSig = sidecars.joinToString(",") { it.signature }
                            val prior = priorByUri[item.uri]

                            val entry: CachedEntry? = if (
                                !forceFull &&
                                prior != null &&
                                prior.lastModifiedMs == item.lastModifiedMs &&
                                prior.sizeBytes == item.sizeBytes
                            ) {
                                if (prior.coverSignature == coverSig) {
                                    prior
                                } else {
                                    // Sidecar cover.jpg changed without touching audio file: re-decorate only.
                                    CachedEntry(
                                        song = LocalArtwork.decorateOne(prior.song, sidecars),
                                        lastModifiedMs = item.lastModifiedMs,
                                        sizeBytes = item.sizeBytes,
                                        coverSignature = coverSig,
                                    )
                                }
                            } else {
                                val parsed = if (item.isMediaStore && item.mediaStoreSong != null) {
                                    LocalArtwork.decorateOne(item.mediaStoreSong, sidecars)
                                } else {
                                    newlyParsedCounter.incrementAndGet()
                                    LocalMediaRepository.indexDiscoveredFile(
                                        context = context,
                                        item = item,
                                        videoIdByUri = videoIdByUri,
                                        filterNonMusic = filterNonMusic,
                                        minDurationMs = minDurationMs,
                                    )
                                }
                                parsed?.let { song ->
                                    val preservedAdded = if (prior != null) {
                                        prior.song.localDateAddedSeconds
                                    } else {
                                        song.localDateAddedSeconds?.takeIf { it > 0L } ?: nowSec
                                    }
                                    CachedEntry(
                                        song = song.copy(localDateAddedSeconds = preservedAdded),
                                        lastModifiedMs = item.lastModifiedMs,
                                        sizeBytes = item.sizeBytes,
                                        coverSignature = coverSig,
                                    )
                                }
                            }

                            val done = processedCounter.incrementAndGet()
                            val newCount = newlyParsedCounter.get()
                            synchronized(resultsLock) {
                                if (entry != null) {
                                    orderedResults[idx] = entry
                                    liveBuffer.add(entry.song)
                                }
                                emitSnapshotLocked(done)
                                maybeCheckpointLocked(newCount)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            synchronized(resultsLock) {
                val partial = orderedResults.filterNotNull()
                if (partial.isNotEmpty()) {
                    writeDisk(context, SavedV2(key, null, partial))
                    mutableSongs.value = partial.map { it.song }
                }
            }
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            throw cancelled
        }

        if (sourceKey() != key) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            return
        }

        val finalEntries = orderedResults.filterNotNull()
        val finalSongs = finalEntries.map { it.song }
        val saved = SavedV2(key, discovery.mediaStoreSignature, finalEntries)
        writeDisk(context, saved)
        mutableSongs.value = finalSongs
        mutableProgress.value = ScanProgress(
            phase = ScanPhase.IDLE,
            processed = total,
            total = total,
            discovered = total,
            revokedFolders = discovery.revokedSafFolders,
        )

        // Start low-priority background thumbnail caching for immediate scroll smoothness.
        prewarmJob?.cancel()
        prewarmJob = indexScope.launch {
            LocalArtwork.prewarmThumbnails(context, finalSongs)
        }
    }

    private fun isPathOnUnmountedExternalVolume(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return when {
            path.startsWith("/storage/") -> {
                val volume = path.removePrefix("/storage/").substringBefore('/')
                volume !in setOf("emulated", "self") && !File("/storage/$volume").canRead()
            }
            path.startsWith("/mnt/media_rw/") -> {
                val volume = path.removePrefix("/mnt/media_rw/").substringBefore('/')
                !File("/mnt/media_rw/$volume").canRead()
            }
            path.startsWith("/mnt/usb_storage/") -> {
                val volume = path.removePrefix("/mnt/usb_storage/").substringBefore('/')
                !File("/mnt/usb_storage/$volume").canRead()
            }
            else -> false
        }
    }
}
