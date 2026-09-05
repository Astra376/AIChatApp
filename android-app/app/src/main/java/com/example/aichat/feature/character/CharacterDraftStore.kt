package com.example.aichat.feature.character

import android.content.Context
import com.example.aichat.core.model.CharacterDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedCharacterDraft(
    val draft: CharacterDraft = CharacterDraft(),
    val step: CharacterCreateStep = CharacterCreateStep.NAME,
    val portraitOptions: List<String> = emptyList(),
    val selectedPreview: String? = null
)

@Singleton
class CharacterDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("character_drafts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(userId: String): SavedCharacterDraft = runCatching {
        json.decodeFromString(SavedCharacterDraft.serializer(), preferences.getString(userId, null) ?: "{}")
    }.getOrDefault(SavedCharacterDraft())

    fun save(userId: String, value: SavedCharacterDraft) {
        preferences.edit().putString(userId, json.encodeToString(SavedCharacterDraft.serializer(), value)).apply()
    }
}
