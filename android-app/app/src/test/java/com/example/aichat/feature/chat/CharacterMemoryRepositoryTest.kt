package com.example.aichat.feature.chat

import com.example.aichat.core.db.ConversationMemoryDao
import com.example.aichat.core.db.ConversationMemoryEntity
import com.example.aichat.core.network.CharacterEmotionDto
import com.example.aichat.core.network.CharacterMemoryDto
import com.example.aichat.core.network.ConversationApi
import com.example.aichat.core.network.ConversationDetailDto
import com.example.aichat.core.network.ConversationSummaryDto
import com.example.aichat.core.network.CreateConversationRequestDto
import com.example.aichat.core.network.CursorPageDto
import com.example.aichat.core.network.MemoryLimitsDto
import com.example.aichat.core.network.UpdateCharacterMemoryRequestDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CharacterMemoryRepositoryTest {
    @Test
    fun standardLimits_rejectOversizedSave_withoutTruncatingPreviouslySavedMemory() = runTest {
        val dao = FakeMemoryDao()
        val api = FakeMemoryApi().apply { detail = detail.copy(shortTerm = "a".repeat(6_000)) }
        val repository = CharacterMemoryRepository(dao, api)
        repository.refresh(ID).getOrThrow()

        val result = repository.save(ID, "b".repeat(4_001), "long")

        assertThat(result.isFailure).isTrue()
        assertThat(api.updates).isEmpty()
        assertThat(dao.getByConversation(ID)?.shortTerm).hasLength(6_000)
    }

    @Test
    fun ultraLimits_acceptFullSizedShortMidAndLongMemory() = runTest {
        val api = FakeMemoryApi().apply {
            detail = detail.copy(tier = "ultra", limits = MemoryLimitsDto(shortTerm = 16_000, midTerm = 16_000, longTerm = 64_000))
        }
        val repository = CharacterMemoryRepository(FakeMemoryDao(), api)
        repository.refresh(ID).getOrThrow()
        val saved = repository.save(ID, "s".repeat(16_000), "l".repeat(64_000), "m".repeat(16_000)).getOrThrow()
        assertThat(saved.shortTerm).hasLength(16_000)
        assertThat(saved.midTerm).hasLength(16_000)
        assertThat(saved.longTerm).hasLength(64_000)
        assertThat(repository.observeMemory(ID).first()?.memory?.tier).isEqualTo("ultra")
    }

    @Test
    fun saveWithoutMidTermArgument_preservesExistingMidTermMemory() = runTest {
        val api = FakeMemoryApi().apply { detail = detail.copy(midTerm = "An unresolved promise") }
        val repository = CharacterMemoryRepository(FakeMemoryDao(), api)
        repository.save(ID, "recent", "lasting").getOrThrow()
        assertThat(api.updates.single().midTerm).isEqualTo("An unresolved promise")
    }

    @Test
    fun failedSave_keepsCachedMemoryAndContextUnchanged() = runTest {
        val api = FakeMemoryApi().apply {
            detail = detail.copy(shortTerm = "original", midTerm = "story arc", emotion = CharacterEmotionDto(mood = "curious"))
            updateError = java.io.IOException("offline")
        }
        val dao = FakeMemoryDao()
        val repository = CharacterMemoryRepository(dao, api)
        repository.refresh(ID).getOrThrow()
        assertThat(repository.save(ID, "changed", "lasting", "changed arc").isFailure).isTrue()
        val observed = repository.observeMemory(ID).first()
        assertThat(observed?.memory?.shortTerm).isEqualTo("original")
        assertThat(observed?.memory?.midTerm).isEqualTo("story arc")
        assertThat(observed?.memory?.emotion?.mood).isEqualTo("curious")
    }

    @Test
    fun cachedMemoryBeforeRefresh_hasNoAssumedEntitlementsOrDiscardedContent() = runTest {
        val dao = FakeMemoryDao()
        dao.upsert(ConversationMemoryEntity(ID, "cached short", "cached long", 1))
        val repository = CharacterMemoryRepository(dao, FakeMemoryApi())
        val observed = repository.observeMemory(ID).first()
        assertThat(observed?.hasServerDetails).isFalse()
        assertThat(observed?.memory?.longTerm).isEqualTo("cached long")
    }

    @Test
    fun repeatedSaveWhileRequestPending_doesNotQueueAnotherWrite() = runTest {
        val api = FakeMemoryApi()
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        api.beforeUpdate = { requested.complete(Unit); finish.await() }
        val repository = CharacterMemoryRepository(FakeMemoryDao(), api)
        val first = async { repository.save(ID, "first", "lasting") }
        requested.await()
        assertThat(repository.save(ID, "second", "lasting").isFailure).isTrue()
        finish.complete(Unit)
        first.await().getOrThrow()
        assertThat(api.updates.map { it.shortTerm }).containsExactly("first")
    }

    @Test
    fun incomingMemoryDetails_updateLimitsAndCharacterState_withoutOverwritingUnsavedDraft() {
        val draft = CharacterMemoryUiState(shortTerm = "my unsaved edit", originalShortTerm = "old")
        val updated = draft.withMemory(CharacterMemoryDto(ID, "server short", "server long", 1,
            limits = MemoryLimitsDto(16_000, 16_000, 64_000), tier = "ultra",
            emotion = CharacterEmotionDto(mood = "hopeful")), serverDetails = true)
        assertThat(updated.shortTerm).isEqualTo("my unsaved edit")
        assertThat(updated.originalShortTerm).isEqualTo("old")
        assertThat(updated.limits.shortTerm).isEqualTo(16_000)
        assertThat(updated.emotion?.mood).isEqualTo("hopeful")
        assertThat(updated.hasChanges).isTrue()
    }

    private class FakeMemoryDao : ConversationMemoryDao {
        private val state = MutableStateFlow<ConversationMemoryEntity?>(null)
        override fun observeByConversation(conversationId: String): Flow<ConversationMemoryEntity?> = state.map { it?.takeIf { it.conversationId == conversationId } }
        override suspend fun getByConversation(conversationId: String) = state.value?.takeIf { it.conversationId == conversationId }
        override suspend fun upsert(memory: ConversationMemoryEntity) { state.value = memory }
        override suspend fun clear() { state.value = null }
    }

    private class FakeMemoryApi : ConversationApi {
        var detail = CharacterMemoryDto(ID, "", "", 1)
        var updateError: Throwable? = null
        var beforeUpdate: suspend () -> Unit = {}
        val updates = mutableListOf<UpdateCharacterMemoryRequestDto>()
        override suspend fun getCharacterMemory(conversationId: String) = detail
        override suspend fun updateCharacterMemory(conversationId: String, body: UpdateCharacterMemoryRequestDto): CharacterMemoryDto {
            updates += body
            beforeUpdate()
            updateError?.let { throw it }
            detail = detail.copy(shortTerm = body.shortTerm, longTerm = body.longTerm, midTerm = body.midTerm ?: detail.midTerm, updatedAt = detail.updatedAt + 1)
            return detail
        }
        override suspend fun getConversations(cursor: String?): CursorPageDto<ConversationSummaryDto> = error("unused")
        override suspend fun createConversation(body: CreateConversationRequestDto): ConversationSummaryDto = error("unused")
        override suspend fun getConversation(conversationId: String): ConversationDetailDto = error("unused")
        override suspend fun markConversationRead(conversationId: String) = Unit
    }

    private companion object { const val ID = "conversation-1" }
}
