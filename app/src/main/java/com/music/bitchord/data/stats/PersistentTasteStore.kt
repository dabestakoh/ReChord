package com.music.bitchord.data.stats

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.music.bitchord.playback.SessionTasteFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow

/**
 * Durable per-account/profile taste memory (`filesDir/taste_memory/<sha256(listener)>.json`).
 *
 * Persists artist affinities, cooled artists, dismissed Featured seeds, and
 * skipped/disliked videoIds across restarts with natural 14-day half-life decay
 * so ReChord learns from positive and negative actions over time without
 * leaking signals across accounts or channels.
 */
object PersistentTasteStore {
    private const val TAG = "PersistentTasteStore"
    private const val DIR_NAME = "taste_memory"
    private const val SCHEMA = 1
    private const val HALF_LIFE_MS = 14.0 * 24 * 60 * 60 * 1000
    private const val MAX_ARTISTS = 200
    private const val MAX_DISMISSED = 100
    private const val MAX_SKIPPED = 150
    private const val SAVE_DEBOUNCE_MS = 1_500L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeListener: String? = null

    private var saveJob: Job? = null

    @Serializable
    private data class StoredTasteMemory(
        val schema: Int = SCHEMA,
        val updatedAtMs: Long = 0L,
        val skippedVideoIds: List<String> = emptyList(),
        val dismissedSeedKeys: List<String> = emptyList(),
        val artistAffinities: Map<String, Double> = emptyMap(),
    )

    fun init(context: Context, initialListener: String? = null) {
        appContext = context.applicationContext
        SessionTasteFeedback.setOnChangedListener { scheduleSave() }
        if (initialListener != null) switchListener(initialListener)
    }

    fun switchListener(listener: String?) {
        val ctx = appContext ?: return
        val normalized = listener?.takeIf(String::isNotBlank) ?: "signed-out"
        synchronized(lock) {
            if (activeListener == normalized) return
            // Flush previous listener before switching so no signal is lost.
            activeListener?.let { prev -> writeLocked(ctx, prev, buildPayloadLocked(System.currentTimeMillis())) }
            activeListener = normalized
            val stored = readLocked(ctx, normalized)
            val now = System.currentTimeMillis()
            val ageMs = (now - stored.updatedAtMs).coerceAtLeast(0L)
            val decay = if (stored.updatedAtMs > 0L) 0.5.pow(ageMs / HALF_LIFE_MS) else 1.0
            val decayedAffinities = stored.artistAffinities
                .mapValues { (_, score) -> score * decay }
                .filterValues { abs(it) >= 0.15 }
            val cooled = decayedAffinities
                .filterValues { it <= SessionTasteFeedback.ARTIST_COOLDOWN_SCORE }
                .keys
                .toList()
            SessionTasteFeedback.hydrate(
                SessionTasteFeedback.Snapshot(
                    skippedVideoIds = stored.skippedVideoIds,
                    cooledArtists = cooled,
                    dismissedSeedKeys = stored.dismissedSeedKeys,
                    artistAffinities = decayedAffinities,
                ),
            )
        }
    }

    private fun scheduleSave() {
        val ctx = appContext ?: return
        val listener = activeListener ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            synchronized(lock) {
                if (activeListener != listener) return@synchronized
                writeLocked(ctx, listener, buildPayloadLocked(System.currentTimeMillis()))
            }
        }
    }

    private fun buildPayloadLocked(now: Long): StoredTasteMemory {
        val snap = SessionTasteFeedback.snapshotForPersistence()
        val boundedArtists = snap.artistAffinities.entries
            .sortedByDescending { abs(it.value) }
            .take(MAX_ARTISTS)
            .associate { it.key to it.value }
        return StoredTasteMemory(
            schema = SCHEMA,
            updatedAtMs = now,
            skippedVideoIds = snap.skippedVideoIds.takeLast(MAX_SKIPPED),
            dismissedSeedKeys = snap.dismissedSeedKeys.takeLast(MAX_DISMISSED),
            artistAffinities = boundedArtists,
        )
    }

    private fun readLocked(context: Context, listener: String): StoredTasteMemory {
        val file = fileFor(context, listener)
        if (!file.exists()) return StoredTasteMemory()
        return runCatching {
            val parsed = json.decodeFromString<StoredTasteMemory>(AtomicFile(file).readFully().decodeToString())
            if (parsed.schema == SCHEMA) parsed else StoredTasteMemory()
        }.onFailure { Log.w(TAG, "Failed to read taste memory", it) }
            .getOrDefault(StoredTasteMemory())
    }

    private fun writeLocked(context: Context, listener: String, state: StoredTasteMemory) {
        val file = fileFor(context, listener)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(state).encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            Log.w(TAG, "Failed to save taste memory", t)
        }
    }

    private fun fileFor(context: Context, listener: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(listener.encodeToByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIR_NAME), "$hex.json")
    }
}
