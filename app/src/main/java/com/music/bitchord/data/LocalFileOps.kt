package com.music.bitchord.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import com.music.bitchord.data.model.Song
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local file management operations (All-Files access check, MediaStore URI lookup,
 * and permanent deletion from device storage with scoped-storage fallback).
 */
object LocalFileOps {

    sealed class DeleteResult {
        data class Deleted(val songs: List<Song>) : DeleteResult()
        data class NeedsSystemConfirm(val pendingIntent: PendingIntent, val songs: List<Song>) : DeleteResult()
        data class Failed(val message: String) : DeleteResult()
    }

    /** True if the app can directly modify/delete local files without per-action system prompts. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            true
        }

    fun allFilesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** Resolves a MediaStore `content://media/external/audio/media/<id>` URI for [song] when available. */
    fun resolveMediaStoreUri(context: Context, song: Song): Uri? {
        val raw = song.localUri
        if (!raw.isNullOrBlank() && raw.startsWith("content://media/")) {
            return runCatching { Uri.parse(raw) }.getOrNull()
        }
        val path = song.localPath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        }.getOrNull()
    }

    fun createWriteRequestIntent(context: Context, uri: Uri): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        }.getOrNull()
    }

    /** Total size in bytes for displaying in the delete confirmation dialog. */
    fun estimateTotalBytes(songs: List<Song>): Long = songs.sumOf { song ->
        song.localFileSizeBytes?.takeIf { it > 0L }
            ?: song.localPath?.let(::File)?.takeIf { it.exists() }?.length()
            ?: 0L
    }

    /**
     * Permanently deletes [songs] from device storage.
     * - If All-Files access is granted (or API <= 29), deletes directly and cleans up MediaStore.
     * - Otherwise on API 30+, returns a single [PendingIntent] from `MediaStore.createDeleteRequest`
     *   so the user approves the entire batch in one system prompt.
     * - Also supports SAF document URIs via `DocumentsContract.deleteDocument`.
     */
    suspend fun deleteFromDevice(context: Context, songs: List<Song>): DeleteResult = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext DeleteResult.Deleted(emptyList())
        val appContext = context.applicationContext

        if (hasAllFilesAccess()) {
            val deleted = ArrayList<Song>(songs.size)
            for (song in songs) {
                if (deleteSingleDirectly(appContext, song)) {
                    deleted.add(song)
                }
            }
            if (deleted.isNotEmpty()) {
                cleanupAfterDelete(appContext, deleted)
                return@withContext DeleteResult.Deleted(deleted)
            }
        }

        // Scoped storage (API 30+ without All-Files access): try SAF first, else batch MediaStore.createDeleteRequest
        val safSongs = songs.filter { it.localUri?.startsWith("content://com.android.externalstorage") == true }
        val deletedSaf = ArrayList<Song>()
        for (song in safSongs) {
            val ok = runCatching {
                DocumentsContract.deleteDocument(appContext.contentResolver, Uri.parse(song.localUri!!))
            }.getOrDefault(false)
            if (ok) deletedSaf.add(song)
        }
        val remaining = songs - deletedSaf.toSet()
        if (remaining.isEmpty()) {
            cleanupAfterDelete(appContext, deletedSaf)
            return@withContext DeleteResult.Deleted(deletedSaf)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaUris = remaining.mapNotNull { resolveMediaStoreUri(appContext, it) }
            if (mediaUris.isNotEmpty()) {
                if (deletedSaf.isNotEmpty()) cleanupAfterDelete(appContext, deletedSaf)
                val pi = runCatching {
                    MediaStore.createDeleteRequest(appContext.contentResolver, mediaUris)
                }.getOrNull()
                if (pi != null) {
                    return@withContext DeleteResult.NeedsSystemConfirm(pi, remaining)
                }
            }
        }

        if (deletedSaf.isNotEmpty()) {
            cleanupAfterDelete(appContext, deletedSaf)
            return@withContext DeleteResult.Deleted(deletedSaf)
        }
        DeleteResult.Failed("Permission denied")
    }

    /** Called after the user approves the system `createDeleteRequest` dialog. */
    suspend fun finishDeleteAfterSystemConfirm(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        cleanupAfterDelete(context.applicationContext, songs)
    }

    private fun deleteSingleDirectly(context: Context, song: Song): Boolean {
        var removed = false
        val file = song.localPath?.let(::File)
        if (file != null && file.exists()) {
            removed = runCatching { file.delete() }.getOrDefault(false)
        }
        val mediaUri = resolveMediaStoreUri(context, song)
        if (mediaUri != null) {
            val rows = runCatching { context.contentResolver.delete(mediaUri, null, null) }.getOrDefault(0)
            if (rows > 0) removed = true
        }
        if (!removed && song.localUri?.startsWith("content://") == true) {
            removed = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(song.localUri!!))
            }.getOrDefault(false)
        }
        return removed
    }

    private suspend fun cleanupAfterDelete(context: Context, deleted: List<Song>) {
        LocalLibraryIndex.removeSongs(context, deleted)
        // Also remove deleted tracks from offline playlists & Liked songs
        val playlists = OfflinePlaylists.playlists.value
        for (playlist in playlists) {
            for (song in deleted) {
                if (playlist.songs.any { it.localUri == song.localUri || it.videoId == song.videoId }) {
                    OfflinePlaylists.remove(playlist.id, song)
                }
            }
        }
    }
}
