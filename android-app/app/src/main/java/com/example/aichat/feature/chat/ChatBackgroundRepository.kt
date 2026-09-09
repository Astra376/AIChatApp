package com.example.aichat.feature.chat

import com.example.aichat.core.db.AppDatabase
import com.example.aichat.core.db.ConversationDao
import com.example.aichat.core.db.ConversationSceneDao
import com.example.aichat.core.db.ConversationSceneEntity
import com.example.aichat.core.network.GenerateChatBackgroundRequestDto
import com.example.aichat.core.network.ImageApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Singleton
class ChatBackgroundRepository @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val sceneDao: ConversationSceneDao,
    private val imageApi: ImageApi
) {
    private val generationLocks = ConcurrentHashMap<String, Mutex>()
    suspend fun ensureInitialBackground(conversationId: String): Result<Unit> = refresh(conversationId, initial = true)
    suspend fun refreshIfSceneChanged(conversationId: String): Result<Unit> = refresh(conversationId)
    suspend fun repairFailedBackground(conversationId: String, failedImageUrl: String): Result<Unit> {
        // Fetch the authoritative current scene; never create a new paid image
        // merely because Coil had a transient download failure.
        if (sceneDao.getByConversation(conversationId)?.imageUrl != failedImageUrl) return Result.success(Unit)
        return refresh(conversationId)
    }
    private suspend fun refresh(conversationId: String, initial: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                val conversation = conversationDao.getById(conversationId) ?: return@withLock
                val cached = sceneDao.getByConversation(conversationId)
                if (initial && cached != null && cached.sceneKey.startsWith("anime:") && cached.updatedAt >= conversation.updatedAt) return@withLock
                val requestedAt = System.currentTimeMillis()
                val scene = imageApi.generateChatBackground(GenerateChatBackgroundRequestDto(conversationId = conversationId))
                require(scene.imageUrl.isNotBlank() && scene.sceneKey.isNotBlank()) { "Scene background is not ready." }
                sceneDao.upsert(ConversationSceneEntity(conversationId, "anime:${scene.sceneKey}", scene.imageUrl, scene.prompt, requestedAt))
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
}
