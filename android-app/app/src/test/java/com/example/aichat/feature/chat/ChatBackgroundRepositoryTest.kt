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
    fun ensureInitialBackground_persistsGeneratedUrlForCharacterAndConversation() = runTest {
        imageApi.backgroundUrl = "https://assets.example/scene.jpg"

        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()

        val character = database.characterDao().getById(CHARACTER_ID)
        val scene = database.conversationSceneDao().getByConversation(CONVERSATION_ID)
        assertThat(imageApi.backgroundRequests).hasSize(1)
        assertThat(imageApi.backgroundRequests.single().prompt).contains("Astra")
        assertThat(character?.initialSceneUrl).isEqualTo("https://assets.example/scene.jpg")
        assertThat(character?.initialSceneKey).isNotEmpty()
        assertThat(imageApi.backgroundRequests.single().requestKey)
            .isEqualTo(character?.initialSceneKey)
        assertThat(scene?.imageUrl).isEqualTo("https://assets.example/scene.jpg")
        assertThat(scene?.sceneKey).isEqualTo(character?.initialSceneKey)
    }

    @Test
    fun ensureInitialBackground_reusesPersistedCharacterSceneWithoutGeneratingAgain() = runTest {
        val character = requireNotNull(database.characterDao().getById(CHARACTER_ID))
        database.characterDao().upsert(
            character.copy(
                initialSceneUrl = "https://assets.example/existing.jpg",
                initialSceneKey = "existing-key"
            )
        )

        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()

        val scene = database.conversationSceneDao().getByConversation(CONVERSATION_ID)
        assertThat(imageApi.backgroundRequests).isEmpty()
        assertThat(scene?.imageUrl).isEqualTo("https://assets.example/existing.jpg")
        assertThat(scene?.sceneKey).isEqualTo("existing-key")
    }

    @Test
    fun ensureInitialBackground_providerFailureLeavesSceneStateUnchanged() = runTest {
        imageApi.backgroundFailure = IllegalStateException("provider unavailable")

        val result = repository.ensureInitialBackground(CONVERSATION_ID)

        assertThat(result.isFailure).isTrue()
        assertThat(imageApi.backgroundRequests).hasSize(1)
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)).isNull()
        assertThat(database.characterDao().getById(CHARACTER_ID)?.initialSceneUrl).isNull()
    }

    @Test
    fun ensureInitialBackground_concurrentCallsGenerateOnlyOnce() = runTest {
        imageApi.backgroundDelayMillis = 50L

        coroutineScope {
            listOf(
                async { repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow() },
                async { repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow() }
            ).awaitAll()
        }

        assertThat(imageApi.backgroundRequests).hasSize(1)
    }

    @Test
    fun repairFailedBackground_replacesBrokenPersistedInitialScene() = runTest {
        val brokenUrl =
            "https://worker.example/v1/assets/chat-backgrounds/user-1/broken.jpg"
        val character = requireNotNull(database.characterDao().getById(CHARACTER_ID))
        database.characterDao().upsert(
            character.copy(
                initialSceneUrl = brokenUrl,
                initialSceneKey = "broken-key"
            )
        )
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        imageApi.backgroundUrl = "https://assets.example/repaired.jpg"

        repository.repairFailedBackground(CONVERSATION_ID, brokenUrl).getOrThrow()

        val repairedCharacter = database.characterDao().getById(CHARACTER_ID)
        val repairedScene = database.conversationSceneDao().getByConversation(CONVERSATION_ID)
        assertThat(imageApi.backgroundRequests).hasSize(1)
        assertThat(repairedCharacter?.initialSceneUrl)
            .isEqualTo("https://assets.example/repaired.jpg")
        assertThat(repairedScene?.imageUrl).isEqualTo("https://assets.example/repaired.jpg")
        assertThat(repairedScene?.sceneKey).isEqualTo(repairedCharacter?.initialSceneKey)
    }

    @Test
    fun repairFailedBackground_ignoresUrlThatIsNoLongerCurrent() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        val requestsBeforeRepair = imageApi.backgroundRequests.size

        repository.repairFailedBackground(
            CONVERSATION_ID,
            "https://assets.example/stale.jpg"
        ).getOrThrow()

        assertThat(imageApi.backgroundRequests).hasSize(requestsBeforeRepair)
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)?.imageUrl)
            .isEqualTo(imageApi.backgroundUrl)
    }

    @Test
    fun repairFailedBackground_generationFailureKeepsBrokenUrlForLaterRetry() = runTest {
        val brokenUrl = "https://assets.example/missing.jpg"
        val character = requireNotNull(database.characterDao().getById(CHARACTER_ID))
        database.characterDao().upsert(
            character.copy(
                initialSceneUrl = brokenUrl,
                initialSceneKey = "broken-key"
            )
        )
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        imageApi.backgroundFailure = IllegalStateException("provider unavailable")

        val result = repository.repairFailedBackground(CONVERSATION_ID, brokenUrl)

        assertThat(result.isFailure).isTrue()
        assertThat(database.characterDao().getById(CHARACTER_ID)?.initialSceneUrl)
            .isEqualTo(brokenUrl)
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)?.imageUrl)
            .isEqualTo(brokenUrl)
    }

    @Test
    fun reopeningChatPreservesTheCurrentScene() = runTest {
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        val scene = requireNotNull(database.conversationSceneDao().getByConversation(CONVERSATION_ID))
        database.conversationSceneDao().upsert(scene.copy(sceneKey = "new-scene", imageUrl = "https://assets.example/new.jpg"))
        repository.ensureInitialBackground(CONVERSATION_ID).getOrThrow()
        assertThat(database.conversationSceneDao().getByConversation(CONVERSATION_ID)?.imageUrl)
            .isEqualTo("https://assets.example/new.jpg")
        assertThat(imageApi.backgroundRequests).hasSize(1)
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
            return GenerateChatBackgroundResponseDto(backgroundUrl)
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val CHARACTER_ID = "character-1"
        const val CONVERSATION_ID = "conversation-1"
    }
}
