package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The latest bounded restart snapshot; routine disk writes are ordered and coalesced. */
object LastPlayed {
    class Snapshot(val songs: List<Song>, val index: Int, val positionMs: Long)
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "LastPlayed-writer").apply { isDaemon = true } }
    private val lock = Any()
    private var generation = 0L
    private var latest: Snapshot? = null
    private var loaded = false
    private var pending: Pair<Long, Snapshot>? = null
    private var scheduled = false
    @Volatile private var queueEncoding: Pair<List<Song>, String>? = null

    fun init(context: Context) { prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    internal fun initForTesting(testPrefs: SharedPreferences) {
        writer.submit {}.get()
        synchronized(lock) { generation++; pending = null; prefs = testPrefs; latest = null; loaded = false }
    }
    internal fun awaitWritesForTesting() { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }

    internal fun window(size: Int, index: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val safe = index.coerceIn(0, size - 1)
        return (safe - MAX_QUEUE_HISTORY).coerceAtLeast(0) until (safe + 1 + KEEP_AHEAD).coerceAtMost(size)
    }

    private fun bounded(songs: List<Song>, index: Int, positionMs: Long): Snapshot {
        val safe = index.coerceIn(songs.indices)
        val range = window(songs.size, safe)
        return Snapshot(songs.subList(range.first, range.last + 1).toList(), safe - range.first, positionMs.coerceAtLeast(0))
    }

    private fun encode(snapshot: Snapshot): String {
        queueEncoding?.takeIf { it.first === snapshot.songs }?.let { return it.second }
        val encoded = json.encodeToString(StoredQueue.serializer(), StoredQueue(snapshot.songs.map(StoredTrack::from)))
        queueEncoding = snapshot.songs to encoded
        return encoded
    }

    private fun write(snapshot: Snapshot, encoded: String): SharedPreferences.Editor = prefs.edit()
        .putString(KEY_QUEUE, encoded).putInt(KEY_INDEX, snapshot.index).putLong(KEY_POSITION, snapshot.positionMs)

    private fun enqueue(snapshot: Snapshot) {
        synchronized(lock) {
            latest = snapshot
            loaded = true
            pending = ++generation to snapshot
            if (scheduled) return
            scheduled = true
            writer.execute {
                while (true) {
                    val work = synchronized(lock) {
                        pending?.also { pending = null } ?: run { scheduled = false; return@execute }
                    }
                    // An I/O failure must not escape a background thread and kill playback.
                    runCatching {
                        val encoded = encode(work.second)
                        synchronized(lock) {
                            if (generation == work.first) write(work.second, encoded).apply()
                        }
                    }
                }
            }
        }
    }

    fun saveQueue(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) { clear(); return }
        enqueue(bounded(songs, index, load()?.positionMs ?: 0L))
    }

    fun savePlaybackState(index: Int, positionMs: Long) {
        val current = load() ?: return
        enqueue(Snapshot(current.songs, index.coerceIn(current.songs.indices), positionMs.coerceAtLeast(0)))
    }

    /** A lifecycle boundary supersedes pending saves and reaches disk before returning. */
    fun saveQueueImmediately(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) { clearImmediately(); return }
        val snapshot = bounded(songs, index, positionMs)
        val encoded = encode(snapshot)
        synchronized(lock) {
            generation++; pending = null; latest = snapshot; loaded = true
            write(snapshot, encoded).commit()
        }
    }

    /** Never waits for queued encoding: reads the authoritative live snapshot first. */
    fun load(): Snapshot? = synchronized(lock) {
        if (loaded) return@synchronized latest
        val raw = prefs.getString(KEY_QUEUE, null)
        val stored = raw?.let { runCatching { json.decodeFromString<StoredQueue>(it) }.getOrNull() }
        latest = stored?.takeIf { it.tracks.isNotEmpty() }?.let {
            Snapshot(it.tracks.map(StoredTrack::toSong),
                prefs.getInt(KEY_INDEX, it.legacyIndex ?: 0).coerceIn(it.tracks.indices),
                prefs.getLong(KEY_POSITION, it.legacyPositionMs ?: 0).coerceAtLeast(0))
        }
        loaded = true
        latest
    }

    private fun clear(durable: Boolean) = synchronized(lock) {
        generation++; pending = null; latest = null; loaded = true
        prefs.edit().clear().let { if (durable) it.commit() else it.apply() }
        Unit
    }
    fun clear() = clear(false)
    fun clearImmediately() = clear(true)

    @Serializable
    private data class StoredQueue(
        val tracks: List<StoredTrack>,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val index: Int? = null,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val positionMs: Long? = null,
    ) {
        val legacyIndex: Int? get() = index
        val legacyPositionMs: Long? get() = positionMs
    }

    @Serializable
    private data class StoredTrack(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val auto: Boolean = false,
        val tier: String? = null,
        val entryId: String? = null,
        val local: String? = null,
        val path: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val video: Boolean = false,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
    ) {
        fun toSong(): Song {
            val resolvedTier = when (tier) {
                "USER_QUEUE" -> com.music.bitchord.data.model.QueueTier.USER_QUEUE
                "CONTEXT" -> com.music.bitchord.data.model.QueueTier.CONTEXT
                "AUTOPLAY" -> com.music.bitchord.data.model.QueueTier.AUTOPLAY
                else -> if (auto) com.music.bitchord.data.model.QueueTier.AUTOPLAY else com.music.bitchord.data.model.QueueTier.CONTEXT
            }
            return Song(
                videoId = id,
                title = title,
                artist = artist,
                thumbnailUrl = artwork,
                durationText = duration,
                albumName = album,
                isExplicit = explicit,
                isVideo = video,
                queueTier = resolvedTier,
                queueEntryId = entryId,
                radioName = radio,
                playbackSource = source,
                playbackSourceType = sourceType?.let {
                    runCatching { com.music.bitchord.data.model.PlaybackSourceType.valueOf(it) }.getOrNull()
                },
                playbackSourceId = sourceId,
                localUri = local,
                localPath = path,
            )
        }

        companion object {
            fun from(song: Song) = StoredTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                auto = song.fromAutoplay,
                tier = song.queueTier.name,
                entryId = song.queueEntryId,
                local = song.localUri,
                path = song.localPath,
                duration = song.durationText,
                album = song.albumName,
                explicit = song.isExplicit,
                video = song.isVideo,
                radio = song.radioName,
                source = song.playbackSource,
                sourceType = song.playbackSourceType?.name,
                sourceId = song.playbackSourceId,
            )
        }
    }

    private const val KEEP_AHEAD = 50
    private const val PREFS_NAME = "bitchord_last_played"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
}
