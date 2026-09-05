package com.example.aichat.core.model

enum class CharacterVisibility {
    PUBLIC,
    UNLISTED,
    PRIVATE
}

data class CharacterSummary(
    val id: String,
    val ownerUserId: String,
    val authorUsername: String = "",
    val name: String,
    val tagline: String,
    val greeting: String,
    val bio: String,
    val systemPrompt: String,
    val definitionPrivate: Boolean = false,
    val visibility: CharacterVisibility,
    val avatarUrl: String?,
    val initialSceneUrl: String? = null,
    val initialSceneKey: String? = null,
    val publicChatCount: Int,
    val likeCount: Int,
    val likedByMe: Boolean,
    val lastActiveAt: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@kotlinx.serialization.Serializable
data class CharacterDraft(
    val id: String? = null,
    val name: String = "",
    val tagline: String = "",
    val greeting: String = "",
    val appearance: String = "",
    val bio: String = "",
    val systemPrompt: String = "",
    val characterDefinition: String = "",
    val definitionPrivate: Boolean = false,
    val visibility: CharacterVisibility = CharacterVisibility.PUBLIC,
    val avatarUrl: String? = null
)

data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String?
)
