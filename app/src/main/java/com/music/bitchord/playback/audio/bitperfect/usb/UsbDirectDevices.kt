/*
 * Copyright (C) 2026 BitChord Project
 *
 * Adapted in part from Mornye (https://github.com/mornyeapp/Mornye),
 * android/app/src/main/kotlin/com/zarz/mornye/UsbDirectPlayback.kt,
 * Copyright 2026 zarzet.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode

/**
 * The USB side of the direct driver that is not about streaming: which
 * attached device is a USB audio DAC, Android's permission to open it, its
 * descriptors, and the volume the listener last chose on it.
 *
 * Permission is asked for once, when the mode is on and a DAC is attached;
 * the answer arrives asynchronously, and [onChanged] tells the player to
 * re-decide its output. Descriptors are read once per attachment, because
 * the playback thread asks "could this track go direct?" for every format.
 */
internal object UsbDirectDevices {

    private const val TAG = "UsbDirectDevices"

    private var appContext: Context? = null
    private var permissionAction = ""
    private var requested: Int? = null
    private val declined = HashSet<Int>()
    private val descriptors = HashMap<Int, ByteArray>()
    private val volumes = HashMap<Int, Double>()

    /** A DAC was attached, detached or granted: whatever is playing should decide again. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Told when a device goes away, so an open stream on it stops at once. */
    @Volatile var onDetached: ((Int) -> Unit)? = null

    @Volatile private var attachPromptEnabled = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    synchronized(this@UsbDirectDevices) {
                        descriptors.remove(device.deviceId)
                        volumes.remove(device.deviceId)
                        declined.remove(device.deviceId)
                        if (requested == device.deviceId) requested = null
                    }
                    onDetached?.invoke(device.deviceId)
                    onChanged?.invoke()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    synchronized(this@UsbDirectDevices) {
                        declined.remove(device.deviceId)
                    }
                    // When UsbAttachActivity is enabled and permission is not yet granted,
                    // let Android's single system attach dialog handle it instead of firing
                    // a duplicate requestPermission() from PlaybackService.
                    if (!attachPromptEnabled || manager(context)?.hasPermission(device) == true) {
                        onChanged?.invoke()
                    }
                }
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    synchronized(this@UsbDirectDevices) {
                        requested = null
                        if (granted) declined.remove(device.deviceId) else declined.add(device.deviceId)
                    }
                    Log.i(TAG, "USB permission for ${device.productName}: $granted")
                    if (granted) onChanged?.invoke()
                }
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        permissionAction = "${app.packageName}.USB_AUDIO_PERMISSION"
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }

    fun manager(context: Context): UsbManager? = context.getSystemService(UsbManager::class.java)

    /** The first attached device with a USB Audio streaming interface, by device id. */
    fun audioDevice(manager: UsbManager): UsbDevice? =
        runCatching { manager.deviceList.values }.getOrDefault(emptyList()).sortedBy { it.deviceId }.firstOrNull { device ->
            (0 until device.interfaceCount).any {
                val iface = device.getInterface(it)
                iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2
            }
        }

    /** Asks Android for permission to the attached DAC, once per attachment unless [force] is true. */
    @Synchronized
    fun requestPermission(context: Context, force: Boolean = false) {
        val manager = manager(context) ?: return
        val device = audioDevice(manager) ?: return
        if (force) declined.remove(device.deviceId)
        if (manager.hasPermission(device) || requested == device.deviceId || device.deviceId in declined) return
        requested = device.deviceId
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(permissionAction).setPackage(context.packageName),
            flags,
        )
        runCatching { manager.requestPermission(device, intent) }
            .onFailure { requested = null; Log.w(TAG, "USB permission request failed", it) }
    }

    /** The DAC's descriptors, read once per attachment; null without permission. */
    @Synchronized
    fun rawDescriptors(manager: UsbManager, device: UsbDevice): ByteArray? {
        descriptors[device.deviceId]?.let { return it }
        if (!manager.hasPermission(device)) return null
        val connection = runCatching { manager.openDevice(device) }.getOrNull() ?: return null
        return try {
            connection.rawDescriptors?.also { descriptors[device.deviceId] = it }
        } finally {
            connection.close()
        }
    }

    @Synchronized
    fun rememberedVolume(deviceId: Int): Double? = volumes[deviceId]

    @Synchronized
    fun rememberVolume(deviceId: Int, db: Double) {
        volumes[deviceId] = db
    }

    /** Called by [UsbAttachActivity] after the user approves a system USB attach prompt. */
    fun notifyAttached() {
        onChanged?.invoke()
    }

    /**
     * Enables or disables [UsbAttachActivity] so Android prompts for exclusive
     * USB DAC access on external attach only while Direct USB mode is active.
     */
    fun setAttachPromptEnabled(context: Context, enabled: Boolean) {
        attachPromptEnabled = enabled
        runCatching {
            val app = context.applicationContext
            val component = ComponentName(app, UsbAttachActivity::class.java)
            val desired = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (app.packageManager.getComponentEnabledSetting(component) != desired) {
                app.packageManager.setComponentEnabledSetting(
                    component,
                    desired,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    /**
     * Requests USB DAC permission from the foreground Activity whenever the app
     * resumes while Direct USB mode is active, avoiding Android 14+/OEM
     * background-service dialog restrictions without touching MainActivity.
     */
    fun installForegroundPrompt(app: Application) {
        val direct = AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT
        setAttachPromptEnabled(app, direct)
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is UsbAttachActivity) return
                    val isDirect = AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT
                    setAttachPromptEnabled(activity, isDirect)
                    if (isDirect) {
                        init(activity)
                        requestPermission(activity)
                    }
                }
                override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, o: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            },
        )
    }
}
