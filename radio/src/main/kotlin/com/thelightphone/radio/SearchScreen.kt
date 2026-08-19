// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
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
 * Maps JSON fields from the API to Kotlin properties.
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
class SearchViewModel : RadioBaseViewModel<Station?>() {
    // Ktor HTTP client for network requests
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
            })
        }
        // Identifying our tool to the API servers
        install(DefaultRequest) {
            header("User-Agent", "LightPhoneRadioTool/1.0")
        }
    }

    private var currentScreen: SimpleLightScreen<Station?>? = null
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<RadioBrowserStation>>(emptyList())
    val isSearching = MutableStateFlow(false)
    /** True once at least one search has completed (drives "No results found"). */
    val hasSearched = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Station?>) {
        super.onScreenShow(screen)
        currentScreen = screen
    }

    fun updateQuery(newQuery: String) {
        query.value = newQuery
    }

    /**
     * Executes the search against the Radio Browser API.
     * Uses a specific mirror (de1) for reliability and limits results to 50.
     */
    fun search() {
        val name = query.value.trim()
        if (name.length < 2) return
        
        viewModelScope.launch {
            isSearching.value = true
            try {
                // Radio Browser allows searching by name, tags, and country.
                // We use hidebroken=true to ensure we only get active streams.
                val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                val url = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&limit=50&hidebroken=true&order=clickcount&reverse=true"
                
                android.util.Log.d("SearchViewModel", "Searching: $url")
                
                val response: List<RadioBrowserStation> = client.get(url).body()
                android.util.Log.d("SearchViewModel", "Search results for '$name': ${response.size}")
                results.value = response
            } catch (e: Exception) {
                android.util.Log.e("SearchViewModel", "Search failed for '$name'", e)
                results.value = emptyList()
            } finally {
                isSearching.value = false
                hasSearched.value = true
            }
        }
    }

    /** Whether the input is a direct stream URL rather than a search term. */
    private fun looksLikeUrl(input: String): Boolean =
        input.contains("://") || (input.contains(".") && !input.contains(" "))

    /** Whether the current query reads as a direct URL (drives the "Play this URL" row). */
    fun queryLooksLikeUrl(): Boolean = looksLikeUrl(query.value.trim())

    /** Plays a direct URL, deriving a readable name from its host. */
    fun selectUrl(input: String) {
        val url = input.trim()
        val name = url
            .removePrefix("http://").removePrefix("https://")
            .substringBefore("/").substringBefore(";")
            .ifBlank { "Untitled" }
        android.util.Log.d("SearchViewModel", "Direct URL: $name | $url")
        currentScreen?.goBack(Station(name, url))
    }

    /** Returns the selected station metadata back to the HomeScreen. */
    fun selectStation(station: RadioBrowserStation) {
        val streamUrl = station.urlResolved?.takeIf { it.isNotBlank() } ?: station.url
        android.util.Log.d("SearchViewModel", "Selected: ${station.name} | URL: $streamUrl | Codec: ${station.codec}")
        currentScreen?.goBack(Station(station.name, streamUrl))
    }

    override fun onCleared() {
        // Ensure network client is closed
        client.close()
        super.onCleared()
    }
}

/**
 * Screen for discovering radio stations via the online directory, merged
 * with direct stream-URL entry: a URL plays immediately, anything else
 * searches. Typing uses the embedded LightSDK keyboard.
 */
class SearchScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Station?, SearchViewModel>(sealedActivity) {
    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel()

