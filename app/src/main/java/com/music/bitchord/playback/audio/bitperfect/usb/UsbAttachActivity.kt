package com.music.bitchord.playback.audio.bitperfect.usb

import android.app.Activity
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Lightweight no-display trampoline for [UsbManager.ACTION_USB_DEVICE_ATTACHED].
 *
 * Enabled only while Direct USB mode is active so Android prompts for exclusive
 * USB DAC access when a DAC is plugged in outside ReChord without bothering
 * listeners who use standard output.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsbDirectDevices.init(applicationContext)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            UsbDirectDevices.notifyAttached()
        }
        finish()
    }
}
