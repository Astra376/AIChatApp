package com.example.aichat.core.network

import kotlinx.serialization.Serializable

@Serializable
data class GroupCharacterDto(val id: String, val name: String, val avatarUrl: String? = null)

@Serializable
data class GroupMessageDto(
    val id: String,
    val groupId: String,
    val position: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
    val characterId: String? = null,
    val characterName: String? = null,
    val avatarUrl: String? = null,
    val status: String = "complete"
)

@Serializable
data class GroupDetailDto(
    val id: String,
    val name: String,
    val characters: List<GroupCharacterDto>,
    val messages: List<GroupMessageDto> = emptyList(),
    val activeRunId: String? = null,
    val activeRunExpiresAt: Long? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long = 0,
    val nextBeforePosition: Int? = null
)

@Serializable
data class GroupPageDto(val items: List<GroupDetailDto>, val nextCursor: String? = null)

@Serializable
data class CreateGroupRequestDto(val name: String, val characterIds: List<String>)

@Serializable
data class GroupSendRequestDto(val userMessageId: String, val content: String)

@Serializable
data class GroupStopRequestDto(val runId: String)

@Serializable
data class GroupPresenceRequestDto(val typing: Boolean)
