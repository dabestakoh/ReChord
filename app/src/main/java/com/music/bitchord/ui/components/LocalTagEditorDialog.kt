package com.music.bitchord.ui.components

import android.app.Activity
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.LocalFileOps
import com.music.bitchord.data.LocalTagWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.AccentRed
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalTagEditorDialog(
    song: Song,
    onSaved: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val directWrite = remember(song) { LocalTagWriter.supportsDirectFileWrite(song) }

    var title by remember(song) { mutableStateOf(song.title) }
    var artist by remember(song) { mutableStateOf(song.artist) }
    var album by remember(song) { mutableStateOf(song.albumName.orEmpty()) }
    var albumArtist by remember(song) { mutableStateOf(song.localAlbumArtist.orEmpty()) }
    var trackText by remember(song) { mutableStateOf(song.trackNumber?.toString().orEmpty()) }
    var discText by remember(song) { mutableStateOf(song.localDiscNumber?.toString().orEmpty()) }
    var genre by remember(song) { mutableStateOf("") }
    var year by remember(song) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var showAllFilesPrompt by remember { mutableStateOf(false) }
    var pendingTags by remember { mutableStateOf<LocalTagWriter.EditableTags?>(null) }

    LaunchedEffect(song) {
        val (loadedGenre, loadedYear) = withContext(Dispatchers.IO) {
            var g = ""
            var y = ""
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    val file = song.localPath?.let(::File)?.takeIf { it.exists() && it.canRead() }
                    if (file != null) retriever.setDataSource(file.path)
                    else song.localUri?.let { retriever.setDataSource(context, Uri.parse(it)) }
                    g = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim().orEmpty()
                    y = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                        ?.trim()?.takeIf { it != "0" }.orEmpty()
                } finally {
                    runCatching { retriever.release() }
                }
            }
            g to y
        }
        if (genre.isEmpty()) genre = loadedGenre
        if (year.isEmpty()) year = loadedYear
    }

    fun executeSave(tags: LocalTagWriter.EditableTags, afterGrant: Boolean, requestWriteLauncher: (IntentSenderRequest) -> Unit) {
        saving = true
        scope.launch {
            when (val res = LocalTagWriter.saveTags(context, song, tags, afterSystemGrant = afterGrant)) {
                is LocalTagWriter.SaveResult.Saved -> {
                    saving = false
                    Toast.makeText(context, R.string.local_edit_tags_saved, Toast.LENGTH_SHORT).show()
                    onSaved(res.updatedSong)
                    onDismiss()
                }
                is LocalTagWriter.SaveResult.NeedsWritePermission -> {
                    saving = false
                    val pi = LocalFileOps.createWriteRequestIntent(context, res.mediaUri)
                    if (pi != null) {
                        pendingTags = tags
                        requestWriteLauncher(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        Toast.makeText(context, R.string.local_edit_tags_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                is LocalTagWriter.SaveResult.Error -> {
                    saving = false
                    Toast.makeText(context, R.string.local_edit_tags_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val tags = pendingTags
        pendingTags = null
        if (result.resultCode == Activity.RESULT_OK && tags != null) {
            executeSave(tags, afterGrant = true) {}
        }
    }

    fun currentTags() = LocalTagWriter.EditableTags(
        title = title.trim(),
        artist = artist.trim(),
        album = album.trim(),
        albumArtist = albumArtist.trim(),
        trackNumber = trackText.trim().toIntOrNull()?.takeIf { it > 0 },
        discNumber = discText.trim().toIntOrNull()?.takeIf { it > 0 },
        genre = genre.trim(),
        year = year.trim(),
    )

    if (showAllFilesPrompt) {
        LocalAllFilesAccessPromptDialog(
            onOpenSettings = {
                showAllFilesPrompt = false
                AppSettings.markLocalAllFilesPromptShown()
                LocalFileOps.allFilesSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
            },
            onContinueWithPrompt = {
                showAllFilesPrompt = false
                AppSettings.markLocalAllFilesPromptShown()
                executeSave(currentTags(), afterGrant = false) { writePermissionLauncher.launch(it) }
            },
            onDismiss = { showAllFilesPrompt = false },
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        focusedLabelColor = scheme.onSurface,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        cursorColor = scheme.primary,
    )

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.local_edit_tags_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!directWrite) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.local_edit_tags_app_only_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(scheme.onSurface.copy(alpha = 0.08f))
                                        .border(1.dp, scheme.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        IconButton(onClick = { if (!saving) onDismiss() }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = scheme.onSurface)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.local_edit_tags_field_title)) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = artist,
                            onValueChange = { artist = it },
                            label = { Text(stringResource(R.string.local_edit_tags_field_artist)) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = album,
                            onValueChange = { album = it },
                            label = { Text(stringResource(R.string.local_edit_tags_field_album)) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = albumArtist,
                            onValueChange = { albumArtist = it },
                            label = { Text(stringResource(R.string.local_edit_tags_field_album_artist)) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = trackText,
                                onValueChange = { trackText = it.filter(Char::isDigit).take(4) },
                                label = { Text(stringResource(R.string.local_edit_tags_field_track)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = fieldColors,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = discText,
                                onValueChange = { discText = it.filter(Char::isDigit).take(3) },
                                label = { Text(stringResource(R.string.local_edit_tags_field_disc)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = fieldColors,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (directWrite) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = genre,
                                    onValueChange = { genre = it },
                                    label = { Text(stringResource(R.string.local_edit_tags_field_genre)) },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.weight(1.4f),
                                )
                                OutlinedTextField(
                                    value = year,
                                    onValueChange = { year = it.filter(Char::isDigit).take(4) },
                                    label = { Text(stringResource(R.string.local_edit_tags_field_year)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = fieldColors,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !saving,
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (directWrite && !LocalFileOps.hasAllFilesAccess() &&
                                    !AppSettings.localAllFilesPromptShown.value &&
                                    LocalFileOps.allFilesSettingsIntent(context) != null
                                ) {
                                    showAllFilesPrompt = true
                                } else {
                                    executeSave(currentTags(), afterGrant = false) {
                                        writePermissionLauncher.launch(it)
                                    }
                                }
                            },
                            enabled = !saving && title.isNotBlank(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = scheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(R.string.done), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalDeleteFromDeviceDialog(
    songs: List<Song>,
    onDeleted: (List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (songs.isEmpty()) return
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val totalBytes = remember(songs) { LocalFileOps.estimateTotalBytes(songs) }
    val sizeText = remember(totalBytes) { formatCompactSize(totalBytes) }
    var deleting by remember { mutableStateOf(false) }
    var showAllFilesPrompt by remember { mutableStateOf(false) }
    var pendingSystemSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val confirmed = pendingSystemSongs
        pendingSystemSongs = emptyList()
        if (result.resultCode == Activity.RESULT_OK && confirmed.isNotEmpty()) {
            scope.launch {
                LocalFileOps.finishDeleteAfterSystemConfirm(context, confirmed)
                Toast.makeText(context, R.string.local_delete_done, Toast.LENGTH_SHORT).show()
                onDeleted(confirmed)
                onDismiss()
            }
        } else {
            deleting = false
        }
    }

    fun startDelete() {
        deleting = true
        scope.launch {
            when (val res = LocalFileOps.deleteFromDevice(context, songs)) {
                is LocalFileOps.DeleteResult.Deleted -> {
                    deleting = false
                    Toast.makeText(context, R.string.local_delete_done, Toast.LENGTH_SHORT).show()
                    onDeleted(res.songs)
                    onDismiss()
                }
                is LocalFileOps.DeleteResult.NeedsSystemConfirm -> {
                    pendingSystemSongs = res.songs
                    deletePermissionLauncher.launch(IntentSenderRequest.Builder(res.pendingIntent.intentSender).build())
                }
                is LocalFileOps.DeleteResult.Failed -> {
                    deleting = false
                    Toast.makeText(context, R.string.local_delete_failed, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }
    }

    if (showAllFilesPrompt) {
        LocalAllFilesAccessPromptDialog(
            onOpenSettings = {
                showAllFilesPrompt = false
                AppSettings.markLocalAllFilesPromptShown()
                LocalFileOps.allFilesSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
            },
            onContinueWithPrompt = {
                showAllFilesPrompt = false
                AppSettings.markLocalAllFilesPromptShown()
                startDelete()
            },
            onDismiss = { showAllFilesPrompt = false },
        )
    }

    val message = if (songs.size == 1) {
        stringResource(R.string.local_delete_confirm_single, songs.first().title, sizeText)
    } else {
        stringResource(R.string.local_delete_confirm_multi, songs.size, sizeText)
    }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        containerColor = scheme.surface,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.local_delete_confirm_title), fontWeight = FontWeight.SemiBold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!LocalFileOps.hasAllFilesAccess() &&
                        !AppSettings.localAllFilesPromptShown.value &&
                        LocalFileOps.allFilesSettingsIntent(context) != null
                    ) {
                        showAllFilesPrompt = true
                    } else {
                        startDelete()
                    }
                },
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentRed),
            ) {
                Text(stringResource(R.string.local_delete_from_device), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LocalAllFilesAccessPromptDialog(
    onOpenSettings: () -> Unit,
    onContinueWithPrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.local_all_files_prompt_title), fontWeight = FontWeight.SemiBold) },
        text = { Text(stringResource(R.string.local_all_files_prompt_message)) },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.primary),
            ) {
                Text(stringResource(R.string.local_all_files_open_settings), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueWithPrompt,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.local_all_files_continue_prompt))
            }
        },
    )
}

private fun formatCompactSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
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
