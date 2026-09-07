package com.example.aichat.feature.chat

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.ThemeMode
import com.example.aichat.core.ui.LocalAppBackdrop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses the exact provider PNG from the live comparison, downloaded and SHA-checked in CI. */
class ChatArtworkTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realPng_keepsSceneVisible_andFitsEntireUpperBody_inBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "alpha-body-test.png")
        instrumentation.context.assets.open("character-body.png").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertTrue(bitmap.hasAlpha())
        assertEquals(0, AndroidColor.alpha(bitmap.getPixel(0, 0)))
        assertTrue(AndroidColor.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 240)
        bitmap.recycle()
        val source = mutableStateOf<String?>(null)
        val theme = mutableStateOf(ThemeMode.DARK)
        composeRule.setContent {
            AppTheme(themeMode = theme.value) {
                CompositionLocalProvider(LocalAppBackdrop provides {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF267568)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFBA8745)))
                    }
                }) {
                    Box(Modifier.width(240.dp).height(480.dp).testTag("body-scene")) {
                        ChatSceneBackground(imageUrl = null, emotionPortraitUrl = source.value, onLoadFailed = {})
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            composeRule.runOnIdle { source.value = null; theme.value = mode }
            val baseline = composeRule.onNodeWithTag("body-scene").captureToImage().toPixelMap()
            composeRule.runOnIdle { source.value = file.absolutePath }
            fun difference(x: Float, y: Float): Float {
                val pixels = composeRule.onNodeWithTag("body-scene").captureToImage().toPixelMap()
                val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                val expected = baseline[(baseline.width * x).toInt(), (baseline.height * y).toInt()]
                return kotlin.math.abs(actual.red - expected.red) + kotlin.math.abs(actual.green - expected.green) + kotlin.math.abs(actual.blue - expected.blue)
            }
            composeRule.waitUntil(5_000) { difference(0.5f, 0.5f) > 0.025f }
            assertTrue("Alpha must reveal the scene beside the body", difference(0.02f, 0.5f) < 0.012f)
            assertTrue("Fit must preserve the top margin instead of zooming/cropping", difference(0.5f, 0.20f) < 0.012f)
            val label = if (mode == ThemeMode.DARK) "dark" else "light"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/meek-body-$label.png"
            )).use { it.readBytes() }
        }
    }
}
