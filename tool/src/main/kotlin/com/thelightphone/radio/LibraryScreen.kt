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

enum class LibraryTab {
    Favourites, Recent
}

class LibraryViewModel(private val filesDir: File) : LightViewModel<Station?>() {
    private val stationsFile = File(filesDir, "stations.json")
    private val recentPlayedFile = File(filesDir, "recent_played.json")
    
    val favourites = MutableStateFlow<List<Station>>(emptyList())
    val recent = MutableStateFlow<List<Station>>(emptyList())
    val activeTab = MutableStateFlow(LibraryTab.Favourites)
    
    private var currentScreen: SimpleLightScreen<Station?>? = null

    init {
        loadFavourites()
        loadRecent()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        currentScreen = screen
    }

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

    private fun saveFavourites() {
        try {
            val json = Json.encodeToString(favourites.value)
            stationsFile.writeText(json)
        } catch (e: Exception) {}
    }

    private fun saveRecent() {
        try {
            val json = Json.encodeToString(recent.value)
            recentPlayedFile.writeText(json)
        } catch (e: Exception) {}
    }

    fun selectStation(station: Station) {
        currentScreen?.goBack(station)
    }

    fun removeFavourite(station: Station) {
        val newList = favourites.value.toMutableList()
        newList.remove(station)
        favourites.value = newList
        saveFavourites()
    }

    fun removeRecent(station: Station) {
        val newList = recent.value.toMutableList()
        newList.remove(station)
        recent.value = newList
        saveRecent()
    }

    fun setActiveTab(tab: LibraryTab) {
        activeTab.value = tab
    }
}

class LibraryScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Station?, LibraryViewModel>(sealedActivity) {
    override val viewModelClass = LibraryViewModel::class.java
    override fun createViewModel() = LibraryViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val favourites by viewModel.favourites.collectAsState()
        val recent by viewModel.recent.collectAsState()
        val activeTab by viewModel.activeTab.collectAsState()

        LightTheme(colors = LightThemeColors.Dark) {
            val colors = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Library"),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .lightClickable { viewModel.setActiveTab(LibraryTab.Favourites) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LightText(
                            text = "Favourites",
                            variant = LightTextVariant.Subheading,
                        )
                        if (activeTab == LibraryTab.Favourites) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(0.6f)
                                    .height(2.dp)
                                    .background(colors.content)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .lightClickable { viewModel.setActiveTab(LibraryTab.Recent) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LightText(
                            text = "Recent",
                            variant = LightTextVariant.Subheading,
                        )
                        if (activeTab == LibraryTab.Recent) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(0.6f)
                                    .height(2.dp)
                                    .background(colors.content)
                            )
                        }
                    }
                }

                val currentList = if (activeTab == LibraryTab.Favourites) favourites else recent
                val onDelete: (Station) -> Unit = if (activeTab == LibraryTab.Favourites) {
                    { viewModel.removeFavourite(it) }
                } else {
                    { viewModel.removeRecent(it) }
                }

                if (currentList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LightText(
                            text = if (activeTab == LibraryTab.Favourites) "No favourites yet" else "No recent stations",
                            variant = LightTextVariant.Copy,
                        )
                    }
                } else {
                    LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
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
        }
    }

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
            ) {
                LightText(text = station.name, variant = LightTextVariant.Copy)
                LightText(text = station.url, variant = LightTextVariant.Fine, maxLines = 1)
            }
            
            Box(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .lightClickable(onClick = onDelete)
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
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = {}),
                center = LightTopBarCenter.Text("Library"),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
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
                    LightText(text = "Recent", variant = LightTextVariant.Subheading)
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
            LightText(text = url, variant = LightTextVariant.Fine, maxLines = 1)
        }
        com.thelightphone.sdk.ui.LightIcon(
            icon = LightIcons.CLOSE,
            modifier = Modifier.padding(8.dp),
            size = 1.5f
        )
    }
}
