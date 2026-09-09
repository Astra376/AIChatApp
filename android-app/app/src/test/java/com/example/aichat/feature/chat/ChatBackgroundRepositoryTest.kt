package com.example.aichat.feature.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aichat.core.db.AppDatabase
import com.example.aichat.core.db.CharacterEntity
import com.example.aichat.core.db.ConversationEntity
import com.example.aichat.core.network.GenerateChatBackgroundRequestDto
import com.example.aichat.core.network.GenerateChatBackgroundResponseDto
import com.example.aichat.core.network.GeneratePortraitRequestDto
import com.example.aichat.core.network.GeneratePortraitResponseDto
import com.example.aichat.core.network.ImageApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatBackgroundRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var imageApi: FakeImageApi
    private lateinit var repository: ChatBackgroundRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        imageApi = FakeImageApi()
        repository = ChatBackgroundRepository(
            database = database,
            conversationDao = database.conversationDao(),
            sceneDao = database.conversationSceneDao(),
            imageApi = imageApi
        )
        seedConversation()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sendsConversationIdentityForServerSideSceneGrounding() = runTest {
        repository.refreshIfSceneChanged(CONVERSATION_ID).getOrThrow()
        assertThat(imageApi.backgroundRequests.single().conversationId).isEqualTo(CONVERSATION_ID)
        assertThat(imageApi.backgroundRequests.single().prompt).isEmpty()
        assertThat(imageApi.backgroundRequests.single().requestKey).isNull()
    }

    @Test
    fun persistsAuthoritativeSceneWithoutSharingItWithOtherChats() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        val scene = database.conversationSceneDao().getByConversation(CONVERSATION_ID)
        assertThat(scene?.sceneKey).isEqualTo("anime:server-scene")
        assertThat(scene?.imageUrl).isEqualTo(imageApi.backgroundUrl)
        assertThat(database.characterDao().getById(CHARACTER_ID)?.initialSceneUrl).isNull()
    }

    @Test
    fun replacesLegacyCharacterBackgroundWithTheCurrentStoryScene() = runTest {
        val character = requireNotNull(database.characterDao().getById(CHARACTER_ID))
        database.characterDao().upsert(character.copy(initialSceneUrl = "https://assets.example/legacy.jpg", initialSceneKey = "legacy"))
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)?.imageUrl).isEqualTo(imageApi.backgroundUrl)
        assertThat(imageApi.backgroundRequests).hasSize(1)
    }

    @Test
    fun initialProviderFailureLeavesSceneStateUnchanged() = runTest {
        imageApi.backgroundFailure = IllegalStateException("provider unavailable")
        assertThat(repository.ensureInitialBackground(CONVERSATION_ID).isFailure).isTrue()
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)).isNull()
    }

    @Test
    fun concurrentInitialCallsRequestOnlyOnce() = runTest {
        imageApi.backgroundDelayMillis = 50L
        coroutineScope { listOf(async { repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow() }, async { repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow() }).awaitAll() }
        assertThat(imageApi.backgroundRequests).hasSize(1)
    }

    @Test
    fun repairResolvesTheSameServerSceneWithoutNewRequestKey() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        repository.repairFailedBackground(CONVERSATION_ID, imageApi.backgroundUrl).getOrThrow()
        assertThat(imageApi.backgroundRequests).hasSize(2)
        assertThat(imageApi.backgroundRequests.map { it.conversationId }.distinct()).containsExactly(CONVERSATION_ID)
        assertThat(imageApi.backgroundRequests.all { it.requestKey == null }).isTrue()
    }

    @Test
    fun repairIgnoresAStaleImageFailure() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        repository.repairFailedBackground(CONVERSATION_ID, "https://assets.example/stale.jpg").getOrThrow()
        assertThat(imageApi.backgroundRequests).hasSize(1)
    }

    @Test
    fun failedRefreshPreservesPersistedScene() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        val before = database.conversationSceneDao().getByConversation(CONVERSATION_ID)
        imageApi.backgroundFailure = IllegalStateException("provider unavailable")
        assertThat(repository.refreshIfSceneChanged(CONVERSATION_ID).isFailure).isTrue()
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)).isEqualTo(before)
    }

    @Test
    fun reopeningUnchangedChatReusesCacheButNewActivityRechecksScene() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        assertThat(imageApi.backgroundRequests).hasSize(1)
        val conversation = requireNotNull(database.conversationDao().getById(CONVERSATION_ID))
        database.conversationDao().upsert(conversation.copy(updatedAt = System.currentTimeMillis() + 1_000))
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        assertThat(imageApi.backgroundRequests).hasSize(2)
    }

    private suspend fun seedConversation() {
        database.characterDao().upsert(
            CharacterEntity(
                id = CHARACTER_ID,
                ownerUserId = USER_ID,
                authorUsername = "astra",
                name = "Astra",
                tagline = "A stargazer",
                greeting = "Hello",
                bio = "A character who watches the night sky.",
                systemPrompt = "Stay in character.",
                definitionPrivate = false,
                visibility = "PUBLIC",
                avatarUrl = null,
                initialSceneUrl = null,
                initialSceneKey = null,
                publicChatCount = 0,
                likeCount = 0,
                likedByMe = false,
                lastActiveAt = 100L,
                createdAt = 100L,
                updatedAt = 100L
            )
        )
        database.conversationDao().upsert(
            ConversationEntity(
                id = CONVERSATION_ID,
                ownerUserId = USER_ID,
                characterId = CHARACTER_ID,
                version = 1,
                updatedAt = 100L,
                startedAt = 100L,
                lastMessageAt = null,
                previewText = "",
                unreadCount = 0,
                hasUnreadBadge = false
            )
        )
    }

    private class FakeImageApi : ImageApi {
        override suspend fun uploadPortrait(body: okhttp3.RequestBody): com.example.aichat.core.network.GeneratePortraitResponseDto = error("unused")
        val backgroundRequests = mutableListOf<GenerateChatBackgroundRequestDto>()
        var backgroundUrl = "https://assets.example/generated.jpg"
        var backgroundFailure: Throwable? = null
        var backgroundDelayMillis = 0L

        override suspend fun generatePortrait(body: GeneratePortraitRequestDto): GeneratePortraitResponseDto {
            error("Not used")
        }

        override suspend fun generateChatBackground(
            body: GenerateChatBackgroundRequestDto
        ): GenerateChatBackgroundResponseDto {
            backgroundRequests += body
            if (backgroundDelayMillis > 0L) delay(backgroundDelayMillis)
            backgroundFailure?.let { throw it }
            return GenerateChatBackgroundResponseDto(backgroundUrl, "server-scene", "A moonlit garden")
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val CHARACTER_ID = "character-1"
        const val CONVERSATION_ID = "conversation-1"
    }
}
