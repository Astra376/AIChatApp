package com.example.aichat.feature.chat

import com.example.aichat.core.db.ConversationMemoryDao
import com.example.aichat.core.db.ConversationMemoryEntity
import com.example.aichat.core.network.CharacterMemoryDto
import com.example.aichat.core.network.ConversationApi
import com.example.aichat.core.network.UpdateCharacterMemoryRequestDto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Extended details remain server-owned; the existing Room cache keeps its identity and data. */
data class CharacterMemorySnapshot(val memory: CharacterMemoryDto, val hasServerDetails: Boolean)

@Singleton
class CharacterMemoryRepository @Inject constructor(
    private val memoryDao: ConversationMemoryDao,
    private val conversationApi: ConversationApi
) {
    private val remoteDetails = MutableStateFlow<Map<String, CharacterMemoryDto>>(emptyMap())
    private val saving = ConcurrentHashMap.newKeySet<String>()
    private val revisions = ConcurrentHashMap<String, Long>()

    fun observeMemory(conversationId: String): Flow<CharacterMemorySnapshot?> = combine(
        memoryDao.observeByConversation(conversationId), remoteDetails
    ) { cached, details ->
        if (cached == null) null else {
            val remote = details[conversationId]
            CharacterMemorySnapshot(
                memory = remote ?: CharacterMemoryDto(cached.conversationId, cached.shortTerm, cached.longTerm, cached.updatedAt),
                hasServerDetails = remote != null
            )
        }
    }

    suspend fun refresh(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        memoryResult {
            val revision = revisions[conversationId] ?: 0L
            val detail = conversationApi.getCharacterMemory(conversationId)
            if (conversationId !in saving && (revisions[conversationId] ?: 0L) == revision) cache(detail)
        }
    }

    suspend fun save(
        conversationId: String,
        shortTerm: String,
        longTerm: String,
        midTerm: String? = null,
        emotion: com.example.aichat.core.network.CharacterEmotionDto? = null,
        personality: com.example.aichat.core.network.CharacterPersonalityDto? = null,
        psychology: com.example.aichat.core.network.CharacterPsychologyDto? = null
    ): Result<CharacterMemoryDto> = withContext(Dispatchers.IO) {
        memoryResult {
            check(saving.add(conversationId)) { "Memory is already being saved." }
            revisions.merge(conversationId, 1L) { previous, _ -> previous + 1 }
            try {
                // Use server-resolved limits; PATCH rechecks the current entitlement. Never truncate memory.
                val current = remoteDetails.value[conversationId] ?: conversationApi.getCharacterMemory(conversationId)
                val middle = midTerm ?: current.midTerm
                require(shortTerm.length <= current.limits.shortTerm) { "Short-term memory exceeds your ${current.limits.shortTerm}-character limit." }
                require(middle.length <= current.limits.midTerm) { "Mid-term memory exceeds your ${current.limits.midTerm}-character limit." }
                require(longTerm.length <= current.limits.longTerm) { "Long-term memory exceeds your ${current.limits.longTerm}-character limit." }
                val saved = conversationApi.updateCharacterMemory(conversationId,
                    UpdateCharacterMemoryRequestDto(shortTerm.trim(), longTerm.trim(), middle.trim(), emotion, personality, psychology))
                cache(saved)
                saved
            } finally {
                saving.remove(conversationId)
            }
        }
    }

    private suspend fun cache(memory: CharacterMemoryDto) {
        memoryDao.upsert(ConversationMemoryEntity(memory.conversationId, memory.shortTerm, memory.longTerm, memory.updatedAt))
        remoteDetails.update { it + (memory.conversationId to memory) }
    }
}

private suspend inline fun <T> memoryResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
