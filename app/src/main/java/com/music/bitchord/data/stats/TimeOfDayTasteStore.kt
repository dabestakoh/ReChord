package com.music.bitchord.data.stats

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.SessionTasteFeedback
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime

/**
 * Lightweight on-device store learning what tracks and artists the listener
 * tends to finish during 4 time-of-day windows (Morning, Afternoon, Evening, Night).
 *
 * Feeds a gentle time-of-day boost into Home Featured (`TopPicks.LocalTaste`)
 * without adding network calls or storing precise timestamps.
 */
object TimeOfDayTasteStore {
    private const val TAG = "TimeOfDayTasteStore"
    private const val FILE_NAME = "time_of_day_taste.json"
    private const val MAX_PER_BUCKET = 60
    private const val SCHEMA = 1

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var appContext: Context? = null

    @Serializable
    private data class StoredEntry(
        val videoId: String,
        val artistKey: String,
    )

    @Serializable
    private data class StoredTaste(
        val schema: Int = SCHEMA,
        val buckets: Map<Int, List<StoredEntry>> = emptyMap(),
    )

    data class WindowTaste(
        val videoIds: Set<String> = emptySet(),
        val artistKeys: Set<String> = emptySet(),
    )

    private var cached: StoredTaste? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun bucketForHour(hour: Int): Int = when (hour) {
        in 5..11 -> 0   // Morning
        in 12..16 -> 1  // Afternoon
        in 17..21 -> 2  // Evening
        else -> 3       // Night
    }

    fun record(song: Song, hour: Int = LocalDateTime.now().hour) {
        val ctx = appContext ?: return
        if (song.videoId.isBlank()) return
        val artistKey = SessionTasteFeedback.primaryArtistKey(song)
        val bucket = bucketForHour(hour)
        synchronized(lock) {
            val current = readLocked(ctx)
            val existing = current.buckets[bucket].orEmpty()
            val updatedList = buildList(minOf(existing.size + 1, MAX_PER_BUCKET)) {
                add(StoredEntry(song.videoId, artistKey))
                for (entry in existing) {
                    if (entry.videoId != song.videoId) add(entry)
                    if (size >= MAX_PER_BUCKET) break
                }
            }
            val next = current.copy(buckets = current.buckets + (bucket to updatedList))
            cached = next
            writeLocked(ctx, next)
        }
    }

    fun forHour(context: Context, hour: Int = LocalDateTime.now().hour): WindowTaste {
        appContext = context.applicationContext
        val bucket = bucketForHour(hour)
        val entries = synchronized(lock) { readLocked(context).buckets[bucket].orEmpty() }
        if (entries.isEmpty()) return WindowTaste()
        return WindowTaste(
            videoIds = entries.mapTo(HashSet()) { it.videoId },
            artistKeys = entries.mapNotNullTo(HashSet()) { it.artistKey.takeIf(String::isNotBlank) },
        )
    }

    private fun readLocked(context: Context): StoredTaste {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val loaded = if (!file.exists()) {
            StoredTaste()
        } else {
            runCatching {
                val parsed = json.decodeFromString<StoredTaste>(AtomicFile(file).readFully().decodeToString())
                if (parsed.schema == SCHEMA) parsed else StoredTaste()
            }.onFailure { Log.w(TAG, "Failed to read time-of-day taste", it) }
                .getOrDefault(StoredTaste())
        }
        cached = loaded
        return loaded
    }

    private fun writeLocked(context: Context, state: StoredTaste) {
        val file = File(context.filesDir, FILE_NAME)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(state).encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            Log.w(TAG, "Failed to save time-of-day taste", t)
        }
    }
}
