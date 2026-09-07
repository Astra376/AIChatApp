package com.example.aichat.feature.ultra

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UltraScreenContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plansComeFirst_purchaseStaysVisible_andTestUltraCanBeEnabledAndDisabled() {
        val info = mutableStateOf(UltraDto(previewAvailable = true))
        val cadence = mutableStateOf("annual")
        var purchases = 0
        compose.setContent {
            AppTheme(themeMode = ThemeMode.DARK) {
                UltraScreenContent(
                    UltraViewModel.State(info = info.value, loading = false),
                    listOf(UltraPlan("annual", "$117.52", "year", true, 30), UltraPlan("monthly", "$13.99", "month", true)),
                    cadence.value, { cadence.value = it }, {}, {},
                    onSetPreview = { info.value = info.value.copy(active = it, previewActive = it) },
                    onPurchase = { purchases++; info.value = info.value.copy(active = !info.value.active, previewActive = !info.value.previewActive) }
                )
            }
        }
        compose.onNodeWithTag("ultra-plan-annual").assertIsDisplayed()
        compose.onNodeWithTag("ultra-plan-monthly").performClick()
        compose.onNodeWithText("Mock purchase · Monthly").assertIsDisplayed()
        val buttonTop = compose.onNodeWithTag("ultra-purchase").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("ultra-content").performScrollToNode(hasText("Alternative app icons and custom home screen shortcuts."))
        compose.onNodeWithTag("ultra-purchase").assertIsDisplayed()
        assertEquals(buttonTop, compose.onNodeWithTag("ultra-purchase").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("ultra-purchase").performClick()
        compose.onNodeWithText("Disable test Ultra").assertIsDisplayed()
        compose.onNodeWithTag("ultra-content").performScrollToNode(hasTestTag("ultra-test-toggle"))
        compose.onNodeWithTag("ultra-test-toggle").performClick()
        compose.onNodeWithText("Mock purchase · Monthly").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, purchases); assertTrue(!info.value.active) }
        compose.onNodeWithTag("ultra-content").performScrollToIndex(0)
        compose.waitForIdle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/meek-ultra-dark.png")
        ).use { it.readBytes() }
    }

    @Test fun realBillingDoesNotExposeTheMockToggle() {
        compose.setContent {
            AppTheme(themeMode = ThemeMode.DARK) {
                UltraScreenContent(UltraViewModel.State(info = UltraDto(), loading = false),
                    emptyList(), "annual", {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("ultra-test-toggle").assertDoesNotExist()
        compose.onNodeWithTag("ultra-purchase").assertIsNotEnabled()
    }
}
