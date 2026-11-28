/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio.bitperfect

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.audio.bitperfect.usb.UsbDirectDevices
import com.music.bitchord.playback.audio.bitperfect.usb.UsbDirectTransport
import com.music.bitchord.playback.audio.bitperfect.usb.UsbVolume
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a [BitPerfectAudioSink] asks of [BitPerfectOutput], kept narrow so a test can stand in for it. */
internal interface BitPerfectOutputs {
    fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean

    fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): BitPerfectTransport

    fun release(requester: Any)

    fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String)

    fun publish(status: BitPerfectOutput.Status)

    fun publishIdle()

    fun reportAndroidMixerFallback(sampleRate: Int, channels: Int, sourceBits: Int) = Unit

    /** See [BitPerfectOutput.normalOutputPending]. */
    fun normalOutputPending(): Boolean
}

/**
 * Process-wide owner of the one exclusive output.
 *
 * Both crossfade players build a [BitPerfectAudioSink], but a DAC opened
 * bit-perfect has room for a single stream: Android's bit-perfect mixer
 * mutes everything else, and a claimed USB interface belongs to one handle.
 * Whichever sink opens first holds it until it releases; the other is told
 * no and stays on the ordinary path.
 *
 * A route that refused to open is remembered until the attached device, the
 * selected mode or [AppSettings.convertForLimitedDac] changes, so a track that
 * keeps failing is not retried on every buffer.
 */
internal object BitPerfectOutput {

    private const val TAG = "BitPerfectOutput"
    private const val NO_DEVICE = -1
    const val NO_ANDROID_MIXER_PROFILE = "no_android_mixer_profile"
    const val ANDROID_MIXER_FORMAT_MISMATCH = "android_mixer_format_mismatch"
    const val ANDROID_MIXER_REJECTED = "android_mixer_rejected"
    private const val DIRECT_HANDBACK_MS = 3000L

    data class Status(
        val active: Boolean = false,
        val driver: String? = null,
        val deviceName: String? = null,
        val sampleRateHz: Int = 0,
        val outputBits: Int = 0,
        val channelCount: Int = 0,
        val validOutputBits: Int = 0,
        val dacOutputVerified: Boolean = false,
        val sourceBits: Int = 0,
        val sourceRateHz: Int = 0,
        /** False once any sample of the current track had to be rounded. */
        val exact: Boolean = true,
        /**
         * The DAC can't take this track as it is, so it is being converted to
         * the best format the DAC has. Never [exact]: check this one first.
         */
        val dacLimited: Boolean = false,
        /** Why the last attempt fell back to normal output, if it did. */
        val fallbackReason: String? = null,
        val openingUsb: Boolean = false,
        val androidMixerOutputVerified: Boolean = false,
        val exclusiveOutputVerified: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /**
     * Whether the selected mode has its device attached, so lossless tracks
     * will be going to the exclusive output: a USB DAC for either USB mode,
     * wired headphones for the built-in DAC. What settings fade and what
     * stops asking for Dolby Atmos follow this, not the bare mode — with the
     * mode on and nothing plugged in, everything plays as normal and should
     * look it.
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var watching = false

    /** Starts following the attached devices; [refreshReady] covers the mode and USB. */
    @Synchronized
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(
            object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = refreshReady(app)
                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = refreshReady(app)
            },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        refreshReady(app)
    }

    private var lastOutputMode: BitPerfectMode? = null
    private var lastAndroidUsbId: Int? = null
    @Volatile private var effectiveMode: BitPerfectMode = BitPerfectMode.OFF

    private const val CAPABILITY_PREFS = "rechord_dac_capabilities"
    private val probeExecutor = Executors.newSingleThreadExecutor { Thread(it, "ReChord-DAC-probe") }

    internal data class WiredCapabilities(
        val key: String,
        val aaudioExclusiveSupported: Boolean,
        val directRatesByDepth: Map<Int, Set<Int>>,
    ) {
        val supportsAnyBitPerfect: Boolean
            get() = aaudioExclusiveSupported || directRatesByDepth.isNotEmpty()
    }

    @Volatile private var wiredCapabilities: WiredCapabilities? = null
    @Volatile private var probingWiredKey: String? = null
    @Volatile private var probingWiredFuture: java.util.concurrent.Future<*>? = null