    @Composable
    override fun Content() {
        val results by viewModel.results.collectAsState()
        val searching by viewModel.isSearching.collectAsState()
        val hasSearched by viewModel.hasSearched.collectAsState()
        val query by viewModel.query.collectAsState()
        val isUrl = viewModel.queryLooksLikeUrl()
        val volumePanel by viewModel.volumePanel.collectAsState()

        // Single source of truth for the text; keep the ViewModel in sync for
        // URL detection and results.
        val state = rememberTextFieldState()
        LaunchedEffect(state.text) {
            viewModel.updateQuery(state.text.toString())
        }

        // Podcast-style flow: a typing view and a separate "Search Results"
        // view; back from results returns to editing the query.
        var showResults by remember { mutableStateOf(false) }
        var showKeyboard by remember { mutableStateOf(true) }

        val onSubmit = {
            val input = query.trim()
            if (viewModel.queryLooksLikeUrl()) {
                viewModel.selectUrl(query) // direct URL — pops back to Home
            } else if (input.length >= 2) {
                // search() also guards, but don't flip to the results view
                // for an empty/1-char query — nothing to search
                showResults = true
                showKeyboard = false
                viewModel.search()
            }
        }

        fun backToSearch() {
            showResults = false
            showKeyboard = true
        }

        LightTheme(colors = LightThemeColors.Dark) {
            val colors = LightThemeTokens.colors
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                if (showResults) {
                    // ---- Search Results view (podcast style: no query shown) ----
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { backToSearch() }),
                        center = LightTopBarCenter.Text("Search Results"),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (searching) {
                            LightText(
                                text = "Searching...",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        } else if (hasSearched && results.isEmpty()) {
                            LightText(
                                text = "No results found",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }

                        // List of search results — scrollbar flush right
                        LightScrollView(modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                results.forEach { station ->
                                    SearchResultRow(station) {
                                        viewModel.selectStation(station)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ---- Find Stations (typing) view ----
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Find Stations"),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // Podcast-style input: 2-unit side padding, text pushed
                        // down to ~y300, thin full-width underline (measured
                        // from the LP3 podcast search, 2026-08-19)
                        Column(modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp())) {
                            Spacer(modifier = Modifier.height(3f.gridUnitsAsDp()))

                            // Hint: this field searches or takes a direct URL
                            LightText(
                                text = "Search stations or enter a URL",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp())
                            )

                            // Horizontally scrollable text area (the LP3 input row);
                            // tap it to bring the keyboard back
                            val inputScrollState = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(inputScrollState)
                                    .lightClickable { showKeyboard = true }
                            ) {
                                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

                                // Auto-scroll to keep the cursor in view while typing
                                LaunchedEffect(state.text) {
                                    inputScrollState.animateScrollTo(inputScrollState.maxValue)
                                }

                                BasicText(
                                    text = state.text.toString(),
                                    style = LightThemeTokens.typography.subheading.copy(color = colors.content),
                                    onTextLayout = { textLayout = it },
                                    modifier = Modifier.width(IntrinsicSize.Max),
                                    maxLines = 1,
                                    softWrap = false
                                )

                                // Cursor
                                textLayout?.let { layout ->
                                    val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                                    val rect = layout.getCursorRect(cursorPos)
                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                                            .width(1.5.dp)
                                            .height(with(LocalDensity.current) { rect.height.toDp() })
                                            .background(colors.content),
                                    )
                                }
                            }

                            // Fixed underline below the scrollable box
                            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.content))

                            // Direct URL detected — offer to play it immediately
                            if (isUrl) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable(onClick = { viewModel.selectUrl(query) })
                                        .padding(vertical = 12.dp)
                                ) {
                                    LightText(text = "Play this URL", variant = LightTextVariant.Copy)
                                    LightText(text = query.trim(), variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
                                }
                            }
                        }
                    }

                // Standard keyboard logic (LightSDK embedded keyboard)
                val keyboardCallback = remember(state) {
                    object : Lp3RepeatableKeyboardCallback {
                        override fun onKeyPressed(code: Int) {}
                        override fun onSpecialKeyPressed(key: SpecialKey) {}
                        override fun onKeyReleased(code: Int) {
                            state.edit {
                                val start = selection.min
                                replace(start, selection.max, buildString { appendCodePoint(code) })
                                selection = TextRange(start + 1)
                            }
                        }
                        override fun onSpecialKeyReleased(key: SpecialKey) {
                            when (key) {
                                SpecialKey.Backspace -> state.edit {
                                    val start = selection.min
                                    if (start > 0) {
                                        delete(start - 1, start)
                                        selection = TextRange(start - 1)
                                    }
                                }
                                SpecialKey.Return -> onSubmit()
                                SpecialKey.Space -> state.edit {
                                    val start = selection.min
                                    replace(start, selection.max, " ")
                                    selection = TextRange(start + 1)
                                }
                                else -> Unit
                            }
                        }
                        override fun onKeyRepeated(code: Int) {
                            state.edit {
                                val start = selection.min
                                replace(start, selection.max, buildString { appendCodePoint(code) })
                                selection = TextRange(start + 1)
                            }
                        }
                        override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
                            if (specialKey == SpecialKey.Space) {
                                state.edit {
                                    val start = selection.min
                                    replace(start, selection.max, " ")
                                    selection = TextRange(start + 1)
                                }
                            }
                        }
                        override fun onKeyLongPressed(code: Int) {}
                        override fun onSpecialKeyLongPressed(key: SpecialKey) {
                            if (key == SpecialKey.Backspace) {
                                state.edit {
                                    val cur = state.text.toString()
                                    val end = selection.min
                                    if (end > 0) {
                                        val start = cur.substring(0, end).trimEnd().indexOfLast { it.isWhitespace() } + 1
                                        delete(start, end)
                                        selection = TextRange(start)
                                    }
                                }
                            }
                        }
                        override fun onSubmitWord(word: CharSequence) {
                            state.edit {
                                val start = selection.min
                                replace(start, selection.max, word.toString())
                                selection = TextRange(start + word.length)
                            }
                        }
                    }
                }

                val keyboardViewModel = viewModel<EnQwertyLp3KeyboardViewModel<Unit>>(
                    key = "search-station-keyboard-v1",
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return EnQwertyLp3KeyboardViewModel<Unit>(
                                keyboardCallback,
                                // No emoji, mic, or enter rows — like the native
                                // podcast keyboard (submit via the bottom-bar icon)
                                keyboardOptionsFlow = MutableStateFlow(
                                    KeyboardOptions(
                                        emojis = emptyList(),
                                        displayReturn = false,
                                        displayVoice = false,
                                        enableKeyAnimation = true,
                                        swipeEnabled = false,
                                    )
                                ),
                                optionsForLayout = { LayoutOptions(!it.isRootLayout) }
                            ).apply { setCapsMode(true) } as T
                        }
                    }
                )

                // Passes-style bottom: the search action lives in the
                // keyboard's reserved bottom zone (additionalBottomHeight =
                // 5 units), so the keys sit at the same height as the passes
                // add-code editor and the bar is nested in the keyboard block
                if (showKeyboard) {
                    LightEmbeddedLp3Keyboard(
                        viewModel = keyboardViewModel,
                        additionalBottomHeight = 5f.gridUnitsAsDp(),
                        bottomBar = {
                            LightBottomBar(
                                topPadding = 0.dp,
                                items = listOf(
                                    LightBarButton.LightIcon(
                                        icon = LightIcons.SEARCH,
                                        onClick = onSubmit,
                                    )
                                ),
                            )
                        },
                    )
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

    /** Individual search result row showing Name and stream metadata (Codec/Bitrate). */
    @Composable
    private fun SearchResultRow(station: RadioBrowserStation, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 12.dp)
        ) {
            LightText(
                text = station.name,
                variant = LightTextVariant.Copy,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val details = mutableListOf<String>()
            station.country?.takeIf { it.isNotBlank() }?.let { details.add(it) }
            station.codec?.takeIf { it.isNotBlank() }?.let { details.add(it.uppercase()) }
            station.bitrate?.takeIf { it > 0 }?.let { details.add("${it}kbps") }
            
            if (details.isNotEmpty()) {
                LightText(text = details.joinToString(" • "), variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
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
                center = LightTopBarCenter.Text("Find stations"),
            )

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                LightText(
                    text = "Search stations or enter a URL",
                    variant = LightTextVariant.Detail,
                    lighten = true
                )

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
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        LightText(text = details, variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
    }
}
