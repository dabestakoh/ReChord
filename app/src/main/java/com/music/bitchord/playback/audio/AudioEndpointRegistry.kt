/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio

import android.media.AudioFormat
import android.os.Build
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.WiredHiResDriver
import com.music.bitchord.playback.AudioOutputPolicy
import com.music.bitchord.playback.AudioRouting

/**
 * Single source of truth for resolving the active audio output path across all
 * supported hardware endpoints (Android 14+ USB, Direct USB, DAP built-in Hi-Res,
 * standard wired headphones, Bluetooth LDAC/AAC, HDMI, and phone speaker).
 *
 * Pure and stateless by design so every hardware combination is directly unit-testable.
 */
internal object AudioPathPlanner {

    /**
     * Resolves the effective [BitPerfectMode] for the active hardware and user settings.
     *
     * Rules:
     * - On Android 14+, selecting [BitPerfectMode.USB_ANDROID] never silently hijacks
     *   exclusive [BitPerfectMode.USB_DIRECT], preserving browsing and other-app audio.
     * - On wired / DAP headphone jacks, automatically engages [BitPerfectMode.DAP_EXCLUSIVE]
     *   when [wiredHiResDriver] is [WiredHiResDriver.AUTO] and no USB DAC is routed.
     * - Selecting [WiredHiResDriver.STANDARD_AUDIO_TRACK] bypasses exclusive/direct
     *   and stays on the standard AudioTrack route with full 32-bit float precision.
     */
    fun resolveEffectiveBitPerfectMode(
        configuredMode: BitPerfectMode,
        wiredHiResDriver: WiredHiResDriver,
        selectedOutputKind: AudioRouting.Kind?,
        preferUsbDac: Boolean,
        hasUsbDirectDevice: Boolean,
        hasAndroidUsbDevice: Boolean,
        hasWiredHiResDevice: Boolean,
        hasBluetoothOutput: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): BitPerfectMode {
        if (configuredMode == BitPerfectMode.OFF) return BitPerfectMode.OFF
        val usbDriver = if (configuredMode == BitPerfectMode.USB_ANDROID && sdkInt >= 34) {
            BitPerfectMode.USB_ANDROID
        } else {
            BitPerfectMode.USB_DIRECT
        }
        val usbAttached = when (usbDriver) {
            BitPerfectMode.USB_DIRECT -> hasUsbDirectDevice
            BitPerfectMode.USB_ANDROID -> hasAndroidUsbDevice
            else -> false
        }
        val wiredBitPerfectAllowed =
            wiredHiResDriver == WiredHiResDriver.AUTO && hasWiredHiResDevice && sdkInt >= 27

        if (selectedOutputKind != null) {
            return when (selectedOutputKind) {
                AudioRouting.Kind.USB -> if (usbAttached) usbDriver else BitPerfectMode.OFF
                AudioRouting.Kind.WIRED ->
                    if (wiredBitPerfectAllowed) BitPerfectMode.DAP_EXCLUSIVE else BitPerfectMode.OFF
                else -> BitPerfectMode.OFF
            }
        }
        if (usbAttached && (preferUsbDac || !hasBluetoothOutput)) {
            return usbDriver
        }
        if (wiredBitPerfectAllowed && !hasBluetoothOutput) {
            return BitPerfectMode.DAP_EXCLUSIVE
        }
        return BitPerfectMode.OFF
    }

    /**
     * Resolves whether the standard AudioTrack + DSP route should open a 32-bit float
     * track (preserving 24-bit / 32-bit float audio up to 384 kHz end-to-end).
     */
    fun shouldEnableFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisedEncodings: IntArray,
        directFloatSupported: Boolean,
        directPcm24Or32Supported: Boolean,
        bluetoothCodecBitDepth: Int?,
    ): Boolean {
        val isExternalWiredOrUsb = routeKind == AudioRouting.Kind.USB ||
            routeKind == AudioRouting.Kind.WIRED ||
            routeKind == AudioRouting.Kind.HDMI
        val advertisesHiRes = AudioFormat.ENCODING_PCM_FLOAT in advertisedEncodings ||
            (isExternalWiredOrUsb &&
                (AudioFormat.ENCODING_PCM_24BIT_PACKED in advertisedEncodings ||
                    AudioFormat.ENCODING_PCM_32BIT in advertisedEncodings)) ||
            (requestedMode == OutputPcmMode.FLOAT_32 && routeKind == AudioRouting.Kind.WIRED)
        val directHiResSupported = directFloatSupported ||
            (isExternalWiredOrUsb && directPcm24Or32Supported)
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = requestedMode,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesHiRes || directHiResSupported,
            bluetoothCodecBitDepth = bluetoothCodecBitDepth,
        )
    }
}
