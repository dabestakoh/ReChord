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
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.WiredHiResDriver
import com.music.bitchord.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEndpointRegistryTest {

    @Test
    fun android14UsbNeverSilentlyHijacksDirectUsb() {
        val mode = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_ANDROID,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = true,
            hasAndroidUsbDevice = false,
            hasWiredHiResDevice = false,
            hasBluetoothOutput = false,
            sdkInt = 34,
        )
        assertEquals(BitPerfectMode.OFF, mode)
    }

    @Test
    fun android14UsbActivatesWhenAndroidUsbDevicePresent() {
        val mode = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_ANDROID,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = true,
            hasAndroidUsbDevice = true,
            hasWiredHiResDevice = false,
            hasBluetoothOutput = false,
            sdkInt = 34,
        )
        assertEquals(BitPerfectMode.USB_ANDROID, mode)
    }

    @Test
    fun android14DirectUsbOptInWorks() {
        val mode = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_DIRECT,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = true,
            hasAndroidUsbDevice = true,
            hasWiredHiResDevice = false,
            hasBluetoothOutput = false,
            sdkInt = 34,
        )
        assertEquals(BitPerfectMode.USB_DIRECT, mode)
    }

    @Test
    fun dapWiredHiResEngagesAutoAndRespectsStandardAudioTrackOverride() {
        val autoMode = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_DIRECT,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = false,
            hasAndroidUsbDevice = false,
            hasWiredHiResDevice = true,
            hasBluetoothOutput = false,
            sdkInt = 31,
        )
        assertEquals(BitPerfectMode.DAP_EXCLUSIVE, autoMode)

        val standardOverride = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_DIRECT,
            wiredHiResDriver = WiredHiResDriver.STANDARD_AUDIO_TRACK,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = false,
            hasAndroidUsbDevice = false,
            hasWiredHiResDevice = true,
            hasBluetoothOutput = false,
            sdkInt = 31,
        )
        assertEquals(BitPerfectMode.OFF, standardOverride)
    }

    @Test
    fun usbAndBluetoothRespectPreferUsbDac() {
        val preferUsb = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_DIRECT,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = true,
            hasUsbDirectDevice = true,
            hasAndroidUsbDevice = false,
            hasWiredHiResDevice = false,
            hasBluetoothOutput = true,
            sdkInt = 31,
        )
        assertEquals(BitPerfectMode.USB_DIRECT, preferUsb)

        val preferBt = AudioPathPlanner.resolveEffectiveBitPerfectMode(
            configuredMode = BitPerfectMode.USB_DIRECT,
            wiredHiResDriver = WiredHiResDriver.AUTO,
            selectedOutputKind = null,
            preferUsbDac = false,
            hasUsbDirectDevice = true,
            hasAndroidUsbDevice = false,
            hasWiredHiResDevice = false,
            hasBluetoothOutput = true,
            sdkInt = 31,
        )
        assertEquals(BitPerfectMode.OFF, preferBt)
    }

    @Test
    fun floatOutputPreservesHiResOnDapUsbAndLdacWhileKeepingSpeakerAndPcm16Safe() {
        // Phone speaker always stays 16-bit
        assertFalse(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
                advertisedEncodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT),
                directFloatSupported = true,
                directPcm24Or32Supported = true,
                bluetoothCodecBitDepth = null,
            ),
        )
        // DAP wired 4.4mm with Direct 24/32-bit preserves Float32 in AUTO
        assertTrue(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.AUTO,
                routeKind = AudioRouting.Kind.WIRED,
                advertisedEncodings = intArrayOf(),
                directFloatSupported = false,
                directPcm24Or32Supported = true,
                bluetoothCodecBitDepth = null,
            ),
        )
        // USB DAC advertising 32-bit preserves Float32 in AUTO and FLOAT_32
        assertTrue(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.AUTO,
                routeKind = AudioRouting.Kind.USB,
                advertisedEncodings = intArrayOf(AudioFormat.ENCODING_PCM_32BIT),
                directFloatSupported = false,
                directPcm24Or32Supported = false,
                bluetoothCodecBitDepth = null,
            ),
        )
        // Explicit 16-bit always stays 16-bit
        assertFalse(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.WIRED,
                advertisedEncodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT),
                directFloatSupported = true,
                directPcm24Or32Supported = true,
                bluetoothCodecBitDepth = null,
            ),
        )
        // Bluetooth LDAC 24-bit uses Float32; AAC 16-bit stays 16-bit
        assertTrue(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.AUTO,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisedEncodings = intArrayOf(),
                directFloatSupported = false,
                directPcm24Or32Supported = false,
                bluetoothCodecBitDepth = 24,
            ),
        )
        assertFalse(
            AudioPathPlanner.shouldEnableFloatOutput(
                requestedMode = OutputPcmMode.AUTO,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisedEncodings = intArrayOf(),
                directFloatSupported = false,
                directPcm24Or32Supported = false,
                bluetoothCodecBitDepth = 16,
            ),
        )
    }
}
