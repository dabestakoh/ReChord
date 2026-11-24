package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.LocalStorageVolumes
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalMusicScanMode
import com.music.bitchord.data.settings.LocalScanFolder
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.theme.AccentRed
import java.io.File
import java.util.Locale

/**
 * Multi-folder & subfolder picker dialog for Local Music.
 *
 * Styled strictly with ReChord's monochrome glass color roles (`surface`,
 * `primary`/`onPrimary`, `onSurface`, `onSurfaceVariant`, `outline`) so every
 * element has crisp contrast in Light, Dark, and pure-black AMOLED themes, and
 * adapts to Portrait phones (`360×800dp`), DAP Landscape (`560–720×360dp`),
 * 720p Car Head-Units (`640×360dp`, `853×480dp`, `1280×720dp`), and Tablets.
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
    val scheme = MaterialTheme.colorScheme
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
                color = scheme.surface,
                contentColor = scheme.onSurface,
                border = BorderStroke(1.dp, scheme.outline),
                tonalElevation = 0.dp,
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
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (volumeSummary != null) {
                                Text(
                                    text = volumeSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = scheme.onSurface,
                            )
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

                    val isAllMode = mode == LocalMusicScanMode.ALL_FOLDERS
                    var excludeExpanded by remember { mutableStateOf(false) }
                    val selectedPaths = activeList.filter { !it.isTreeUri }
                        .mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) }
                    val browser: @Composable (Modifier) -> Unit = { m ->
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
                            selectedPaths = selectedPaths,
                            onToggleDir = toggleDirectory,
                            safAvailable = safAvailable,
                            onLaunchSaf = { safLauncher.launch(null) },
                            internalLabel = internalLabel,
                            modifier = m,
                        )
                    }
                    val selectedPane: @Composable (Modifier) -> Unit = { m ->
                        SelectedFoldersPane(
                            isExcludedList = isAllMode,
                            folders = activeList,
                            onToggleSubfolders = toggleSubfolders,
                            onRemove = removeFolder,
                            modifier = m,
                        )
                    }

                    // The whole body scrolls (nothing nested), so any number of selected folders
                    // can never squeeze the browser away; header and action bar stay pinned.
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (isAllMode) AllFoldersHint()
                                if (!isAllMode || excludeExpanded) browser(Modifier.fillMaxWidth())
                                if (isAllMode) {
                                    ExcludeFoldersHeader(
                                        count = excludedFolders.size,
                                        expanded = excludeExpanded,
                                        onToggle = { excludeExpanded = !excludeExpanded },
                                    )
                                }
                            }
                            if (!isAllMode || excludeExpanded) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    selectedPane(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (isAllMode) {
                                AllFoldersHint()
                                ExcludeFoldersHeader(
                                    count = excludedFolders.size,
                                    expanded = excludeExpanded,
                                    onToggle = { excludeExpanded = !excludeExpanded },
                                )
                            }
                            if (!isAllMode || excludeExpanded) {
                                Spacer(Modifier.height(8.dp))
                                selectedPane(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = scheme.outline.copy(alpha = 0.6f))
                                Spacer(Modifier.height(10.dp))
                                browser(Modifier.fillMaxWidth())
                            }
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
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
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
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                                disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
                                disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (mode == LocalMusicScanMode.ALL_FOLDERS) {
                                    stringResource(R.string.local_search_all_folders)
                                } else {
                                    stringResource(R.string.local_start_scan_folders, selectedFolders.size)
                                },
                                fontWeight = FontWeight.SemiBold,
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
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.07f)
    val fg = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val borderColor = if (selected) scheme.primary else scheme.outline
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, borderColor, CircleShape)
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
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            text = stringResource(
                if (isExcludedList) R.string.local_excluded_folders else R.string.local_music_folder,
            ) + if (folders.isNotEmpty()) " (${folders.size})" else "",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.onSurface.copy(alpha = 0.06f))
                    .border(1.dp, scheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.local_no_folders_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                folders.forEach { folder ->
                    key("${folder.isTreeUri}:${folder.id}") {
                    val expired = remember(folder) {
                        folder.isTreeUri && !LocalStorageVolumes.hasPersistedSafPermission(context, folder.id)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.onSurface.copy(alpha = 0.07f))
                            .border(1.dp, scheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .heightIn(min = 44.dp)
                            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (expired) Icons.Rounded.WarningAmber else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (expired) AccentRed else scheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = folder.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (expired) {
                                Text(
                                    text = stringResource(R.string.local_folder_permission_expired),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentRed,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.local_include_subfolders),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = folder.includeSubfolders,
                            onCheckedChange = { onToggleSubfolders(folder, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = scheme.onPrimary,
                                checkedTrackColor = scheme.primary,
                                checkedBorderColor = scheme.primary,
                                uncheckedThumbColor = scheme.onSurfaceVariant,
                                uncheckedTrackColor = scheme.onSurface.copy(alpha = 0.14f),
                                uncheckedBorderColor = scheme.outline,
                            ),
                        )
                        IconButton(onClick = { onRemove(folder) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllFoldersHint() {
    Text(
        text = stringResource(R.string.local_all_folders_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Collapsed-by-default "Exclude folders (optional)" row for All folders mode. */
