package com.example.aichat.feature.customization

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.aichat.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LauncherAppearanceTest {
    @Test @Config(sdk=[28])
    fun presetSwitchKeepsOneLauncherAndLeavesMainActivityEnabledOnOlderAndroid() = verifySwitch()

    @Test @Config(sdk=[35])
    fun presetSwitchKeepsOneLauncherAndLeavesMainActivityEnabledOnModernAndroid() = verifySwitch()

    private fun verifySwitch() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val variants=listOf("default","midnight","rose","mint","sunset")
        for(selected in listOf("rose","mint","default")) {
            LauncherAppearance.select(context,selected)
            variants.forEach { variant ->
                val component=ComponentName(context,"${context.packageName}.Launcher${variant.replaceFirstChar(Char::uppercase)}")
                assertEquals(if(variant==selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,context.packageManager.getComponentEnabledSetting(component))
            }
            assertTrue(context.packageManager.getComponentEnabledSetting(ComponentName(context,MainActivity::class.java)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }
    }
}
