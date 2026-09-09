package com.example.aichat.feature.customization

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import com.example.aichat.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object LauncherAppearance {
    private val variants = listOf("default", "midnight", "rose", "mint", "sunset")
    fun select(context: Context, key: String) {
        require(key in variants)
        val pm = context.packageManager
        val changes = variants.map { variant ->
            val component=ComponentName(context, "${context.packageName}.Launcher${variant.replaceFirstChar(Char::uppercase)}")
            component to if (variant==key) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(changes.map { (component,state) -> PackageManager.ComponentEnabledSetting(component,state,PackageManager.DONT_KILL_APP) })
        } else {
            // Enable the destination before disabling the previous alias: there is always a launcher entry.
            changes.sortedByDescending { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }.forEach { (component,state) ->
                pm.setComponentEnabledSetting(component,state,PackageManager.DONT_KILL_APP)
            }
        }
    }
    suspend fun pinCustom(context: Context, url: String, client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        val manager=context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return@withContext false
        val bitmap=client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "The icon could not be downloaded." }
            val bytes=response.body?.bytes() ?: error("The icon is empty.")
            require(bytes.size<=10_000_000)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("The icon could not be opened.")
        }
        val iconSize = minOf(manager.iconMaxWidth,manager.iconMaxHeight).coerceIn(96,512)
        val side=minOf(bitmap.width,bitmap.height)
        val square=android.graphics.Bitmap.createBitmap(bitmap,(bitmap.width-side)/2,(bitmap.height-side)/2,side,side)
        val scaled=android.graphics.Bitmap.createScaledBitmap(square,iconSize,iconSize,true)
        val shortcut=ShortcutInfo.Builder(context,"meek-custom-icon").setShortLabel("Meek")
            .setIcon(Icon.createWithAdaptiveBitmap(scaled))
            .setIntent(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)).build()
        withContext(Dispatchers.Main) { manager.requestPinShortcut(shortcut,null) }
    }
}
