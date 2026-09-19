// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.*
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
 * Logic for searching stations and managing persistent search history.
 */
class SearchViewModel(private val filesDir: File) : LightViewModel<Station?>() {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(DefaultRequest) {
            header("User-Agent", "LightPhoneRadioTool/1.0")
        }
    }

    private val historyFile = File(filesDir, "search_history.json")
    private var currentScreen: SimpleLightScreen<Station?>? = null
    
    val results = MutableStateFlow<List<RadioBrowserStation>>(emptyList())
    val searchHistory = MutableStateFlow<List<String>>(emptyList())
    val isSearching = MutableStateFlow(false)
    val lastQuery = MutableStateFlow("")

    init {
        loadHistory()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        currentScreen = screen
    }

    private fun loadHistory() {
        if (historyFile.exists()) {
            try {
                searchHistory.value = Json.decodeFromString(historyFile.readText())
            } catch (e: Exception) {}
        }
    }

    private fun saveHistory() {
        try {
            historyFile.writeText(Json.encodeToString(searchHistory.value))
        } catch (e: Exception) {}
    }

    fun addToHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        
        val newList = searchHistory.value.toMutableList()
        newList.removeAll { it.equals(trimmed, ignoreCase = true) }
        newList.add(0, trimmed)
        if (newList.size > 15) newList.removeAt(newList.size - 1)
        
        searchHistory.value = newList
        saveHistory()
    }

    fun removeFromHistory(query: String) {
        val newList = searchHistory.value.toMutableList()
        newList.remove(query)
        searchHistory.value = newList
        saveHistory()
    }

    fun search(query: String) {
        val name = query.trim()
        if (name.length < 2) return
        
        lastQuery.value = name
        addToHistory(name)
        
        viewModelScope.launch {
            isSearching.value = true
            try {
                val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                val url = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&limit=50&hidebroken=true&order=clickcount&reverse=true"
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

    fun selectStation(station: RadioBrowserStation) {
        val streamUrl = if (station.urlResolved?.contains(".") == true && !station.urlResolved.endsWith(".")) {
            station.urlResolved
        } else {
            station.url
        }
        currentScreen?.goBack(Station(station.name, streamUrl))
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}

/**
 * Enhanced Search Screen with persistent history and state preservation.
 */
class SearchScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Station?, SearchViewModel>(sealedActivity) {
    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val results by viewModel.results.collectAsState()
        val history by viewModel.searchHistory.collectAsState()
        val searching by viewModel.isSearching.collectAsState()
        val lastQuery by viewModel.lastQuery.collectAsState()
        val focusManager = LocalFocusManager.current
        
        // Persist the typing state locally to the screen
        val inputState = rememberTextFieldState(lastQuery)

        LightTheme(colors = LightThemeColors.Dark) {
            val colors = LightThemeTokens.colors
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Find stations"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.search(inputState.text.toString())
                        }
                    )
                )

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    // Modern, standard-compliant input (Underline style)
                    Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                        BasicTextField(
                            state = inputState,
                            textStyle = LightThemeTokens.typography.copy.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                            onKeyboardAction = {
                                focusManager.clearFocus()
                                viewModel.search(inputState.text.toString())
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White))
                    }

                    if (searching) {
                        LightText("Searching...", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
                    }

                    LightScrollView(modifier = Modifier.weight(1f)) {
                        Column {
                            if (results.isNotEmpty()) {
                                // Show Results
                                LightText("Results", variant = LightTextVariant.Detail, lighten = false, modifier = Modifier.padding(vertical = 8.dp))
                                results.forEach { station ->
                                    SearchResultRow(station) {
                                        viewModel.selectStation(station)
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                            
                            if (history.isNotEmpty()) {
                                // Show Search History
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                                    LightText("Recent searches", variant = LightTextVariant.Detail, modifier = Modifier.weight(1f))
                                }
                                history.forEach { item ->
                                    HistoryRow(
                                        query = item,
                                        onSelect = { 
                                            inputState.edit { 
                                                replace(0, length, item)
                                            }
                                            viewModel.search(item) 
                                        },
                                        onDelete = { viewModel.removeFromHistory(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchResultRow(station: RadioBrowserStation, onClick: () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 12.dp)) {
            LightText(text = station.name, variant = LightTextVariant.Copy)
            val details = mutableListOf<String>()
            station.country?.takeIf { it.isNotBlank() }?.let { details.add(it) }
            station.codec?.takeIf { it.isNotBlank() }?.let { details.add(it.uppercase()) }
            station.bitrate?.takeIf { it > 0 }?.let { details.add("${it}kbps") }
            if (details.isNotEmpty()) {
                LightText(text = details.joinToString(" • "), variant = LightTextVariant.Fine)
            }
        }
    }

    @Composable
    private fun HistoryRow(query: String, onSelect: () -> Unit, onDelete: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).lightClickable(onClick = onSelect)) {
                LightText(text = query, variant = LightTextVariant.Copy)
            }
            Box(modifier = Modifier.lightClickable(onClick = onDelete).padding(8.dp)) {
                LightIcon(icon = LightIcons.CLOSE, size = 1f)
            }
        }
    }
}
