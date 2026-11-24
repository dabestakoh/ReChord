package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.LocalStorageVolumes
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalMusicScanMode
import com.music.bitchord.data.settings.LocalScanFolder
import java.io.File
import java.util.Locale

/**
 * Multi-folder & subfolder picker dialog for Local Music.
 *
 * Adapts to Portrait phones (`360×800dp`), DAP Landscape (`560–720×360dp`),
 * 720p Car Head-Units (`640×360dp`, `853×480dp`, `1280×720dp`), and Tablets:
 * switches to a 2-column layout in landscape with primary actions always visible
 * inside `WindowInsets.safeDrawing`.
 */
@Composable
internal fun LocalFolderPickerDialog(
    initialMode: LocalMusicScanMode,
    initialFolders: List<LocalScanFolder>,
    initialExcludedFolders: List<LocalScanFolder>,
    onConfirm: (mode: LocalMusicScanMode, folders: List<LocalScanFolder>, excluded: List<LocalScanFolder>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val legacySetting by AppSettings.localLegacyScanMode.collectAsStateWithLifecycle()
    val useLegacyBrowser = remember(legacySetting) {
        LocalStorageVolumes.shouldUseLegacyScan(context, legacySetting)
    }
    val detectedVolumes = remember(useLegacyBrowser) {
        LocalStorageVolumes.detectVolumes(context).filter { it.isReadable }
    }
    val safAvailable = remember { LocalStorageVolumes.isSafFolderPickerAvailable(context) }

    var mode by remember {
        mutableStateOf(
            if (initialMode == LocalMusicScanMode.ALL_FOLDERS) LocalMusicScanMode.ALL_FOLDERS
            else LocalMusicScanMode.SPECIFIC_FOLDERS,
        )
    }
    var selectedFolders by remember { mutableStateOf(initialFolders) }
    var excludedFolders by remember { mutableStateOf(initialExcludedFolders) }
    var removedSafUris by remember { mutableStateOf<Set<String>>(emptySet()) }

    val activeList = if (mode == LocalMusicScanMode.ALL_FOLDERS) excludedFolders else selectedFolders
    fun updateActiveList(next: List<LocalScanFolder>) {
        if (mode == LocalMusicScanMode.ALL_FOLDERS) excludedFolders = next else selectedFolders = next
    }

    var currentVolumeIndex by remember { mutableStateOf(0) }
    val activeVolume = detectedVolumes.getOrNull(currentVolumeIndex)
    var currentDir by remember(activeVolume?.canonicalPath) {
        mutableStateOf(activeVolume?.let { File(it.canonicalPath) })
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val uriStr = treeUri.toString()
        val label = LocalMediaRepository.selectedFolderLabel(uriStr) ?: uriStr
        val entry = LocalScanFolder(
            id = uriStr,
            isTreeUri = true,
            label = label,
            includeSubfolders = true,
        )
        if (activeList.none { it.isTreeUri && it.id == uriStr }) {
            updateActiveList(activeList + entry)
            removedSafUris = removedSafUris - uriStr
        }
    }

    val internalLabel = stringResource(R.string.local_storage_internal)
    val volumeSummary = remember(detectedVolumes, internalLabel) {
        if (detectedVolumes.isEmpty()) null
        else detectedVolumes.joinToString(" · ") { if (it.isPrimary) internalLabel else it.label }
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
            val dialogMaxWidth = if (isLandscape) (maxWidth * 0.96f).coerceAtMost(860.dp) else maxWidth.coerceAtMost(520.dp)
            val dialogMaxHeight = (maxHeight * 0.94f).coerceAtMost(if (isLandscape) 520.dp else 720.dp)

            Surface(
                modifier = Modifier
                    .width(dialogMaxWidth)
                    .heightIn(max = dialogMaxHeight),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Header + Mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.local_folder_picker_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (volumeSummary != null) {
                                Text(
                                    text = volumeSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // One-tap switch between Specific folders and All folders
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModeChip(
                            label = stringResource(R.string.local_switch_to_specific_folders),
                            selected = mode == LocalMusicScanMode.SPECIFIC_FOLDERS,
                            onClick = { mode = LocalMusicScanMode.SPECIFIC_FOLDERS },
                            modifier = Modifier.weight(1f),
                        )
                        ModeChip(
                            label = stringResource(R.string.local_switch_to_all_folders),
                            selected = mode == LocalMusicScanMode.ALL_FOLDERS,
                            onClick = { mode = LocalMusicScanMode.ALL_FOLDERS },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val toggleDirectory: (File) -> Unit = { dir ->
                        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                        val existing = activeList.firstOrNull { !it.isTreeUri && it.id.equals(canonical, ignoreCase = true) }
                        if (existing != null) {
                            updateActiveList(activeList - existing)
                        } else {
                            val volPrefix = activeVolume?.let {
                                if (it.isPrimary) internalLabel else it.label
                            }
                            val rel = activeVolume?.let {
                                canonical.removePrefix(it.canonicalPath).trimStart('/')
                            }.orEmpty()
                            val label = listOfNotNull(volPrefix, rel.ifBlank { null }).joinToString(" / ")
                                .ifBlank { dir.name }
                            updateActiveList(
                                activeList + LocalScanFolder(
                                    id = canonical,
                                    isTreeUri = false,
                                    label = label,
                                    includeSubfolders = true,
                                ),
                            )
                        }
                    }

                    val removeFolder: (LocalScanFolder) -> Unit = { folder ->
                        updateActiveList(activeList - folder)
                        if (folder.isTreeUri) removedSafUris = removedSafUris + folder.id
                    }

                    val toggleSubfolders: (LocalScanFolder, Boolean) -> Unit = { folder, checked ->
                        updateActiveList(
                            activeList.map { if (it == folder) it.copy(includeSubfolders = checked) else it },
                        )
                    }

                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Left column: Browser + SAF button
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                FolderBrowserPane(
                                    useLegacyBrowser = useLegacyBrowser,
                                    detectedVolumes = detectedVolumes,
                                    currentVolumeIndex = currentVolumeIndex,
                                    onSelectVolume = { idx ->
                                        currentVolumeIndex = idx
                                        currentDir = detectedVolumes.getOrNull(idx)?.let { File(it.canonicalPath) }
                                    },
                                    currentDir = currentDir,
                                    onNavigate = { currentDir = it },
                                    selectedPaths = activeList.filter { !it.isTreeUri }.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) },
                                    onToggleDir = toggleDirectory,
                                    safAvailable = safAvailable,
                                    onLaunchSaf = { safLauncher.launch(null) },
                                    internalLabel = internalLabel,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Right column: Selected / Excluded list
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                SelectedFoldersPane(
                                    isExcludedList = mode == LocalMusicScanMode.ALL_FOLDERS,
                                    folders = activeList,
                                    onToggleSubfolders = toggleSubfolders,
                                    onRemove = removeFolder,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            SelectedFoldersPane(
                                isExcludedList = mode == LocalMusicScanMode.ALL_FOLDERS,
                                folders = activeList,
                                onToggleSubfolders = toggleSubfolders,
                                onRemove = removeFolder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(10.dp))
                            FolderBrowserPane(
                                useLegacyBrowser = useLegacyBrowser,
                                detectedVolumes = detectedVolumes,
                                currentVolumeIndex = currentVolumeIndex,
                                onSelectVolume = { idx ->
                                    currentVolumeIndex = idx
                                    currentDir = detectedVolumes.getOrNull(idx)?.let { File(it.canonicalPath) }
                                },
                                currentDir = currentDir,
                                onNavigate = { currentDir = it },
                                selectedPaths = activeList.filter { !it.isTreeUri }.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) },
                                onToggleDir = toggleDirectory,
                                safAvailable = safAvailable,
                                onLaunchSaf = { safLauncher.launch(null) },
                                internalLabel = internalLabel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Bottom action bar — always visible in both Portrait and Landscape.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        val canConfirm = mode == LocalMusicScanMode.ALL_FOLDERS || selectedFolders.isNotEmpty()
                        Button(
                            onClick = {
                                if (removedSafUris.isNotEmpty()) {
                                    LocalStorageVolumes.releaseSafPermissions(context, removedSafUris)
                                }
                                onConfirm(mode, selectedFolders, excludedFolders)
                            },
                            enabled = canConfirm,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (mode == LocalMusicScanMode.ALL_FOLDERS) {
                                    stringResource(R.string.local_search_all_folders)
                                } else {
                                    stringResource(R.string.local_start_scan_folders, selectedFolders.size)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectedFoldersPane(
    isExcludedList: Boolean,
    folders: List<LocalScanFolder>,
    onToggleSubfolders: (LocalScanFolder, Boolean) -> Unit,
    onRemove: (LocalScanFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = stringResource(
                if (isExcludedList) R.string.local_excluded_folders else R.string.local_music_folder,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.local_no_folders_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(folders, key = { "${it.isTreeUri}:${it.id}" }) { folder ->
                    val expired = remember(folder) {
                        folder.isTreeUri && !LocalStorageVolumes.hasPersistedSafPermission(context, folder.id)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (expired) Icons.Rounded.WarningAmber else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = folder.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (expired) {
                                Text(
                                    text = stringResource(R.string.local_folder_permission_expired),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.local_include_subfolders),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = folder.includeSubfolders,
                            onCheckedChange = { onToggleSubfolders(folder, it) },
                        )
                        IconButton(onClick = { onRemove(folder) }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cancel),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderBrowserPane(
    useLegacyBrowser: Boolean,
    detectedVolumes: List<LocalStorageVolumes.DetectedVolume>,
    currentVolumeIndex: Int,
    onSelectVolume: (Int) -> Unit,
    currentDir: File?,
    onNavigate: (File) -> Unit,
    selectedPaths: Set<String>,
    onToggleDir: (File) -> Unit,
    safAvailable: Boolean,
    onLaunchSaf: () -> Unit,
    internalLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (safAvailable) {
            OutlinedButton(
                onClick = onLaunchSaf,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.local_add_folder_system_picker),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (useLegacyBrowser && detectedVolumes.isNotEmpty() && currentDir != null) {
            val activeVol = detectedVolumes.getOrNull(currentVolumeIndex)
            // Volume selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detectedVolumes.forEachIndexed { idx, vol ->
                    val selected = idx == currentVolumeIndex
                    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    Row(
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, border, RoundedCornerShape(10.dp))
                            .clickable { onSelectVolume(idx) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (vol.isPrimary) Icons.Rounded.Smartphone else Icons.Rounded.SdStorage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (vol.isPrimary) internalLabel else vol.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            val canGoUp = activeVol != null &&
                currentDir.absolutePath.trimEnd('/') != activeVol.canonicalPath.trimEnd('/') &&
                currentDir.parentFile != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoUp) {
                    IconButton(
                        onClick = { currentDir.parentFile?.let(onNavigate) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = currentDir.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { onToggleDir(currentDir) },
                    modifier = Modifier.size(36.dp),
                ) {
                    val isCurrentSelected = runCatching { currentDir.canonicalPath }
                        .getOrDefault(currentDir.absolutePath)
                        .lowercase(Locale.ROOT) in selectedPaths
                    Icon(
                        imageVector = if (isCurrentSelected) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            val subdirs = remember(currentDir.path) {
                runCatching {
                    currentDir.listFiles()
                        ?.filter {
                            it.isDirectory &&
                                !it.name.startsWith(".") &&
                                it.name.lowercase(Locale.ROOT) !in LocalMediaRepository.skippedSystemDirs
                        }
                        ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                        .orEmpty()
                }.getOrDefault(emptyList())
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(subdirs, key = { it.path }) { dir ->
                    val canonicalLower = remember(dir.path) {
                        runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).lowercase(Locale.ROOT)
                    }
                    val checked = canonicalLower in selectedPaths
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onNavigate(dir) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleDir(dir) },
                        )
                        Icon(
                            imageVector = if (checked) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = dir.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Side-effects and modal dialogs for Local Music management, extracted from
 * `BitChordApp` to keep `MainActivityKt.BitChordApp` under the 64 KB JVM
 * method bytecode limit.
 */
@Composable
internal fun LocalMusicEffectsAndDialogs(
    viewModel: com.music.bitchord.ui.MainViewModel,
    topDetailBrowseId: String?,
    showFolderPicker: Boolean,
    showClearConfirm: Boolean,
    onDismissFolderPicker: () -> Unit,
    onDismissClearConfirm: () -> Unit,
    onRequestStorageForAll: () -> Unit,
) {
    val context = LocalContext.current
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val localMusicScanMode by AppSettings.localMusicScanMode.collectAsStateWithLifecycle()
    val localMusicFolders by AppSettings.localMusicFolders.collectAsStateWithLifecycle()
    val localMusicExcludedFolders by AppSettings.localMusicExcludedFolders.collectAsStateWithLifecycle()
    val localLegacyScanMode by AppSettings.localLegacyScanMode.collectAsStateWithLifecycle()
    val localMusicMinDurationSec by AppSettings.localMusicMinDurationSec.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        LocalStorageVolumes.volumeChanges(context).collect {
            kotlinx.coroutines.delay(1_000)
            viewModel.refreshLocalLibraryIfConfigured()
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        localMusicFolderUri,
        localMusicScanMode,
        localMusicFolders,
        localMusicExcludedFolders,
        localLegacyScanMode,
        localMusicMinDurationSec,
        filterNonMusicAudio,
    ) {
        if (localMusicScanMode != LocalMusicScanMode.NOT_CONFIGURED) {
            viewModel.refreshLocalLibraryIfConfigured()
        }
        if (topDetailBrowseId == "local:all" ||
            com.music.bitchord.data.LocalGroups.folderOf(topDetailBrowseId) == "local:all"
        ) {
            viewModel.reloadLocalDetail(topDetailBrowseId!!)
        }
    }
    androidx.compose.runtime.LaunchedEffect(topDetailBrowseId) {
        if (topDetailBrowseId == "local:all" && localMusicScanMode != LocalMusicScanMode.NOT_CONFIGURED) {
            viewModel.refreshLocalLibraryIfConfigured()
        }
    }
    if (showFolderPicker) {
        LocalFolderPickerDialog(
            initialMode = localMusicScanMode,
            initialFolders = localMusicFolders,
            initialExcludedFolders = localMusicExcludedFolders,
            onConfirm = { mode, folders, excluded ->
                onDismissFolderPicker()
                if (mode == LocalMusicScanMode.ALL_FOLDERS &&
                    !LocalMediaRepository.hasStoragePermission(context)
                ) {
                    AppSettings.setLocalMusicFolders(folders)
                    AppSettings.setLocalMusicExcludedFolders(excluded)
                    onRequestStorageForAll()
                } else {
                    viewModel.startLocalScanFolders(mode, folders, excluded)
                }
            },
            onDismiss = onDismissFolderPicker,
        )
    }
    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissClearConfirm,
            title = { Text(stringResource(R.string.local_clear_library_confirm_title)) },
            text = { Text(stringResource(R.string.local_clear_library_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissClearConfirm()
                        viewModel.clearLocalLibrary()
                    },
                ) {
                    Text(stringResource(R.string.local_clear_library))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearConfirm) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
