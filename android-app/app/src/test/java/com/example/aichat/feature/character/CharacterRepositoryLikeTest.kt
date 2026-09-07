package com.example.aichat.feature.character

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aichat.core.db.AppDatabase
import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.CharacterEntity
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.network.CharacterApi
import com.example.aichat.core.network.CharacterDto
import com.example.aichat.core.network.CharacterLikeStateDto
import com.example.aichat.core.network.CharacterWriteRequestDto
import com.example.aichat.core.network.CursorPageDto
import com.example.aichat.core.network.GenerateChatBackgroundRequestDto
import com.example.aichat.core.network.GenerateChatBackgroundResponseDto
import com.example.aichat.core.network.GenerateGreetingRequestDto
import com.example.aichat.core.network.GenerateGreetingResponseDto
import com.example.aichat.core.network.GeneratePortraitRequestDto
import com.example.aichat.core.network.GeneratePortraitResponseDto
import com.example.aichat.core.network.ImageApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CharacterRepositoryLikeTest {
    private lateinit var database: AppDatabase
    private lateinit var characterDao: CharacterDao
    private lateinit var characterApi: ControllableCharacterApi
    private lateinit var repository: CharacterRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characterDao = database.characterDao()
        characterApi = ControllableCharacterApi()
        repository = CharacterRepository(
            context = context,
            characterDao = characterDao,
            characterApi = characterApi,
            imageApi = UnusedImageApi
        )
        characterDao.upsert(characterEntity())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun toggleLike_updatesRoomBeforeNetworkCompletes_thenReconcilesCanonicalState() = runBlocking {
        val mutation = async { repository.toggleLike(CharacterId) }
        characterApi.likeStarted.receive()

        val optimistic = characterDao.observeById(CharacterId).first { it?.likedByMe == true }
        assertThat(optimistic?.likeCount).isEqualTo(11)

        characterApi.likeResults.send(Result.success(CharacterLikeStateDto(true, 14)))

        assertThat(mutation.await().isSuccess).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(14)
    }

    @Test
    fun toggleLike_networkFailure_rollsBackOptimisticState() = runBlocking {
        val mutation = async { repository.toggleLike(CharacterId) }
        characterApi.likeStarted.receive()

        assertThat(characterDao.getById(CharacterId)?.likedByMe).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(11)
        characterApi.likeResults.send(Result.failure(IllegalStateException("network failed")))

        assertThat(mutation.await().isFailure).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isFalse()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(10)
    }

    @Test
    fun toggleLike_twoRapidMutationsSerializeAndEndInSecondDesiredState() = runBlocking {
        val firstMutation = async { repository.toggleLike(CharacterId) }
        characterApi.likeStarted.receive()
        val secondMutation = async { repository.toggleLike(CharacterId) }

        assertThat(characterApi.unlikeStarted.tryReceive().isFailure).isTrue()
        characterApi.likeResults.send(Result.success(CharacterLikeStateDto(true, 11)))
        assertThat(firstMutation.await().isSuccess).isTrue()

        characterApi.unlikeStarted.receive()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isFalse()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(10)
        characterApi.unlikeResults.send(Result.success(CharacterLikeStateDto(false, 10)))

        assertThat(secondMutation.await().isSuccess).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isFalse()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(10)
    }

    @Test
    fun toggleLike_callerCancellationDetachesWhileMutationReachesCanonicalState() = runBlocking {
        val mutation = async { repository.toggleLike(CharacterId) }
        characterApi.likeStarted.receive()

        mutation.cancelAndJoin()

        assertThat(characterDao.getById(CharacterId)?.likedByMe).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(11)
        characterApi.likeResults.send(Result.success(CharacterLikeStateDto(true, 14)))

        val canonical = withTimeout(5_000L) {
            characterDao.observeById(CharacterId).first { it?.likeCount == 14 }
        }
        assertThat(canonical?.likedByMe).isTrue()
    }

    @Test
    fun refreshCharacter_serializesWithInFlightLikeAndCannotOverwriteItsState() = runBlocking {
        val refresh = async { repository.refreshCharacter(CharacterId) }
        characterApi.getStarted.receive()
        val like = async { repository.toggleLike(CharacterId) }

        assertThat(characterApi.likeStarted.tryReceive().isFailure).isTrue()
        characterApi.getResults.send(characterDto(likedByMe = false, likeCount = 10))
        assertThat(refresh.await().isSuccess).isTrue()

        characterApi.likeStarted.receive()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(11)
        characterApi.likeResults.send(Result.success(CharacterLikeStateDto(true, 11)))

        assertThat(like.await().isSuccess).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likedByMe).isTrue()
        assertThat(characterDao.getById(CharacterId)?.likeCount).isEqualTo(11)
    }

    private fun characterEntity() = CharacterEntity(
        id = CharacterId,
        ownerUserId = "creator-1",
        authorUsername = "creator",
        name = "Astra",
        tagline = "A guide",
        greeting = "Hello",
        bio = "Character bio",
        systemPrompt = "Be helpful",
        definitionPrivate = false,
        visibility = CharacterVisibility.PUBLIC.name,
        avatarUrl = null,
        initialSceneUrl = null,
        initialSceneKey = null,
        publicChatCount = 1_000,
        likeCount = 10,
        likedByMe = false,
        lastActiveAt = 100L,
        createdAt = 100L,
        updatedAt = 100L
    )

    private fun characterDto(
        likedByMe: Boolean,
        likeCount: Int
    ) = CharacterDto(
        id = CharacterId,
        ownerUserId = "creator-1",
        authorUsername = "creator",
        name = "Astra",
        tagline = "A guide",
        greeting = "Hello",
        bio = "Character bio",
        systemPrompt = "Be helpful",
        visibility = CharacterVisibility.PUBLIC.name,
        avatarUrl = null,
        publicChatCount = 1_000,
        likeCount = likeCount,
        likedByMe = likedByMe,
        lastActiveAt = 100L,
        createdAt = 100L,
        updatedAt = 100L
    )

    private companion object {
        const val CharacterId = "character-1"
    }
}

