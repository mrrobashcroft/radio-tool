// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * Defines the tabs available in the Library screen.
 */
enum class LibraryTab {
    Favourites, Recent
}

/**
 * logic for managing the local station library.
 */
class LibraryViewModel(private val filesDir: File) : RadioBaseViewModel<Station?>() {
    private val stationsFile = File(filesDir, "stations.json")
    private val recentPlayedFile = File(filesDir, "recent_played.json")
    
    // Observable lists for the two library sections
    val favourites = MutableStateFlow<List<Station>>(emptyList())
    val recent = MutableStateFlow<List<Station>>(emptyList())
    val activeTab = MutableStateFlow(LibraryTab.Favourites)
    
    private var currentScreen: SimpleLightScreen<Station?>? = null

    init {
        loadFavourites()
        loadRecent()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        super.onScreenShow(screen)
        currentScreen = screen
    }

    /** Loads saved favorites from the tool's private data directory. */
    private fun loadFavourites() {
        if (stationsFile.exists()) {
            try {
                val json = stationsFile.readText()
                favourites.value = Json.decodeFromString(json)
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Failed to load favourites", e)
            }
        }
    }

    /** Loads play history from the tool's private data directory. */
    private fun loadRecent() {
        if (recentPlayedFile.exists()) {
            try {
                val json = recentPlayedFile.readText()
                recent.value = Json.decodeFromString(json)
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Failed to load recent", e)
            }
        }
    }

    /** Persists favourites to disk. */
    private fun saveFavourites() {
        try {
            val json = Json.encodeToString(favourites.value)
            stationsFile.writeText(json)
        } catch (e: Exception) {}
    }

    /** Persists history to disk. */
    private fun saveRecent() {
        try {
            val json = Json.encodeToString(recent.value)
            recentPlayedFile.writeText(json)
        } catch (e: Exception) {}
    }

    /** Returns the selected station metadata to the HomeScreen. */
    fun selectStation(station: Station) {
        currentScreen?.goBack(station)
    }

    /** Removes a station from the Favourites list and saves changes. */
    fun removeFavourite(station: Station) {
        val newList = favourites.value.toMutableList()
        newList.remove(station)
        favourites.value = newList
        saveFavourites()
    }

    /** Removes a station from the Recent list and saves changes. */
    fun removeRecent(station: Station) {
        val newList = recent.value.toMutableList()
        newList.remove(station)
        recent.value = newList
        saveRecent()
    }

    /** Switches the active tab view. */
    fun setActiveTab(tab: LibraryTab) {
        activeTab.value = tab
    }
}

/**
 * Library screen showing the user's favorite and recently played radio stations.
 */
class LibraryScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Station?, LibraryViewModel>(sealedActivity) {
    override val viewModelClass = LibraryViewModel::class.java
    override fun createViewModel() = LibraryViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val favourites by viewModel.favourites.collectAsState()
        val recent by viewModel.recent.collectAsState()
        val activeTab by viewModel.activeTab.collectAsState()
        val volumePanel by viewModel.volumePanel.collectAsState()

        LightTheme(colors = LightThemeColors.Dark) {
            val colors = LightThemeTokens.colors
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                // Header
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Library"),
                )

                // Custom Tab Bar with LP3 styling
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Favourites Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .lightClickable { viewModel.setActiveTab(LibraryTab.Favourites) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LightText(
                                text = "Favourites",
                                variant = LightTextVariant.Subheading,
                                lighten = activeTab != LibraryTab.Favourites
                            )
                            // Underline indicator for the active tab — hugs the label
                            if (activeTab == LibraryTab.Favourites) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(colors.content)
                                )
                            }
                        }
                    }

                    // Recent Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .lightClickable { viewModel.setActiveTab(LibraryTab.Recent) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LightText(
                                text = "Recent",
                                variant = LightTextVariant.Subheading,
                                lighten = activeTab != LibraryTab.Recent
                            )
                            // Underline indicator for the active tab — hugs the label
                            if (activeTab == LibraryTab.Recent) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(colors.content)
                                )
                            }
                        }
                    }
                }

                // Conditional logic to show the correct list and deletion handler
                val currentList = if (activeTab == LibraryTab.Favourites) favourites else recent
                val onDelete: (Station) -> Unit = if (activeTab == LibraryTab.Favourites) {
                    { viewModel.removeFavourite(it) }
                } else {
                    { viewModel.removeRecent(it) }
                }

                // Empty state or Scrollable List
                if (currentList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LightText(
                            text = if (activeTab == LibraryTab.Favourites) "No favourites yet" else "No recent stations",
                            variant = LightTextVariant.Copy,
                            lighten = true
                        )
                    }
                } else {
                    // Scrollbar flush right — rows carry their own side padding
                    LightScrollView(modifier = Modifier.weight(1f)) {
                        Column {
                            currentList.forEach { station ->
                                StationRow(
                                    station = station,
                                    onPlay = { viewModel.selectStation(station) },
                                    onDelete = { onDelete(station) }
                                )
                            }
                        }
                    }
                }
            }

                // Full-screen overlay on top of everything (visual replica — not interactive)
                VolumePanelOverlay(
                    state = volumePanel,
                    onDismiss = { viewModel.dismissVolumePanel() },
                )
            }
        }
    }

    /** Reusable row for displaying a station in a list. */
    @Composable
    private fun StationRow(station: Station, onPlay: () -> Unit, onDelete: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .lightClickable(onClick = onPlay)
                    .padding(start = 24.dp, end = 16.dp)
            ) {
                LightText(
                    text = station.name,
                    variant = LightTextVariant.Copy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                LightText(text = station.url, variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
            }
            
            // Delete button — flush right
            Box(
                modifier = Modifier
                    .lightClickable(onClick = onDelete)
                    .padding(end = 8.dp)
            ) {
                com.thelightphone.sdk.ui.LightIcon(
                    icon = LightIcons.CLOSE,
                    modifier = Modifier.padding(8.dp),
                    size = 1.5f
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLibraryScreen() {
    LightTheme(colors = LightThemeColors.Dark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeColors.Dark.background)
        ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Library"),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Favourites Tab
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LightText(
                            text = "Favourites",
                            variant = LightTextVariant.Subheading,
                        )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(0.6f)
                            .height(2.dp)
                            .background(Color.White)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LightText(text = "Recent", variant = LightTextVariant.Subheading, lighten = true)
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                PreviewStationRow("Radio New Zealand", "https://stream.rnz.co.nz/mp3")
                PreviewStationRow("BBC Radio 6 Music", "http://stream.live.vc.bbc.co.uk/6music")
            }
        }
    }
}

@Composable
private fun PreviewStationRow(name: String, url: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = name, variant = LightTextVariant.Copy)
            LightText(text = url, variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
        }
        com.thelightphone.sdk.ui.LightIcon(
            icon = LightIcons.CLOSE,
            modifier = Modifier.padding(8.dp),
            size = 1.5f
        )
    }
}
