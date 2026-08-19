package com.thelightphone.radio

import android.media.AudioManager
import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.LightVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Base view model for every Radio screen — owns the volume panel.
 *
 * The LP3's rocker adjusts the active stream itself and broadcasts
 * VOLUME_CHANGED_ACTION (the SDK never forwards volume keys to the screen —
 * LightActivity routes them to super). So the panel is driven by
 * [LightVolume]'s receiver: media changes show the Media panel (playing),
 * ringer changes the Ringer/Silent/Vibrate panel (nothing playing). The
 * panel therefore shows whether or not audio is playing, as long as the
 * tool is in the foreground. [onKeyDown] remains as a fallback for devices
 * that do deliver volume keys.
 *
 * Only the visible screen reacts: hidden screens (pushed under another
 * screen, or the tool minimized) ignore volume changes, so a stale panel
 * never flashes when you return.
 */
abstract class RadioBaseViewModel<T> : LightViewModel<T>() {

    /** The volume panel's state (null = hidden). Hosted by every screen's root. */
    val volumePanel = MutableStateFlow<VolumePanelState?>(null)

    @Volatile
    private var screenVisible = false

    init {
        viewModelScope.launch {
            var last: LightVolume.State? = null
            LightVolume.state.collect { current ->
                val prev = last
                last = current
                // Seed once: ignore the initial read and any transition from
                // an unseeded state (the collector may start before observe).
                if (!screenVisible || prev == null || !prev.seeded || !current.seeded) return@collect
                when {
                    prev.mediaLevel != current.mediaLevel && current.mediaMax > 0 ->
                        volumePanel.value = VolumePanelState.Media(current.mediaLevel, current.mediaMax)
                    prev.ringerLevel != current.ringerLevel || prev.ringerMode != current.ringerMode ->
                        volumePanel.value = when (current.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> VolumePanelState.Silent
                            AudioManager.RINGER_MODE_VIBRATE -> VolumePanelState.Vibrate
                            else -> VolumePanelState.Ringer(current.ringerLevel)
                        }
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        super.onScreenShow(screen)
        screenVisible = true
    }

    override fun onScreenHide(screen: SimpleLightScreen<T>) {
        super.onScreenHide(screen)
        screenVisible = false
    }

    override fun onAppPause() {
        screenVisible = false
        super.onAppPause()
    }

    fun dismissVolumePanel() {
        volumePanel.value = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            val current = LightVolume.state.value
            val newLevel = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> (current.mediaLevel + 1).coerceAtMost(current.mediaMax.coerceAtLeast(1))
                else -> (current.mediaLevel - 1).coerceAtLeast(0)
            }
            volumePanel.value = VolumePanelState.Media(newLevel, current.mediaMax)
            return false // let the platform adjust the media stream
        }
        return super.onKeyDown(keyCode, event)
    }
}
