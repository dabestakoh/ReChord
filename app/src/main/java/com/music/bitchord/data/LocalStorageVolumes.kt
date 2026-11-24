package com.music.bitchord.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import com.music.bitchord.data.settings.LocalLegacyScanMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.Locale

/**
 * Discovers readable internal, SD card, and USB/SSD storage volumes across
 * phones, DAPs, and Android car head units (including non-standard OEM mount
 * points such as `/mnt/usb_storage`, `/mnt/media_rw`, and `/udisk`).
 */
object LocalStorageVolumes {

    data class DetectedVolume(
        val canonicalPath: String,
        val label: String,
        val isPrimary: Boolean,
        val isReadable: Boolean,
    )

    private val probeParentDirs = listOf(
        "/storage",
        "/mnt/media_rw",
        "/mnt/usb_storage",
        "/mnt/usb",
    )

    private val probeDirectRoots = listOf(
        "/mnt/usbhost",
        "/mnt/usbhost0",
        "/mnt/usbhost1",
        "/mnt/usbhost2",
        "/mnt/external_sd",
        "/mnt/extsd",
        "/mnt/ext_sd",
        "/mnt/sdcard1",
        "/mnt/sdcard2",
        "/udisk",
        "/udisk0",
        "/udisk1",
        "/storage/usbotg",
        "/storage/udisk",
        "/storage/usb0",
        "/storage/usb1",
        "/storage/sdcard1",
    )

    private val ignoredVolumeNames = setOf("self", "emulated", "enc_emulated", "knox-emulated", "asec", "obb")

    /**
     * Returns all deduplicated storage volumes visible to this process, with
     * primary internal storage first followed by external SD/USB volumes.
     */
    fun detectVolumes(context: Context): List<DetectedVolume> {
        val byCanonical = LinkedHashMap<String, DetectedVolume>()

        fun addCandidate(dir: File?, labelHint: String?, isPrimary: Boolean) {
            if (dir == null || !dir.exists() || !dir.isDirectory) return
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            val normalized = canonical.trimEnd('/').ifEmpty { "/" }
            val readable = dir.canRead() && (dir.list() != null || isPrimary)
            val existing = byCanonical[normalized]
            if (existing != null) {
                if (!existing.isReadable && readable) {
                    byCanonical[normalized] = existing.copy(isReadable = true)
                }
                return
            }
            val label = labelHint?.takeIf { it.isNotBlank() } ?: defaultLabelFor(normalized, isPrimary)
            byCanonical[normalized] = DetectedVolume(
                canonicalPath = normalized,
                label = label,
                isPrimary = isPrimary,
                isReadable = readable,
            )
        }

        // 1. Android StorageManager volumes (most accurate labels + UUIDs).
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { vol ->
                val dir = volumeDirectory(vol)
                val desc = runCatching { vol.getDescription(context) }.getOrNull()
                addCandidate(dir, desc, vol.isPrimary)
            }
        }

        // 2. Primary external storage fallback.
        @Suppress("DEPRECATION")
        runCatching {
            addCandidate(Environment.getExternalStorageDirectory(), null, isPrimary = true)
        }

        // 3. Per-volume roots derived from getExternalFilesDirs(null).
        runCatching {
            context.getExternalFilesDirs(null)?.forEachIndexed { idx, appDir ->
                val root = extractVolumeRoot(appDir)
                addCandidate(root, null, isPrimary = idx == 0)
            }
        }

        // 4. Probe common Android car head-unit & DAP mount points.
        for (parentPath in probeParentDirs) {
            runCatching {
                val parent = File(parentPath)
                if (parent.isDirectory && parent.canRead()) {
                    parent.listFiles()?.forEach { child ->
                        if (child.isDirectory && child.name.lowercase(Locale.ROOT) !in ignoredVolumeNames) {
                            addCandidate(child, null, isPrimary = false)
                        }
                    }
                }
            }
        }
        for (directPath in probeDirectRoots) {
            runCatching {
                val dir = File(directPath)
                if (dir.isDirectory && dir.canRead()) {
                    addCandidate(dir, null, isPrimary = false)
                }
            }
        }

        return byCanonical.values
            .sortedWith(compareByDescending<DetectedVolume> { it.isPrimary }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    /** Readable non-primary external volumes (SD cards, USB SSDs, flash drives). */
    fun readableExternalVolumes(context: Context): List<DetectedVolume> =
        detectVolumes(context).filter { !it.isPrimary && it.isReadable }

    /**
     * Whether direct legacy / external filesystem scanning should run alongside
     * MediaStore and SAF.
     *
     * In [LocalLegacyScanMode.AUTO]: enables automatically on Android <= 9,
     * Android 10 when legacy storage is active, whenever any readable external
     * SD/USB volume is detected, or when the system SAF folder picker is absent.
     */
    fun shouldUseLegacyScan(context: Context, mode: LocalLegacyScanMode): Boolean = when (mode) {
        LocalLegacyScanMode.ENABLED -> true
        LocalLegacyScanMode.DISABLED -> false
        LocalLegacyScanMode.AUTO -> {
            val isLegacyApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && Environment.isExternalStorageLegacy())
            isLegacyApi || readableExternalVolumes(context).isNotEmpty() || !isSafFolderPickerAvailable(context)
        }
    }

    /** True if the system provides an Activity for `ACTION_OPEN_DOCUMENT_TREE` (SAF). */
    fun isSafFolderPickerAvailable(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    /** True if this app currently holds a persisted read grant for [treeUri]. */
    fun hasPersistedSafPermission(context: Context, treeUri: String): Boolean = runCatching {
        val target = Uri.parse(treeUri)
        context.contentResolver.persistedUriPermissions.any { perm ->
            perm.isReadPermission && perm.uri == target
        }
    }.getOrDefault(false)

    /** Releases persisted read grants for removed/cleared SAF tree URIs. */
    fun releaseSafPermissions(context: Context, treeUris: Collection<String>) {
        val resolver = context.contentResolver
        for (raw in treeUris) {
            if (raw.isBlank()) continue
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(raw),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * Compact human-readable list of detected readable volumes for Settings and
     * the folder picker (e.g. `"Internal Storage, USB SSD"`).
     */
    fun readableVolumeSummary(context: Context, internalLabel: String): String? {
        val readable = detectVolumes(context).filter { it.isReadable }
        if (readable.isEmpty()) return null
        return readable.joinToString(", ") { vol ->
            if (vol.isPrimary) internalLabel else vol.label
        }
    }

    /**
     * Emits whenever an external SD card or USB drive mounts, unmounts, or is
     * ejected — critical for car head units where USB SSDs mount 5–15s after boot.
     */
    fun volumeChanges(context: Context): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure {
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        return runCatching {
            val method = volume.javaClass.getMethod("getPathFile")
            method.invoke(volume) as? File
        }.getOrNull()
    }

    private fun extractVolumeRoot(appExternalDir: File?): File? {
        val path = appExternalDir?.absolutePath ?: return null
        val androidIdx = path.indexOf("/Android/")
        if (androidIdx > 0) {
            return File(path.substring(0, androidIdx))
        }
        return null
    }

    private fun defaultLabelFor(canonicalPath: String, isPrimary: Boolean): String {
        if (isPrimary) return "Internal Storage"
        val segment = canonicalPath.substringAfterLast('/')
        return segment.ifBlank { canonicalPath }
    }
}
