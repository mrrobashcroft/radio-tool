// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.*
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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
 * Enum for the Search Hub tabs.
 */
enum class SearchTab {
    Search,
    History
}

/**
 * Enum for the Search modes within the Search tab.
 */
enum class SearchMode {
    Input,
    Results
}

/**
 * Logic for searching stations and managing search history.
 */
class SearchViewModel(private val filesDir: File) : LightViewModel<Station?>() {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(DefaultRequest) { header("User-Agent", "LightPhoneRadioTool/1.0") }
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 10000
        }
    }

    private val historyFile = File(filesDir, "search_history.json")
    private var currentScreen: SimpleLightScreen<Station?>? = null
    
    val results = MutableStateFlow<List<RadioBrowserStation>>(emptyList())
    val searchHistory = MutableStateFlow<List<String>>(emptyList())
    val isSearching = MutableStateFlow(false)
    val hasPerformedSearch = MutableStateFlow(false)
    val activeQuery = MutableStateFlow("")
    val directUrlResult = MutableStateFlow<Station?>(null)
    val directUrlError = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(SearchMode.Input)
    val activeTab = MutableStateFlow(SearchTab.Search)

    init {
        loadHistory()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        currentScreen = screen
        // Always reset to Search tab when entering from Home
        activeTab.value = SearchTab.Search
        if (!hasPerformedSearch.value) {
            mode.value = SearchMode.Input
        }
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

    fun search(query: CharSequence) {
        val name = query.toString().trim()
        if (name.length < 2) return
        
        activeQuery.value = name
        hasPerformedSearch.value = true
        mode.value = SearchMode.Results
        activeTab.value = SearchTab.Search
        addToHistory(name)
        
        viewModelScope.launch {
            try {
                isSearching.value = true
                results.value = emptyList()
                directUrlResult.value = null
                directUrlError.value = null

                val isLikelyAddress = name.contains(".") && !name.contains(" ")
                val isIpAddress = isLikelyAddress && name.firstOrNull()?.isDigit() == true

                if (isLikelyAddress) {
                    var urlToTest = sanitizeUrl(name)
                    // validateStreamUrl now uses execute {} to read ONLY headers and avoid infinite hangs
                    var isValid = validateStreamUrl(urlToTest)
                    
                    if (!isValid && !isIpAddress) {
                        // For domain names, try common fallbacks
                        if (urlToTest.endsWith(";")) {
                            val cleanUrl = urlToTest.removeSuffix(";").removeSuffix("/")
                            if (validateStreamUrl(cleanUrl)) {
                                urlToTest = cleanUrl
                                isValid = true
                            }
                        }
                        if (!isValid && urlToTest.startsWith("http://")) {
                            val secureUrl = urlToTest.replace("http://", "https://")
                            if (validateStreamUrl(secureUrl)) {
                                urlToTest = secureUrl
                                isValid = true
                            }
                        }
                    }

                    if (isValid) {
                        directUrlResult.value = Station(name = "Untitled", url = urlToTest)
                        return@launch
                    } else if (isIpAddress) {
                        // Strict single-pass fail for IPs to avoid multi-timeout "hangs"
                        directUrlError.value = "No results found"
                        return@launch
                    }
                }

                // Point 3: FLEXIBLE SEARCH ENGINE (Word order independent)
                try {
                    val searchWords = name.split(" ").filter { it.isNotBlank() }
                    
                    // 1. Try exact search first
                    val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                    val standardUrl = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&limit=50&hidebroken=true&order=clickcount&reverse=true"
                    val standardResponse: List<RadioBrowserStation> = client.get(standardUrl).body()
                    
                    if (standardResponse.size >= 5) {
                        results.value = standardResponse
                    } else {
                        // 2. BROAD FALLBACK: If few results, search by most unique word and filter locally
                        val significantWord = searchWords.maxByOrNull { it.length } ?: name
                        val encodedWord = java.net.URLEncoder.encode(significantWord, "UTF-8")
                        val broadUrl = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedWord&limit=100&hidebroken=true&order=clickcount&reverse=true"
                        val broadResponse: List<RadioBrowserStation> = client.get(broadUrl).body()
                        
                        // Filter locally to ensure ALL search words are present in any order
                        val filtered = broadResponse.filter { station ->
                            searchWords.all { word -> 
                                station.name.contains(word, ignoreCase = true) || 
                                (station.tags?.contains(word, ignoreCase = true) == true)
                            }
                        }
                        
                        // Combine results, prioritizing standard ones
                        results.value = (standardResponse + filtered).distinctBy { it.url }
                    }
                } catch (e: Exception) {
                    results.value = emptyList()
                }
            } finally {
                isSearching.value = false
            }
        }
    }

    private suspend fun validateStreamUrl(urlString: String): Boolean {
        return try {
            // Using prepareRequest + execute ensures we ONLY read the headers
            client.prepareRequest {
                url(urlString)
                method = HttpMethod.Get
                header("Icy-MetaData", "1")
                header("User-Agent", "LightPhoneRadioTool/1.0")
                timeout {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
                }
            }.execute { response ->
                val status = response.status
                if (status.isSuccess() || status == HttpStatusCode.MovedPermanently || status == HttpStatusCode.Found) {
                    val contentType = response.contentType()?.toString()?.lowercase() ?: ""
                    // Only accept audio/playlist streams (filters out webpages)
                    contentType.contains("audio") || 
                    contentType.contains("mpegurl") || 
                    contentType.contains("application/ogg") ||
                    contentType.contains("application/vnd.apple.mpegurl") ||
                    contentType.contains("application/x-mpegurl") ||
                    contentType.contains("octet-stream")
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sanitizeUrl(input: String): String {
        var url = input.trim()
        if (url.isBlank()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (url.contains(";") || url.lowercase().contains(".m3u8")) return url
        val protocolEnd = url.indexOf("://") + 3
        val firstSlash = url.indexOf("/", protocolEnd)
        if (firstSlash == -1 || firstSlash == url.length - 1) {
            val hostPart = if (firstSlash == -1) url.substring(protocolEnd) else url.substring(protocolEnd, firstSlash)
            val isLegacy = hostPart.firstOrNull()?.isDigit() == true || hostPart.contains(":")
            if (isLegacy) return if (url.endsWith("/")) "${url};" else "${url}/;"
        }
        val hostPartEnd = if (firstSlash == -1) url.length else firstSlash
        val hostPart = url.substring(protocolEnd, hostPartEnd)
        val isLegacyPort = hostPart.contains(":8000") || hostPart.contains(":18442") || hostPart.contains(":8002") || hostPart.contains(":8005") || hostPart.contains(":7000")
        if (isLegacyPort) return "${url};"
        return url
    }

    fun selectStation(station: RadioBrowserStation) {
        val name = if (station.name == "Untitled") "Untitled" else station.name
        val streamUrl = if (station.urlResolved?.contains(".") == true && !station.urlResolved.endsWith(".")) station.urlResolved else station.url
        currentScreen?.goBack(Station(name, streamUrl))
    }

    fun showInput() { mode.value = SearchMode.Input }
    fun setActiveTab(tab: SearchTab) { activeTab.value = tab }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}

/**
 * Safe keyboard callback replicating the official SDK logic exactly.
 */
class OfficialSafeKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) {}
    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }
    override fun onKeyReleased(code: Int) { insertAtCursor(buildString { appendCodePoint(code) }) }
    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteBeforeCursor(1)
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }
    override fun onKeyRepeated(code: Int) { onKeyReleased(code) }
    override fun onSpecialKeyRepeated(specialKey: SpecialKey) { if (specialKey == SpecialKey.Backspace) deleteBeforeCursor(1) }
    override fun onKeyLongPressed(code: Int) {}
    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            state.edit {
                val end = selection.min.coerceIn(0, length)
                if (end > 0) {
                    val textBefore = toString().substring(0, end)
                    val lastSpace = textBefore.trimEnd().lastIndexOf(' ')
                    val start = if (lastSpace >= 0) lastSpace + 1 else 0
                    delete(start, end)
                    selection = TextRange(start)
                }
            }
        }
    }
    override fun onSubmitWord(word: CharSequence) { insertAtCursor(word.toString()) }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min.coerceIn(0, length)
            val end = selection.max.coerceIn(0, length)
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        state.edit {
            val end = selection.min.coerceIn(0, length)
            if (end > 0) {
                val actualCount = if (end >= 2 && Character.isLowSurrogate(toString()[end - 1])) 2 else count
                val start = (end - actualCount).coerceAtLeast(0)
                delete(start, end)
                selection = TextRange(start)
            }
        }
    }
}

