package com.music.bitchord.data

import android.content.Context
import android.database.ContentObserver
import android.provider.MediaStore
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object LocalLibraryIndex {
    @Serializable
    private data class Saved(val source: String, val songs: List<Song>)

    private val lock = Mutex()
    @Volatile private var cached: Saved? = null
    enum class ScanStatus { IDLE, SCANNING, UNAVAILABLE }
    private val mutableScanStatus = MutableStateFlow(ScanStatus.IDLE)
    val scanStatus = mutableScanStatus.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    private fun source() = "${AppSettings.filterNonMusicAudio.value}:${AppSettings.localMusicFolderUri.value}"
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "local-music-index-v1.json"))

    suspend fun get(context: Context): List<Song> = withContext(Dispatchers.IO) {
        cached?.takeIf { it.source == source() }?.let { return@withContext it.songs }
        val savedOnDisk = read(context)
        if (savedOnDisk?.source == source()) return@withContext savedOnDisk.songs
        lock.withLock {
            val key = source()
            val saved = read(context)
            if (saved?.source == key) saved.songs.also { cached = saved } else scan(context, key, saved)
        }
    }

    suspend fun refresh(context: Context): List<Song> = withContext(Dispatchers.IO) {
        lock.withLock { scan(context, source(), read(context)) }
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

    private fun read(context: Context): Saved? = cached ?: runCatching {
        json.decodeFromString<Saved>(file(context).readFully().decodeToString())
    }.getOrNull()

    private suspend fun scan(context: Context, key: String, previous: Saved?): List<Song> {
        mutableScanStatus.value = ScanStatus.SCANNING
        return try {
            reconcile(context, key, previous)
        } finally {
            if (mutableScanStatus.value == ScanStatus.SCANNING) mutableScanStatus.value = ScanStatus.IDLE
        }
    }

    private suspend fun reconcile(context: Context, key: String, previous: Saved?): List<Song> {
        val old = previous?.takeIf { it.source == key }?.songs
        fun unavailable(): List<Song> {
            mutableScanStatus.value = ScanStatus.UNAVAILABLE
            return old.orEmpty()
        }
        if (!LocalMediaRepository.hasStoragePermission(context)) return unavailable()
        val scanned = try {
            LocalMediaRepository.scanLocalMusic(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable()
        }
        if (source() != key) return old.orEmpty()
        // An unmounted source can temporarily return an empty cursor.
        val scannedUris = scanned.mapTo(HashSet()) { it.localUri }
        if (old.orEmpty().any { song ->
                song.localUri !in scannedUris && song.localPath?.let { path ->
                    val volume = path.removePrefix("/storage/").substringBefore('/')
                    path.startsWith("/storage/") && volume !in setOf("emulated", "self") &&
                        !File("/storage/$volume").canRead()
                } == true
            }
        ) return unavailable()
        val dates = previous?.songs.orEmpty().associateBy { it.localUri }
        val now = System.currentTimeMillis() / 1000
        val songs = LocalArtwork.decorate(scanned.map { song ->
            val prior = dates[song.localUri]
            song.copy(localDateAddedSeconds = if (prior != null) prior.localDateAddedSeconds
                else song.localDateAddedSeconds?.takeIf { it > 0 } ?: now.takeIf { old != null })
        })
        val saved = Saved(key, songs)
        if (saved != cached) {
            val atomic = file(context)
            runCatching {
                val stream = atomic.startWrite()
                try {
                    stream.write(json.encodeToString(Saved.serializer(), saved).encodeToByteArray())
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
            }
            cached = saved
        }
        return songs
    }
}
