package com.example.aichat.feature.chatlist

import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.CharacterEntity
import com.example.aichat.core.db.ConversationDao
import com.example.aichat.core.db.ConversationEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.model.ConversationSummary
import com.example.aichat.core.network.ConversationApi
import com.example.aichat.core.network.CreateConversationRequestDto
import com.example.aichat.core.network.ConversationSummaryDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val characterDao: CharacterDao,
    private val conversationApi: ConversationApi
) {
    fun observeConversations(ownerUserId: String): Flow<List<ConversationSummary>> {
        return conversationDao.observeConversationSummaries(ownerUserId).map { rows ->
            rows.map { it.toModel() }
        }
    }

    suspend fun refreshConversations(ownerUserId: String): Result<Unit> {
        return runCatching {
            val page = conversationApi.getConversations()
            page.items.forEach { summary ->
                upsertConversationSummary(ownerUserId, summary)
            }
        }
    }

    suspend fun ensureConversation(ownerUserId: String, characterId: String): Result<String> {
        return runCatching {
            conversationDao.findByOwnerAndCharacter(ownerUserId, characterId)?.let { return@runCatching it.id }
            val summary = conversationApi.createConversation(CreateConversationRequestDto(characterId))
            upsertConversationSummary(ownerUserId, summary)
            summary.id
        }
    }

    suspend fun startNewConversation(ownerUserId: String, characterId: String): Result<String> {
        return runCatching {
            val summary = conversationApi.createConversation(
                CreateConversationRequestDto(characterId = characterId, forceNew = true)
            )
            upsertConversationSummary(ownerUserId, summary)
            summary.id
        }
    }

    suspend fun markConversationRead(conversationId: String): Result<Unit> {
        return runCatching {
            conversationApi.markConversationRead(conversationId)
            conversationDao.getById(conversationId)?.let { entity ->
                conversationDao.upsert(
                    entity.copy(
                        unreadCount = 0,
                        hasUnreadBadge = false
                    )
                )
            }
        }
    }

    private suspend fun upsertConversationSummary(ownerUserId: String, summary: ConversationSummaryDto) {
        val existingCharacter = characterDao.getById(summary.characterId)
        characterDao.upsert(
            CharacterEntity(
                id = summary.characterId,
                ownerUserId = existingCharacter?.ownerUserId ?: "",
                authorUsername = existingCharacter?.authorUsername ?: "",
                name = summary.characterName,
                tagline = existingCharacter?.tagline ?: "",
                greeting = existingCharacter?.greeting ?: "",
                bio = existingCharacter?.bio ?: "",
                systemPrompt = existingCharacter?.systemPrompt ?: "",
                definitionPrivate = existingCharacter?.definitionPrivate ?: false,
                visibility = existingCharacter?.visibility ?: CharacterVisibility.PUBLIC.name,
                avatarUrl = summary.characterAvatarUrl ?: existingCharacter?.avatarUrl,
                initialSceneUrl = existingCharacter?.initialSceneUrl,
                initialSceneKey = existingCharacter?.initialSceneKey,
                publicChatCount = existingCharacter?.publicChatCount ?: 0,
                likeCount = existingCharacter?.likeCount ?: 0,
                likedByMe = existingCharacter?.likedByMe ?: false,
                lastActiveAt = existingCharacter?.lastActiveAt ?: (summary.lastMessageAt ?: summary.updatedAt),
                createdAt = existingCharacter?.createdAt ?: summary.startedAt,
                updatedAt = maxOf(existingCharacter?.updatedAt ?: 0L, summary.updatedAt)
            )
        )
        conversationDao.upsert(
            ConversationEntity(
                id = summary.id,
                ownerUserId = ownerUserId,
                characterId = summary.characterId,
                version = existingConversationVersion(summary.id),
                updatedAt = summary.updatedAt,
                startedAt = summary.startedAt,
                lastMessageAt = summary.lastMessageAt,
                previewText = summary.lastPreview,
                unreadCount = summary.unreadCount,
                hasUnreadBadge = summary.hasUnreadBadge
            )
        )
    }

    private suspend fun existingConversationVersion(conversationId: String): Long {
        return conversationDao.getById(conversationId)?.version ?: 0L
    }
}
