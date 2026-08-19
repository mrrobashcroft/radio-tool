package com.thelightphone.sdk.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * A standard system service that hosts a MediaSession for background audio playback.
 * This is automatically added to a tool's manifest when `enableBackgroundAudio = true`
 * is set in `lighttool.toml`.
 */
@UnstableApi
class LightMediaService : MediaSessionService() {
    
    companion object {
        private const val CHANNEL_ID = "light_audio_channel"
        private const val NOTIFICATION_ID = 1001
        private var activeSession: MediaSession? = null
        private var instance: LightMediaService? = null
        private var wifiLock: WifiManager.WifiLock? = null
        private var wakeLock: PowerManager.WakeLock? = null

        /**
         * Sets the active media session for the background service.
         * Called by LightAudioPlayer when it wants to support background playback.
         */
        fun setActiveSession(session: MediaSession?) {
            activeSession = session
            instance?.attachToSession()
        }

        /**
         * Opens the system Bluetooth settings. The platform server's
         * OpenBluetoothSettings bridge isn't implemented on every device, so
         * the service (which holds Context in the tool's process) launches
         * the settings activity directly.
         */
        fun openBluetoothSettings() {
            instance?.let { service ->
                runCatching {
                    service.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    private var attachedPlayer: Player? = null
    private var playerListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundServiceWithNotification()
        attachToSession()
        LightBluetooth.observe(this)
        LightVolume.observe(this)
    }

    /**
     * (Re)attaches to the active session's player and re-evaluates lock state.
     * The wake/wifi locks are held ONLY while the player actually intends to
     * play (playing or buffering), and released as soon as it pauses, stops,
     * or the session detaches — otherwise a paused player keeps the device
     * awake indefinitely.
     */
    private fun attachToSession() {
        val player = activeSession?.player
        if (player !== attachedPlayer) {
            playerListener?.let { attachedPlayer?.removeListener(it) }
            attachedPlayer = player
            playerListener = player?.let { p ->
                object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        updateLocks(player)
                    }
                }.also { p.addListener(it) }
            }
        }
        updateLocks(player)
        if (player == null) {
            stopSelf()
        }
    }

    private fun updateLocks(player: Player?) {
        val shouldHold = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (shouldHold) {
            acquireLocks()
        } else {
            releaseLocks()
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightRadio:WakeLock")
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }

        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LightRadio:WifiLock")
        }
        wifiLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Radio Playing")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Radio Playing")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Light Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for background audio playback"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return activeSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = activeSession?.player
        if (player == null || !player.playWhenReady || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        playerListener?.let { attachedPlayer?.removeListener(it) }
        attachedPlayer = null
        playerListener = null
        releaseLocks()
        wifiLock = null
        wakeLock = null
        activeSession = null
        instance = null
        super.onDestroy()
    }
}
