package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether a Bluetooth audio (A2DP) device is connected, with no
 * BLUETOOTH permission required: the audio framework reports its active
 * output devices via [AudioManager.getDevices], which needs no permission
 * (only per-device details like address/name would need BLUETOOTH_CONNECT).
 *
 * Started by [LightMediaService] so it runs in the tool's process without
 * the tool needing Context access (the tool plugin bans it). Tools observe
 * [connected] directly from Compose.
 */
object LightBluetooth {
    private val _connected = MutableStateFlow(false)

    /** Whether a Bluetooth A2DP audio device is currently connected. */
    val connected: StateFlow<Boolean> = _connected

    @Volatile
    private var observing = false
    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /** Idempotent; safe to call from any process start (e.g. service onCreate). */
    fun observe(context: Context) {
        if (observing) return
        observing = true
        val audio = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = audio
        _connected.value = isA2dpConnected(audio)
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
        }
        audio.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun update() {
        audioManager?.let { _connected.value = isA2dpConnected(it) }
    }

    private fun isA2dpConnected(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
}
