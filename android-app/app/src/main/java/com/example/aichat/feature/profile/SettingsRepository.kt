package com.example.aichat.feature.profile

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aichat.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app-settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        preferences[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }

    val streamingHaptics: Flow<Boolean> = context.dataStore.data.map { it[STREAMING_HAPTICS] ?: true }

    suspend fun setStreamingHaptics(enabled: Boolean) {
        context.dataStore.edit { it[STREAMING_HAPTICS] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = themeMode.name
        }
    }

    private companion object {
        val STREAMING_HAPTICS = booleanPreferencesKey("streaming_haptics")
        val THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    }
}