@Composable
private fun ExcludeFoldersHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSurface.copy(alpha = 0.07f))
            .border(1.dp, scheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.local_exclude_folders_optional) +
                if (count > 0) " ($count)" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
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
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        if (safAvailable) {
            OutlinedButton(
                onClick = onLaunchSaf,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = scheme.onSurface.copy(alpha = 0.06f),
                    contentColor = scheme.onSurface,
                ),
                border = BorderStroke(1.dp, scheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Rounded.CreateNewFolder,
                    contentDescription = null,
                    tint = scheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.local_add_folder_system_picker),
                    color = scheme.onSurface,
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
                    val bg = if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.06f)
                    val fg = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
                    val border = if (selected) scheme.primary else scheme.outline
                    Row(
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .clip(CircleShape)
                            .background(bg)
                            .border(1.dp, border, CircleShape)
                            .clickable { onSelectVolume(idx) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (vol.isPrimary) Icons.Rounded.Smartphone else Icons.Rounded.SdStorage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = fg,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (vol.isPrimary) internalLabel else vol.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = fg,
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
                    .background(scheme.onSurface.copy(alpha = 0.07f))
                    .border(1.dp, scheme.outline.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoUp) {
                    IconButton(
                        onClick = { currentDir.parentFile?.let(onNavigate) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = scheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = currentDir.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
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
                        tint = scheme.onSurface,
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

            var visibleLimit by remember(currentDir.path) { mutableStateOf(200) }
            Column(modifier = Modifier.fillMaxWidth()) {
                subdirs.take(visibleLimit).forEach { dir ->
                  key(dir.path) {
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
                            colors = CheckboxDefaults.colors(
                                checkedColor = scheme.primary,
                                checkmarkColor = scheme.onPrimary,
                                uncheckedColor = scheme.onSurfaceVariant,
                            ),
                        )
                        Icon(
                            imageVector = if (checked) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (checked) scheme.onSurface else scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = dir.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                  }
                }
                if (subdirs.size > visibleLimit) {
                    TextButton(onClick = { visibleLimit += 200 }) {
                        Text(
                            text = "+${subdirs.size - visibleLimit}",
                            color = scheme.onSurface,
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
internal object LocalMusicDialogRequests {
    val editTagsTarget = kotlinx.coroutines.flow.MutableStateFlow<com.music.bitchord.data.model.Song?>(null)
    val deleteDeviceTargets = kotlinx.coroutines.flow.MutableStateFlow<List<com.music.bitchord.data.model.Song>>(emptyList())
}

@Composable
internal fun LocalMusicEffectsAndDialogs(
    viewModel: MainViewModel,
    topDetailBrowseId: String?,
    showFolderPicker: Boolean,
    showClearConfirm: Boolean,
    onDismissFolderPicker: () -> Unit,
    onDismissClearConfirm: () -> Unit,
    onRequestStorageForScan: (LocalMusicScanMode, List<LocalScanFolder>, List<LocalScanFolder>) -> Unit,
    onSongsDeleted: (List<com.music.bitchord.data.model.Song>) -> Unit = {},
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val localMusicScanMode by AppSettings.localMusicScanMode.collectAsStateWithLifecycle()
    val localMusicFolders by AppSettings.localMusicFolders.collectAsStateWithLifecycle()
    val localMusicExcludedFolders by AppSettings.localMusicExcludedFolders.collectAsStateWithLifecycle()
    val localLegacyScanMode by AppSettings.localLegacyScanMode.collectAsStateWithLifecycle()
    val localMusicMinDurationSec by AppSettings.localMusicMinDurationSec.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val editTagsTarget by LocalMusicDialogRequests.editTagsTarget.collectAsStateWithLifecycle()
    val deleteDeviceTargets by LocalMusicDialogRequests.deleteDeviceTargets.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        LocalStorageVolumes.volumeChanges(context).collect {
            kotlinx.coroutines.delay(1_000)
            viewModel.refreshLocalLibraryIfConfigured()
        }
    }
    LaunchedEffect(
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
        if (topDetailBrowseId == "local:all" || LocalGroups.folderOf(topDetailBrowseId) == "local:all") {
            viewModel.reloadLocalDetail(topDetailBrowseId!!)
        }
    }
    LaunchedEffect(topDetailBrowseId) {
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
                // Request audio/storage permission immediately for ALL_FOLDERS or any
                // SPECIFIC_FOLDERS scan when not granted yet so scanning starts right away.
                val needsPermission = !LocalMediaRepository.hasStoragePermission(context) && (
                    mode == LocalMusicScanMode.ALL_FOLDERS ||
                        folders.any { !it.isTreeUri } ||
                        folders.any { it.isTreeUri }
                    )
                if (needsPermission) {
                    onRequestStorageForScan(mode, folders, excluded)
                } else {
                    viewModel.startLocalScanFolders(mode, folders, excluded)
                }
            },
            onDismiss = onDismissFolderPicker,
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = onDismissClearConfirm,
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.local_clear_library_confirm_title)) },
            text = { Text(stringResource(R.string.local_clear_library_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissClearConfirm()
                        viewModel.clearLocalLibrary()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed),
                ) {
                    Text(stringResource(R.string.local_clear_library), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissClearConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    editTagsTarget?.let { target ->
        com.music.bitchord.ui.components.LocalTagEditorDialog(
            song = target,
            onSaved = {},
            onDismiss = { LocalMusicDialogRequests.editTagsTarget.value = null },
        )
    }
    if (deleteDeviceTargets.isNotEmpty()) {
        com.music.bitchord.ui.components.LocalDeleteFromDeviceDialog(
            songs = deleteDeviceTargets,
            onDeleted = onSongsDeleted,
            onDismiss = { LocalMusicDialogRequests.deleteDeviceTargets.value = emptyList() },
        )
    }
}
