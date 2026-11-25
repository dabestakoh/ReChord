package com.music.bitchord.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.util.Log
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object LocalLibraryIndex {

    enum class RefreshReason { AUDIO_CHANGED, IMAGE_CHANGED, SETTINGS_OR_PERMISSION }

    data class MediaChange(val reason: RefreshReason, val uri: Uri?)

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
        val artworkRevisionVersion: Int = 0,
    )

    @Serializable
    private data class SavedV2(
        val source: String,
        val mediaStoreSignature: String? = null,
        val entries: List<CachedEntry> = emptyList(),
        val rejectedFiles: Map<String, FileRevision> = emptyMap(),
    )

    @Serializable
    private data class FileRevision(val modifiedMs: Long, val sizeBytes: Long)

    private val LocalMediaRepository.DiscoveredAudioFile.revision: FileRevision
        get() = FileRevision(lastModifiedMs, sizeBytes)

    @Serializable
    private data class SavedV1(
        val source: String,
        val songs: List<Song>,
    )

    private const val INDEX_V2_NAME = "local-music-index-v2.json"
    private const val INDEX_V1_NAME = "local-music-index-v1.json"
    private const val FIRST_EMIT_COUNT = 10
    private const val FIRST_EMIT_MS = 120L
    private const val PROGRESS_BATCH_SIZE = 25
    private const val FIRST_SCAN_BATCH_SIZE = 150
    private const val FIRST_SCAN_UPDATE_MS = 750L
    private const val EMIT_INTERVAL_MS = 200L
    private const val CHECKPOINT_EVERY_NEW = 500
    private const val AUTO_REFRESH_COOLDOWN_MS = 15_000L
    private const val MEDIA_CHANGE_DEBOUNCE_MS = 500L

    /** Application-scoped coroutine scope so scans survive navigating out of Local Music. */
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexWorkers = Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    )

    private val lock = Mutex()
    private val jobsLock = Any()
    private val artworkLock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: SavedV2? = null
    @Volatile private var pendingSave: SavedV2? = null
    private var saveRetry: Job? = null
    private var automaticRefresh: Job? = null
    private val pendingReasons = mutableMapOf<RefreshReason, Uri?>()
    @Volatile private var activeJob: Job? = null
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val scanGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var cancelledSourceKey: String? = null
    private var prewarmJob: Job? = null
    private var artworkKeys: List<String> = emptyList()
    private var completedArtworkKeys: List<String> = emptyList()
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

    internal fun applyTagOverrides(
        songs: List<Song>,
        overrides: Map<String, com.music.bitchord.data.settings.LocalTagOverride> = AppSettings.localMusicTagOverrides.value,
    ): List<Song> {
        if (overrides.isEmpty()) return songs
        return songs.map { song ->
            val key = song.localUri ?: song.videoId
            val ov = overrides[key] ?: overrides[song.videoId] ?: return@map song
            song.copy(
                title = ov.title?.takeIf { it.isNotBlank() } ?: song.title,
                artist = ov.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                albumName = ov.albumName ?: song.albumName,
                localAlbumArtist = ov.albumArtist ?: song.localAlbumArtist,
                trackNumber = ov.trackNumber ?: song.trackNumber,
                localDiscNumber = ov.discNumber ?: song.localDiscNumber,
            )
        }
    }

    internal fun filterIgnored(songs: List<Song>, ignored: Set<String> = AppSettings.localMusicIgnoredUris.value): List<Song> {
        val visible = if (ignored.isEmpty()) songs else songs.filter { song ->
            val key = song.localUri ?: song.videoId
            key !in ignored && song.videoId !in ignored
        }
        return applyTagOverrides(visible)
    }

    /** Updates a single edited song immediately in memory and disk index without rescanning. */
    suspend fun updateSongInPlace(context: Context, updated: Song, newLastModifiedMs: Long? = null) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val matchKey = updated.localUri ?: updated.videoId
        lock.withLock {
            if (mutableClearing.value) return@withLock
            mutableSongs.value = mutableSongs.value.map { s ->
                if ((s.localUri ?: s.videoId) == matchKey || s.videoId == updated.videoId) updated else s
            }
            val saved = read(appContext) ?: return@withLock
            val nextEntries = saved.entries.map { entry ->
                val k = entry.song.localUri ?: entry.song.videoId
                if (k == matchKey || entry.song.videoId == updated.videoId) {
                    entry.copy(
                        song = updated,
                        lastModifiedMs = newLastModifiedMs ?: entry.lastModifiedMs,
                    )
                } else {
                    entry
                }
            }
            persistIndex(appContext, saved.copy(entries = nextEntries))
        }
    }

    /** Removes permanently deleted songs from memory, disk index, ignore list, and tag overrides. */
    suspend fun removeSongs(context: Context, deletedSongs: Collection<Song>) = withContext(Dispatchers.IO) {
        if (deletedSongs.isEmpty()) return@withContext
        val appContext = context.applicationContext
        val keys = deletedSongs.flatMap { listOfNotNull(it.localUri, it.videoId) }.toSet()
        AppSettings.removeLocalTagOverrides(keys)
        keys.forEach { AppSettings.removeIgnoredLocalSongUri(it) }
        lock.withLock {
            if (mutableClearing.value) return@withLock
            mutableSongs.value = mutableSongs.value.filterNot { s ->
                (s.localUri != null && s.localUri in keys) || s.videoId in keys
            }
            val saved = read(appContext) ?: return@withLock
            val nextEntries = saved.entries.filterNot { entry ->
                (entry.song.localUri != null && entry.song.localUri in keys) || entry.song.videoId in keys
            }
            persistIndex(appContext, saved.copy(entries = nextEntries))
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
            mutableSongs.value = filterIgnored(saved.entries.map { it.song })
        }
    }

    /**
     * Non-blocking cache read safe for all callers (including Android Auto /
     * `PlaybackService` and Search → Library). Returns empty immediately when
     * Local Music has not been configured yet — never triggers an unsolicited scan.
     */
    suspend fun get(context: Context): List<Song> {
        if (mutableClearing.value || AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) {
            return emptyList()
        }
        val live = mutableSongs.value
        if (live.isNotEmpty()) return live

        return withContext(Dispatchers.IO) {
            val secondCheck = mutableSongs.value
            if (secondCheck.isNotEmpty() || activeJob?.isActive == true || mutableClearing.value) {
                return@withContext secondCheck
            }
            val gen = scanGeneration.get()
            val saved = read(context)
            if (saved != null && saved.entries.isNotEmpty()) {
                val loaded = filterIgnored(saved.entries.map { it.song })
                synchronized(jobsLock) {
                    if (scanGeneration.get() == gen && !mutableClearing.value && mutableSongs.value.isEmpty()) {
                        mutableSongs.value = loaded
                    }
                    return@withContext mutableSongs.value
                }
            }
            emptyList()
        }
    }

    /** Coalesce media notifications and preserve requests that arrive during a scan. */
    fun refreshIfConfigured(
        context: Context,
        reason: RefreshReason = RefreshReason.SETTINGS_OR_PERMISSION,
        uri: Uri? = null,
    ) {
        if (AppSettings.localMusicScanMode.value == LocalMusicScanMode.NOT_CONFIGURED) return
        val appContext = context.applicationContext
        synchronized(jobsLock) {
            if (mutableClearing.value || cancelledSourceKey == sourceKey()) return
            if (reason !in pendingReasons) pendingReasons[reason] = uri
            else if (pendingReasons[reason] != uri) pendingReasons[reason] = null
            if (automaticRefresh?.isActive == true) return
            automaticRefresh = indexScope.launch {
                while (true) {
                    delay(MEDIA_CHANGE_DEBOUNCE_MS)
                    val job = synchronized(jobsLock) {
                        val running = activeJob?.takeIf { it.isActive }
                        if (running != null) {
                            running
                        } else {
                            val reasons = pendingReasons.toMap()
                            pendingReasons.clear()
                            if (mutableClearing.value || reasons.isEmpty()) {
                                automaticRefresh = null
                                return@launch
                            }
                            val recentCheck = System.currentTimeMillis() - lastCompletedScanMs < AUTO_REFRESH_COOLDOWN_MS
                            if (reasons.keys == setOf(RefreshReason.SETTINGS_OR_PERMISSION) && recentCheck) null
                            else startScan(appContext, silent = true, reasons = reasons)
                        }
                    }
                    job?.join()
                    synchronized(jobsLock) {
                        if (pendingReasons.isEmpty()) {
                            automaticRefresh = null
                            return@launch
                        }
                    }
                }
            }
        }
    }

    /** Starts (or restarts if [forceFull]) a background scan in the app-scoped coroutine. */
    fun startScan(
        context: Context,
        forceFull: Boolean = false,
        silent: Boolean = false,
        restart: Boolean = false,
        reasons: Map<RefreshReason, Uri?> = emptyMap(),
    ): Job = synchronized(jobsLock) {
        if (mutableClearing.value) return@synchronized Job().apply { complete() }
        val appContext = context.applicationContext
        if (!silent || forceFull || restart) {
            cancelledSourceKey = null
        }
        if (forceFull || restart) {
            scanGeneration.incrementAndGet()
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
            activeJob?.cancel()
            activeJob = null
        } else {
            val current = activeJob
            if (current?.isActive == true) return@synchronized current
        }
        val gen = scanGeneration.incrementAndGet()
        val job = indexScope.launch(start = CoroutineStart.LAZY) {
            lock.withLock {
                if (scanGeneration.get() == gen) {
                    try {
                        runScan(appContext, forceFull = forceFull, silent = silent, gen = gen, reasons = reasons)
                    } finally {
                        if (progress.value.isActive) updateScanProgress(gen, ScanProgress())
                    }
                }
            }
        }
        activeJobs.add(job)
        activeJob = job
        job.invokeOnCompletion { cause ->
            activeJobs.remove(job)
            synchronized(jobsLock) {
                if (activeJob === job) activeJob = null
                if (cause is CancellationException && scanGeneration.get() == gen + 1 &&
                    cancelledSourceKey == sourceKey() && !mutableClearing.value &&
                    mutableSongs.value.isEmpty() && cached?.entries.isNullOrEmpty() && pendingSave?.entries.isNullOrEmpty()
                ) {
                    AppSettings.setLocalMusicScanMode(LocalMusicScanMode.NOT_CONFIGURED)
                }
            }
        }
        job.start()
        job
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
    fun cancelScan() = synchronized(jobsLock) {
        scanGeneration.incrementAndGet()
        cancelledSourceKey = sourceKey()
        pendingReasons.clear()
        automaticRefresh?.cancel()
        automaticRefresh = null
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
        activeJob?.cancel()
        activeJob = null
        synchronized(artworkLock) {
            val oldJob = prewarmJob
            prewarmJob = null
            artworkKeys = emptyList()
            oldJob?.cancel()
            mutableArtworkProgress.value = null
        }
        mutableProgress.value = ScanProgress(phase = ScanPhase.IDLE)
    }

    private val mutableClearing = MutableStateFlow(false)

    /** True while [clearLibrary] is resetting state, so UI can ignore repeat taps. */
    val clearing: StateFlow<Boolean> = mutableClearing.asStateFlow()

    /**
     * Clears the Local Music index, thumbnail cache, persisted SAF URI grants,
     * and scan configuration, resetting back to the first-time opt-in screen.
     * Does NOT delete user audio files or interrupt active playback.
     *
     * Resets the visible state first and serializes disk cleanup with scans.
     */
    suspend fun clearLibrary(context: Context) {
        if (!mutableClearing.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                val appContext = context.applicationContext
                val safUris = (AppSettings.localMusicFolders.value + AppSettings.localMusicExcludedFolders.value)
                    .filter { it.isTreeUri }
                    .map { it.id } + listOfNotNull(AppSettings.localMusicFolderUri.value.takeIf { it.isNotBlank() })
                cancelScan()
                AppSettings.resetLocalMusicConfig()
                mutableSongs.value = emptyList()
                lock.withLock {
                    saveRetry?.cancel()
                    saveRetry = null
                    pendingSave = null
                    cached = null
                    cancelledSourceKey = null
                    lastCompletedScanMs = 0L
                    synchronized(artworkLock) { completedArtworkKeys = emptyList() }
                    fileV2(appContext).delete()
                    fileV1(appContext).delete()
                    LocalArtwork.clearThumbnailCache(appContext)
                    LocalStorageVolumes.releaseSafPermissions(appContext, safUris)
                }
            }
        } finally {
            mutableClearing.value = false
        }
    }

    fun changes(context: Context) = callbackFlow {
        val resolver = context.contentResolver
        fun observer(reason: RefreshReason) = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(MediaChange(reason, null)) }
            override fun onChange(selfChange: Boolean, uri: Uri?) { trySend(MediaChange(reason, uri)) }
        }
        val audio = observer(RefreshReason.AUDIO_CHANGED)
        val images = observer(RefreshReason.IMAGE_CHANGED)
        val files = observer(RefreshReason.IMAGE_CHANGED)
        runCatching { resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, audio) }
        runCatching { resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, images) }
        runCatching { resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, files) }

        val dynamicLock = Any()
        val treeObservers = mutableListOf<ContentObserver>()
        val directoryObservers = mutableListOf<FileObserver>()

        fun stopTreeObservers() = synchronized(dynamicLock) {
            treeObservers.forEach { runCatching { resolver.unregisterContentObserver(it) } }
            treeObservers.clear()
        }

        fun stopDirectoryObservers() = synchronized(dynamicLock) {
            directoryObservers.forEach { runCatching { it.stopWatching() } }
            directoryObservers.clear()
        }

        val treeWatcherJob = launch(Dispatchers.IO) {
            AppSettings.localMusicFolders
                .map { folders -> folders.filter { it.isTreeUri }.map { it.id }.distinct().sorted() }
                .distinctUntilChanged()
                .collect { treeUris ->
                    stopTreeObservers()
                    synchronized(dynamicLock) {
                        treeUris.forEach { rawUri ->
                            val treeObserver = observer(RefreshReason.IMAGE_CHANGED)
                            val registered = runCatching {
                                resolver.registerContentObserver(Uri.parse(rawUri), true, treeObserver)
                                true
                            }.getOrDefault(false)
                            if (registered) treeObservers += treeObserver
                        }
                    }
                }
        }

        val directoryWatcherJob = launch(Dispatchers.IO) {
            songs.map { indexedSongs ->
                indexedSongs.mapNotNull { it.localPath?.let(::File)?.parentFile?.path }
                    .distinct()
                    .sorted()
            }.distinctUntilChanged().collect { directories ->
                stopDirectoryObservers()
                synchronized(dynamicLock) {
                    directories.forEach { directoryPath ->
                        runCatching {
                            object : FileObserver(
                                directoryPath,
                                FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                                    FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.ATTRIB or
                                    FileObserver.DELETE_SELF or FileObserver.MOVE_SELF,
                            ) {
                                override fun onEvent(event: Int, path: String?) {
                                    val baseEvent = event and FileObserver.ALL_EVENTS
                                    val selfEvent = baseEvent == FileObserver.DELETE_SELF ||
                                        baseEvent == FileObserver.MOVE_SELF
                                    if (selfEvent || (path != null && LocalArtwork.isCoverFileName(path))) {
                                        val uri = path?.let { Uri.fromFile(File(directoryPath, it)) }
                                        trySend(MediaChange(RefreshReason.IMAGE_CHANGED, uri))
                                    }
                                }
                            }.also { it.startWatching() }
                        }.getOrNull()?.let { watcher -> directoryObservers += watcher }
                    }
                }
            }
        }
        awaitClose {
            treeWatcherJob.cancel()
            directoryWatcherJob.cancel()
            stopTreeObservers()
            stopDirectoryObservers()
            runCatching { resolver.unregisterContentObserver(audio) }
            runCatching { resolver.unregisterContentObserver(images) }
            runCatching { resolver.unregisterContentObserver(files) }
        }
    }

    private fun read(context: Context): SavedV2? {
        pendingSave?.let { return it }
        cached?.let { return it }
        val now = scanGeneration.get()
        fun rememberRead(saved: SavedV2): SavedV2 = synchronized(jobsLock) {
            if (!mutableClearing.value && scanGeneration.get() == now && cached == null) cached = saved
            pendingSave ?: cached ?: saved
        }
        val v2 = runCatching {
            val raw = fileV2(context).readFully().decodeToString()
            json.decodeFromString(SavedV2.serializer(), raw)
        }.getOrNull()
        if (v2 != null) return rememberRead(v2)
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
                return rememberRead(migrated)
            }
        }
        return null
    }

    private fun writeDisk(context: Context, saved: SavedV2, canCommit: () -> Boolean = { !mutableClearing.value }): Boolean {
        if (!canCommit()) return false
        val atomic = fileV2(context)
        return try {
            val bytes = json.encodeToString(SavedV2.serializer(), saved).encodeToByteArray()
            if (!canCommit()) return false
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                synchronized(jobsLock) {
                    if (!canCommit()) {
                        atomic.failWrite(stream)
                        return false
                    }
                    atomic.finishWrite(stream)
                    cached = saved
                }
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
            true
        } catch (e: Exception) {
            Log.w("LocalLibraryIndex", "Could not save Local Music index", e)
            false
        }
    }

    /** Called while holding [lock]. Keep the prepared view and retry the write without rescanning. */
    private fun persistIndex(context: Context, saved: SavedV2, canCommit: () -> Boolean = { !mutableClearing.value }) {
        if (!canCommit()) return
        saveRetry?.cancel()
        pendingSave = saved
        if (writeDisk(context, saved, canCommit)) {
            pendingSave = null
            return
        }
        saveRetry = indexScope.launch {
            for (waitMs in listOf(1_000L, 5_000L, 20_000L)) {
                delay(waitMs)
                val stop = lock.withLock {
                    if (pendingSave !== saved || mutableClearing.value || sourceKey() != saved.source) return@withLock true
                    val written = writeDisk(context, saved) {
                        pendingSave === saved && !mutableClearing.value && sourceKey() == saved.source
                    }
                    if (written) pendingSave = null
                    written
                }
                if (stop) break
            }
        }
    }

    private fun warmArtwork(context: Context, songs: List<Song>, gen: Long) {
        val keys = LocalArtwork.warmKeys(songs)
        if (keys.isEmpty()) return
        synchronized(artworkLock) {
            if (scanGeneration.get() != gen || mutableClearing.value) return
            if (keys == completedArtworkKeys || (keys == artworkKeys && prewarmJob?.isActive == true)) return
            val oldJob = prewarmJob
            val newJob = indexScope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    val complete = LocalArtwork.prewarmThumbnails(context, keys) { done, totalArt ->
                        synchronized(artworkLock) {
                            if (prewarmJob === owner) {
                                mutableArtworkProgress.value = if (done < totalArt) done to totalArt else null
                            }
                        }
                    }
                    synchronized(artworkLock) {
                        if (complete && prewarmJob === owner) completedArtworkKeys = keys
                    }
                } finally {
                    synchronized(artworkLock) {
                        if (prewarmJob === owner) {
                            prewarmJob = null
                            mutableArtworkProgress.value = null
                        }
                    }
                }
            }
            artworkKeys = keys
            prewarmJob = newJob
            oldJob?.cancel()
            mutableArtworkProgress.value = null
            newJob.start()
        }
    }

    private suspend fun runScan(
        context: Context, forceFull: Boolean, silent: Boolean, gen: Long, reasons: Map<RefreshReason, Uri?>,
    ) {
        fun isCancelledNow(): Boolean = scanGeneration.get() != gen || mutableClearing.value
        fun ensureGeneration() {
            if (isCancelledNow()) throw CancellationException()
        }
        ensureGeneration()
        val imageUri = reasons[RefreshReason.IMAGE_CHANGED]
        if (reasons.keys == setOf(RefreshReason.IMAGE_CHANGED) && imageUri != null &&
            imageUri.lastPathSegment?.toLongOrNull() != null
        ) {
            // Deleted rows or inaccessible metadata require a conservative cover check.
            val name = runCatching {
                context.contentResolver.query(imageUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
            if (name != null && !LocalArtwork.isCoverFileName(name)) return
        }
        val mode = AppSettings.localMusicScanMode.value
        if (mode == LocalMusicScanMode.NOT_CONFIGURED) {
            updateScanProgress(gen, ScanProgress())
            return
        }

        val key = sourceKey()
        val previous = read(context)
        val sameSource = previous != null && previous.source == key
        val oldEntries = if (sameSource) previous!!.entries else emptyList()
        val isSilentCheck = silent && !forceFull

        // Seed live list immediately from cache so returning users never see a blank screen.
        // When the folder set was edited, keep every cached song still in scope on screen and
        // drop the songs of removed folders instantly instead of blanking the list.
        if (sameSource) {
            if (mutableSongs.value.isEmpty() && oldEntries.isNotEmpty() && !isCancelledNow()) {
                publishSongs(gen, filterIgnored(oldEntries.map { it.song }))
            }
        } else if (previous != null) {
            val folders = AppSettings.localMusicFolders.value
            val excluded = AppSettings.localMusicExcludedFolders.value
            val kept = previous.entries.map { it.song }.filter {
                LocalMediaRepository.isSongInScope(it, mode, folders, excluded)
            }
            val visibleKept = filterIgnored(kept)
            if ((visibleKept.isNotEmpty() || mutableSongs.value.isNotEmpty()) && !isCancelledNow()) {
                publishSongs(gen, visibleKept)
            }
        }

        val useLegacy = LocalStorageVolumes.shouldUseLegacyScan(context, AppSettings.localLegacyScanMode.value)
        val hasStoragePerm = LocalMediaRepository.hasStoragePermission(context)
        val hasSafFolders = mode == LocalMusicScanMode.SPECIFIC_FOLDERS &&
            AppSettings.localMusicFolders.value.any { it.isTreeUri }

        if (!hasStoragePerm && !hasSafFolders && !useLegacy) {
            updateScanProgress(gen, ScanProgress(phase = ScanPhase.UNAVAILABLE))
            return
        }

        // Fast-path: pure MediaStore ALL_FOLDERS with unchanged COUNT/MAX(date_modified)/MAX(_id).
        if (isSilentCheck && reasons.keys == setOf(RefreshReason.SETTINGS_OR_PERMISSION) &&
            sameSource && mode == LocalMusicScanMode.ALL_FOLDERS && !useLegacy &&
            AppSettings.localMusicExcludedFolders.value.isEmpty()
        ) {
            val currentSig = LocalMediaRepository.mediaStoreSignature(context)
            ensureGeneration()
            if (currentSig != null && currentSig == previous?.mediaStoreSignature && oldEntries.isNotEmpty()) {
                lastCompletedScanMs = System.currentTimeMillis()
                return
            }
        }

        ensureGeneration()
        if (!isSilentCheck) {
            updateScanProgress(gen, ScanProgress(phase = ScanPhase.DISCOVERING, discovered = 0))
        }

        val discovery = try {
            LocalMediaRepository.discoverLocalMusic(
                context = context,
                isCancelled = ::isCancelledNow,
            ) { count ->
                ensureGeneration()
                if (!isSilentCheck && !isCancelledNow()) {
                    updateScanProgress(gen, ScanProgress(phase = ScanPhase.DISCOVERING, discovered = count))
                }
            }
        } catch (cancelled: CancellationException) {
            updateScanProgress(gen, ScanProgress())
            throw cancelled
        } catch (_: Exception) {
            if (!isSilentCheck && !isCancelledNow()) {
                updateScanProgress(gen, ScanProgress(phase = ScanPhase.UNAVAILABLE))
            }
            return
        }

        ensureGeneration()
        if (sourceKey() != key) {
            updateScanProgress(gen, ScanProgress())
            return
        }

        // Unmount protection: if a previously indexed external volume/folder is currently
        // unreadable, keep cached tracks and report UNAVAILABLE instead of wiping the library.
        val discoveredUris = discovery.files.mapTo(HashSet(discovery.files.size)) { it.uri }
        val unmountedPreviouslyIndexed = discovery.unavailableVolumes || oldEntries.any { entry ->
            entry.song.localUri !in discoveredUris && isPathOnUnmountedExternalVolume(entry.song.localPath)
        }
        if (unmountedPreviouslyIndexed && discovery.files.size < oldEntries.size) {
            if (!isCancelledNow()) {
                updateScanProgress(gen, ScanProgress(
                    phase = ScanPhase.UNAVAILABLE,
                    revokedFolders = discovery.revokedSafFolders,
                ))
            }
            return
        }

        val total = discovery.files.size
        if (total == 0) {
            ensureGeneration()
            val emptySaved = SavedV2(key, discovery.mediaStoreSignature, emptyList())
            if (emptySaved != previous || pendingSave != null) persistIndex(context, emptySaved) { !isCancelledNow() }
            publishSongs(gen, emptyList())
            updateScanProgress(gen, ScanProgress(revokedFolders = discovery.revokedSafFolders))
            lastCompletedScanMs = System.currentTimeMillis()
            return
        }

        val priorByUri = previous?.entries.orEmpty().associateBy { it.song.localUri }

        val previouslyRejected = if (sameSource) previous?.rejectedFiles.orEmpty() else emptyMap()
        val rejected = ConcurrentHashMap<String, FileRevision>()
        val newUris = if (isSilentCheck) discovery.files.asSequence()
            .filter { it.uri !in priorByUri && previouslyRejected[it.uri] != it.revision }
            .mapTo(HashSet()) { it.uri }
        else emptySet()
        val checkExistingCovers = !isSilentCheck || RefreshReason.AUDIO_CHANGED !in reasons ||
            RefreshReason.IMAGE_CHANGED in reasons || RefreshReason.SETTINGS_OR_PERMISSION in reasons
        val dirCovers = ConcurrentHashMap<String, List<LocalArtwork.SidecarCover>>()
        fun sidecarsFor(item: LocalMediaRepository.DiscoveredAudioFile): List<LocalArtwork.SidecarCover> {
            if (!item.isMediaStore) return item.sidecarCovers
            val path = item.path?.let { File(it).parentFile?.path } ?: return emptyList()
            return dirCovers.computeIfAbsent(path) { LocalArtwork.scanDirectoryCovers(File(it)) }
        }

        if (isSilentCheck && RefreshReason.AUDIO_CHANGED !in reasons && sameSource &&
            total == oldEntries.size + previouslyRejected.size &&
            discovery.files.all { item ->
                ensureGeneration()
                val prior = priorByUri[item.uri]
                if (prior == null) previouslyRejected[item.uri] == item.revision
                else prior.lastModifiedMs == item.lastModifiedMs &&
                    prior.artworkRevisionVersion == LocalArtwork.REVISION_VERSION &&
                    (item.isMediaStore || prior.sizeBytes == item.sizeBytes) &&
                    (!checkExistingCovers || prior.coverSignature == sidecarsFor(item).joinToString(",") { it.signature })
            }
        ) {
            val unchanged = checkNotNull(previous).copy(mediaStoreSignature = discovery.mediaStoreSignature)
            if (unchanged != previous || pendingSave != null) persistIndex(context, unchanged) { !isCancelledNow() }
            lastCompletedScanMs = System.currentTimeMillis()
            updateScanProgress(gen, ScanProgress(revokedFolders = discovery.revokedSafFolders))
            return
        }

        var showIndexProgress = !isSilentCheck

        if (showIndexProgress) {
            updateScanProgress(gen, ScanProgress(
                phase = ScanPhase.INDEXING,
                total = total,
                discovered = total,
                revokedFolders = discovery.revokedSafFolders,
            ))
        }

        val firstLibraryScan = mutableSongs.value.isEmpty()
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val minDurationMs = if (filterNonMusic) AppSettings.localMusicMinDurationSec.value * 1_000L else 0L
        val nowSec = System.currentTimeMillis() / 1_000L

        val processedCounter = AtomicInteger(0)
        val newlyParsedCounter = AtomicInteger(0)
        val resultsLock = Any()
        val orderedResults = arrayOfNulls<CachedEntry>(total)
        // A canceled scan can still keep parsed tracks and their previous cached entries.
        val priorEntryAt = Array(total) { priorByUri[discovery.files[it].uri] }
        val scanScope = CoroutineScope(currentCoroutineContext())
        val checkpointWriting = AtomicBoolean(false)
        var checkpointJob: Job? = null
        var lastEmitTime = android.os.SystemClock.elapsedRealtime()
        var lastEmitCount = 0
        var lastBrowseUpdateTime = lastEmitTime
        var lastBrowseUpdateCount = 0
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

        fun emitProgressLocked(processed: Int) {
            if (isCancelledNow() || !showIndexProgress) return
            val now = android.os.SystemClock.elapsedRealtime()
            val first = lastEmitCount == 0 &&
                (processed >= FIRST_EMIT_COUNT || now - lastEmitTime >= FIRST_EMIT_MS)
            if (first || processed - lastEmitCount >= PROGRESS_BATCH_SIZE || now - lastEmitTime >= EMIT_INTERVAL_MS) {
                lastEmitTime = now
                lastEmitCount = processed
                updateScanProgress(gen, ScanProgress(
                    phase = ScanPhase.INDEXING,
                    processed = processed,
                    total = total,
                    discovered = total,
                    revokedFolders = discovery.revokedSafFolders,
                ))
            }
            // A rescan keeps the established view until the complete result is ready.
            // Only the first scan needs progressive results to become browsable early.
            if (firstLibraryScan && (
                    (!emittedFirstBatch && (first || lastEmitCount > 0)) ||
                        processed - lastBrowseUpdateCount >= FIRST_SCAN_BATCH_SIZE ||
                        now - lastBrowseUpdateTime >= FIRST_SCAN_UPDATE_MS
                    )
            ) {
                if (isCancelledNow()) return
                val songs = filterIgnored(snapshotSongsLocked())
                emittedFirstBatch = true
                lastBrowseUpdateTime = now
                lastBrowseUpdateCount = processed
                if (songs.isNotEmpty()) publishSongs(gen, songs)
            }
        }

        fun maybeCheckpointLocked(newlyParsed: Int) {
            if (isCancelledNow()) return
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
            checkpointJob = scanScope.launch {
                try {
                    writeDisk(context, SavedV2(key, null, partialEntries, rejected.toMap())) { !isCancelledNow() }
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
                            ensureGeneration()
                            coroutineContext.ensureActive()
                            val idx = nextIndex.getAndIncrement()
                            if (idx >= total) break
                            val item = discovery.files[idx]
                            val prior = priorByUri[item.uri]
                            val sizeBytes = if (item.sizeBytes >= 0L) {
                                item.sizeBytes
                            } else {
                                item.path?.let { path ->
                                    runCatching { File(path).length() }.getOrDefault(0L).takeIf { it > 0L }
                                } ?: prior?.sizeBytes ?: 0L
                            }
                            val revisionUnchanged = prior != null &&
                                prior.lastModifiedMs == item.lastModifiedMs &&
                                prior.sizeBytes == sizeBytes
                            val reuseCover = !checkExistingCovers && revisionUnchanged &&
                                prior?.artworkRevisionVersion == LocalArtwork.REVISION_VERSION
                            val sidecars = if (reuseCover) emptyList() else sidecarsFor(item)
                            val coverSig = if (reuseCover) checkNotNull(prior).coverSignature
                                else sidecars.joinToString(",") { it.signature }
                            var confidentlyRejected = false

                            val entry: CachedEntry? = if (prior == null && !forceFull &&
                                previouslyRejected[item.uri] == item.revision
                            ) {
                                confidentlyRejected = true
                                null
                            } else if (
                                !forceFull &&
                                prior != null &&
                                revisionUnchanged
                            ) {
                                if (prior.coverSignature == coverSig &&
                                    prior.artworkRevisionVersion == LocalArtwork.REVISION_VERSION
                                ) {
                                    prior
                                } else {
                                    // Sidecar cover.jpg changed, or an older index needs the stronger artwork key.
                                    CachedEntry(
                                        song = LocalArtwork.decorateOne(
                                            song = checkNotNull(prior).song,
                                            sidecars = sidecars,
                                            mediaRevision = LocalArtwork.FileRevisionToken(item.lastModifiedMs, sizeBytes),
                                        ),
                                        lastModifiedMs = item.lastModifiedMs,
                                        sizeBytes = sizeBytes,
                                        coverSignature = coverSig,
                                        artworkRevisionVersion = LocalArtwork.REVISION_VERSION,
                                    )
                                }
                            } else {
                                val parsed = if (item.isMediaStore && item.mediaStoreSong != null) {
                                    val enriched = LocalMediaRepository.enrichMediaStoreSpecs(item.mediaStoreSong, sizeBytes)
                                    LocalArtwork.decorateOne(
                                        song = enriched,
                                        sidecars = sidecars,
                                        mediaRevision = LocalArtwork.FileRevisionToken(item.lastModifiedMs, sizeBytes),
                                    )
                                } else {
                                    newlyParsedCounter.incrementAndGet()
                                    LocalMediaRepository.indexDiscoveredFile(
                                        context = context,
                                        item = item.copy(sizeBytes = sizeBytes),
                                        videoIdByUri = videoIdByUri,
                                        filterNonMusic = filterNonMusic,
                                        minDurationMs = minDurationMs,
                                        onRejected = { confidentlyRejected = true },
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
                                        artworkRevisionVersion = LocalArtwork.REVISION_VERSION,
                                    )
                                }
                            }

                            ensureGeneration()
                            val newCount = newlyParsedCounter.get()
                            synchronized(resultsLock) {
                                if (entry != null) {
                                    orderedResults[idx] = entry
                                    if (item.uri in newUris) showIndexProgress = true
                                } else if (confidentlyRejected) {
                                    rejected[item.uri] = item.revision
                                } else if (prior != null) {
                                    // An inaccessible file can recover on a later refresh.
                                    orderedResults[idx] = prior
                                }
                                emitProgressLocked(processedCounter.incrementAndGet())
                                maybeCheckpointLocked(newCount)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            val cancelGeneration = gen + 1
            fun canKeepPartial(): Boolean = !mutableClearing.value &&
                scanGeneration.get() == cancelGeneration && cancelledSourceKey == key && sourceKey() == key
            withContext(NonCancellable) {
                checkpointJob?.join()
                if (canKeepPartial()) {
                    val partial = synchronized(resultsLock) { snapshotEntriesLocked() }
                    if (partial.isNotEmpty()) {
                        persistIndex(context, SavedV2(key, null, partial, rejected.toMap()), ::canKeepPartial)
                        if (canKeepPartial()) publishSongs(cancelGeneration, filterIgnored(partial.map { it.song }))
                    }
                }
            }
            throw cancelled
        }

        ensureGeneration()
        if (sourceKey() != key) {
            updateScanProgress(gen, ScanProgress())
            return
        }

        val finalEntries = orderedResults.filterNotNull()
        val finalSongs = filterIgnored(finalEntries.map { it.song })
        val saved = SavedV2(key, discovery.mediaStoreSignature, finalEntries, rejected.toMap())
        checkpointJob?.join()
        ensureGeneration()
        val changedCovers = if (!sameSource) finalSongs else finalSongs.filter { song ->
            val prior = priorByUri[song.localUri]?.song
            song.thumbnailUrl != prior?.thumbnailUrl || song.localAlbumArtwork != prior?.localAlbumArtwork
        }
        if (saved != previous || pendingSave != null || checkpointJob != null) {
            publishSongs(gen, finalSongs)
            if (showIndexProgress) {
                updateScanProgress(gen, ScanProgress(
                    phase = ScanPhase.BUILDING_INDEX,
                    processed = total,
                    total = total,
                    discovered = total,
                    revokedFolders = discovery.revokedSafFolders,
                ))
            }
            persistIndex(context, saved) { !isCancelledNow() && sourceKey() == key }
        }
        lastCompletedScanMs = System.currentTimeMillis()
        updateScanProgress(gen, ScanProgress(revokedFolders = discovery.revokedSafFolders))
        if (changedCovers.isNotEmpty()) warmArtwork(context, changedCovers, gen)
    }

    private fun publishSongs(gen: Long, songs: List<Song>) = synchronized(jobsLock) {
        if (scanGeneration.get() == gen && !mutableClearing.value) mutableSongs.value = songs
    }

    private fun updateScanProgress(gen: Long, next: ScanProgress) = synchronized(jobsLock) {
        if (scanGeneration.get() == gen && !mutableClearing.value) mutableProgress.value = next
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
