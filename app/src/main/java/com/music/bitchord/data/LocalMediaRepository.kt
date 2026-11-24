package com.music.bitchord.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.music.bitchord.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalMusicScanMode
import com.music.bitchord.data.settings.LocalScanFolder
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.coroutines.coroutineContext

object LocalMediaRepository {

    private const val TAG = "BitChord"
    internal const val DEFAULT_MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    // `mp4`/`webm` are allowed for audio-only containers (Dolby AC-4, E-AC-3,
    // ALAC, Opus), and direct filesystem/SAF scans explicitly verify that no
    // video track is present before indexing them.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "wav", "aiff", "aif", "wv", "ape",
        "ogg", "opus", "aac", "webm", "dsf", "dff",
    )

    /** Extensions that can carry a video track and must be verified on direct/SAF scans. */
    internal val possibleVideoExtensions = setOf("mp4", "webm")

    /** Always excluded regardless of [AppSettings.filterNonMusicAudio] so videos never enter Local Music. */
    private val videoPathSegments = listOf(
        "/dcim/",
        "/movies/",
        "/camera/",
        "/screenrecordings/",
        "/screen recordings/",
        "/video/",
        "/videos/",
        "/whatsapp video/",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/call recordings/",
        "/whatsapp voice notes/",
        "/miui/sound_recorder/",
        "/samsung/voice recorder/",
    )

    internal val skippedSystemDirs = setOf(
        "android",
        "lost.dir",
        "\$recycle.bin",
        "system volume information",
        ".trash",
        ".trashes",
        ".thumbnails",
        ".cache",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/ReChord` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%", "%BitChord%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                listOf(DownloadStore.FOLDER, "BitChord").forEach { folderName ->
                    val folder = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MUSIC,
                        ),
                        folderName,
                    )
                    if (folder.exists() && folder.isDirectory) {
                        folder.listFiles()?.forEach { file ->
                            if (file.isFile && isAudioFileName(file.name)) {
                                val uriStr = Uri.fromFile(file).toString()
                                if (uriStr !in knownUris) {
                                    val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                    extraSongs.add(
                                        buildSongFromUri(
                                            context, uriStr, file.name,
                                            ScannedTags(null, null, modified, modified),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/ReChord directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * A way for a build to look beyond MediaStore — the Android TV app reads USB
     * drives MediaStore does not index. Handed what MediaStore found (nothing,
     * without the storage permission) and returns the list to use. Null on the
     * phone, where MediaStore is the whole story.
     */
    @Volatile
    var extraLocalMusic: (suspend (Context, List<Song>) -> List<Song>)? = null

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (extraLocalMusic != null) return@withContext scanLocalMusic(context)
        LocalLibraryIndex.get(context)
    }

    internal data class DiscoveredAudioFile(
        val uri: String,
        val path: String?,
        val displayName: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
        val isMediaStore: Boolean,
        val mediaStoreSong: Song? = null,
        val sidecarCovers: List<LocalArtwork.SidecarCover> = emptyList(),
    )

    internal data class DiscoveryResult(
        val files: List<DiscoveredAudioFile>,
        val mediaStoreSignature: String?,
        val revokedSafFolders: List<String>,
        val unavailableVolumes: Boolean,
    )

    /** Lightweight `< 5ms` MediaStore fingerprint to skip unchanged full cursor reads. */
    internal fun mediaStoreSignature(context: Context): String? {
        if (!hasStoragePermission(context)) return null
        return runCatching {
            val projection = arrayOf(
                "COUNT(${MediaStore.Audio.Media._ID})",
                "MAX(${MediaStore.Audio.Media.DATE_MODIFIED})",
                "MAX(${MediaStore.Audio.Media._ID})",
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) "${c.getLong(0)}:${c.getLong(1)}:${c.getLong(2)}" else "0:0:0"
            }
        }.getOrNull()
    }

    /**
     * Discovers all candidate audio files across MediaStore, persisted SAF
     * folder trees, and (when auto-detected or enabled) direct legacy/external
     * volumes. Emits live discovery counts via [onProgress] without reading
     * per-file tags or running slow per-file stat() calls yet.
     */
    internal suspend fun discoverLocalMusic(
        context: Context,
        onProgress: (discoveredCount: Int) -> Unit = {},
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val mode = AppSettings.localMusicScanMode.value
        if (mode == LocalMusicScanMode.NOT_CONFIGURED) {
            return@withContext DiscoveryResult(emptyList(), null, emptyList(), false)
        }
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val minDurationMs = if (filterNonMusic) AppSettings.localMusicMinDurationSec.value * 1_000L else 0L
        val selectedFolders = normalizeScanFolders(AppSettings.localMusicFolders.value)
        val excludedFolders = AppSettings.localMusicExcludedFolders.value
        val useLegacy = LocalStorageVolumes.shouldUseLegacyScan(context, AppSettings.localLegacyScanMode.value)
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }

        val discovered = ArrayList<DiscoveredAudioFile>(512)
        val seenCanonicalPaths = HashSet<String>(1024)
        val seenUris = HashSet<String>(1024)
        val revokedSaf = mutableListOf<String>()
        var unavailableVolumes = false
        var lastReported = 0
        var lastReportMs = 0L

        fun reportIfNeeded() {
            val now = System.currentTimeMillis()
            if (discovered.size == 1 ||
                discovered.size - lastReported >= 25 ||
                now - lastReportMs >= 150L
            ) {
                lastReported = discovered.size
                lastReportMs = now
                onProgress(discovered.size)
            }
        }

        fun addDiscovered(item: DiscoveredAudioFile) {
            val canonicalKey = item.path?.let(::canonicalKey)
            if (canonicalKey != null && !seenCanonicalPaths.add(canonicalKey)) return
            if (!seenUris.add(item.uri)) return
            discovered.add(item)
            reportIfNeeded()
        }

        val sig = mediaStoreSignature(context)

        // 1. MediaStore query (fast SQLite scan, streamed row-by-row with live counts).
        if (hasStoragePermission(context)) {
            queryMediaStore(
                context = context,
                filterNonMusic = filterNonMusic,
                minDurationMs = minDurationMs,
                mode = mode,
                selectedFolders = selectedFolders,
                excludedFolders = excludedFolders,
                videoIdByUri = videoIdByUri,
            ) { song ->
                addDiscovered(
                    DiscoveredAudioFile(
                        uri = song.localUri ?: song.videoId,
                        path = song.localPath,
                        displayName = song.localPath?.substringAfterLast('/') ?: song.title,
                        lastModifiedMs = (song.localDateModifiedSeconds ?: 0L) * 1_000L,
                        sizeBytes = -1L, // Deferred to parallel indexing workers.
                        isMediaStore = true,
                        mediaStoreSong = song,
                    ),
                )
            }
        }

        // 2. Direct traversal for SPECIFIC_FOLDERS (both SAF trees & direct File paths)
        //    so external SSDs, SD cards, nested FLAC/MQA/DSD folders, and .nomedia folders
        //    skipped by MediaStore are always found.
        if (mode == LocalMusicScanMode.SPECIFIC_FOLDERS) {
            for (folder in selectedFolders) {
                coroutineContext.ensureActive()
                if (folder.isTreeUri) {
                    // Prefer fast native File traversal whenever the SAF tree resolves to a
                    // readable local path (50x faster than DocumentsContract IPC).
                    val directPath = runCatching {
                        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(folder.id))
                        treeDocumentIdToPath(docId)
                    }.getOrNull()
                    val directDir = directPath?.let(::File)?.takeIf { it.isDirectory && it.canRead() && it.list() != null }
                    if (directDir != null) {
                        walkFileFolder(
                            root = directDir,
                            includeSubfolders = folder.includeSubfolders,
                            excludedFolders = excludedFolders,
                            filterNonMusic = filterNonMusic,
                            onFile = ::addDiscovered,
                        )
                    } else {
                        if (!LocalStorageVolumes.hasPersistedSafPermission(context, folder.id)) {
                            revokedSaf += folder.label
                            continue
                        }
                        walkSafFolder(
                            context = context,
                            folder = folder,
                            excludedFolders = excludedFolders,
                            filterNonMusic = filterNonMusic,
                            onFile = ::addDiscovered,
                        )
                    }
                } else {
                    val dir = File(folder.id)
                    if (!dir.exists() || !dir.canRead()) {
                        unavailableVolumes = true
                        continue
                    }
                    walkFileFolder(
                        root = dir,
                        includeSubfolders = folder.includeSubfolders,
                        excludedFolders = excludedFolders,
                        filterNonMusic = filterNonMusic,
                        onFile = ::addDiscovered,
                    )
                }
            }
        } else if (mode == LocalMusicScanMode.ALL_FOLDERS && useLegacy) {
            // 3. Direct legacy/external volume scan across ALL readable volumes (primary +
            //    external SD/USB SSDs), deduplicated against MediaStore via canonical paths
            //    so .dsf/.dff/.wv/.ape/.nomedia and unindexed folders are never missed.
            val volumes = LocalStorageVolumes.detectVolumes(context).filter { it.isReadable }
            for (vol in volumes) {
                coroutineContext.ensureActive()
                walkFileFolder(
                    root = File(vol.canonicalPath),
                    includeSubfolders = true,
                    excludedFolders = excludedFolders,
                    filterNonMusic = filterNonMusic,
                    onFile = ::addDiscovered,
                )
            }
        }

        if (discovered.size != lastReported) onProgress(discovered.size)
        DiscoveryResult(
            files = discovered,
            mediaStoreSignature = sig,
            revokedSafFolders = revokedSaf,
            unavailableVolumes = unavailableVolumes,
        )
    }

    /**
     * Parses metadata for one non-MediaStore [DiscoveredAudioFile] using
     * [FastAudioTagReader] first, falling back to [MediaMetadataRetriever].
     * Returns null if the file is a video or fails duration/non-music gates.
     */
    internal fun indexDiscoveredFile(
        context: Context,
        item: DiscoveredAudioFile,
        videoIdByUri: Map<String, String>,
        filterNonMusic: Boolean,
        minDurationMs: Long,
    ): Song? {
        item.mediaStoreSong?.let { return it }

        val ext = item.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mustVerifyNoVideo = ext in possibleVideoExtensions

        var fastTags: FastAudioTagReader.FastTags? = null
        if (!mustVerifyNoVideo) {
            fastTags = if (item.uri.startsWith("file://") && item.path != null) {
                FastAudioTagReader.readFile(File(item.path))
            } else {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { stream ->
                        FastAudioTagReader.readStream(stream, ext, item.sizeBytes)
                    }
                }.getOrNull()
            }
        }

        var title = fastTags?.title
        var artist = fastTags?.artist
        var albumName = fastTags?.album
        var albumArtist = fastTags?.albumArtist
        var durationMs = fastTags?.durationMs ?: 0L
        var trackNumber = fastTags?.trackNumber
        var discNumber = fastTags?.discNumber
        var hasVideo = false

        // Fall back to MediaMetadataRetriever for containers FastAudioTagReader
        // doesn't parse (M4A/MP4/WV/APE/WebM) or whenever video verification is needed.
        val needsFallback = mustVerifyNoVideo ||
            fastTags == null ||
            (title == null && artist == null && albumName == null && durationMs <= 0L)

        if (needsFallback) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (item.uri.startsWith("file://") && item.path != null) {
                        retriever.setDataSource(item.path)
                    } else {
                        retriever.setDataSource(context, Uri.parse(item.uri))
                    }
                    if (mustVerifyNoVideo) {
                        val videoFlag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                        val audioFlag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        if (videoFlag != null || audioFlag == null) {
                            hasVideo = true
                        }
                    }
                    if (!hasVideo) {
                        title = title ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).cleanTag()
                        artist = artist ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).cleanTag()
                        albumName = albumName ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).cleanTag()
                        albumArtist = albumArtist ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).cleanTag()
                        if (durationMs <= 0L) {
                            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                        trackNumber = trackNumber ?: trackNumberFromTag(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        )
                        discNumber = discNumber ?: trackNumberFromTag(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                        )
                    }
                } finally {
                    retriever.release()
                }
            }
        }

        if (hasVideo) return null
        if (!isEligibleLocalMusic(
                durationMs = durationMs,
                displayName = item.displayName,
                path = item.path,
                minDurationMs = minDurationMs,
                filterNonMusic = filterNonMusic,
                hasVideo = false,
            )
        ) return null

        val resolvedTitle = title?.removeAudioExtension()
            ?: item.displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
            ?: item.displayName
        val modSec = item.lastModifiedMs.takeIf { it > 0L }?.div(1_000L)

        val sampleRateHz = fastTags?.sampleRateHz
        val bitsPerSample = fastTags?.bitsPerSample
        val channels = fastTags?.channels
        val formatLabel = ext.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        val hiRes = FastAudioTagReader.isHiResAudio(ext, sampleRateHz, bitsPerSample)

        val baseSong = Song(
            videoId = videoIdByUri[item.uri] ?: item.uri,
            title = resolvedTitle,
            artist = artist ?: "Unknown Artist",
            thumbnailUrl = null,
            durationText = durationMs.takeIf { it > 0L }?.let(::formatDuration),
            albumName = albumName,
            localUri = item.uri,
            localPath = item.path,
            localDateAddedSeconds = modSec,
            localDateModifiedSeconds = modSec,
            trackNumber = trackNumber,
            sourceHiRes = hiRes,
            localAlbumArtist = albumArtist,
            localDiscNumber = discNumber,
            localSampleRateHz = sampleRateHz,
            localBitsPerSample = bitsPerSample,
            localChannels = channels,
            localFormat = formatLabel,
            localFileSizeBytes = item.sizeBytes.takeIf { it > 0L },
        )
        return LocalArtwork.decorateOne(baseSong, item.sidecarCovers)
    }

    /**
     * Reads fast binary header specs (~0.1 ms) for MediaStore lossless files so
     * they carry `localSampleRateHz`, `localBitsPerSample`, `localChannels`,
     * `localFormat`, `localFileSizeBytes`, and `sourceHiRes` without slowing indexing.
     */
    internal fun enrichMediaStoreSpecs(song: Song, sizeBytes: Long): Song {
        val ext = song.localPath?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val formatLabel = ext.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        val file = song.localPath?.let(::File)?.takeIf { it.canRead() }
        val fast = if (file != null && ext in setOf("flac", "wav", "aiff", "aif", "dsf")) {
            FastAudioTagReader.readFile(file)
        } else {
            null
        }
        val sr = fast?.sampleRateHz ?: song.localSampleRateHz
        val bps = fast?.bitsPerSample ?: song.localBitsPerSample
        val ch = fast?.channels ?: song.localChannels
        val hiRes = FastAudioTagReader.isHiResAudio(ext, sr, bps)
        return song.copy(
            sourceHiRes = hiRes,
            localSampleRateHz = sr,
            localBitsPerSample = bps,
            localChannels = ch,
            localFormat = formatLabel ?: song.localFormat,
            localFileSizeBytes = sizeBytes.takeIf { it > 0L } ?: song.localFileSizeBytes,
        )
    }

    private fun queryMediaStore(
        context: Context,
        filterNonMusic: Boolean,
        minDurationMs: Long,
        mode: LocalMusicScanMode,
        selectedFolders: List<LocalScanFolder>,
        excludedFolders: List<LocalScanFolder>,
        videoIdByUri: Map<String, String>,
        onSong: (Song) -> Unit,
    ) {
        // Deliberately omit `IS_MUSIC != 0`: Android's MediaScanner frequently marks
        // FLAC, WAV, AIFF, Opus, DSD, and untagged files as IS_MUSIC = 0. Our own
        // extension, alarm/ringtone/notification/podcast, video, recorder, and
        // duration gates filter non-music accurately without dropping real songs.
        val selection = if (filterNonMusic) {
            buildString {
                append("${MediaStore.Audio.Media.IS_ALARM} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                }
                if (minDurationMs > 0L) {
                    append(
                        " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                            " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                            " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                    )
                }
            }
        } else {
            null
        }
        val selectionArgs = if (filterNonMusic && minDurationMs > 0L) {
            arrayOf(minDurationMs.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            if (Build.VERSION.SDK_INT >= 30) AUDIO_PROJECTION else AUDIO_PROJECTION.dropLast(1).toTypedArray(),
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val columns = AudioColumns(cursor)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(columns.displayName).orEmpty()
                val durationMs = cursor.getLong(columns.duration)
                val path = cursor.getString(columns.data)

                if (!isEligibleLocalMusic(
                        durationMs = durationMs,
                        displayName = displayName,
                        path = path,
                        minDurationMs = minDurationMs,
                        filterNonMusic = filterNonMusic,
                        hasVideo = false,
                    )
                ) continue
                if (mode == LocalMusicScanMode.SPECIFIC_FOLDERS &&
                    selectedFolders.none { isPathInFolder(path, it) }
                ) continue
                if (isPathExcluded(path, excludedFolders)) continue

                onSong(columns.song(cursor, videoIdByUri))
            }
        }
    }

    private suspend fun walkFileFolder(
        root: File,
        includeSubfolders: Boolean,
        excludedFolders: List<LocalScanFolder>,
        filterNonMusic: Boolean,
        onFile: (DiscoveredAudioFile) -> Unit,
    ) {
        val stack = ArrayDeque<Pair<File, Int>>()
        val visitedDirs = HashSet<String>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, depth) = stack.removeLast()
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!visitedDirs.add(canonical)) continue
            if (isPathExcluded(canonical, excludedFolders)) continue
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue

            val sidecars = entries.asSequence()
                .filter { it.isFile && LocalArtwork.isCoverFileName(it.name) }
                .sortedWith(
                    compareBy<File> { LocalArtwork.coverSortOrder(it.name) }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )
                .map { file ->
                    LocalArtwork.SidecarCover(
                        source = "file:${file.path}",
                        signature = "${file.name}:${file.lastModified()}:${file.length()}",
                    )
                }
                .toList()

            for (entry in entries) {
                val name = entry.name
                if (name.startsWith(".")) continue
                if (entry.isDirectory) {
                    if (!includeSubfolders || depth >= 16) continue
                    val lower = name.lowercase(Locale.ROOT)
                    if (lower in skippedSystemDirs) continue
                    val childPath = "${entry.path.replace('\\', '/').lowercase(Locale.ROOT)}/"
                    if (videoPathSegments.any(childPath::contains)) continue
                    if (filterNonMusic && nonMusicPathSegments.any(childPath::contains)) continue
                    stack.addLast(entry to depth + 1)
                } else if (entry.isFile) {
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in localMusicExtensions) continue
                    val filePath = entry.path
                    if (!isPathAllowed(filePath, filterNonMusic)) continue
                    onFile(
                        DiscoveredAudioFile(
                            uri = Uri.fromFile(entry).toString(),
                            path = filePath,
                            displayName = name,
                            lastModifiedMs = entry.lastModified(),
                            sizeBytes = entry.length(),
                            isMediaStore = false,
                            sidecarCovers = sidecars,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun walkSafFolder(
        context: Context,
        folder: LocalScanFolder,
        excludedFolders: List<LocalScanFolder>,
        filterNonMusic: Boolean,
        onFile: (DiscoveredAudioFile) -> Unit,
    ) {
        val treeUri = runCatching { Uri.parse(folder.id) }.getOrNull() ?: return
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val rootVirtualPath = treeDocumentIdToPath(rootDocId) ?: "/saf/${folder.label}"
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val stack = ArrayDeque<Triple<String, String, Int>>()
        stack.addLast(Triple(rootDocId, rootVirtualPath, 0))

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (docId, dirPath, depth) = stack.removeLast()
            if (isPathExcluded(dirPath, excludedFolders)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val subdirs = mutableListOf<Triple<String, String, Int>>()
            val audioDocs = mutableListOf<DiscoveredAudioFile>()
            val coverDocs = mutableListOf<Pair<String, LocalArtwork.SidecarCover>>()

            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    while (c.moveToNext()) {
                        val childId = if (idCol >= 0) c.getString(idCol) else null
                        val name = if (nameCol >= 0) c.getString(nameCol) else null
                        val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                        val mod = if (modCol >= 0) c.getLong(modCol) else 0L
                        val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        if (childId.isNullOrBlank() || name.isNullOrBlank() || name.startsWith(".")) continue
                        val childPath = "$dirPath/$name"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!folder.includeSubfolders || depth >= 16) continue
                            if (name.lowercase(Locale.ROOT) in skippedSystemDirs) continue
                            val lowerPath = "${childPath.lowercase(Locale.ROOT)}/"
                            if (videoPathSegments.any(lowerPath::contains)) continue
                            if (filterNonMusic && nonMusicPathSegments.any(lowerPath::contains)) continue
                            subdirs.add(Triple(childId, childPath, depth + 1))
                        } else if (LocalArtwork.isCoverFileName(name)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString()
                            coverDocs.add(
                                name to LocalArtwork.SidecarCover(
                                    source = docUri,
                                    signature = "$name:$mod:$size",
                                ),
                            )
                        } else {
                            if (mime?.startsWith("video/", ignoreCase = true) == true) continue
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext !in localMusicExtensions) continue
                            if (!isPathAllowed(childPath, filterNonMusic)) continue
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString()
                            audioDocs.add(
                                DiscoveredAudioFile(
                                    uri = docUri,
                                    path = childPath,
                                    displayName = name,
                                    lastModifiedMs = mod,
                                    sizeBytes = size,
                                    isMediaStore = false,
                                ),
                            )
                        }
                    }
                }
            }

            val sortedCovers = coverDocs
                .sortedWith(
                    compareBy<Pair<String, LocalArtwork.SidecarCover>> { LocalArtwork.coverSortOrder(it.first) }
                        .thenBy { it.first.lowercase(Locale.ROOT) },
                )
                .map { it.second }

            for (doc in audioDocs) {
                onFile(doc.copy(sidecarCovers = sortedCovers))
            }
            for (sub in subdirs) {
                stack.addLast(sub)
            }
        }
    }

    internal suspend fun scanLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            return@withContext extraLocalMusic?.invoke(context, emptyList()) ?: emptyList()
        }
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val minDurationMs = if (filterNonMusic) AppSettings.localMusicMinDurationSec.value * 1_000L else 0L
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val songs = mutableListOf<Song>()
        queryMediaStore(
            context = context,
            filterNonMusic = filterNonMusic,
            minDurationMs = minDurationMs,
            mode = AppSettings.localMusicScanMode.value.takeUnless { it == LocalMusicScanMode.NOT_CONFIGURED }
                ?: LocalMusicScanMode.ALL_FOLDERS,
            selectedFolders = AppSettings.localMusicFolders.value,
            excludedFolders = AppSettings.localMusicExcludedFolders.value,
            videoIdByUri = videoIdByUri,
            onSong = songs::add,
        )
        extraLocalMusic?.invoke(context, songs) ?: songs
    }

    private fun canonicalKey(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path).normalizedPath()

    private val AUDIO_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.TRACK,
        "album_artist",
    )

    /**
     * Where [AUDIO_PROJECTION]'s columns landed in one cursor, looked up once
     * rather than per row — and the one reading of a row into a [Song], so a
     * file opened from a file manager comes out exactly as the same file does
     * in Local Music. That sameness is what lets a like or a playlist entry
     * made from either be found from the other.
     */
    private class AudioColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
        val albumArtist = cursor.getColumnIndex("album_artist")

        fun song(cursor: Cursor, videoIdByUri: Map<String, String>): Song {
            val rowId = cursor.getLong(id)
            val name = cursor.getString(displayName).orEmpty()
            val artId = cursor.getLong(albumId)
            val durationMs = cursor.getLong(duration)
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rowId).toString()
            val songTitle = cursor.getString(title).cleanTag()?.removeAudioExtension()
                ?: name.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?: "Track $rowId"
            val trackNumber = if (track >= 0 && !cursor.isNull(track)) {
                trackNumberFromMediaStore(cursor.getInt(track))
            } else {
                null
            }
            return Song(
                videoId = videoIdByUri[contentUri] ?: contentUri,
                title = songTitle,
                artist = cursor.getString(artist).cleanTag() ?: "Unknown Artist",
                thumbnailUrl = if (artId > 0) ContentUris.withAppendedId(ALBUM_ART_URI, artId).toString() else null,
                durationText = durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
                albumName = cursor.getString(album).cleanTag(),
                localUri = contentUri,
                localPath = cursor.getString(data),
                localDateAddedSeconds = cursor.getLong(dateAdded),
                localDateModifiedSeconds = cursor.getLong(dateModified),
                trackNumber = trackNumber,
                localAlbumArtist = albumArtist.takeIf { it >= 0 }?.let { cursor.getString(it).cleanTag() },
                localDiscNumber = track.takeIf { it >= 0 }?.let { cursor.getInt(it) / 1000 }?.takeIf { it > 0 },
            )
        }
    }

    // Lazy so loading this object doesn't touch the framework: unit tests of
    // the pure helpers here run against a stub Uri.parse that returns null.
    private val ALBUM_ART_URI: Uri by lazy { Uri.parse("content://media/external/audio/albumart") }

    /**
     * Which of [songs] still have a file behind them, in their order — for an
     * offline playlist, whose record outlives a file deleted from under it.
     *
     * One MediaStore query for every row it indexes, by id, rather than a
     * listing of the whole device; a `file://` row is asked of the disk. Rows
     * this can't check — another provider's, or every row while the storage
     * permission is missing — are kept: hiding a track because it couldn't be
     * looked at would empty a playlist over a permission prompt.
     */
    suspend fun present(context: Context, songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val mediaIds = songs.mapNotNull { it.localUri?.mediaStoreAudioId() }
        val existing = HashSet<Long>()
        val canAsk = mediaIds.isNotEmpty() && runCatching {
            mediaIds.distinct().chunked(500).forEach { chunk ->
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) existing += cursor.getLong(0)
                }
            }
        }.isSuccess
        // MediaStore only answers for files this app may see. Without the
        // permission its silence is not an answer, except for this app's own
        // downloads, which it always sees.
        val trustMissing = canAsk && hasStoragePermission(context)
        songs.filter { song ->
            val uri = song.localUri ?: return@filter false
            val id = uri.mediaStoreAudioId()
            when {
                id != null -> id in existing || !trustMissing
                uri.startsWith("file://") -> Uri.parse(uri).path?.let { File(it).exists() } != false
                uri.startsWith("content://") -> isSafDocumentPresent(context, uri)
                else -> true
            }
        }
    }

    private fun isSafDocumentPresent(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return true
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrElse { err ->
            // SecurityException means grant was revoked or drive unmounted; don't silently wipe offline playlist.
            err is SecurityException
        }
    }

    /** The row id of a `content://media/…/audio/media/<id>` uri, else null. */
    private fun String.mediaStoreAudioId(): Long? {
        if (!startsWith("content://media/")) return null
        val uri = Uri.parse(this)
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[segments.size - 2] != "media" || "audio" !in segments) return null
        return segments.last().toLongOrNull()
    }

    /**
     * A file another app handed over to be played — "Open with" from a file
     * manager, a tap on an attachment.
     *
     * Read as Local Music would read it whenever MediaStore indexes it, so a
     * heart or a playlist entry made from here is the same entry the Local
     * Music folder shows; otherwise off the file's own tags, under the name
     * the sending app gave it.
     */
    suspend fun songFromUri(context: Context, uri: Uri): Song = withContext(Dispatchers.IO) {
        mediaStoreRow(context, uri)?.let { return@withContext it }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        buildSongFromUri(context, uri.toString(), name)
    }

    /** The MediaStore row behind [uri], when there is one this app may read. */
    private fun mediaStoreRow(context: Context, uri: Uri): Song? = runCatching {
        val resolver = context.contentResolver
        val (target, selection, args) = when {
            uri.scheme == "file" -> {
                val path = uri.path ?: return@runCatching null
                Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path))
            }
            uri.authority == MediaStore.AUTHORITY -> Triple(uri, null, null)
            // A documents-provider uri (most file managers) names the same
            // file MediaStore does under another id; API 29 can translate.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DocumentsContract.isDocumentUri(context, uri) ->
                Triple(MediaStore.getMediaUri(context, uri) ?: return@runCatching null, null, null)
            else -> return@runCatching null
        }
        resolver.query(target, if (Build.VERSION.SDK_INT >= 30) AUDIO_PROJECTION else AUDIO_PROJECTION.dropLast(1).toTypedArray(), selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val videoIdByUri = Downloads.saved.value.entries.associate { (id, saved) -> saved to id }
            AudioColumns(cursor).song(cursor, videoIdByUri)
        }
    }.getOrNull()

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String): String? = selectedFolder(treeUri)?.label

    internal fun isInSelectedFolder(path: String?, treeUri: String): Boolean {
        if (treeUri.isBlank()) return true
        return isInFolder(path, selectedFolder(treeUri), includeSubfolders = true)
    }

    /**
     * Removes redundant child folders when a parent folder with
     * [LocalScanFolder.includeSubfolders] enabled already covers them.
     */
    internal fun normalizeScanFolders(folders: List<LocalScanFolder>): List<LocalScanFolder> {
        val distinct = folders.distinctBy { "${it.isTreeUri}:${it.id.normalizedPath()}" }
        return distinct.filter { candidate ->
            val candidatePath = folderComparablePath(candidate) ?: return@filter true
            distinct.none { other ->
                if (other === candidate || !other.includeSubfolders) return@none false
                val otherPath = folderComparablePath(other) ?: return@none false
                otherPath != candidatePath && candidatePath.startsWith("$otherPath/")
            }
        }
    }

    internal fun isPathInFolder(path: String?, folder: LocalScanFolder): Boolean {
        val candidate = path?.normalizedPath() ?: return false
        val root = folderComparablePath(folder) ?: return false
        return matchesFolderPath(candidate, root, folder.includeSubfolders)
    }

    internal fun isPathExcluded(path: String?, excludedFolders: List<LocalScanFolder>): Boolean {
        if (excludedFolders.isEmpty() || path.isNullOrBlank()) return false
        val candidate = path.normalizedPath()
        return excludedFolders.any { folder ->
            val root = folderComparablePath(folder) ?: return@any false
            matchesFolderPath(candidate, root, folder.includeSubfolders)
        }
    }

    /**
     * True when an already-indexed [song] still belongs to the given scan scope.
     * Used to instantly drop songs of removed folders (and keep the rest on screen)
     * before the verification scan finishes. A song also covered by another selected
     * folder stays.
     */
    internal fun isSongInScope(
        song: Song,
        mode: LocalMusicScanMode,
        folders: List<LocalScanFolder>,
        excluded: List<LocalScanFolder>,
    ): Boolean {
        if (isPathExcluded(song.localPath, excluded)) return false
        return when (mode) {
            LocalMusicScanMode.ALL_FOLDERS -> true
            LocalMusicScanMode.SPECIFIC_FOLDERS -> folders.any { folder ->
                isPathInFolder(song.localPath, folder) ||
                    (folder.isTreeUri && song.localUri?.startsWith(folder.id) == true)
            }
            else -> false
        }
    }


    internal fun matchesFolderPath(candidateNormalized: String, rootNormalized: String, includeSubfolders: Boolean): Boolean {
        if (candidateNormalized == rootNormalized) return true
        if (!candidateNormalized.startsWith("$rootNormalized/")) return false
        if (includeSubfolders) return true
        val remainder = candidateNormalized.substring(rootNormalized.length + 1)
        return '/' !in remainder
    }

    private fun folderComparablePath(folder: LocalScanFolder): String? =
        if (folder.isTreeUri) selectedFolder(folder.id)?.absolutePath
        else folder.id.normalizedPath()

    private fun isInFolder(path: String?, folder: SelectedFolder?, includeSubfolders: Boolean): Boolean {
        if (folder == null) return false
        val candidate = path?.normalizedPath() ?: return false
        return matchesFolderPath(candidate, folder.absolutePath, includeSubfolders)
    }

    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val path = treeDocumentIdToPath(documentId) ?: return@runCatching null
        val relative = documentId.substringAfter(':', "").trim('/')
        val volume = documentId.substringBefore(':')
        val label = when {
            documentId.startsWith("raw:") -> path.substringAfterLast('/').ifBlank { path }
            relative.isNotBlank() -> relative
            else -> volume
        }
        SelectedFolder(
            absolutePath = path.normalizedPath(),
            label = label,
        )
    }.getOrNull()

    internal fun treeDocumentIdToPath(documentId: String): String? {
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:").replace('\\', '/').trimEnd('/')
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        if (volume.isBlank()) return null
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        return listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/")
    }

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    private fun isPathAllowed(path: String?, filterNonMusic: Boolean): Boolean {
        val normalizedPath = path?.replace('\\', '/')?.lowercase(Locale.ROOT) ?: return true
        if (videoPathSegments.any(normalizedPath::contains)) return false
        if (filterNonMusic && nonMusicPathSegments.any(normalizedPath::contains)) return false
        return true
    }

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     *
     * Video files ([hasVideo] or video directory paths) are ALWAYS rejected even
     * when [filterNonMusic] is false.
     */
    internal fun isEligibleLocalMusic(
        durationMs: Long,
        displayName: String,
        path: String?,
        minDurationMs: Long = DEFAULT_MIN_LOCAL_MUSIC_DURATION_MS,
        filterNonMusic: Boolean = true,
        hasVideo: Boolean = false,
    ): Boolean {
        if (hasVideo) return false
        if (filterNonMusic && minDurationMs > 0L && durationMs in 1 until minDurationMs) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        return isPathAllowed(path, filterNonMusic)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var trackNumber: Int? = null
        var albumArtist: String? = null
        var discNumber: Int? = null
        var embeddedArt: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriStr))
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val metaTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)

                if (!metaTitle.isNullOrBlank()) title = metaTitle
                if (!metaArtist.isNullOrBlank()) artist = metaArtist
                albumName = metaAlbum.cleanTag()
                if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
                trackNumber = trackNumberFromTag(metaTrack)
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).cleanTag()
                discNumber = trackNumberFromTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
                if (scanned?.artworkUrl == null) {
                    embeddedArt = cachedEmbeddedArt(
                        java.io.File(context.cacheDir, EMBEDDED_ART_DIR),
                        uriStr,
                        retriever.embeddedPicture,
                    )
                }
            } finally {
                retriever.release()
            }
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl ?: embeddedArt,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
            trackNumber = trackNumber,
            localAlbumArtist = albumArtist,
            localDiscNumber = discNumber,
        )
    }

    internal fun cachedEmbeddedArt(dir: java.io.File, key: String, picture: ByteArray?): String? {
        if (picture == null || picture.isEmpty()) return null
        return runCatching {
            dir.mkdirs()
            val file = java.io.File(dir, "${key.hashCode().toUInt()}-${picture.size}.img")
            if (!file.exists()) file.writeBytes(picture)
            "file://${file.absolutePath}"
        }.getOrNull()
    }

    private const val EMBEDDED_ART_DIR = "embedded_art"

    /**
     * MediaStore packs the disc into the same int (`disc * 1000 + track`), so
     * 1003 is disc 1 track 3 rather than track 1003. Unpacks it; null when the
     * scanner wrote nothing usable.
     */
    internal fun trackNumberFromMediaStore(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        val track = if (raw >= 1000) raw % 1000 else raw
        return track.takeIf { it in 1..999 }
    }

    /**
     * `CD_TRACK_NUMBER` arrives as `"3"` or `"3/12"`. Takes the head; null when
     * it isn't a number.
     */
    internal fun trackNumberFromTag(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
