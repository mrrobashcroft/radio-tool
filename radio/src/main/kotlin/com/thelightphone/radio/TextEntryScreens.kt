// Initial build design compiled by Rob Ashcroft, August 2026
package com.thelightphone.radio

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
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
                keyboardOptionsFlow = MutableStateFlow(
                    // No emoji, mic, or enter keys — like the native podcast keyboard
                    KeyboardOptions(
                        emojis = emptyList(),
                        displayReturn = false,
                        displayVoice = false,
                        enableKeyAnimation = true,
                        swipeEnabled = false,
                    )
                ),
                onSubmit = { 
                    val trimmed = it.toString().trim()
                    if (trimmed.isNotBlank()) {
                        goBack(trimmed)
                    }
                },
                onBack = { goBack(null) },
                submitLabel = "SAVE",
                singleLine = true // Ensures no line returns allowed
            )
        }
    }
}
