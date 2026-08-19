package com.thelightphone.sdk.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the media and ringer volumes (level of max) with no permissions: a
 * dynamic [VOLUME_CHANGED_ACTION] receiver plus direct AudioManager reads.
 * Started by [LightMediaService] so the tool never needs Context access (the
 * tool plugin bans it). Tools read [state] directly from Compose to drive the
 * volume panel.
 *
 * Both streams are tracked because the rocker adjusts whichever stream is
 * "active": media while audio plays, ringer otherwise — the panel shows the
 * Media or Ringer replica accordingly.
 */
object LightVolume {
    /**
     * Volume snapshot. A stream's max is 0 until its first read.
     * [ringerMode] is [AudioManager.RINGER_MODE_NORMAL]/[_SILENT]/[_VIBRATE].
     * [seeded] is false until the first real read — consumers must ignore
     * transitions from an unseeded state (the collector may start before
     * [observe] runs).
     */
    data class State(
        val mediaLevel: Int = 0,
        val mediaMax: Int = 0,
        val ringerLevel: Int = 0,
        val ringerMax: Int = 0,
        val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        val seeded: Boolean = false,
    )

    private val _state = MutableStateFlow(State())

    /** Current volume snapshot (media + ringer). */
    val state: StateFlow<State> = _state

    @Volatile
    private var observing = false
    private var audioManager: AudioManager? = null
    private var receiver: BroadcastReceiver? = null

    /** Idempotent; safe to call from any process start (e.g. service onCreate). */
    fun observe(context: Context) {
        if (observing) return
        observing = true
        val audio = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = audio
        update(audio)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Constant not present in the compile SDK's android.jar — use
                // the literal, like audiobooks' VolumeChangeMonitor.
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    audioManager?.let(::update)
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver!!,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun update(audio: AudioManager) {
        _state.value = State(
            mediaLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            ringerLevel = audio.getStreamVolume(AudioManager.STREAM_RING),
            ringerMax = audio.getStreamMaxVolume(AudioManager.STREAM_RING),
            ringerMode = audio.ringerMode,
            seeded = true,
        )
    }
}
