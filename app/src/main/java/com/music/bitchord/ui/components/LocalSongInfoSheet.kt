package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.data.FastAudioTagReader
import com.music.bitchord.data.model.Song
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalSongDetails(
    val format: String?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val channels: Int?,
    val bitrateKbps: Int?,
    val durationText: String?,
    val isHiRes: Boolean,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val year: String?,
    val hasEmbeddedArt: Boolean?,
    val fileName: String?,
    val folderPath: String?,
    val fullPath: String?,
    val fileSizeBytes: Long?,
    val dateAddedSec: Long?,
    val dateModifiedSec: Long?,
)

@Composable
internal fun LocalSongInfoDialog(
    song: Song,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var currentSong by remember(song) { mutableStateOf(song) }
    var editingTags by remember { mutableStateOf(false) }
    val details by produceState(initialValue = initialDetails(currentSong), key1 = currentSong) {
        value = withContext(Dispatchers.IO) { loadLocalSongDetails(context, currentSong) }
    }

    if (editingTags) {
        LocalTagEditorDialog(
            song = currentSong,
            onSaved = { updated -> currentSong = updated },
            onDismiss = { editingTags = false },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isLandscape = maxWidth >= 560.dp && maxHeight < maxWidth
            val dialogWidth = if (isLandscape) (maxWidth * 0.90f).coerceAtMost(640.dp) else maxWidth.coerceAtMost(480.dp)
            val dialogMaxHeight = (maxHeight * 0.92f).coerceAtMost(if (isLandscape) 480.dp else 700.dp)

            Surface(
                modifier = Modifier
                    .width(dialogWidth)
                    .heightIn(max = dialogMaxHeight),
                shape = RoundedCornerShape(24.dp),
                color = scheme.surface,
                contentColor = scheme.onSurface,
                border = BorderStroke(1.dp, scheme.outline),
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.song_info),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (details.isHiRes) {
                                    HiResBadge(tint = scheme.onSurface)
                                }
                            }
                            Text(
                                text = details.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = scheme.onSurface,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))

                    // Scrollable detail sections
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InfoSectionCard(title = stringResource(R.string.local_info_section_audio)) {
                            InfoRow(stringResource(R.string.local_info_format), details.format)
                            InfoRow(
                                stringResource(R.string.local_info_sample_rate),
                                details.sampleRateHz?.let(::formatSampleRate),
                            )
                            InfoRow(
                                stringResource(R.string.local_info_bit_depth),
                                details.bitsPerSample?.let { "$it-bit" },
                            )
                            InfoRow(
                                stringResource(R.string.local_info_channels),
                                details.channels?.let(::formatChannels),
                            )
                            InfoRow(
                                stringResource(R.string.local_info_bitrate),
                                details.bitrateKbps?.let { "$it kbps" },
                            )
                            InfoRow(stringResource(R.string.local_info_duration), details.durationText)
                        }

                        InfoSectionCard(title = stringResource(R.string.local_info_section_tags)) {
                            InfoRow(stringResource(R.string.songs), details.title)
                            InfoRow(stringResource(R.string.artist), details.artist.takeIf { it.isNotBlank() })
                            InfoRow(stringResource(R.string.song_info_album), details.album)
                            InfoRow(stringResource(R.string.local_info_album_artist), details.albumArtist)
                            InfoRow(stringResource(R.string.local_info_track_number), details.trackNumber?.toString())
                            InfoRow(stringResource(R.string.local_info_disc_number), details.discNumber?.toString())
                            InfoRow(stringResource(R.string.local_info_genre), details.genre)
                            InfoRow(stringResource(R.string.local_info_year), details.year)
                            InfoRow(
                                stringResource(R.string.local_info_embedded_art),
                                details.hasEmbeddedArt?.let {
                                    if (it) stringResource(R.string.local_info_yes) else stringResource(R.string.local_info_no)
                                },
                            )
                        }

                        InfoSectionCard(title = stringResource(R.string.local_info_section_file)) {
                            InfoRow(stringResource(R.string.local_info_filename), details.fileName)
                            InfoRow(stringResource(R.string.local_info_folder), details.folderPath)
                            InfoRow(stringResource(R.string.local_info_path), details.fullPath)
                            InfoRow(
                                stringResource(R.string.local_info_size),
                                details.fileSizeBytes?.let(::formatFileSize),
                            )
                            InfoRow(
                                stringResource(R.string.local_info_date_added),
                                details.dateAddedSec?.takeIf { it > 0L }?.let(::formatTimestamp),
                            )
                            InfoRow(
                                stringResource(R.string.local_info_date_modified),
                                details.dateModifiedSec?.takeIf { it > 0L }?.let(::formatTimestamp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Bottom action bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val copyTarget = details.fullPath ?: currentSong.localUri
                        if (!copyTarget.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("path", copyTarget))
                                    Toast.makeText(context, R.string.local_info_path_copied, Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.local_info_copy_path),
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        IconButton(onClick = { shareLocalSongs(context, listOf(currentSong)) }) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.local_share_files),
                                tint = scheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = { editingTags = true },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, scheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = scheme.onSurface.copy(alpha = 0.06f),
                                contentColor = scheme.onSurface,
                            ),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Text(stringResource(R.string.local_edit_tags), maxLines = 1)
                        }
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .border(1.dp, scheme.outline.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
    }
}