private class ControllableCharacterApi : CharacterApi {
    override suspend fun autoCreate(body: com.example.aichat.core.network.AutoCreateCharacterRequestDto): com.example.aichat.core.network.AutoCreateCharacterDto = error("unused")
    override suspend fun psychology(characterId: String): com.example.aichat.core.network.CharacterPsychologyDefaultsDto = error("unused")
    override suspend fun emotionPortraits(characterId: String): com.example.aichat.core.network.EmotionPortraitsDto = error("unused")
    override suspend fun generateEmotionPortraits(characterId: String): com.example.aichat.core.network.EmotionPortraitsDto = error("unused")

    val likeStarted = Channel<Unit>(Channel.UNLIMITED)
    val unlikeStarted = Channel<Unit>(Channel.UNLIMITED)
    val getStarted = Channel<Unit>(Channel.UNLIMITED)
    val likeResults = Channel<Result<CharacterLikeStateDto>>(Channel.UNLIMITED)
    val unlikeResults = Channel<Result<CharacterLikeStateDto>>(Channel.UNLIMITED)
    val getResults = Channel<CharacterDto>(Channel.UNLIMITED)

    override suspend fun likeCharacter(characterId: String): CharacterLikeStateDto {
        likeStarted.send(Unit)
        return likeResults.receive().getOrThrow()
    }

    override suspend fun unlikeCharacter(characterId: String): CharacterLikeStateDto {
        unlikeStarted.send(Unit)
        return unlikeResults.receive().getOrThrow()
    }

    override suspend fun createCharacter(body: CharacterWriteRequestDto): CharacterDto =
        unused()

    override suspend fun generateGreeting(
        body: GenerateGreetingRequestDto
    ): GenerateGreetingResponseDto = unused()

    override suspend fun updateCharacter(
        characterId: String,
        body: CharacterWriteRequestDto
    ): CharacterDto = unused()

    override suspend fun getCharacter(characterId: String): CharacterDto {
        getStarted.send(Unit)
        return getResults.receive()
    }

    override suspend fun getOwnedCharacters(cursor: String?): CursorPageDto<CharacterDto> =
        unused()

    override suspend fun getLikedCharacters(cursor: String?): CursorPageDto<CharacterDto> =
        unused()
}

private object UnusedImageApi : ImageApi {
        override suspend fun uploadPortrait(body: okhttp3.RequestBody): com.example.aichat.core.network.GeneratePortraitResponseDto = error("unused")
    override suspend fun generatePortrait(
        body: GeneratePortraitRequestDto
    ): GeneratePortraitResponseDto = unused()

    override suspend fun generateChatBackground(
        body: GenerateChatBackgroundRequestDto
    ): GenerateChatBackgroundResponseDto = unused()
}

private fun <T> unused(): T = error("Unexpected API call in like test.")
