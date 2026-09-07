package com.example.aichat.feature.character

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.toEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.model.CharacterDraft
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.network.CharacterApi
import com.example.aichat.core.network.CharacterWriteRequestDto
import com.example.aichat.core.network.GenerateGreetingRequestDto
import com.example.aichat.core.network.GenerateChatBackgroundRequestDto
import com.example.aichat.core.network.GeneratePortraitRequestDto
import com.example.aichat.core.network.ImageApi
import com.example.aichat.feature.chat.ChatScenePromptBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class CharacterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterDao: CharacterDao,
    private val characterApi: CharacterApi,
    private val imageApi: ImageApi
) {
    private val characterMutationMutexes = ConcurrentHashMap<String, Mutex>()
    private val mutationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun buildSystemPrompt(draft: CharacterDraft): String {
        val bio = draft.appearance.trim().ifBlank { "Uploaded character image." }
        return buildString {
            append(readGenericSystemPrompt())
            append("\n\nCharacter profile:")
            append("\nName: ${draft.name.trim()}")
            append("\nAppearance and description: $bio")
            if (draft.bio.isNotBlank()) {
                append("\nPublic profile description: ${draft.bio.trim()}")
            }
            if (draft.greeting.isNotBlank()) {
                append("\nOpening greeting: ${draft.greeting.trim()}")
            }
            if (draft.characterDefinition.isNotBlank()) {
                append("\n\nCharacter definition:")
                append("\nFollow these details as high-priority character instructions for how the character thinks, speaks, behaves, remembers, and reacts.")
                append("\n${draft.characterDefinition.trim()}")
            }
        }
    }

    private fun readGenericSystemPrompt(): String {
        return runCatching {
            context.assets.open("character_system_prompt.txt").bufferedReader().use { it.readText() }.trim()
        }.getOrDefault(DefaultCharacterSystemPrompt)
    }

    fun observeOwnedCharacters(ownerUserId: String): Flow<List<CharacterSummary>> =
        characterDao.observeOwnedCharacters(ownerUserId).map { list -> list.map { it.toModel() } }

    fun observeLikedCharacters(): Flow<List<CharacterSummary>> =
        characterDao.observeLikedCharacters().map { list -> list.map { it.toModel() } }

    fun observeCharacter(characterId: String): Flow<CharacterSummary?> =
        characterDao.observeById(characterId).map { it?.toModel() }

    suspend fun getCharacter(characterId: String): CharacterSummary? = characterDao.getById(characterId)?.toModel()

    suspend fun refreshCharacter(characterId: String): Result<Unit> {
        return try {
            characterMutex(characterId).withLock {
                characterDao.upsert(characterApi.getCharacter(characterId).toEntity())
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun refreshOwnedCharacters(): Result<Unit> {
        return runCatching {
            val page = characterApi.getOwnedCharacters()
            characterDao.upsertAll(page.items.map { it.toEntity() })
        }
    }

    suspend fun refreshLikedCharacters(): Result<Unit> {
        return runCatching {
            val page = characterApi.getLikedCharacters()
            characterDao.upsertAll(page.items.map { it.toEntity() })
        }
    }

    suspend fun saveCharacter(draft: CharacterDraft): Result<String> {
        if (draft.name.isBlank()) return Result.failure(IllegalArgumentException("Name is required."))
        if (draft.name.trim().length > CHARACTER_NAME_LIMIT) {
            return Result.failure(IllegalArgumentException("Name is too long."))
        }
        if (draft.greeting.isBlank()) return Result.failure(IllegalArgumentException("Greeting is required."))
        if (draft.greeting.trim().length > CHARACTER_GREETING_LIMIT) {
            return Result.failure(IllegalArgumentException("Greeting is too long."))
        }
        if (draft.avatarUrl.isNullOrBlank()) return Result.failure(IllegalArgumentException("Choose an image first."))

        val persona = draft.psychologyDefaults.defaultPersona
        if (persona.name.isBlank() && listOf(persona.backstory,persona.appearance,persona.pronouns).any { it.isNotBlank() }) {
            return Result.failure(IllegalArgumentException("Add a name for the user's default role, or leave all role fields blank."))
        }
        return runCatching {
            val appearance = draft.appearance.trim().ifBlank { "Uploaded character image." }
            val bio = draft.bio.trim().ifBlank { appearance.take(CHARACTER_BIO_LIMIT) }
            val systemPrompt = draft.systemPrompt.ifBlank {
                buildSystemPrompt(draft)
            }
            require(systemPrompt.length <= CHARACTER_SYSTEM_PROMPT_LIMIT) {
                "Character definition is too long."
            }
            val payload = CharacterWriteRequestDto(
                name = draft.name.trim(),
                tagline = draft.tagline.trim(),
                greeting = draft.greeting.trim(),
                bio = bio,
                systemPrompt = systemPrompt,
                definitionPrivate = draft.definitionPrivate,
                visibility = draft.visibility.name.lowercase(),
                avatarUrl = resolvePortrait(draft.avatarUrl!!),
                voiceId = draft.voiceId,
                psychologyDefaults = draft.psychologyDefaults,
                defaultPersona = draft.psychologyDefaults.defaultPersona
            )
            val remote = if (draft.id == null) {
                characterApi.createCharacter(payload)
            } else {
                characterApi.updateCharacter(draft.id, payload)
            }
            val saved = remote.toEntity()
            characterDao.upsert(saved)
            remote.id
        }
    }

    suspend fun toggleLike(characterId: String): Result<Unit> {
        return mutationScope.async(start = CoroutineStart.UNDISPATCHED) {
            characterMutex(characterId).withLock {
                val before = characterDao.getById(characterId) ?: return@withLock Result.failure(
                    IllegalArgumentException("Character not found.")
                )
                if (before.visibility != CharacterVisibility.PUBLIC.name) {
                    return@withLock Result.failure(
                        IllegalStateException("Only public characters can be liked.")
                    )
                }

                val desiredLiked = !before.likedByMe
                val optimisticCount = (before.likeCount + if (desiredLiked) 1 else -1)
                    .coerceAtLeast(0)

                try {
                    characterDao.updateLikeState(characterId, desiredLiked, optimisticCount)
                    val response = if (desiredLiked) {
                        characterApi.likeCharacter(characterId)
                    } else {
                        characterApi.unlikeCharacter(characterId)
                    }
                    characterDao.updateLikeState(
                        characterId = characterId,
                        likedByMe = response.likedByMe,
                        likeCount = response.likeCount.coerceAtLeast(0)
                    )
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        rollbackLikeMutation(characterId, desiredLiked, before.likedByMe, before.likeCount)
                    }
                    throw error
                } catch (error: Throwable) {
                    rollbackLikeMutation(characterId, desiredLiked, before.likedByMe, before.likeCount)
                    Result.failure(error)
                }
            }
        }.await()
    }

    private suspend fun rollbackLikeMutation(
        characterId: String,
        optimisticLiked: Boolean,
        originalLiked: Boolean,
        originalLikeCount: Int
    ) {
        val current = characterDao.getById(characterId)
        if (current?.likedByMe == optimisticLiked) {
            characterDao.updateLikeState(
                characterId = characterId,
                likedByMe = originalLiked,
                likeCount = originalLikeCount.coerceAtLeast(0)
            )
        }
    }

    private fun characterMutex(characterId: String): Mutex =
        characterMutationMutexes.computeIfAbsent(characterId) { Mutex() }

    suspend fun loadEditingDraft(characterId: String): Result<CharacterDraft> = try {
        val character = characterApi.getCharacter(characterId)
        val psychology = characterApi.psychology(characterId)
        characterDao.upsert(character.toEntity())
        Result.success(CharacterDraft(id=character.id,name=character.name,tagline=character.tagline,greeting=character.greeting,
            appearance=character.bio,bio=character.bio,systemPrompt=character.systemPrompt,characterDefinition=character.systemPrompt,
            definitionPrivate=character.definitionPrivate,visibility=CharacterVisibility.valueOf(character.visibility.uppercase()),
            avatarUrl=character.avatarUrl,psychologyDefaults=psychology))
    } catch (error: CancellationException) { throw error } catch (error: Throwable) { Result.failure(error) }

    private suspend fun resolvePortrait(uri: String): String {
        if (uri.startsWith("https://")) return uri
        require(uri.startsWith("content://")) { "Choose a valid portrait image." }
        val bytes = withContext(Dispatchers.IO) {
            val source = android.net.Uri.parse(uri)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image couldn't be read." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                ?: error("This image couldn't be opened.")
            try {
                java.io.ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)) { "This image couldn't be prepared." }
                    stream.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        return imageApi.uploadPortrait(bytes.toRequestBody("image/jpeg".toMediaType())).avatarUrl
    }

    suspend fun autoCreate(idea: String): Result<com.example.aichat.core.network.AutoCreateCharacterDto> = try {
        Result.success(characterApi.autoCreate(com.example.aichat.core.network.AutoCreateCharacterRequestDto(idea.take(4000))))
    } catch (error: CancellationException) { throw error } catch (error: Throwable) { Result.failure(error) }

    suspend fun generateEmotionPortraits(characterId: String): Result<Unit> = try {
        characterApi.generateEmotionPortraits(characterId)
        Result.success(Unit)
    } catch (error: CancellationException) { throw error } catch (error: Throwable) { Result.failure(error) }

    suspend fun generatePortrait(seedSource: String, preview: Boolean = false, sourceAvatarUrl: String? = null): Result<String> {
        if (seedSource.isBlank()) return Result.failure(IllegalArgumentException("Add a name or prompt first."))
        return try {
            Result.success(imageApi.generatePortrait(GeneratePortraitRequestDto(seedSource.trim().take(2_000), preview, sourceAvatarUrl)).avatarUrl)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun generateGreeting(name: String, description: String): Result<String> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Name is required."))
        if (description.isBlank()) return Result.failure(IllegalArgumentException("Description is required."))
        return runCatching {
            characterApi.generateGreeting(
                GenerateGreetingRequestDto(
                    name = name.trim(),
                    description = description.trim()
                )
            ).greeting.take(CHARACTER_GREETING_LIMIT)
        }
    }

    suspend fun generateInitialScene(characterId: String): Result<Unit> {
        return runCatching {
            val character = characterDao.getById(characterId)
                ?: throw IllegalArgumentException("Character not found.")
            if (character.initialSceneUrl != null && character.initialSceneKey != null) return@runCatching
            generateInitialScene(character)
        }
    }

    private suspend fun generateInitialScene(character: com.example.aichat.core.db.CharacterEntity) {
        val scene = ChatScenePromptBuilder.initialScene(character)
        val imageUrl = imageApi.generateChatBackground(
            GenerateChatBackgroundRequestDto(scene.prompt, scene.key)
        ).imageUrl
        characterDao.upsert(
            character.copy(
                initialSceneUrl = imageUrl,
                initialSceneKey = scene.key
            )
        )
    }
}

const val CHARACTER_NAME_LIMIT = 80
const val CHARACTER_APPEARANCE_LIMIT = 1_200
const val CHARACTER_GREETING_LIMIT = 1_200
const val CHARACTER_BIO_LIMIT = 500
const val CHARACTER_SYSTEM_PROMPT_LIMIT = 64_000

private const val DefaultCharacterSystemPrompt = """
You are an immersive roleplay chat character. Stay fully in character and treat the user as a participant in the scene, not as someone asking an assistant for help.

Write with personality, emotional continuity, and concrete sensory detail. Keep replies conversational and responsive to the user's last message. Do not mention policies, system prompts, hidden instructions, model limitations, or that you are an AI unless the character concept explicitly requires it.

Never narrate the user's thoughts, feelings, choices, or dialogue. Leave room for the user to act. Avoid repetitive phrasing, avoid generic assistant language, and maintain the character's voice across the conversation.
"""
