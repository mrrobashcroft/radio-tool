// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Data model for a radio station.
 * Marked as @Serializable to allow easy saving/loading to JSON files.
 */
@Serializable
data class Station(val name: String, val url: String)

/**
 * The core logic for the Radio tool.
 * Handles audio playback via Media3, persistence of station data, and screen navigation.
 */
class RadioViewModel(
    filesDir: File,
    private val sealedActivity: SealedLightActivity
) : LightViewModel<Unit>() {
    // SDK provided audio player wrapper
    private val audio = DefaultLightAudio(sealedActivity)
    private val player: LightAudioPlayer = audio.newPlayer()
    
    // File paths for local persistence
    private val stationsFile = File(filesDir, "stations.json")
    private val recentPlayedFile = File(filesDir, "recent_played.json")
    private val lastPlayedFile = File(filesDir, "last_played.json")

    private var currentScreen: SimpleLightScreen<Unit>? = null
    
    // Observable state for the UI
    val streamUrl = MutableStateFlow("https://stream.radiokps.nz/")
    val stationName = MutableStateFlow("Radio")
    val stations = MutableStateFlow<List<Station>>(emptyList())
    val recentStations = MutableStateFlow<List<Station>>(emptyList())
    
    // Playback state forwarded from the SDK player
    val isPlaying = player.isPlaying
    val playbackState = player.playbackState
    val error = player.error
    val isFavourite = MutableStateFlow(false)
    
    // Tracks a station that has been selected but hasn't started playing yet
    private var pendingRecentStation: Station? = null

    init {
        // Initial setup: load saved data and find what we were playing last
        loadStations()
        loadRecentStations()
        loadLastPlayed()
        updateFavouriteState()
        
        // Start watching the player state for "Proof of Play"
        observePlaybackForHistory()
    }

    /** 
     * Watches the player's "isPlaying" state. 
     * Once a station actually starts playing, we move it from 'pending' to the official history.
     */
    private fun observePlaybackForHistory() {
        viewModelScope.launch {
            isPlaying.collect { playing ->
                if (playing) {
                    pendingRecentStation?.let { station ->
                        android.util.Log.d("RadioViewModel", "Proof of Play received for: ${station.name}. Adding to history.")
                        addToRecent(station)
                        pendingRecentStation = null
                    }
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        currentScreen = screen
        // Reload stations and recent list whenever returning to home to stay in sync with Library
        loadStations()
        loadRecentStations()
        updateFavouriteState()
    }

    /** Updates the boolean state used to show a solid or outline star. */
    private fun updateFavouriteState() {
        isFavourite.value = stations.value.any { it.url == streamUrl.value }
    }

    /** Loads the list of user-favorited stations from disk. */
    private fun loadStations() {
        if (stationsFile.exists()) {
            try {
                val json = stationsFile.readText()
                stations.value = Json.decodeFromString(json)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "Failed to load favourites", e)
            }
        }
    }

    /** Loads the history of played stations from disk. */
    private fun loadRecentStations() {
        if (recentPlayedFile.exists()) {
            try {
                val json = recentPlayedFile.readText()
                recentStations.value = Json.decodeFromString(json)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "Failed to load recent", e)
            }
        }
    }

    /** Loads the station metadata that was active when the app was last closed. */
    private fun loadLastPlayed() {
        if (lastPlayedFile.exists()) {
            try {
                val json = lastPlayedFile.readText()
                val station = Json.decodeFromString<Station>(json)
                stationName.value = station.name
                streamUrl.value = sanitizeUrl(station.url)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "Failed to load last played", e)
            }
        }
    }

    /** Saves current favorites to disk. */
    private fun saveStations() {
        try {
            val json = Json.encodeToString(stations.value)
            stationsFile.writeText(json)
        } catch (e: Exception) {}
    }

    /** Saves play history to disk. */
    private fun saveRecentStations() {
        try {
            val json = Json.encodeToString(recentStations.value)
            recentPlayedFile.writeText(json)
        } catch (e: Exception) {}
    }

    /** Saves currently active station metadata to disk. */
    private fun saveLastPlayed() {
        try {
            val station = Station(stationName.value, streamUrl.value)
            val json = Json.encodeToString(station)
            lastPlayedFile.writeText(json)
        } catch (e: Exception) {}
    }

    /** Adds a station to the top of the history list, maintaining a max of 10 items. */
    private fun addToRecent(station: Station) {
        loadRecentStations() // Ensure we have the latest list from disk before modifying
        val newList = recentStations.value.toMutableList()
        newList.removeAll { it.url == station.url }
        newList.add(0, station)
        if (newList.size > 10) {
            newList.removeAt(newList.size - 1)
        }
        recentStations.value = newList
        saveRecentStations()
    }

    /** Toggles playback of the current station URL. */
    fun togglePlayback() {
        if (isPlaying.value) {
            player.stop()
        } else {
            val sanitizedUrl = sanitizeUrl(streamUrl.value)
            val station = Station(stationName.value, sanitizedUrl)
            val item = LightAudioItem(
                source = LightAudioSource.UrlSource(station.url),
                metadata = LightMediaMetadata(title = station.name)
            )
            
            // Mark as pending - will be added to history once 'isPlaying' becomes true
            pendingRecentStation = station
            
            player.setMediaQueue(listOf(item))
            player.play()
            saveLastPlayed()
            updateFavouriteState()
        }
    }

    /** Adds or removes the current station from the user's curated Favorites list. */
    fun toggleFavourite() {
        loadStations() // Ensure we have the latest list from disk before modifying
        val sanitizedUrl = sanitizeUrl(streamUrl.value)
        val currentStation = Station(stationName.value, sanitizedUrl)
        val newList = stations.value.toMutableList()
        val alreadyFavourite = newList.any { it.url == currentStation.url }
        
        if (alreadyFavourite) {
            newList.removeAll { it.url == currentStation.url }
        } else {
            newList.add(0, currentStation)
        }
        
        stations.value = newList
        saveStations()
        updateFavouriteState()
    }

    /** Transitions the player to a new station and starts playback immediately. */
    fun playStation(station: Station) {
        val sanitizedUrl = sanitizeUrl(station.url)
        val sanitizedStation = Station(station.name, sanitizedUrl)
        
        android.util.Log.d("RadioViewModel", "Playing: ${station.name} | URL: $sanitizedUrl")
        stationName.value = station.name
        streamUrl.value = sanitizedUrl
        
        val item = LightAudioItem(
            source = LightAudioSource.UrlSource(sanitizedUrl),
            metadata = LightMediaMetadata(title = station.name)
        )
        
        // Mark as pending - history will only update if connection is successful
        pendingRecentStation = sanitizedStation
        
        player.setMediaQueue(listOf(item))
        player.play()
        saveLastPlayed()
        updateFavouriteState()
    }

    /**
     * Ensures URLs have a protocol and selectively appends SHOUTcast ';' suffix.
     * We only add the suffix for raw IP addresses or URLs without a path,
     * ensuring we don't break modern domain-based streams (like Radio NZ).
     */
    private fun sanitizeUrl(input: String): String {
        var url = input.trim()
        if (url.isBlank()) return url
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        
        // If it's already got a semicolon or is an HLS playlist, don't touch it
        if (url.contains(";") || url.lowercase().contains(".m3u8")) {
            return url
        }

        val protocolEnd = url.indexOf("://") + 3
        val firstSlash = url.indexOf("/", protocolEnd)
        
        // 1. No path at all OR just a trailing slash -> force SHOUTcast mode
        // (e.g. http://server:port or http://domain.com/)
        if (firstSlash == -1 || firstSlash == url.length - 1) {
            return if (url.endsWith("/")) "${url};" else "${url}/;"
        }

        // 2. Has a path, but is it an IP address or known SHOUTcast port?
        val hostPart = url.substring(protocolEnd, firstSlash)
        val isLikelyLegacyServer = hostPart.firstOrNull()?.isDigit() == true || 
                                  hostPart.contains(":8000") || 
                                  hostPart.contains(":18442") ||
                                  hostPart.contains(":8002")
        
        if (isLikelyLegacyServer) {
            // Append ; to the end of the URL to force stream over status page
            return if (url.endsWith("/")) "${url};" else "${url};"
        }
        
        // 3. Standard domain with a path (e.g. Radio NZ) -> leave as-is
        return url
    }

    // State for search persistence
    private var lastSearchQuery: String = ""

    /** Navigation handlers for sub-screens */
    
    fun openSearch() {
        currentScreen?.navigateTo({ SearchEntryScreen(it, lastSearchQuery) }) { selectedStation ->
            selectedStation?.let {
                playStation(it)
            }
        }
    }

    fun openLibrary() {
        currentScreen?.navigateTo({ LibraryScreen(it) }) { selectedStation ->
            selectedStation?.let { playStation(it) }
        }
    }

    fun openRename() {
        val currentName = stationName.value
        currentScreen?.navigateTo({ RenameScreen(it, currentName) }) { newName ->
            if (newName is String && newName.isNotBlank() && newName != currentName) {
                updateStationName(newName)
            }
        }
    }

    private fun updateStationName(newName: String) {
        val oldUrl = streamUrl.value
        stationName.value = newName
        
        loadStations()
        loadRecentStations()
        
        // Update in Favourites if present
        // We use sanitizeUrl for comparison because the list might contain either raw or sanitized URLs
        val favs = stations.value.toMutableList()
        val favIndex = favs.indexOfFirst { sanitizeUrl(it.url) == oldUrl }
        if (favIndex != -1) {
            favs[favIndex] = Station(newName, favs[favIndex].url)
            stations.value = favs
            saveStations()
        }

        // Update in Recent if present
        val recents = recentStations.value.toMutableList()
        val recentIndex = recents.indexOfFirst { sanitizeUrl(it.url) == oldUrl }
        if (recentIndex != -1) {
            recents[recentIndex] = Station(newName, recents[recentIndex].url)
            recentStations.value = recents
            saveRecentStations()
        }
        
        saveLastPlayed()
    }

    fun openAddStation() {
        currentScreen?.navigateTo({ AddStationUrlScreen(it) }) { selectedStation ->
            selectedStation?.let {
                playStation(it)
            }
        }
    }

    fun openBluetooth() {
        viewModelScope.launch {
            // This relies on the LightOS server implementing this custom bridge method.
            // On early SDK builds for physical hardware, this may not trigger an action yet.
            callRemoteServiceMethod(LightServiceMethod.OpenBluetoothSettings, Unit)
        }
    }

    override fun onBackPressed(): Boolean {
        currentScreen?.minimize()
        return true
    }

    override fun onCleared() {
        // Clean up resources when the app is fully closed
        player.release()
        super.onCleared()
    }
}