/**
 * Tabbed Search Hub implementation.
 */
class SearchScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Station?, SearchViewModel>(sealedActivity) {
    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val results by viewModel.results.collectAsState()
        val directUrl by viewModel.directUrlResult.collectAsState()
        val directError by viewModel.directUrlError.collectAsState()
        val history by viewModel.searchHistory.collectAsState()
        val searching by viewModel.isSearching.collectAsState()
        val hasSearched by viewModel.hasPerformedSearch.collectAsState()
        val activeQuery by viewModel.activeQuery.collectAsState()
        val mode by viewModel.mode.collectAsState()
        val activeTab by viewModel.activeTab.collectAsState()
        
        val inputState = rememberTextFieldState(activeQuery)
        val colors = LightThemeTokens.colors
        val density = LocalDensity.current

        LightTheme(colors = LightThemeColors.Dark) {
            Surface {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. TOP BAR
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Find stations"),
                        rightButton = if (activeTab == SearchTab.Search && mode != SearchMode.Input) {
                            LightBarButton.LightIcon(LightIcons.SEARCH, onClick = { viewModel.showInput() })
                        } else null
                    )

                    // 2. TABS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TabItem("Search", activeTab == SearchTab.Search) { viewModel.setActiveTab(SearchTab.Search) }
                        TabItem("History", activeTab == SearchTab.History) { viewModel.setActiveTab(SearchTab.History) }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        if (activeTab == SearchTab.Search) {
                            if (mode == SearchMode.Input) {
                                // 3. TYPING AREA (Official SDK Replication - Exact Parity with Rename)
                                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                                
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                textLayout?.let { layout ->
                                                    inputState.edit { selection = TextRange(layout.getOffsetForPosition(down.position)) }
                                                }
                                                drag(down.id) { change ->
                                                    textLayout?.let { layout ->
                                                        inputState.edit { selection = TextRange(layout.getOffsetForPosition(change.position)) }
                                                    }
                                                    change.consume()
                                                }
                                            }
                                        },
                                    verticalArrangement = Arrangement.Top
                                ) {
                                    Box(contentAlignment = Alignment.TopStart) {
                                        BasicText(
                                            text = inputState.text.toString(),
                                            style = LightThemeTokens.typography.copy.copy(color = colors.content),
                                            onTextLayout = { textLayout = it },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        // Static Indicator Box (Identical to Rename/Notes - matches "No blinking" requirement)
                                        textLayout?.let { layout ->
                                            // SDK blueprint coercion: uses layout text length for 100% safety
                                            val cursorPos = inputState.selection.min.coerceIn(0, layout.layoutInput.text.length)
                                            val rect = layout.getCursorRect(cursorPos)
                                            Box(
                                                modifier = Modifier
                                                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                                                    .width(2.dp)
                                                    .height(with(density) { rect.height.toDp() })
                                                    .background(colors.content),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    // Native SDK Underline (3px)
                                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.content))
                                }
                            } else {
                                ResultsListView(results, directUrl, directError, history, searching, hasSearched, activeQuery, inputState)
                            }
                        } else {
                            HistoryListView(history, inputState)
                        }
                    }

                    // 4. KEYBOARD (Using Safe SDK callback logic)
                    if (activeTab == SearchTab.Search && mode == SearchMode.Input) {
                        val keyboardCallback = remember(inputState) {
                            OfficialSafeKeyboardCallback(inputState) { viewModel.search(inputState.text) }
                        }
                        
                        val keyboardViewModel = viewModel<EnQwertyLp3KeyboardViewModel<Unit>>(
                            key = "SearchHubKeyboard",
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    return EnQwertyLp3KeyboardViewModel<Unit>(
                                        keyboardCallback,
                                        keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
                                        optionsForLayout = { LayoutOptions(!it.isRootLayout) }
                                    ) as T
                                }
                            }
                        )
                        LightEmbeddedLp3Keyboard(keyboardViewModel)
                        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.SEARCH, onClick = { viewModel.search(inputState.text.toString()) })))
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.TabItem(text: String, isActive: Boolean, onClick: () -> Unit) {
        val colors = LightThemeTokens.colors
        Column(
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LightText(
                text = text,
                variant = LightTextVariant.Subheading,
            )
            if (isActive) {
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

    @Composable
    private fun ResultsListView(results: List<RadioBrowserStation>, directUrl: Station?, directError: String?, history: List<String>, searching: Boolean, hasSearched: Boolean, activeQuery: String, inputState: TextFieldState) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            if (searching) {
                LightText("Searching for \"$activeQuery\"...", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
            } else if (hasSearched && results.isEmpty() && directUrl == null) {
                LightText(directError ?: "No results found", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                LightText("Results for \"$activeQuery\"", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 16.dp))
            }
            LightScrollView(modifier = Modifier.weight(1f)) {
                Column {
                    directUrl?.let { station ->
                        SearchResultRow(RadioBrowserStation(station.name, station.url), isDirect = true) {
                            viewModel.selectStation(RadioBrowserStation(station.name, station.url))
                        }
                    }
                    results.forEach { station -> SearchResultRow(station) { viewModel.selectStation(station) } }
                    
                    if (!searching && history.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        LightText("Recent searches", variant = LightTextVariant.Detail, modifier = Modifier.padding(vertical = 8.dp))
                        history.forEach { item ->
                            HistoryRow(query = item, onSelect = { 
                                // Pre-fill the input and switch to search tab for editing
                                inputState.edit { replace(0, length, item) }
                                viewModel.setActiveTab(SearchTab.Search)
                                viewModel.showInput()
                            }, onDelete = { viewModel.removeFromHistory(item) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HistoryListView(history: List<String>, inputState: TextFieldState) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LightText("No recent searches", variant = LightTextVariant.Copy)
                }
            } else {
                LightScrollView(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        history.forEach { item ->
                            HistoryRow(query = item, onSelect = { 
                                // Pre-fill the input and switch to search tab for editing
                                inputState.edit { replace(0, length, item) }
                                viewModel.setActiveTab(SearchTab.Search)
                                viewModel.showInput()
                            }, onDelete = { viewModel.removeFromHistory(item) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchResultRow(station: RadioBrowserStation, isDirect: Boolean = false, onClick: () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 12.dp)) {
            LightText(text = station.name.ifBlank { station.url }, variant = LightTextVariant.Copy)
            LightText(text = if (isDirect) "Verified direct stream" else station.url, variant = LightTextVariant.Fine, maxLines = 1)
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
