// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Simple concrete ViewModel for text entry screens.
 */
class SimpleEntryViewModel<T> : LightViewModel<T>()

/**
 * Screen for renaming a station using the standard full-screen editor style.
 */
class RenameScreen(
    private val sealedActivity: SealedLightActivity,
    private val initialName: String
) : LightScreen<String?, SimpleEntryViewModel<String?>>(sealedActivity) {

    override val viewModelClass: Class<SimpleEntryViewModel<String?>> = SimpleEntryViewModel::class.java as Class<SimpleEntryViewModel<String?>>
    override fun createViewModel(): SimpleEntryViewModel<String?> = SimpleEntryViewModel()

    @Composable
    override fun Content() {
        val state = rememberTextFieldState(initialName)
        
        LightTheme(colors = LightThemeColors.Dark) {
            LightTextInputEditor(
                title = "Rename",
                state = state,
                keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
                onSubmit = { 
                    val trimmed = it.toString().trim()
                    if (trimmed.isNotBlank()) {
                        goBack(trimmed)
                    }
                },
                onBack = { goBack(null) },
                submitLabel = "SAVE",
                singleLine = false // Allow wrapping for station names as requested
            )
        }
    }
}

/**
 * Redesigned screen for adding a station URL manually.
 */
class AddStationUrlScreen(
    private val sealedActivity: SealedLightActivity
) : LightScreen<Station?, SimpleEntryViewModel<Station?>>(sealedActivity) {

    override val viewModelClass: Class<SimpleEntryViewModel<Station?>> = SimpleEntryViewModel::class.java as Class<SimpleEntryViewModel<Station?>>
    override fun createViewModel(): SimpleEntryViewModel<Station?> = SimpleEntryViewModel()

    @Composable
    override fun Content() {
        val state = rememberTextFieldState()
        
        LightTheme(colors = LightThemeColors.Dark) {
            LightTextInputEditor(
                title = "Add manually",
                state = state,
                keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
                onSubmit = { 
                    val input = it.toString().trim()
                    if (input.isNotBlank()) {
                        goBack(Station("Untitled", input))
                    }
                },
                onBack = { goBack(null) },
                submitLabel = "ADD",
                submitIcon = LightIcons.ADD,
                singleLine = true // Enables horizontal scrolling and enter-to-submit
            )
        }
    }
}

/**
 * Screen for entering a search query for radio stations.
 */
class SearchEntryScreen(
    private val sealedActivity: SealedLightActivity
) : LightScreen<Station?, SimpleEntryViewModel<Station?>>(sealedActivity) {

    override val viewModelClass: Class<SimpleEntryViewModel<Station?>> = SimpleEntryViewModel::class.java as Class<SimpleEntryViewModel<Station?>>
    override fun createViewModel(): SimpleEntryViewModel<Station?> = SimpleEntryViewModel()

    @Composable
    override fun Content() {
        val state = rememberTextFieldState()
        val currentScreen = this
        
        LightTheme(colors = LightThemeColors.Dark) {
            LightTextInputEditor(
                title = "Find stations",
                state = state,
                keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
                onSubmit = { 
                    val query = it.toString().trim()
                    if (query.isNotBlank()) {
                        // Navigate forward to the results list
                        currentScreen.navigateTo({ SearchScreen(it, query) }) { selectedStation ->
                            // When a station is selected in the list, pass it all the way back to the Home screen
                            selectedStation?.let { goBack(it) }
                        }
                    }
                },
                onBack = { goBack(null) },
                submitLabel = "SEARCH",
                submitIcon = LightIcons.SEARCH,
                singleLine = true // Enables horizontal scrolling and enter-to-submit
            )
        }
    }
}
