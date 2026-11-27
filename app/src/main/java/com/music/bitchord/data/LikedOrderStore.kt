package com.music.bitchord.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlist.applyLikedCustomOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable per-account local custom order for Liked Music (`VLLM`).
 *
 * YouTube Music's `edit_playlist` API does not allow reordering the server-managed
 * Liked Music auto-playlist. Keeping the user's custom `videoId` order here (in
 * `filesDir`, separate from `cacheDir`) ensures:
 * - Android cache eviction never erases the user's manual Liked Music order.
 * - `PlaylistSnapshotCache`'s `VLLM` snapshot stays in YouTube's natural order so
 *   its sub-second Page-1 head-merge fast path continues to work at full speed.
 * - Resetting to default order is instant and works offline.
 */
object LikedOrderStore {
    private const val TAG = "LikedOrderStore"
    private const val DIR_NAME = "liked_order"
    private const val SCHEMA = 1

    private val lock = Any()
    // Empty list sentinel means "checked disk, no custom order saved".
    private val memory = ConcurrentHashMap<String, List<String>>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class StoredLikedOrder(
        val schema: Int = SCHEMA,
        val videoIds: List<String> = emptyList(),
    )

    fun read(context: Context, listener: String): List<String>? {
        memory[listener]?.let { return it.takeIf(List<String>::isNotEmpty) }
        return synchronized(lock) {
            memory[listener]?.let { return@synchronized it.takeIf(List<String>::isNotEmpty) }
            val file = fileFor(context, listener)
            if (!file.exists()) {
                memory[listener] = emptyList()
                return@synchronized null
            }
            val parsed = runCatching {
                val bytes = AtomicFile(file).readFully()
                val stored = json.decodeFromString<StoredLikedOrder>(bytes.decodeToString())
                if (stored.schema == SCHEMA) stored.videoIds.filter(String::isNotBlank) else emptyList()
            }.onFailure {
                Log.w(TAG, "Failed to read liked order", it)
            }.getOrDefault(emptyList())
            memory[listener] = parsed
            parsed.takeIf(List<String>::isNotEmpty)
        }
    }

    fun hasCustomOrder(context: Context, listener: String): Boolean =
        !read(context, listener).isNullOrEmpty()

    fun save(context: Context, listener: String, videoIds: List<String>) {
        val cleaned = videoIds.asSequence().filter(String::isNotBlank).distinct().toList()
        synchronized(lock) {
            if (cleaned.isEmpty()) {
                clearLocked(context, listener)
                return
            }
            val file = fileFor(context, listener)
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                val payload = json.encodeToString(StoredLikedOrder(SCHEMA, cleaned))
                stream.write(payload.encodeToByteArray())
                atomic.finishWrite(stream)
                memory[listener] = cleaned
            } catch (t: Throwable) {
                atomic.failWrite(stream)
                Log.w(TAG, "Failed to save liked order", t)
            }
        }
    }

    fun clear(context: Context, listener: String) {
        synchronized(lock) {
            clearLocked(context, listener)
        }
    }

    fun apply(context: Context, listener: String, songs: List<Song>): List<Song> {
        val custom = read(context, listener) ?: return songs
        return applyLikedCustomOrder(songs, custom)
    }

    private fun clearLocked(context: Context, listener: String) {
        memory[listener] = emptyList()
        val file = fileFor(context, listener)
        if (file.exists()) {
            runCatching { AtomicFile(file).delete() }
                .onFailure { Log.w(TAG, "Failed to delete liked order", it) }
        }
    }

    private fun fileFor(context: Context, listener: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(listener.encodeToByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIR_NAME), "$hex.json")
    }
}
