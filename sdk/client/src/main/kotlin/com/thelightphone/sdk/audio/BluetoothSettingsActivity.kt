package com.thelightphone.sdk.audio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Transparent bridge to the system Bluetooth settings.
 *
 * A tool cannot call `startActivity` directly (the SDK plugin forbids it), and
 * launching from a background service is unreliable — so the tool (foreground
 * process) starts this activity via `SimpleLightScreen.openBluetoothSettings`,
 * which opens the phone's Bluetooth settings and finishes.
 */
class BluetoothSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