    private val _usbHasHardwareVolume = MutableStateFlow(false)
    val usbHasHardwareVolume: StateFlow<Boolean> = _usbHasHardwareVolume.asStateFlow()

    private fun wiredKey(device: android.media.AudioDeviceInfo): String {
        val addr = if (Build.VERSION.SDK_INT >= 28) device.address.orEmpty() else ""
        return "${Build.FINGERPRINT}|${device.type}|$addr|${device.productName}"
    }

    private fun loadWiredCapabilities(context: Context, key: String): WiredCapabilities? {
        val encoded = context.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE)
            .getString("wired_$key", null) ?: return null
        var aaudio = false
        val rates = LinkedHashMap<Int, Set<Int>>()
        for (part in encoded.split(';')) {
            if (part.startsWith("ex=")) {
                aaudio = part.substringAfter("ex=") == "1"
            } else if (':' in part) {
                val rate = part.substringBefore(':').toIntOrNull() ?: continue
                val depths = part.substringAfter(':').split(',').mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }
                if (depths.isNotEmpty()) rates[rate] = depths
            }
        }
        return WiredCapabilities(key, aaudio, rates)
    }

    private fun saveWiredCapabilities(context: Context, caps: WiredCapabilities) {
        val encoded = buildString {
            append("ex=").append(if (caps.aaudioExclusiveSupported) '1' else '0')
            for ((rate, depths) in caps.directRatesByDepth) {
                append(';').append(rate).append(':').append(depths.joinToString(","))
            }
        }
        context.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE).edit()
            .putString("wired_${caps.key}", encoded)
            .apply()
    }

    private fun ensureWiredCapabilities(context: Context, audio: AudioManager?) {
        val wired = audio?.let(ExclusiveTransport::wiredHiResDevice)
        if (wired == null) {
            wiredCapabilities = null
            probingWiredKey = null
            return
        }
        val key = wiredKey(wired)
        if (wiredCapabilities?.key == key) return
        val cached = loadWiredCapabilities(context, key)
        if (cached != null) {
            wiredCapabilities = cached
            return
        }
        // AudioManager.getDirectPlaybackSupport queries the current active route;
        // do not probe or persist wired capabilities while Bluetooth or USB is active.
        val activeKind = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            ?.let { AudioRouting.activeOf(it, null) }
            ?.let { AudioRouting.kindOf(it.type) }
        if (activeKind != AudioRouting.Kind.WIRED) return
        if (probingWiredKey == key) return
        probingWiredKey = key
        val app = context.applicationContext
        probingWiredFuture = probeExecutor.submit {
            val directMap = if (Build.VERSION.SDK_INT >= 29) {
                DirectAudioTrackTransport.probeCapabilities()
            } else {
                emptyMap()
            }
            val aaudioOk = if (Build.VERSION.SDK_INT >= 27) {
                runCatching {
                    ExclusiveTransport.open(audio, 48_000, 2, 16, convert = true).release()
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            val probed = WiredCapabilities(key, aaudioOk, directMap)
            saveWiredCapabilities(app, probed)
            Log.i(
                TAG,
                "Probed & persisted wired DAC (${wired.productName}): aaudioExclusive=$aaudioOk, directRates=${directMap.keys}",
            )
            synchronized(this@BitPerfectOutput) {
                if (probingWiredKey == key) {
                    wiredCapabilities = probed
                    probingWiredKey = null
                    probingWiredFuture = null
                    recomputeReady()
                }
            }
        }
    }

    private fun selectedOutputKind(audio: AudioManager?): AudioRouting.Kind? {
        val selectedId = AudioRouting.selectedId.value ?: return null
        val device = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == selectedId }
            ?: return null
        return AudioRouting.kindOf(device.type)
    }

    private fun hasBluetoothOutput(audio: AudioManager?): Boolean =
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            AudioRouting.kindOf(it.type) == AudioRouting.Kind.BLUETOOTH
        } == true

    /**
     * Resolves the active bit-perfect driver for the user's configured mode,
     * automatically engaging built-in Hi-Res (AAudio Exclusive) on wired/DAP
     * outputs when no USB DAC is routed, and respecting Prefer USB DAC,
     * the Audio Output Picker, and Bluetooth priority.
     */
    internal fun resolveEffectiveMode(
        configuredMode: BitPerfectMode,
        selectedOutputKind: AudioRouting.Kind?,
        preferUsbDac: Boolean,
        hasUsbDirectDevice: Boolean,
        hasAndroidUsbDevice: Boolean,
        hasWiredHiResDevice: Boolean,
        hasBluetoothOutput: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
        wiredHiResDriver: com.music.bitchord.data.settings.WiredHiResDriver = AppSettings.wiredHiResDriver.value,
    ): BitPerfectMode = com.music.bitchord.playback.audio.AudioPathPlanner.resolveEffectiveBitPerfectMode(
        configuredMode = configuredMode,
        wiredHiResDriver = wiredHiResDriver,
        selectedOutputKind = selectedOutputKind,
        preferUsbDac = preferUsbDac,
        hasUsbDirectDevice = hasUsbDirectDevice,
        hasAndroidUsbDevice = hasAndroidUsbDevice,
        hasWiredHiResDevice = hasWiredHiResDevice,
        hasBluetoothOutput = hasBluetoothOutput,
        sdkInt = sdkInt,
    )

    private fun resolveEffectiveMode(context: Context, audio: AudioManager?): BitPerfectMode {
        val configuredMode = AppSettings.bitPerfectMode.value
        if (configuredMode == BitPerfectMode.OFF) return BitPerfectMode.OFF
        val hasDirect = UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice) != null
        val hasAndroidUsb = Build.VERSION.SDK_INT >= 34 && audio?.let(MixerTransport::usbDevice) != null
        val hasWired = audio?.let(ExclusiveTransport::wiredHiResDevice) != null
        return resolveEffectiveMode(
            configuredMode = configuredMode,
            selectedOutputKind = selectedOutputKind(audio),
            preferUsbDac = AppSettings.preferUsbDac.value,
            hasUsbDirectDevice = hasDirect,
            hasAndroidUsbDevice = hasAndroidUsb,
            hasWiredHiResDevice = hasWired,
            hasBluetoothOutput = hasBluetoothOutput(audio),
        )
    }

    @Synchronized
    fun refreshReady(context: Context) {
        appContextForApprovals = context.applicationContext
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = resolveEffectiveMode(context, audio)
        effectiveMode = mode
        ensureWiredCapabilities(context, audio)
        val androidUsb = if (mode == BitPerfectMode.USB_ANDROID && Build.VERSION.SDK_INT >= 34) {
            audio?.let(MixerTransport::usbDevice)
        } else null
        if (mode != lastOutputMode || androidUsb?.id != lastAndroidUsbId) {
            _status.value = Status()
            lastOutputMode = mode
            lastAndroidUsbId = androidUsb?.id
        }
        val attached = when (mode) {
            BitPerfectMode.OFF -> false
            BitPerfectMode.USB_ANDROID -> Build.VERSION.SDK_INT >= 34 && audio != null &&
                androidUsb != null && !MixerTransport.bitPerfectProfiles(audio, androidUsb).isNullOrEmpty()
            // Android's own driver lets go of the DAC while this one streams,
            // so it is looked for as a USB device, not an audio output.
            BitPerfectMode.USB_DIRECT ->
                UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice) != null
            BitPerfectMode.DAP_EXCLUSIVE -> Build.VERSION.SDK_INT >= 27 &&
                audio?.let(ExclusiveTransport::wiredHiResDevice) != null
        }
        this.attached = attached
        refreshFixedVolumeDevice(
            context,
            if (mode == BitPerfectMode.USB_DIRECT)
                UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice)
            else null,
        )
        recomputeReady()
    }

    @Volatile private var appContextForApprovals: Context? = null
    @Volatile private var attached = false

    /**
     * Re-derived whenever any of its inputs moves — the mode, the attached
     * device, or the output opening and closing — rather than only on device
     * events. Computed once at the moment the mode went off, it caught the
     * stream that was still closing and stayed true with the mode off.
     */
    private fun recomputeReady() {
        val currentStatus = _status.value
        val wiredCaps = wiredCapabilities
        val wiredUnsupported = effectiveMode == BitPerfectMode.DAP_EXCLUSIVE &&
            wiredCaps != null && !wiredCaps.supportsAnyBitPerfect
        val wiredFallback = effectiveMode == BitPerfectMode.DAP_EXCLUSIVE &&
            !currentStatus.active && currentStatus.fallbackReason != null
        _ready.value = effectiveMode != BitPerfectMode.OFF && !wiredUnsupported && !wiredFallback &&
            (attached || currentStatus.active)
    }

    fun forContext(context: Context): BitPerfectOutputs {
        val app = context.applicationContext
        return object : BitPerfectOutputs {
            override fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                canOpen(app, sampleRate, channels, sourceBits, requester)

            override fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                open(app, sampleRate, channels, sourceBits, requester)

            override fun release(requester: Any) = this@BitPerfectOutput.release(requester)

            override fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String) =
                markFailed(app, sampleRate, channels, sourceBits, reason)

            override fun publish(status: Status) = this@BitPerfectOutput.publish(status)

            override fun publishIdle() = this@BitPerfectOutput.publishIdle()

            override fun reportAndroidMixerFallback(sampleRate: Int, channels: Int, sourceBits: Int) =
                reportAndroidMixerFallback(app, sampleRate, channels, sourceBits)

            override fun normalOutputPending() = normalOutputPending(app)
        }
    }

    /** Fixed-volume approval belongs to a DAC, never to a global setting. */
    data class FixedVolumeDevice(val key: String, val label: String, val approved: Boolean)
    private const val FIXED_VOLUME_PREFS = "rechord_usb_fixed_volume_approvals"
    private val _fixedVolumeDevice = MutableStateFlow<FixedVolumeDevice?>(null)
    val fixedVolumeDevice: StateFlow<FixedVolumeDevice?> = _fixedVolumeDevice.asStateFlow()
    private val _fixedVolumeApprovalRequest = MutableStateFlow<FixedVolumeDevice?>(null)
    val fixedVolumeApprovalRequest: StateFlow<FixedVolumeDevice?> = _fixedVolumeApprovalRequest.asStateFlow()
    private var declinedDuringAttachment: String? = null

    private fun deviceKey(device: android.hardware.usb.UsbDevice): String {
        // A serial distinguishes two identical models when Android exposes it.
        // Otherwise permission is remembered per USB model, not transient deviceId.
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        return "${device.vendorId}:${device.productId}:${device.manufacturerName.orEmpty()}:" +
            "${device.productName.orEmpty()}:$serial"
    }

    private fun isFixedVolumeApproved(context: Context, device: android.hardware.usb.UsbDevice): Boolean =
        context.getSharedPreferences(FIXED_VOLUME_PREFS, Context.MODE_PRIVATE)
            .getBoolean(deviceKey(device), false)

    private fun refreshFixedVolumeDevice(context: Context, device: android.hardware.usb.UsbDevice?) {
        if (device == null) {
            _fixedVolumeDevice.value = null
            _fixedVolumeApprovalRequest.value = null
            _usbHasHardwareVolume.value = false
            declinedDuringAttachment = null
            return
        }
        val key = deviceKey(device)
        val approved = isFixedVolumeApproved(context, device)
        val info = FixedVolumeDevice(key, device.productName?.takeIf(String::isNotBlank) ?: "USB DAC", approved)
        _fixedVolumeDevice.value = info
        if (approved || _fixedVolumeApprovalRequest.value?.key != key) {
            _fixedVolumeApprovalRequest.value = null
        }
        val prefs = context.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE)
        val prefKey = "usb_hw_vol_$key"
        if (prefs.contains(prefKey)) {
            _usbHasHardwareVolume.value = prefs.getBoolean(prefKey, false)
        } else {
            val usb = UsbDirectDevices.manager(context)
            val raw = if (usb != null && usb.hasPermission(device)) UsbDirectDevices.rawDescriptors(usb, device) else null
            if (raw != null) {
                val plans = UsbDirectTransport.plans(raw, 48_000, 2, 16, convert = true)
                val hasHwVol = plans.firstOrNull()?.let { plan ->
                    UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, 2) != null
                } ?: false
                prefs.edit().putBoolean(prefKey, hasHwVol).apply()
                _usbHasHardwareVolume.value = hasHwVol
            } else {
                _usbHasHardwareVolume.value = false
            }
        }
    }

    private fun requestFixedVolumeApproval(context: Context, device: android.hardware.usb.UsbDevice) {
        refreshFixedVolumeDevice(context, device)
        val info = _fixedVolumeDevice.value ?: return
        if (!info.approved && declinedDuringAttachment != info.key) {
            _fixedVolumeApprovalRequest.value = info
        }
    }

    /** Explicit approval, once per connected DAC, after a warning dialog. */
    fun approveFixedVolume(context: Context) {
        val device = UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice) ?: return
        val request = _fixedVolumeApprovalRequest.value ?: return
        if (request.key != deviceKey(device)) return
        context.getSharedPreferences(FIXED_VOLUME_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(request.key, true).apply()
        declinedDuringAttachment = null
        refreshFixedVolumeDevice(context, device)
        UsbDirectDevices.onChanged?.invoke()
    }

    /** Called by a settings toggle to request approval for the current DAC. */
    fun requestCurrentFixedVolumeApproval() {
        _fixedVolumeDevice.value?.takeIf { !it.approved }?.let {
            declinedDuringAttachment = null
            _fixedVolumeApprovalRequest.value = it
        }
    }

    fun dismissFixedVolumeApproval() {
        declinedDuringAttachment = _fixedVolumeApprovalRequest.value?.key
        _fixedVolumeApprovalRequest.value = null
    }

    fun revokeFixedVolume(context: Context) {
        val device = UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice) ?: return
        context.getSharedPreferences(FIXED_VOLUME_PREFS, Context.MODE_PRIVATE).edit()
            .remove(deviceKey(device)).apply()
        refreshFixedVolumeDevice(context, device)
        UsbDirectDevices.onChanged?.invoke()
    }

    /** The DAC's Feature Unit volume while direct USB output is open; null otherwise. */
    private val _hardwareVolume = MutableStateFlow<UsbVolume.Snapshot?>(null)
    val hardwareVolume: StateFlow<UsbVolume.Snapshot?> = _hardwareVolume.asStateFlow()
    private val pendingVolume = AtomicReference<Double?>(null)
    private val volumeExecutor = Executors.newSingleThreadExecutor { Thread(it, "BitChord-USB-volume") }

    init {
        // A detached DAC stops its stream at once rather than at the next write.
        UsbDirectDevices.onDetached = { deviceId ->
            synchronized(this) {
                (active as? UsbDirectTransport)?.takeIf { it.usbDeviceId == deviceId }?.let {
                    it.markDetached()
                    publishVolume(null)
                    DacVolume.onSoftwareLevel(null)
                }
                softwareLevels.remove(deviceId)
            }
        }
    }

    private var owner: Any? = null
    @Volatile private var active: BitPerfectTransport? = null

    @Volatile private var directReleasedAtMs = 0L

    val directHandbackRecent: Boolean
        get() = directReleasedAtMs != 0L &&
            android.os.SystemClock.elapsedRealtime() - directReleasedAtMs <= DIRECT_HANDBACK_MS

    /**
     * Whether a track meant for normal output should wait: the direct driver
     * has just handed the DAC back, and Android's own USB audio driver has
     * not re-attached to it yet. Until it does the DAC is not an audio output
     * at all, and a track started now would play from the phone's speaker —
     * a local Dolby or multichannel file following a lossless one, say.
     * Waits at most [DIRECT_HANDBACK_MS]; a DAC that never comes back (it was
     * unplugged) leaves normal output to Android.
     */
    fun normalOutputPending(context: Context): Boolean {
        val releasedAt = directReleasedAtMs
        if (releasedAt == 0L) return false
        if (android.os.SystemClock.elapsedRealtime() - releasedAt > DIRECT_HANDBACK_MS) {
            directReleasedAtMs = 0L
            return false
        }
        val usbOutput = context.getSystemService(AudioManager::class.java)
            ?.let(MixerTransport::usbDevice) != null
        return !usbOutput
    }
    private val failed = HashSet<String>()
    private var failedFor: String? = null

    /** Whether a sink asking for this stream now could get the exclusive output. */
    @Synchronized
    fun canOpen(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean {
        if (owner != null && owner !== requester) return false
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val mode = resolveEffectiveMode(context, manager)
        effectiveMode = mode
        if (mode == BitPerfectMode.OFF) return false
        return when (mode) {
            BitPerfectMode.OFF -> false
            BitPerfectMode.USB_ANDROID -> {
                if (Build.VERSION.SDK_INT < 34) return false
                val device = MixerTransport.usbDevice(manager) ?: return false
                val profiles = MixerTransport.bitPerfectProfiles(manager, device) ?: return false
                MixerTransport.bestFormat(profiles, sampleRate, channels, sourceBits, AppSettings.convertForLimitedDac.value) != null
            }
            // Nothing short of opening a stream says whether Android will grant
            // exclusive mode, so the first track of each format finds out.
            BitPerfectMode.DAP_EXCLUSIVE -> {
                if (Build.VERSION.SDK_INT < 27) return false
                val wired = ExclusiveTransport.wiredHiResDevice(manager) ?: return false
                val caps = wiredCapabilities?.takeIf { it.key == wiredKey(wired) }
                    ?: loadWiredCapabilities(context, wiredKey(wired))?.also { wiredCapabilities = it }
                if (caps != null && !caps.supportsAnyBitPerfect) return false
                key(mode, NO_DEVICE, sampleRate, channels, sourceBits) !in currentFailures(mode, NO_DEVICE)
            }
            // Permission is asked for by the service when the mode is chosen or a
            // DAC is attached; until it is granted, tracks use normal output.
            BitPerfectMode.USB_DIRECT -> {
                val direct = active as? UsbDirectTransport
                if (owner === requester && direct?.accepts(sampleRate, channels, sourceBits) == true) return true
                val usb = UsbDirectDevices.manager(context) ?: return false
                val device = UsbDirectDevices.audioDevice(usb) ?: return false
                if (!usb.hasPermission(device)) return false
                if (key(mode, device.deviceId, sampleRate, channels, sourceBits) in currentFailures(mode, device.deviceId)) {
                    return false
                }
                val raw = UsbDirectDevices.rawDescriptors(usb, device) ?: return false
                UsbDirectTransport.plans(raw, sampleRate, channels, sourceBits, AppSettings.convertForLimitedDac.value).isNotEmpty()
            }
        }
    }

    /** Opens the exclusive output for [requester], or throws with the reason. */
    @Synchronized
    fun open(
        context: Context,
        sampleRate: Int,
        channels: Int,
        sourceBits: Int,
        requester: Any,
    ): BitPerfectTransport {
        if (owner != null && owner !== requester) throw BitPerfectTransportException("busy")
        val manager = context.getSystemService(AudioManager::class.java)
            ?: throw BitPerfectTransportException("no_audio_manager")
        val mode = resolveEffectiveMode(context, manager)
        effectiveMode = mode
        val convert = AppSettings.convertForLimitedDac.value
        val transport = when (mode) {
            BitPerfectMode.OFF -> throw BitPerfectTransportException("off")
            BitPerfectMode.USB_ANDROID -> {
                if (Build.VERSION.SDK_INT < 34) throw BitPerfectTransportException("android_version")
                MixerTransport.open(manager, sampleRate, channels, sourceBits, convert)
            }
            BitPerfectMode.DAP_EXCLUSIVE -> {
                if (Build.VERSION.SDK_INT < 27) throw BitPerfectTransportException("android_version")
                val wired = ExclusiveTransport.wiredHiResDevice(manager)
                    ?: throw BitPerfectTransportException("exclusive_unavailable")
                val key = wiredKey(wired)
                val inFlight = probingWiredFuture
                if (inFlight != null && probingWiredKey == key) {
                    runCatching { inFlight.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
                val caps = wiredCapabilities?.takeIf { it.key == key }
                    ?: loadWiredCapabilities(context, key)?.also { wiredCapabilities = it }
                val cachedForChannels = caps?.directRatesByDepth?.takeIf { channels == 2 }
                if (caps != null && !caps.aaudioExclusiveSupported && caps.directRatesByDepth.isNotEmpty() && Build.VERSION.SDK_INT >= 29) {
                    DirectAudioTrackTransport.open(manager, sampleRate, channels, sourceBits, convert, cachedForChannels)
                } else if (caps != null && !caps.supportsAnyBitPerfect) {
                    throw BitPerfectTransportException("exclusive_unavailable")
                } else {
                    try {
                        ExclusiveTransport.open(manager, sampleRate, channels, sourceBits, convert)
                    } catch (error: BitPerfectTransportException) {
                        if (error.message == "exclusive_unavailable" && Build.VERSION.SDK_INT >= 29) {
                            val directMap = cachedForChannels ?: DirectAudioTrackTransport.probeCapabilities(channels)
                            DirectAudioTrackTransport.open(manager, sampleRate, channels, sourceBits, convert, directMap).also {
                                if (sampleRate == 48_000 && channels == 2 && sourceBits == 16) {
                                    val updated = WiredCapabilities(key, aaudioExclusiveSupported = false, directRatesByDepth = directMap)
                                    wiredCapabilities = updated
                                    saveWiredCapabilities(context, updated)
                                }
                            }
                        } else {
                            throw error
                        }
                    }
                }
            }
            BitPerfectMode.USB_DIRECT -> {
                val usb = UsbDirectDevices.manager(context) ?: throw BitPerfectTransportException("no_usb")
                val device = UsbDirectDevices.audioDevice(usb) ?: throw BitPerfectTransportException("no_usb")
                if (!usb.hasPermission(device)) throw BitPerfectTransportException("permission_denied")
                val devKey = deviceKey(device)
                val prefs = context.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE)
                val persistedLevel = prefs.getFloat("usb_sw_vol_$devKey", -1f).takeIf { it in 0f..1f }
                val initialSwLevel = softwareLevels[device.deviceId] ?: persistedLevel ?: 0.5f
                softwareLevels[device.deviceId] = initialSwLevel
                activeUsbDeviceKey = devKey
                UsbDirectTransport.open(
                    manager = usb,
                    device = device,
                    rate = sampleRate,
                    channels = channels,
                    sourceBits = sourceBits,
                    convert = convert,
                    allowFixedVolume = true,
                    rememberedDb = UsbDirectDevices.rememberedVolume(device.deviceId),
                    softwareVolume = true,
                    softwareLevel = initialSwLevel,
                    keepAliveSpeaker = context.getSystemService(AudioManager::class.java)
                        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
                )
            }
        }
        owner = requester
        active = transport
        val direct = transport as? UsbDirectTransport
        if (direct != null) {
            val verifiedHwVol = direct.volume != null
            _usbHasHardwareVolume.value = verifiedHwVol
            UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice)?.let { usbDev ->
                context.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("usb_hw_vol_${deviceKey(usbDev)}", verifiedHwVol)
                    .apply()
            }
        }
        publishVolume(direct?.volume)
        DacVolume.onSoftwareLevel(direct?.takeIf { it.softwareVolume }?.softwareLevel)
        return transport
    }

    /**
     * A newer request still queued keeps [DacVolume]'s level where it was
     * dragged to; only the last one's read-back is allowed to move it.
     */
    private val softwareLevels = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    @Volatile private var activeUsbDeviceKey: String? = null

    fun setSoftwareVolume(level: Float) {
        val direct = (active as? UsbDirectTransport)?.takeIf { it.softwareVolume } ?: return
        direct.softwareLevel = level
        softwareLevels[direct.usbDeviceId] = level
        val key = activeUsbDeviceKey ?: return
        appContextForApprovals?.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putFloat("usb_sw_vol_$key", level)
            ?.apply()
    }

    private fun publishVolume(snapshot: UsbVolume.Snapshot?) {
        _hardwareVolume.value = snapshot
        DacVolume.onSnapshot(snapshot)
    }

    @Synchronized
    fun release(requester: Any) {
        if (owner !== requester) return
        if (active is UsbDirectTransport) {
            val usbStillAttached = appContextForApprovals
                ?.let(UsbDirectDevices::manager)
                ?.let(UsbDirectDevices::audioDevice) != null
            if (usbStillAttached) {
                directReleasedAtMs = android.os.SystemClock.elapsedRealtime()
            } else {
                directReleasedAtMs = 0L
            }
        }
        owner = null
        active = null
        activeUsbDeviceKey = null
        publishVolume(null)
        DacVolume.onSoftwareLevel(null)
    }

    /**
     * Sets the DAC's own volume while direct USB output is open. Requests
     * arrive as fast as a slider moves; only the latest pending one is sent.
     */
    fun setHardwareVolume(db: Double) {
        if (pendingVolume.getAndSet(db) != null) return
        volumeExecutor.execute {
            val target = pendingVolume.getAndSet(null) ?: return@execute
            val transport = synchronized(this) { active as? UsbDirectTransport } ?: return@execute
            runCatching { transport.setVolume(target) }
                .onSuccess { snapshot ->
                    UsbDirectDevices.rememberVolume(transport.usbDeviceId, snapshot.currentDb)
                    synchronized(DacVolume) {
                        if (pendingVolume.get() == null) publishVolume(snapshot) else _hardwareVolume.value = snapshot
                    }
                }
                .onFailure { Log.w(TAG, "USB volume change failed", it) }
        }
    }

    @Synchronized
    private fun reportAndroidMixerFallback(context: Context, rate: Int, channels: Int, bits: Int) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val mode = resolveEffectiveMode(context, manager)
        if (mode != BitPerfectMode.USB_ANDROID ||
            Build.VERSION.SDK_INT < 34 || owner != null || _status.value.active
        ) return
        val device = MixerTransport.usbDevice(manager) ?: return
        val profiles = MixerTransport.bitPerfectProfiles(manager, device)
        val reason = when {
            profiles == null -> ANDROID_MIXER_REJECTED
            profiles.isEmpty() -> NO_ANDROID_MIXER_PROFILE
            channels !in 1..2 || bits == 0 ||
                MixerTransport.bestFormat(profiles, rate, channels, bits, AppSettings.convertForLimitedDac.value) == null ->
                ANDROID_MIXER_FORMAT_MISMATCH
            else -> return
        }
        Log.i(TAG, "Android USB fallback ($reason) for $rate Hz/$channels ch/$bits-bit on ${device.productName}; profiles=$profiles")
        _status.value = Status(fallbackReason = reason)
    }

    /** Records that this stream could not be opened on the attached device. */
    @Synchronized
    fun markFailed(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, reason: String) {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = resolveEffectiveMode(context, audio)
        effectiveMode = mode
        if (reason == ANDROID_MIXER_REJECTED && mode != BitPerfectMode.USB_ANDROID) return
        val deviceId = when (mode) {
            BitPerfectMode.USB_DIRECT ->
                UsbDirectDevices.manager(context)?.let(UsbDirectDevices::audioDevice)?.deviceId ?: NO_DEVICE
            else -> NO_DEVICE
        }
        // Android can reject an otherwise supported request while its route
        // is changing. The router falls back for this configuration, and a
        // later configuration is allowed to request the mixer again.
        if (mode != BitPerfectMode.USB_ANDROID && reason != "Wired Hi-Res route changed") {
            currentFailures(mode, deviceId).add(key(mode, deviceId, sampleRate, channels, sourceBits))
        }
        Log.i(TAG, "Bit-perfect output unavailable ($reason) for $sampleRate Hz/$channels ch/$sourceBits-bit")
        _status.value = Status(fallbackReason = reason)
        recomputeReady()
    }

    fun publish(status: Status) {
        _status.value = status
        recomputeReady()
    }

    /** Clears an active status, leaving a fallback reason in place for the settings row. */
    fun publishIdle() {
        val current = _status.value
        if ((current.active || current.openingUsb) && current.fallbackReason == null) _status.value = Status()
        recomputeReady()
    }

    private fun currentFailures(mode: BitPerfectMode, deviceId: Int): MutableSet<String> {
        // Fixed-volume permission changes USB_DIRECT eligibility: retry past failures.
        val approved = if (mode == BitPerfectMode.USB_DIRECT) {
            val app = appContextForApprovals
            val device = app?.let(UsbDirectDevices::manager)?.let(UsbDirectDevices::audioDevice)
            app != null && device != null && isFixedVolumeApproved(app, device)
        } else false
        val scope = "$mode/$deviceId/${AppSettings.convertForLimitedDac.value}/$approved"
        if (failedFor != scope) {
            failed.clear()
            failedFor = scope
        }
        return failed
    }

    private fun key(mode: BitPerfectMode, deviceId: Int, rate: Int, channels: Int, bits: Int) =
        "$mode/$deviceId/$rate/$channels/$bits"
}