/**
 * The main "Now Playing" screen of the Radio tool.
 * Annotated with @InitialScreen so the SDK knows to launch this first.
 */
@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, RadioViewModel>(sealedActivity) {

    override val viewModelClass: Class<RadioViewModel> = RadioViewModel::class.java

    override fun createViewModel(): RadioViewModel = RadioViewModel(lightContext.filesDir, sealedActivity)

    @Composable
    override fun Content() {
        // Collect observable state from the ViewModel
        val name by viewModel.stationName.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val state by viewModel.playbackState.collectAsState()
        val error by viewModel.error.collectAsState()
        val isFavourite by viewModel.isFavourite.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // Derive user-friendly status text from ExoPlayer states
        val statusText = when {
            error != null -> "Error loading stream"
            playing && state == Player.STATE_READY -> "Playing Live Stream..."
            state == Player.STATE_BUFFERING -> "Connecting..."
            else -> "Stopped"
        }

        // Apply the standard Light Phone theme (follows system-wide Light/Dark mode)
        LightTheme(colors = themeColors) {
            val colors = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                // Top Bar with standard back button and tool title
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { minimize() }),
                    center = LightTopBarCenter.Text("Radio")
                )

                // Main "Now Playing" area centered on screen
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Station Title Row with Favourite Star
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LightText(
                            text = name,
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 40.dp)
                                .lightClickable { viewModel.openRename() }
                        )
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .lightClickable { viewModel.toggleFavourite() }
                        ) {
                            com.thelightphone.sdk.ui.LightIcon(
                                icon = if (isFavourite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                                size = 1.5f
                            )
                        }
                    }

                    // Playback Status Indicator
                    LightText(
                        text = statusText,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )

                    // Large Center Play/Stop Button
                    Box(
                        modifier = Modifier
                            .lightClickable { viewModel.togglePlayback() }
                            .padding(16.dp)
                    ) {
                        com.thelightphone.sdk.ui.LightIcon(
                            icon = if (playing) LightIcons.STOP else LightIcons.PLAY,
                            size = 2.5f
                        )
                    }
                }

                // Standard LightOS Bottom Navigation Bar
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.SEARCH, onClick = viewModel::openSearch),
                        LightBarButton.LightIcon(LightIcons.ADD, onClick = viewModel::openAddStation),
                        LightBarButton.LightIcon(LightIcons.LIST, onClick = viewModel::openLibrary),
                        LightBarButton.LightIcon(LightIcons.BLUETOOTH, onClick = viewModel::openBluetooth)
                    )
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHomeScreenDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        PreviewContent()
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHomeScreenLight() {
    LightTheme(colors = LightThemeColors.Light) {
        PreviewContent()
    }
}

@Composable
private fun PreviewContent() {
    val colors = LightThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = {}),
            center = LightTopBarCenter.Text("Radio")
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                LightText(
                    text = "Radio",
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
                
                com.thelightphone.sdk.ui.LightIcon(
                    icon = LightIcons.STAR_OUTLINE,
                    size = 1.5f,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            LightText(
                text = "Stopped",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            com.thelightphone.sdk.ui.LightIcon(
                icon = LightIcons.PLAY,
                size = 2.5f
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(LightIcons.SEARCH, onClick = {}),
                LightBarButton.LightIcon(LightIcons.ADD, onClick = {}),
                LightBarButton.LightIcon(LightIcons.LIST, onClick = {}),
                LightBarButton.LightIcon(LightIcons.BLUETOOTH, onClick = {})
            )
        )
    }
}
