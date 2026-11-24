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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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

    enum class ScanPhase { IDLE, DISCOVERING, INDEXING, BUILDING_INDEX, UNAVAILABLE }

    data class ScanProgress(
        val phase: ScanPhase = ScanPhase.IDLE,
        val processed: Int = 0,
        val total: Int = 0,
        val discovered: Int = 0,
        val revokedFolders: List<String> = emptyList(),
    ) {
        val isActive: Boolean get() = phase == ScanPhase.DISCOVERING || phase == ScanPhase.INDEXING ||
            phase == ScanPhase.BUILDING_INDEX
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
    private const val FIRST_EMIT_COUNT = 10
    private const val FIRST_EMIT_MS = 120L
    private const val EMIT_BATCH_SIZE = 25
    private const val EMIT_INTERVAL_MS = 200L
    private const val CHECKPOINT_EVERY_NEW = 500
    private const val AUTO_REFRESH_COOLDOWN_MS = 15_000L
    private const val CLEAR_CANCEL_WAIT_MS = 1_500L

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
    @Volatile private var lastCompletedScanMs: Long = 0L

    private val mutableProgress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = mutableProgress.asStateFlow()

    private val mutableArtworkProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Non-null (`done to total`) while background thumbnail pre-caching is active. */
    val artworkProgress: StateFlow<Pair<Int, Int>?> = mutableArtworkProgress.asStateFlow()

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

    internal fun filterIgnored(songs: List<Song>, ignored: Set<String> = AppSettings.localMusicIgnoredUris.value): List<Song> {
        if (ignored.isEmpty()) return songs
        return songs.filter { song ->
            val key = song.localUri ?: song.videoId
            key !in ignored && song.videoId !in ignored
        }
    }

    /** Hides [songs] from Local Music without deleting audio files; survives rescans. */
    fun hideSongs(songs: Collection<Song>) {
        val uris = songs.mapNotNull { it.localUri ?: it.videoId.takeIf { id -> id.isNotBlank() } }
        if (uris.isEmpty()) return
        AppSettings.addIgnoredLocalSongUris(uris)
        mutableSongs.value = filterIgnored(mutableSongs.value)
    }

    /** Restores all hidden Local Music tracks immediately from the cached index. */
    suspend fun restoreHiddenSongs(context: Context) = withContext(Dispatchers.IO) {
        AppSettings.clearIgnoredLocalSongUris()
        val saved = read(context.applicationContext)
        if (saved != null && saved.entries.isNotEmpty()) {
            mutableSongs.value = saved.entries.map { it.song }
        }
    }

    /**
     * Non-blocking cache read safe for all callers (including Android Auto /
     * `PlaybackService` and Search → Library). Returns empty immediately when
     * Local Music has not been configured yet — never triggers an unsolicited scan.
     */
    suspend fun get(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) {
            return@withContext emptyList()
        }
        val live = filterIgnored(mutableSongs.value)
        if (live.isNotEmpty()) return@withContext live

        val saved = read(context)
        if (saved != null && saved.entries.isNotEmpty()) {
            val loaded = filterIgnored(saved.entries.map { it.song })
            mutableSongs.value = loaded
            return@withContext loaded
        }
        emptyList()
    }

    /**
     * Launches an incremental check in the app-scoped background scope if Local
     * Music is configured. When [silent] is true and a library is already cached,
     * skips showing the `"Scanning folders…"` banner unless new/modified tracks
     * are actually discovered, and respects a 15-second cooldown.
     */
    fun refreshIfConfigured(context: Context, silent: Boolean = true) {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) return
        if (activeJob?.isActive == true) return
        if (silent && mutableSongs.value.isNotEmpty() &&
            (System.currentTimeMillis() - lastCompletedScanMs) < AUTO_REFRESH_COOLDOWN_MS
        ) {
            return
        }
        startScan(context.applicationContext, forceFull = false, silent = silent)
    }

    /** Starts (or restarts if [forceFull]) a background scan in the app-scoped coroutine. */
    fun startScan(
        context: Context,
        forceFull: Boolean = false,
        silent: Boolean = false,
        restart: Boolean = false,
    ): Job {
        val appContext = context.applicationContext
        if (forceFull || restart) {
            activeJob?.cancel()
        } else if (activeJob?.isActive == true) {
            return activeJob!!
        }
        val job = indexScope.launch {
            lock.withLock {
                runScan(appContext, forceFull = forceFull, silent = silent)
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
        startScan(context.applicationContext, forceFull = forceFull, silent = false).join()
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

    private val mutableClearing = MutableStateFlow(false)

    /** True while [clearLibrary] is resetting state, so UI can ignore repeat taps. */
    val clearing: StateFlow<Boolean> = mutableClearing.asStateFlow()

    /**
     * Clears the Local Music index, thumbnail cache, persisted SAF URI grants,
     * and scan configuration, resetting back to the first-time opt-in screen.
     * Does NOT delete user audio files or interrupt active playback.
     *
     * Returns as soon as the visible state is reset; disk cleanup runs in the
     * background (serialized behind the index lock so a following scan can't collide).
     * Repeat calls while a clear is in flight are ignored.
     */
    suspend fun clearLibrary(context: Context) {
        if (!mutableClearing.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val safUris = (AppSettings.localMusicFolders.value + AppSettings.localMusicExcludedFolders.value)
                    .filter { it.isTreeUri }
                    .map { it.id } + listOfNotNull(AppSettings.localMusicFolderUri.value.takeIf { it.isNotBlank() })
                val running = activeJob
                cancelScan()
                // Bounded wait so a scan stuck in blocking IO can never freeze the clear.
                withTimeoutOrNull(CLEAR_CANCEL_WAIT_MS) { running?.join() }
                AppSettings.resetLocalMusicConfig()
                cached = null
                lastCompletedScanMs = 0L
                mutableSongs.value = emptyList()
                mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
                indexScope.launch {
                    lock.withLock {
                        runCatching { fileV2(appContext).delete() }
                        runCatching { fileV1(appContext).delete() }
                        LocalArtwork.clearThumbnailCache(appContext)
                        LocalStorageVolumes.releaseSafPermissions(appContext, safUris)
                    }
                }
            }
        } finally {
            mutableClearing.value = false
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

    private suspend fun runScan(context: Context, forceFull: Boolean, silent: Boolean) {
        val mode = AppSettings.localMusicScanMode.value
        if (mode == LocalMusicScanMode.NOT_CONFIGURED) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            return
        }

        val key = sourceKey()
        val previous = read(context)
        val sameSource = previous != null && previous.source == key
        val oldEntries = if (sameSource) previous!!.entries else emptyList()
        val isSilentCheck = silent && sameSource && oldEntries.isNotEmpty() && !forceFull

        // Seed live list immediately from cache so returning users never see a blank screen.
        // When the folder set was edited, keep every cached song still in scope on screen and
        // drop the songs of removed folders instantly instead of blanking the list.
        if (sameSource) {
            if (mutableSongs.value.isEmpty() && oldEntries.isNotEmpty()) {
                mutableSongs.value = filterIgnored(oldEntries.map { it.song })
            }
        } else if (previous != null) {
            val folders = AppSettings.localMusicFolders.value
            val excluded = AppSettings.localMusicExcludedFolders.value
            val kept = previous.entries.map { it.song }.filter {
                LocalMediaRepository.isSongInScope(it, mode, folders, excluded)
            }
            val visibleKept = filterIgnored(kept)
            if (visibleKept.isNotEmpty() || mutableSongs.value.isNotEmpty()) mutableSongs.value = visibleKept
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
                mutableSongs.value = filterIgnored(oldEntries.map { it.song })
                mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
                lastCompletedScanMs = System.currentTimeMillis()
                return
            }
        }

        if (!isSilentCheck) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.DISCOVERING, discovered = 0)
        }

        val discovery = try {
            LocalMediaRepository.discoverLocalMusic(context) { count ->
                if (!isSilentCheck) {
                    mutableProgress.value = ScanProgress(
                        phase = ScanPhase.DISCOVERING,
                        discovered = count,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
            throw cancelled
        } catch (_: Exception) {
            if (!isSilentCheck) mutableProgress.value = ScanProgress(phase = ScanPhase.UNAVAILABLE)
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
            lastCompletedScanMs = System.currentTimeMillis()
            return
        }

        val priorByUri = previous?.entries.orEmpty().associateBy { it.song.localUri }

        // In silent check mode, if every discovered URI already exists with matching
        // mtime and count matches oldEntries, skip indexing completely with zero banner flash.
        if (isSilentCheck && total == oldEntries.size && discovery.files.all { item ->
                val prior = priorByUri[item.uri]
                prior != null && prior.lastModifiedMs == item.lastModifiedMs
            }
        ) {
            lastCompletedScanMs = System.currentTimeMillis()
            if (discovery.revokedSafFolders.isNotEmpty()) {
                mutableProgress.value = ScanProgress(
                    phase = ScanPhase.IDLE,
                    revokedFolders = discovery.revokedSafFolders,
                )
            }
            return
        }

        // Immediately enter INDEXING with zero blocking pre-pass.
        mutableProgress.value = ScanProgress(
            phase = ScanPhase.INDEXING,
            processed = 0,
            total = total,
            discovered = total,
            revokedFolders = discovery.revokedSafFolders,
        )

        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val minDurationMs = if (filterNonMusic) AppSettings.localMusicMinDurationSec.value * 1_000L else 0L
        val nowSec = System.currentTimeMillis() / 1_000L

        // Memoize MediaStore directory covers lazily inside parallel workers (no blocking pre-pass!).
        val dirCovers = ConcurrentHashMap<String, List<LocalArtwork.SidecarCover>>()

        val processedCounter = AtomicInteger(0)
        val newlyParsedCounter = AtomicInteger(0)
        val resultsLock = Any()
        val orderedResults = arrayOfNulls<CachedEntry>(total)
        // Stable discovery order: not-yet-processed slots show their previous cached song so the
        // list never shrinks, jumps or reorders while indexing.
        val priorEntryAt = Array(total) { priorByUri[discovery.files[it].uri] }
        val checkpointWriting = AtomicBoolean(false)
        var checkpointJob: Job? = null
        var lastEmitTime = System.currentTimeMillis()
        var lastEmitCount = 0
        var lastCheckpointNew = 0
        var emittedFirstBatch = false

        fun snapshotSongsLocked(): List<Song> {
            val out = ArrayList<Song>(total)
            for (i in 0 until total) {
                val song = (orderedResults[i] ?: priorEntryAt[i])?.song
                if (song != null) out.add(song)
            }
            return out
        }

        fun snapshotEntriesLocked(): List<CachedEntry> {
            val out = ArrayList<CachedEntry>(total)
            for (i in 0 until total) {
                val entry = orderedResults[i] ?: priorEntryAt[i]
                if (entry != null) out.add(entry)
            }
            return out
        }

        fun emitSnapshotLocked(processed: Int, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val shouldEmit = force ||
                (!emittedFirstBatch && (processed >= FIRST_EMIT_COUNT || now - lastEmitTime >= FIRST_EMIT_MS)) ||
                (processed - lastEmitCount >= EMIT_BATCH_SIZE) ||
                (now - lastEmitTime >= EMIT_INTERVAL_MS)
            if (!shouldEmit) return
            emittedFirstBatch = true
            lastEmitTime = now
            lastEmitCount = processed
            mutableSongs.value = filterIgnored(snapshotSongsLocked())
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
            // Never block the workers on JSON encoding: snapshot under the lock, write on a
            // single-flight background coroutine, and skip if one is already running.
            if (!checkpointWriting.compareAndSet(false, true)) return
            lastCheckpointNew = newlyParsed
            val partialEntries = snapshotEntriesLocked()
            if (partialEntries.isEmpty()) {
                checkpointWriting.set(false)
                return
            }
            checkpointJob = indexScope.launch {
                try {
                    writeDisk(context, SavedV2(key, null, partialEntries))
                } finally {
                    checkpointWriting.set(false)
                }
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
                                val dirPath = item.path?.let { File(it).parentFile?.path }
                                if (dirPath != null) {
                                    dirCovers.computeIfAbsent(dirPath) { LocalArtwork.scanDirectoryCovers(File(it)) }
                                } else {
                                    emptyList()
                                }
                            } else {
                                item.sidecarCovers
                            }
                            val coverSig = sidecars.joinToString(",") { it.signature }
                            val prior = priorByUri[item.uri]
                            val sizeBytes = if (item.sizeBytes >= 0L) {
                                item.sizeBytes
                            } else {
                                prior?.sizeBytes ?: item.path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
                            }

                            val entry: CachedEntry? = if (
                                !forceFull &&
                                prior != null &&
                                prior.lastModifiedMs == item.lastModifiedMs &&
                                (item.isMediaStore || prior.sizeBytes == sizeBytes)
                            ) {
                                if (prior.coverSignature == coverSig) {
                                    prior
                                } else {
                                    // Sidecar cover.jpg changed without touching audio file: re-decorate only.
                                    CachedEntry(
                                        song = LocalArtwork.decorateOne(prior.song, sidecars),
                                        lastModifiedMs = item.lastModifiedMs,
                                        sizeBytes = sizeBytes,
                                        coverSignature = coverSig,
                                    )
                                }
                            } else {
                                val parsed = if (item.isMediaStore && item.mediaStoreSong != null) {
                                    val enriched = LocalMediaRepository.enrichMediaStoreSpecs(item.mediaStoreSong, sizeBytes)
                                    LocalArtwork.decorateOne(enriched, sidecars)
                                } else {
                                    newlyParsedCounter.incrementAndGet()
                                    LocalMediaRepository.indexDiscoveredFile(
                                        context = context,
                                        item = item.copy(sizeBytes = sizeBytes),
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
                                        sizeBytes = sizeBytes,
                                        coverSignature = coverSig,
                                    )
                                }
                            }

                            val done = processedCounter.incrementAndGet()
                            val newCount = newlyParsedCounter.get()
                            synchronized(resultsLock) {
                                if (entry != null) {
                                    orderedResults[idx] = entry
                                }
                                emitSnapshotLocked(done)
                                maybeCheckpointLocked(newCount)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            // Keep everything already known (new + not-yet-reprocessed cached entries), but never
            // write after the library was cleared or the folder set changed under us.
            val stillValid = AppSettings.localMusicScanMode.value != LocalMusicScanMode.NOT_CONFIGURED &&
                sourceKey() == key
            if (stillValid) {
                withContext(NonCancellable) {
                    checkpointJob?.join()
                    val partial = synchronized(resultsLock) { snapshotEntriesLocked() }
                    if (partial.isNotEmpty()) {
                        writeDisk(context, SavedV2(key, null, partial))
                        mutableSongs.value = filterIgnored(partial.map { it.song })
                    }
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
        // Show the complete list now, then say what the remaining wait is (writing the index).
        mutableSongs.value = filterIgnored(finalSongs)
        mutableProgress.value = ScanProgress(
            phase = ScanPhase.BUILDING_INDEX,
            processed = total,
            total = total,
            discovered = total,
            revokedFolders = discovery.revokedSafFolders,
        )
        checkpointJob?.join()
        val saved = SavedV2(key, discovery.mediaStoreSignature, finalEntries)
        writeDisk(context, saved)
        lastCompletedScanMs = System.currentTimeMillis()
        mutableProgress.value = ScanProgress(
            phase = ScanPhase.IDLE,
            processed = total,
            total = total,
            discovered = total,
            revokedFolders = discovery.revokedSafFolders,
        )

        // Start low-priority background thumbnail caching for ALL unique song & album covers.
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
