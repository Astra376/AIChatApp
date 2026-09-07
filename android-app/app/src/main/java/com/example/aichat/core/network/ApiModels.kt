package com.example.aichat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponseDto(
    val code: String,
    val message: String
)

@Serializable
data class SessionResponseDto(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresAt") val expiresAt: Long
)

@Serializable
data class GoogleAuthRequestDto(
    @SerialName("idToken") val idToken: String
)

@Serializable
data class RefreshSessionRequestDto(
    @SerialName("refreshToken") val refreshToken: String
)

@Serializable
data class ProfileDto(
    val userId: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    @SerialName("description") val bio: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    @SerialName("description") val bio: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val characterCount: Int,
    val interactionCount: Int,
    val likeCount: Int
)

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String,
    @SerialName("description") val bio: String? = null
)

@Serializable
data class CharacterDto(
    val id: String,
    val ownerUserId: String,
    val authorUsername: String = "",
    val name: String,
    val tagline: String,
    val greeting: String = "",
    @SerialName("description") val bio: String,
    val systemPrompt: String,
    val definitionPrivate: Boolean = false,
    val visibility: String,
    val avatarUrl: String? = null,
    val initialSceneUrl: String? = null,
    val initialSceneKey: String? = null,
    val publicChatCount: Int,
    val likeCount: Int,
    val likedByMe: Boolean,
    val lastActiveAt: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CharacterLikeStateDto(
    val likedByMe: Boolean,
    val likeCount: Int
)

@Serializable
data class CharacterWriteRequestDto(
    val name: String,
    val tagline: String,
    val greeting: String,
    @SerialName("description") val bio: String,
    val systemPrompt: String,
    val definitionPrivate: Boolean,
    val visibility: String,
    val avatarUrl: String? = null,
    val voiceId: String? = null
)

@Serializable
data class ConversationSummaryDto(
    val id: String,
    val characterId: String,
    val characterName: String,
    val characterAvatarUrl: String? = null,
    val updatedAt: Long,
    val startedAt: Long,
    val lastMessageAt: Long? = null,
    val lastPreview: String,
    val unreadCount: Int = 0,
    val hasUnreadBadge: Boolean = false
)

@Serializable
data class AssistantRegenerationDto(
    val id: String,
    val messageId: String,
    val content: String,
    val createdAt: Long
)

@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val position: Int,
    val role: String,
    val content: String,
    val edited: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val selectedRegenerationId: String? = null,
    val regenerations: List<AssistantRegenerationDto> = emptyList()
)

@Serializable
data class ConversationDetailDto(
    val id: String,
    val ownerUserId: String,
    val conversationVersion: Long,
    val character: CharacterDto,
    val messages: List<MessageDto>,
    val activeRunId: String? = null,
    val activeRunExpiresAt: Long? = null
)

@Serializable
data class CharacterMemoryDto(
    val conversationId: String,
    val shortTerm: String,
    val longTerm: String,
    val updatedAt: Long
)

@Serializable
data class UpdateCharacterMemoryRequestDto(
    val shortTerm: String,
    val longTerm: String
)

@Serializable
data class SendMessageRequestDto(
    val userMessageId: String,
    val content: String
)

@Serializable
data class CreateConversationRequestDto(
    val characterId: String,
    val forceNew: Boolean = false
)

@Serializable
data class EditMessageRequestDto(
    val content: String
)

@Serializable
data class SelectRegenerationRequestDto(
    val regenerationId: String? = null
)

@Serializable
data class GeneratePortraitRequestDto(
    val prompt: String,
    val preview: Boolean = false,
    val sourceAvatarUrl: String? = null
)

@Serializable
data class GeneratePortraitResponseDto(
    val avatarUrl: String
)

@Serializable
data class GenerateGreetingRequestDto(
    val name: String,
    val description: String
)

@Serializable
data class GenerateGreetingResponseDto(
    val greeting: String
)

@Serializable
data class GenerateChatBackgroundRequestDto(
    val prompt: String,
    val requestKey: String? = null
)

@Serializable
data class GenerateChatBackgroundResponseDto(
    val imageUrl: String
)

@Serializable
data class StreamEventDto(
    val type: String,
    val runId: String? = null,
    val conversationVersion: Long? = null,
    val userMessage: MessageDto? = null,
    val assistantMessageId: String? = null,
    val textDelta: String? = null,
    val assistantMessage: MessageDto? = null,
    val messageId: String? = null,
    val regeneration: AssistantRegenerationDto? = null,
    val selectedRegenerationId: String? = null,
    val conversationSummary: ConversationSummaryDto? = null,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class CursorPageDto<T>(
    val items: List<T>,
    val nextCursor: String? = null
)

@Serializable
data class StopChatRequestDto(val runId: String, val partialReply: StoppedReplyDto? = null)

@Serializable
data class StoppedReplyDto(val messageId: String, val text: String, val regenerate: Boolean = false)

@kotlinx.serialization.Serializable
data class TrendingSearchesResponseDto(val queries: List<String> = emptyList())
