package com.example.aichat.feature.customization

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test

class AppearancePolishTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bundledStylesRenderImmediately_andShowActualChoices() {
        compose.setContent {
            AppTheme(ThemeMode.DARK) {
                Surface {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppearanceProfileHeader("Meek", null, emptyList(), AppearanceDto(ultra = true, profileFont = "serif", frame = "halo", bannerId = "preset:aurora", profileBackgroundId = "preset:midnight"))
                        PresetChoices("Banner", "aurora", true, onChoose = {})
                        FrameChoices("halo", true, {})
                        IconChoices("default", true, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Meek").assertIsDisplayed()
        compose.onNodeWithContentDescription("default Meek icon").assertIsDisplayed()
        compose.waitForIdle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/meek-appearance-dark.png")).use { it.readBytes() }
    }
}
