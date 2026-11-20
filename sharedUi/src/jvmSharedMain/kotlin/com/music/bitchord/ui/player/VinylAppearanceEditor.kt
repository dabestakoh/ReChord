package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.VinylAppearance
import com.music.bitchord.data.settings.VinylColorMode
import com.music.bitchord.data.settings.VinylFinish
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.theme.rememberArtworkPlayerAnalysis
import org.jetbrains.compose.resources.stringResource

@Composable
fun VinylAppearanceEditor(
    value: VinylAppearance,
    onChange: (VinylAppearance) -> Unit,
    artwork: String?,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkPlayerAnalysis(artwork).accent
    val sample = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.aspectRatio(0.76f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(12.dp))) {
                VinylArtwork(
                    artwork, false, { 0.35f }, Modifier.fillMaxSize(),
                    appearance = value, palette = palette ?: sample,
                )
            }
        }
        Text(
            stringResource(Res.string.vinyl_color),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in VinylColorMode.entries) {
                val label = when (mode) {
                    VinylColorMode.BLACK -> Res.string.vinyl_black
                    VinylColorMode.ALBUM -> Res.string.vinyl_album
                    VinylColorMode.CUSTOM -> Res.string.vinyl_custom
                }
                FilterChip(value.colorMode == mode, { onChange(value.copy(colorMode = mode)) }, {
                    Text(stringResource(label))
                })
            }
        }
        if (value.colorMode == VinylColorMode.CUSTOM) {
            val colors = remember(palette) {
                listOfNotNull(palette?.toArgb(), 0xFF111418.toInt(), 0xFFC53C54.toInt(), 0xFFD39433.toInt(),
                    0xFF278170.toInt(), 0xFF33668D.toInt(), 0xFF704D99.toInt(), 0xFFE3D8BE.toInt()).distinct()
            }
            var colorInput by remember { mutableStateOf(hex(value.customColor)) }
            var accentInput by remember { mutableStateOf(value.secondaryColor?.let(::hex).orEmpty()) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                colors.forEach { color ->
                    val label = "#${hex(color)}"
                    val selected = value.customColor == color
                    Box(
                        Modifier.size(48.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = label }
                            .clickable(role = Role.Button) {
                                colorInput = hex(color)
                                onChange(value.copy(customColor = color))
                            }
                            .padding(6.dp)
                            .background(Color(color), CircleShape)
                            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                    )
                }
            }
            VinylHexField(colorInput, stringResource(Res.string.vinyl_custom_hex)) { input, color ->
                colorInput = input
                if (color != null) onChange(value.copy(customColor = color))
            }
            if (value.finish != VinylFinish.PLAIN && value.finish != VinylFinish.PICTURE_DISC && value.finish != VinylFinish.TRANSLUCENT) {
                VinylHexField(accentInput, stringResource(Res.string.vinyl_secondary_hex)) { input, color ->
                    accentInput = input
                    if (input.isEmpty() || color != null) onChange(value.copy(secondaryColor = color))
                }
                TextButton(onClick = {
                    accentInput = ""
                    onChange(value.copy(secondaryColor = null))
                }) { Text(stringResource(Res.string.vinyl_automatic)) }
            }
        }
        Text(
            stringResource(Res.string.vinyl_finish),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (finish in VinylFinish.entries) {
                FilterChip(value.finish == finish, { onChange(value.copy(finish = finish)) }, {
                    Text(stringResource(when (finish) {
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
                    }))
                })
            }
        }
    }
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