private fun initialDetails(song: Song): LocalSongDetails {
    val file = song.localPath?.let(::File)
    val ext = song.localFormat ?: file?.extension?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    return LocalSongDetails(
        format = ext,
        sampleRateHz = song.localSampleRateHz,
        bitsPerSample = song.localBitsPerSample,
        channels = song.localChannels,
        bitrateKbps = null,
        durationText = song.durationText,
        isHiRes = song.sourceHiRes,
        title = song.title,
        artist = song.artist,
        album = song.albumName,
        albumArtist = song.localAlbumArtist,
        trackNumber = song.trackNumber,
        discNumber = song.localDiscNumber,
        genre = null,
        year = null,
        hasEmbeddedArt = null,
        fileName = file?.name,
        folderPath = file?.parentFile?.name,
        fullPath = song.localPath ?: song.localUri,
        fileSizeBytes = song.localFileSizeBytes,
        dateAddedSec = song.localDateAddedSeconds,
        dateModifiedSec = song.localDateModifiedSeconds,
    )
}

private fun loadLocalSongDetails(context: Context, song: Song): LocalSongDetails {
    val base = initialDetails(song)
    val file = song.localPath?.let(::File)?.takeIf { it.exists() && it.canRead() }
    val ext = file?.extension?.lowercase(Locale.ROOT)
        ?: song.localFormat?.lowercase(Locale.ROOT)
    val fast = file?.let { FastAudioTagReader.readFile(it) }

    var bitrateKbps: Int? = null
    var genre: String? = null
    var year: String? = null
    var hasArt: Boolean? = null
    var sr = base.sampleRateHz ?: fast?.sampleRateHz
    var bps = base.bitsPerSample ?: fast?.bitsPerSample
    var ch = base.channels ?: fast?.channels
    var sizeBytes = base.fileSizeBytes ?: file?.length()?.takeIf { it > 0L }

    if (file != null && ext in setOf("flac", "mp3")) {
        hasArt = FastAudioTagReader.readEmbeddedPictureFile(file, maxBytes = 1024 * 1024) != null
    }

    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = song.localUri
            if (file != null) {
                retriever.setDataSource(file.path)
            } else if (!uri.isNullOrBlank()) {
                retriever.setDataSource(context, Uri.parse(uri))
            }
            bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()?.takeIf { it > 0L }?.div(1_000L)?.toInt()
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
            if (sr == null) {
                sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            }
            if (bps == null && android.os.Build.VERSION.SDK_INT >= 31) {
                bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            }
            if (hasArt == null) {
                hasArt = retriever.embeddedPicture != null
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    if (sizeBytes == null && !song.localUri.isNullOrBlank()) {
        sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(song.localUri), "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L }
    }

    val hiRes = base.isHiRes || FastAudioTagReader.isHiResAudio(ext, sr, bps)
    return base.copy(
        format = base.format ?: ext?.uppercase(Locale.ROOT),
        sampleRateHz = sr,
        bitsPerSample = bps,
        channels = ch,
        bitrateKbps = bitrateKbps,
        isHiRes = hiRes,
        genre = genre,
        year = year,
        hasEmbeddedArt = hasArt,
        fileSizeBytes = sizeBytes,
    )
}

internal fun shareLocalSongs(context: Context, songs: List<Song>) {
    val uris = ArrayList<Uri>(songs.size)
    for (song in songs) {
        val resolved = resolveShareUri(context, song) ?: continue
        uris.add(resolved)
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.local_share_files)))
    }
}

private fun resolveShareUri(context: Context, song: Song): Uri? {
    val raw = song.localUri
    if (!raw.isNullOrBlank() && raw.startsWith("content://")) {
        return runCatching { Uri.parse(raw) }.getOrNull()
    }
    val file = song.localPath?.let(::File)?.takeIf { it.exists() } ?: return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrElse { Uri.fromFile(file) }
}

private fun formatSampleRate(hz: Int): String {
    val khz = hz / 1_000.0
    return if (hz % 1_000 == 0) "${hz / 1_000} kHz" else String.format(Locale.US, "%.1f kHz", khz)
}

private fun formatChannels(ch: Int): String = when (ch) {
    1 -> "Mono (1 ch)"
    2 -> "Stereo (2 ch)"
    6 -> "5.1 (6 ch)"
    8 -> "7.1 (8 ch)"
    else -> "$ch ch"
}

private fun formatFileSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(seconds: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(seconds * 1_000L))
}.getOrDefault("")
