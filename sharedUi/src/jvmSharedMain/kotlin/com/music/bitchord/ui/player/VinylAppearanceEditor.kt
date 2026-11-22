package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.VinylAppearance
import com.music.bitchord.data.settings.VinylColorMode
import com.music.bitchord.data.settings.VinylFinish
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.theme.rememberArtworkPlayerAnalysis
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VinylAppearanceEditor(
    value: VinylAppearance,
    onChange: (VinylAppearance) -> Unit,
    artwork: String?,
    modifier: Modifier = Modifier,
    availableHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val palette = rememberArtworkPlayerAnalysis(artwork).accent
    val sample = MaterialTheme.colorScheme.primary
    val activePalette = palette ?: sample
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val backdropBase = Color(0xFF141417)
    val backdropTint = lerp(backdropBase, activePalette, 0.34f)
    val colorEditable = value.finish != VinylFinish.PICTURE_DISC && value.finish != VinylFinish.GOLD_RECORD
    val usesAccent = value.finish != VinylFinish.PLAIN && value.finish != VinylFinish.TRANSLUCENT

    val showcaseCard: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { cardHeight ->
        // Live spinning showcase card on an ambient dark platter backdrop.
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(backdropTint, backdropBase)))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .aspectRatio(0.84f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                VinylArtwork(
                    artwork = artwork,
                    isPlaying = !reduceAnimation,
                    shown = { 0.36f },
                    modifier = Modifier.fillMaxSize(),
                    appearance = value,
                    palette = activePalette,
                )
            }
        }
    }

    val controlsContent: @Composable (Int) -> Unit = { finishColumns ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (colorEditable) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(Res.string.vinyl_color),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    VinylSegmentedBar(
                        options = VinylColorMode.entries.map { mode ->
                            stringResource(
                                when (mode) {
                                    VinylColorMode.BLACK -> Res.string.vinyl_black
                                    VinylColorMode.ALBUM -> Res.string.vinyl_album
                                    VinylColorMode.CUSTOM -> Res.string.vinyl_custom
                                },
                            )
                        },
                        selectedIndex = value.colorMode.ordinal,
                        onSelect = { index -> onChange(value.copy(colorMode = VinylColorMode.entries[index])) },
                    )

                    if (value.colorMode == VinylColorMode.CUSTOM) {
                        val primarySwatches = remember(palette) {
                            listOfNotNull(
                                palette?.toArgb(),
                                0xFF14161A.toInt(),
                                0xFFC53C54.toInt(),
                                0xFFD39433.toInt(),
                                0xFF278170.toInt(),
                                0xFF33668D.toInt(),
                                0xFF704D99.toInt(),
                                0xFFE3D8BE.toInt(),
                            ).distinct()
                        }
                        val accentSwatches = remember(palette) {
                            listOfNotNull(
                                palette?.toArgb(),
                                0xFFF2ECE1.toInt(),
                                0xFFE05A72.toInt(),
                                0xFFE8B75B.toInt(),
                                0xFF4BB5A0.toInt(),
                                0xFF669FD4.toInt(),
                                0xFFB084E6.toInt(),
                            ).distinct()
                        }
                        var colorInput by remember(value.customColor) { mutableStateOf(hex(value.customColor)) }
                        var accentInput by remember(value.secondaryColor) {
                            mutableStateOf(value.secondaryColor?.let(::hex).orEmpty())
                        }
                        var showHex by remember { mutableStateOf(false) }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            primarySwatches.forEach { color ->
                                VinylColorDot(
                                    color = Color(color),
                                    label = "#${hex(color)}",
                                    selected = value.customColor == color,
                                    onClick = {
                                        colorInput = hex(color)
                                        onChange(value.copy(customColor = color))
                                    },
                                )
                            }
                        }

                        if (usesAccent) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(Res.string.vinyl_accent_color),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = {
                                    accentInput = ""
                                    onChange(value.copy(secondaryColor = null))
                                }) {
                                    Text(stringResource(Res.string.vinyl_automatic))
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                accentSwatches.forEach { color ->
                                    VinylColorDot(
                                        color = Color(color),
                                        label = "#${hex(color)}",
                                        selected = value.secondaryColor == color,
                                        onClick = {
                                            accentInput = hex(color)
                                            onChange(value.copy(secondaryColor = color))
                                        },
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = { showHex = !showHex },
                            modifier = Modifier.align(Alignment.Start),
                        ) {
                            Text(stringResource(Res.string.vinyl_hex_codes))
                        }
                        if (showHex) {
                            VinylHexField(colorInput, stringResource(Res.string.vinyl_custom_hex)) { input, color ->
                                colorInput = input
                                if (color != null) onChange(value.copy(customColor = color))
                            }
                            if (usesAccent) {
                                VinylHexField(accentInput, stringResource(Res.string.vinyl_secondary_hex)) { input, color ->
                                    accentInput = input
                                    if (input.isEmpty() || color != null) onChange(value.copy(secondaryColor = color))
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(Res.string.vinyl_finish),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Visual grid of mini vinyl previews (adapts column count to available width).
                val rows = remember(finishColumns) { VinylFinish.entries.chunked(finishColumns) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { finish ->
                                val isSelected = value.finish == finish
                                val label = stringResource(vinylFinishLabel(finish))
                                val tileShape = RoundedCornerShape(16.dp)
                                val tileBg = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                }
                                val borderColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(tileShape)
                                        .background(tileBg)
                                        .border(if (isSelected) 2.dp else 1.dp, borderColor, tileShape)
                                        .semantics { selected = isSelected }
                                        .clickable(role = Role.RadioButton) {
                                            onChange(value.copy(finish = finish))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    VinylFinishSwatch(
                                        appearance = value.copy(finish = finish),
                                        palette = activePalette,
                                        artwork = artwork,
                                        modifier = Modifier.size(56.dp),
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            repeat(finishColumns - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportHeight = availableHeight ?: if (maxHeight != androidx.compose.ui.unit.Dp.Infinity) maxHeight else null
        val useWideLandscape = maxWidth >= 560.dp && (viewportHeight == null || maxWidth > viewportHeight || viewportHeight < 520.dp)
        if (useWideLandscape) {
            val previewHeight = ((viewportHeight ?: 420.dp) * 0.56f).coerceIn(168.dp, 236.dp)
            val rightCols = ((maxWidth * 0.62f) / 98.dp).toInt().coerceIn(3, 5)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(0.36f)) { showcaseCard(previewHeight) }
                Box(Modifier.weight(0.64f)) { controlsContent(rightCols) }
            }
        } else {
            val previewHeight = if (viewportHeight != null) {
                minOf(232.dp, (viewportHeight * 0.34f).coerceAtLeast(168.dp))
            } else {
                232.dp
            }
            val cols = (maxWidth / 104.dp).toInt().coerceIn(3, 5)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                showcaseCard(previewHeight)
                controlsContent(cols)
            }
        }
    }
}

private fun vinylFinishLabel(finish: VinylFinish): StringResource = when (finish) {
    VinylFinish.PLAIN -> Res.string.vinyl_plain
    VinylFinish.TRANSLUCENT -> Res.string.vinyl_translucent
    VinylFinish.SMOKED -> Res.string.vinyl_smoked
    VinylFinish.MARBLED -> Res.string.vinyl_marbled
    VinylFinish.SWIRL -> Res.string.vinyl_swirl
    VinylFinish.GALAXY -> Res.string.vinyl_galaxy
    VinylFinish.SPLATTER -> Res.string.vinyl_splatter
    VinylFinish.SPLIT -> Res.string.vinyl_split
    VinylFinish.COLOR_IN_COLOR -> Res.string.vinyl_color_in_color
    VinylFinish.SHIMMER -> Res.string.vinyl_shimmer
    VinylFinish.METALLIC -> Res.string.vinyl_metallic
    VinylFinish.GLITTER -> Res.string.vinyl_glitter
    VinylFinish.PICTURE_DISC -> Res.string.vinyl_picture_disc
    VinylFinish.PINWHEEL -> Res.string.vinyl_pinwheel
    VinylFinish.TRI_COLOR -> Res.string.vinyl_tri_color
    VinylFinish.GOLD_RECORD -> Res.string.vinyl_gold_record
}

@Composable
private fun VinylSegmentedBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VinylColorDot(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(5.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun VinylHexField(input: String, label: String, onColor: (String, Int?) -> Unit) {
    val error = input.isNotEmpty() && input.length != 6
    OutlinedTextField(
        value = input,
        onValueChange = { typed ->
            val hex = typed.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
            val rgb = if (hex.length == 6) hex.toIntOrNull(16)?.or(0xFF000000.toInt()) else null
            onColor(hex, rgb)
        },
        label = { Text(label) },
        prefix = { Text("#") },
        supportingText = if (error) { { Text(stringResource(Res.string.vinyl_hex_error)) } } else null,
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hex(color: Int): String = (color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
