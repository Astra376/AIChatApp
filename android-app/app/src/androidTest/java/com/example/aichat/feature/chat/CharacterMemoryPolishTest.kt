package com.example.aichat.feature.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.ThemeMode
import com.example.aichat.core.network.CharacterPersonalityDto
import org.junit.Rule
import org.junit.Test

class CharacterMemoryPolishTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fixedCategoriesKeepMemorySelectionAndDraftVisible() {
        compose.setContent {
            AppTheme(ThemeMode.DARK) {
                CharacterMemoryScreen(PaddingValues(), CharacterMemoryUiState(isLoading = false, hasServerDetails = true, shortTerm = "Current scene draft", longTerm = "Lasting memory draft", personality = CharacterPersonalityDto()), remember { SnackbarHostState() }, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Current scene draft").assertIsDisplayed()
        compose.onNodeWithText("Long term").performClick()
        compose.onNodeWithText("Lasting memory draft").assertIsDisplayed()
        compose.onNodeWithText("Personality", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Lasting memory draft").assertIsDisplayed()
        compose.waitForIdle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/meek-psychology-dark.png")).use { it.readBytes() }
    }
}
