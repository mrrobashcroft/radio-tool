// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data model for the Radio Browser API response.
 */
@Serializable
data class RadioBrowserStation(
    @SerialName("name") val name: String,
    @SerialName("url") val url: String,
    @SerialName("url_resolved") val urlResolved: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("tags") val tags: String? = null,
    @SerialName("codec") val codec: String? = null,
    @SerialName("bitrate") val bitrate: Int? = null
)

/**
 * logic for searching stations via the Radio Browser community API.
 */
class SearchViewModel : LightViewModel<Station?>() {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(DefaultRequest) {
            header("User-Agent", "LightPhoneRadioTool/1.0")
        }
    }

    private var currentScreen: SimpleLightScreen<Station?>? = null
    val results = MutableStateFlow<List<RadioBrowserStation>>(emptyList())
    val isSearching = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        currentScreen = screen
    }

    /**
     * Executes the search against the Radio Browser API.
     */
    fun search(query: String) {
        val name = query.trim()
        if (name.length < 2) return
        
        viewModelScope.launch {
            isSearching.value = true
            try {
                // Radio Browser allows searching by name, tags, and country.
                // Using the 'de1' mirror as it is generally the most reliable.
                val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                val url = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&limit=50&hidebroken=true&order=clickcount&reverse=true"
                
                android.util.Log.d("SearchViewModel", "Searching Radio Browser: $url")
                
                val response: List<RadioBrowserStation> = client.get(url).body()
                results.value = response
            } catch (e: Exception) {
                android.util.Log.e("SearchViewModel", "Search failed for '$name'", e)
                results.value = emptyList()
            } finally {
                isSearching.value = false
            }
        }
    }

    /** Returns the selected station metadata back to the HomeScreen. */
    fun selectStation(station: RadioBrowserStation) {
        // Prioritize the original URL if urlResolved looks truncated or suspicious
        val streamUrl = if (station.urlResolved?.contains(".") == true && !station.urlResolved.endsWith(".")) {
            station.urlResolved
        } else {
            station.url
        }
        android.util.Log.d("SearchViewModel", "Selected: ${station.name} | URL: $streamUrl | Codec: ${station.codec}")
        currentScreen?.goBack(Station(station.name, streamUrl))
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}

/**
 * Screen for displaying radio station search results.
 */
class SearchScreen(
    private val sealedActivity: SealedLightActivity,
    private val query: String
) : LightScreen<Station?, SearchViewModel>(sealedActivity) {
    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel()

    @Composable
    override fun Content() {
        val results by viewModel.results.collectAsState()
        val searching by viewModel.isSearching.collectAsState()

        // Trigger search once when the screen is first shown
        LaunchedEffect(query) {
            viewModel.search(query)
        }

        LightTheme(colors = LightThemeColors.Dark) {
            val colors = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                // Top Bar - sentence case as requested
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Results"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            // Go back to the entry screen to allow a new search
                            goBack()
                        }
                    )
                )

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    // User feedback during search
                    if (searching) {
                        LightText("Searching for \"$query\"...", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
                    } else if (results.isEmpty()) {
                        LightText("No results found for \"$query\"", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LightText("Results for \"$query\"", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
                    }

                    // List of search results
                    LightScrollView(modifier = Modifier.weight(1f)) {
                        Column {
                            results.forEach { station ->
                                SearchResultRow(station) {
                                    viewModel.selectStation(station)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Individual search result row showing Name and stream metadata. */
    @Composable
    private fun SearchResultRow(station: RadioBrowserStation, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 12.dp)
        ) {
            LightText(text = station.name, variant = LightTextVariant.Copy)
            val details = mutableListOf<String>()
            station.country?.takeIf { it.isNotBlank() }?.let { details.add(it) }
            station.codec?.takeIf { it.isNotBlank() }?.let { details.add(it.uppercase()) }
            station.bitrate?.takeIf { it > 0 }?.let { details.add("${it}kbps") }
            
            if (details.isNotEmpty()) {
                LightText(text = details.joinToString(" • "), variant = LightTextVariant.Fine, maxLines = 1)
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewSearchScreen() {
    LightTheme(colors = LightThemeColors.Dark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeColors.Dark.background)
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = {}),
                center = LightTopBarCenter.Text("Results"),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.SEARCH, onClick = {})
            )

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                LightText("Results for \"Jazz\"", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
                PreviewSearchResultRow("Jazz Radio", "MP3 • 128kbps")
                PreviewSearchResultRow("Classic Jazz FM", "AAC • 64kbps")
            }
        }
    }
}

@Composable
private fun PreviewSearchResultRow(name: String, details: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        LightText(text = name, variant = LightTextVariant.Copy)
        LightText(text = details, variant = LightTextVariant.Fine, maxLines = 1)
    }
}
