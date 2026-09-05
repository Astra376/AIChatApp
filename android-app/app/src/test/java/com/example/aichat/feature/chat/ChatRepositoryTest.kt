package com.example.aichat.feature.chat

import android.content.Context
import app.cash.turbine.test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aichat.core.db.AppDatabase
import com.example.aichat.core.db.AssistantRegenerationEntity
import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.ConversationDao
import com.example.aichat.core.db.ConversationEntity
import com.example.aichat.core.db.MessageDao
import com.example.aichat.core.db.MessageEntity
import com.example.aichat.core.model.MessageSendState
import com.example.aichat.core.network.AssistantRegenerationDto
import com.example.aichat.core.network.ChatApi
import com.example.aichat.core.network.ChatStreamEvent
import com.example.aichat.core.network.ChatStreamingClient
import com.example.aichat.core.network.CharacterDto
import com.example.aichat.core.network.CharacterMemoryDto
import com.example.aichat.core.network.ConversationApi
import com.example.aichat.core.network.ConversationDetailDto
import com.example.aichat.core.network.ConversationSummaryDto
import com.example.aichat.core.network.CreateConversationRequestDto
import com.example.aichat.core.network.CursorPageDto
import com.example.aichat.core.network.EditMessageRequestDto
import com.example.aichat.core.network.MessageDto
import com.example.aichat.core.network.SelectRegenerationRequestDto
import com.example.aichat.core.network.UpdateCharacterMemoryRequestDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var characterDao: CharacterDao
    private lateinit var messageDao: MessageDao
    private lateinit var streamingClient: FakeStreamingClient
    private lateinit var chatApi: FakeChatApi
    private lateinit var conversationApi: FakeConversationApi
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = database.conversationDao()
        characterDao = database.characterDao()
        messageDao = database.messageDao()
        streamingClient = FakeStreamingClient()
        conversationApi = FakeConversationApi()
        chatApi = FakeChatApi()
        repository = ChatRepository(
            database = database,
            conversationDao = conversationDao,
            characterDao = characterDao,
            conversationSceneDao = database.conversationSceneDao(),
            messageDao = messageDao,
            regenerationDao = database.assistantRegenerationDao(),
            chatApi = chatApi,
            conversationApi = conversationApi,
            streamingClient = streamingClient
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sendMessage_acceptAndComplete_persistsSingleAssistantReply() = runTest {
        seedConversation(version = 1)
        streamingClient.sendHandler = { conversationId, userMessageId, _ ->
            flow {
                emit(
                    ChatStreamEvent.AcceptedSend(
                        runId = "run-1",
                        conversationVersion = 1,
                        userMessage = remoteMessage(
                            id = userMessageId,
                            conversationId = conversationId,
                            position = 1,
                            role = "user",
                            content = "hello",
                            createdAt = 100L,
                            updatedAt = 100L
                        ),
                        assistantMessageId = "assistant-1"
                    )
                )
                emit(ChatStreamEvent.Delta(runId = "run-1", textDelta = "hello"))
                emit(
                    ChatStreamEvent.CompletedSend(
                        runId = "run-1",
                        conversationVersion = 2,
                        assistantMessage = remoteMessage(
                            id = "assistant-1",
                            position = 2,
                            role = "assistant",
                            content = "hello there",
                            createdAt = 200L,
                            updatedAt = 200L
                        ),
                        conversationSummary = conversationSummary(version = 2, preview = "hello there")
                    )
                )
            }
        }

        repository.sendMessage(CONVERSATION_ID, "hello").getOrThrow()

        val messages = messageDao.getMessages(CONVERSATION_ID)
        val activeStream = repository.observeActiveStream(CONVERSATION_ID).first()

        assertThat(messages).hasSize(2)
        assertThat(messages.count { it.role == "ASSISTANT" }).isEqualTo(1)
        assertThat(messages[0].role).isEqualTo("USER")
        assertThat(messages[1].id).isEqualTo("assistant-1")
        assertThat(activeStream).isNull()
    }

    @Test
    fun stopRequested_afterAcceptedSend_reconcilesPartialReplyAndKeepsUserMessage() = runTest {
        seedConversation(version = 1)
        val sendEvents = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.sendHandler = { _, _, _ -> sendEvents }

        val sendJob = backgroundScope.launch {
            repository.sendMessage(CONVERSATION_ID, "hello")
        }

        repository.observeActiveStream(CONVERSATION_ID).first { it != null }
        val pendingMessage = messageDao.getLocalOnlyMessages(CONVERSATION_ID).single()
        sendEvents.emit(
            ChatStreamEvent.AcceptedSend(
                runId = "run-1",
                conversationVersion = 1,
                userMessage = remoteMessage(
                    id = pendingMessage.id,
                    position = 1,
                    role = "user",
                    content = "hello",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                assistantMessageId = "assistant-1"
            )
        )
        sendEvents.emit(ChatStreamEvent.Delta(runId = "run-1", textDelta = "partial reply"))

        val stream = repository.observeActiveStream(CONVERSATION_ID).first { it?.text == "partial reply" }
        checkNotNull(stream)
        conversationApi.detail = conversationDetail(
            messages = listOf(
                remoteMessage(
                    id = pendingMessage.id,
                    position = 1,
                    role = "user",
                    content = "hello",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                remoteMessage(
                    id = "assistant-1",
                    position = 2,
                    role = "assistant",
                    content = "partial reply",
                    createdAt = 200L,
                    updatedAt = 200L
                )
            )
        )

        assertThat(repository.requestStop(CONVERSATION_ID, stream.draftKey)).isTrue()
        sendJob.cancel()
        sendJob.join()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.status)
            .isEqualTo(ActiveStreamStatus.STOPPING)

        repository.reconcileStoppedStream(CONVERSATION_ID, stream.draftKey).getOrThrow()

        assertThat(chatApi.stoppedReplies.single().runId).isEqualTo("run-1")
        assertThat(chatApi.stoppedReplies.single().partialReply?.text).isEqualTo("partial reply")
        assertThat(chatApi.stoppedReplies.single().partialReply?.messageId).isEqualTo("assistant-1")
        assertThat(chatApi.stoppedReplies.single().partialReply?.regenerate).isFalse()

        val messages = messageDao.getMessages(CONVERSATION_ID)
        assertThat(messages.map { it.id }).containsExactly(pendingMessage.id, "assistant-1").inOrder()
        assertThat(messages.first().content).isEqualTo("hello")
        assertThat(messages.last().content).isEqualTo("partial reply")
        assertThat(messageDao.getById(pendingMessage.id)?.sendState).isEqualTo(MessageSendState.SENT.name)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun failedStopReconciliationAlwaysUnlocksLocalChat() = runTest {
        seedConversation(version = 1)
        val events = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.sendHandler = { _, _, _ -> events }
        val job = backgroundScope.launch { repository.sendMessage(CONVERSATION_ID, "hello") }
        val stream = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first { it != null })
        repository.requestStop(CONVERSATION_ID, stream.draftKey)
        job.cancel()
        job.join()
        conversationApi.failure = java.io.IOException("offline")
        assertThat(repository.reconcileStoppedStream(CONVERSATION_ID, stream.draftKey).isFailure).isTrue()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun stopRequested_beforeAcceptance_keepsUserMessageAndSettlesDraft() = runTest {
        seedConversation(version = 1)
        val sendEvents = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.sendHandler = { _, _, _ -> sendEvents }

        val sendJob = backgroundScope.launch {
            repository.sendMessage(CONVERSATION_ID, "don't remove this")
        }

        val stream = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first { it != null })
        val pendingMessage = messageDao.getLocalOnlyMessages(CONVERSATION_ID).single()
        assertThat(repository.requestStop(CONVERSATION_ID, stream.draftKey)).isTrue()
        sendJob.cancel()
        sendJob.join()

        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.status)
            .isEqualTo(ActiveStreamStatus.STOPPING)
        repository.reconcileStoppedStream(CONVERSATION_ID, stream.draftKey).getOrThrow()

        assertThat(messageDao.getById(pendingMessage.id)?.content).isEqualTo("don't remove this")
        assertThat(messageDao.getById(pendingMessage.id)?.sendState).isEqualTo(MessageSendState.PENDING.name)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun stopRequested_duringContinuation_reconcilesPartialReply() = runTest {
        seedConversation(version = 1)
        messageDao.insert(
            sentMessage(
                id = "assistant-0",
                position = 0,
                role = "ASSISTANT",
                content = "opening",
                createdAt = 50L,
                updatedAt = 50L
            )
        )
        val continueEvents = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.continueHandler = { continueEvents }

        val continueJob = backgroundScope.launch {
            repository.continueAssistant(CONVERSATION_ID)
        }

        repository.observeActiveStream(CONVERSATION_ID).first { it != null }
        continueEvents.emit(
            ChatStreamEvent.AcceptedContinue(
                runId = "run-continue",
                conversationVersion = 1,
                assistantMessageId = "assistant-1"
            )
        )
        continueEvents.emit(ChatStreamEvent.Delta("run-continue", "continued text"))
        val stream = checkNotNull(
            repository.observeActiveStream(CONVERSATION_ID).first { it?.text == "continued text" }
        )
        conversationApi.detail = conversationDetail(
            messages = listOf(
                remoteMessage(
                    id = "assistant-0",
                    position = 0,
                    role = "assistant",
                    content = "opening",
                    createdAt = 50L,
                    updatedAt = 50L
                ),
                remoteMessage(
                    id = "assistant-1",
                    position = 1,
                    role = "assistant",
                    content = "continued text",
                    createdAt = 100L,
                    updatedAt = 100L
                )
            )
        )

        assertThat(repository.requestStop(CONVERSATION_ID, stream.draftKey)).isTrue()
        continueJob.cancel()
        continueJob.join()
        repository.reconcileStoppedStream(CONVERSATION_ID, stream.draftKey).getOrThrow()

        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
        assertThat(messageDao.getById("assistant-0")?.content).isEqualTo("opening")
        assertThat(messageDao.getById("assistant-1")?.content).isEqualTo("continued text")
    }

    @Test
    fun regenerateLatestAssistant_persistsSelectedVariant() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll(
            listOf(
                sentMessage(
                    id = "user-1",
                    position = 1,
                    role = "USER",
                    content = "hello",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                sentMessage(
                    id = "assistant-1",
                    position = 2,
                    role = "ASSISTANT",
                    content = "old answer",
                    createdAt = 200L,
                    updatedAt = 200L
                )
            )
        )
        streamingClient.regenerateHandler = { _ ->
            flow {
                emit(
                    ChatStreamEvent.AcceptedRegenerate(
                        runId = "run-2",
                        conversationVersion = 1,
                        messageId = "assistant-1",
                        assistantMessageId = "assistant-1"
                    )
                )
                emit(ChatStreamEvent.Delta(runId = "run-2", textDelta = "better"))
                emit(
                    ChatStreamEvent.CompletedRegenerate(
                        runId = "run-2",
                        conversationVersion = 2,
                        messageId = "assistant-1",
                        regeneration = AssistantRegenerationDto(
                            id = "regen-1",
                            messageId = "assistant-1",
                            content = "better answer",
                            createdAt = 300L
                        ),
                        selectedRegenerationId = "regen-1",
                        conversationSummary = conversationSummary(version = 2, preview = "better answer")
                    )
                )
            }
        }

        repository.regenerateLatestAssistant("assistant-1").getOrThrow()

        val message = messageDao.getById("assistant-1")
        val regenerations = database.assistantRegenerationDao().getByMessage("assistant-1")
        val activeStream = repository.observeActiveStream(CONVERSATION_ID).first()

        assertThat(message?.selectedRegenerationId).isEqualTo("regen-1")
        assertThat(regenerations.map(AssistantRegenerationEntity::id)).containsExactly("regen-1")
        assertThat(activeStream).isNull()
    }

    @Test
    fun stopRequested_duringRegeneration_reconcilesPartialVariant() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll(
            listOf(
                sentMessage(
                    id = "user-1",
                    position = 1,
                    role = "USER",
                    content = "hello",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                sentMessage(
                    id = "assistant-1",
                    position = 2,
                    role = "ASSISTANT",
                    content = "old answer",
                    createdAt = 200L,
                    updatedAt = 200L
                )
            )
        )
        val regenerateEvents = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.regenerateHandler = { regenerateEvents }
        val regenerateJob = backgroundScope.launch {
            repository.regenerateLatestAssistant("assistant-1")
        }

        repository.observeActiveStream(CONVERSATION_ID).first { it != null }
        regenerateEvents.emit(
            ChatStreamEvent.AcceptedRegenerate(
                runId = "run-regen",
                conversationVersion = 1,
                messageId = "assistant-1",
                assistantMessageId = "assistant-1"
            )
        )
        regenerateEvents.emit(ChatStreamEvent.Delta("run-regen", "partial variant"))
        val stream = checkNotNull(
            repository.observeActiveStream(CONVERSATION_ID).first { it?.text == "partial variant" }
        )
        val regeneration = AssistantRegenerationDto(
            id = "regen-partial",
            messageId = "assistant-1",
            content = "partial variant",
            createdAt = 300L
        )
        conversationApi.detail = conversationDetail(
            messages = listOf(
                remoteMessage(
                    id = "user-1",
                    position = 1,
                    role = "user",
                    content = "hello",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                remoteMessage(
                    id = "assistant-1",
                    position = 2,
                    role = "assistant",
                    content = "old answer",
                    createdAt = 200L,
                    updatedAt = 300L,
                    selectedRegenerationId = regeneration.id,
                    regenerations = listOf(regeneration)
                )
            )
        )

        assertThat(repository.requestStop(CONVERSATION_ID, stream.draftKey)).isTrue()
        regenerateJob.cancel()
        regenerateJob.join()
        repository.reconcileStoppedStream(CONVERSATION_ID, stream.draftKey).getOrThrow()

        val message = messageDao.getById("assistant-1")
        assertThat(message?.selectedRegenerationId).isEqualTo("regen-partial")
        assertThat(database.assistantRegenerationDao().getById("regen-partial")?.content)
            .isEqualTo("partial variant")
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun sendMessage_eofAfterPartialDelta_failsAndClearsOnlyItsDraft() = runTest {
        seedConversation(version = 1)
        streamingClient.sendHandler = { conversationId, userMessageId, _ ->
            flow {
                emit(
                    ChatStreamEvent.AcceptedSend(
                        runId = "run-eof",
                        conversationVersion = 1,
                        userMessage = remoteMessage(
                            id = userMessageId,
                            conversationId = conversationId,
                            position = 1,
                            role = "user",
                            content = "hello",
                            createdAt = 100L,
                            updatedAt = 100L
                        ),
                        assistantMessageId = "assistant-eof"
                    )
                )
                emit(ChatStreamEvent.Delta("run-eof", "partial"))
            }
        }

        val result = repository.sendMessage(CONVERSATION_ID, "hello")

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(SendMessageFailedException::class.java)
        assertThat((error as SendMessageFailedException).accepted).isTrue()
        assertThat(messageDao.getMessages(CONVERSATION_ID).single().content).isEqualTo("hello")
        assertThat(messageDao.getMessages(CONVERSATION_ID).single().sendState)
            .isEqualTo(MessageSendState.SENT.name)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun continueAssistant_eofAfterPartialDelta_failsAndClearsDraft() = runTest {
        seedConversation(version = 1)
        streamingClient.continueHandler = {
            flow {
                emit(ChatStreamEvent.AcceptedContinue("run-eof", 1, "assistant-eof"))
                emit(ChatStreamEvent.Delta("run-eof", "partial"))
            }
        }

        val result = repository.continueAssistant(CONVERSATION_ID)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("terminal event")
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun regenerateLatestAssistant_eofAfterPartialDelta_failsAndClearsDraft() = runTest {
        seedConversation(version = 1)
        messageDao.insert(
            sentMessage(
                id = "assistant-1",
                position = 1,
                role = "ASSISTANT",
                content = "old answer",
                createdAt = 100L,
                updatedAt = 100L
            )
        )
        streamingClient.regenerateHandler = {
            flow {
                emit(ChatStreamEvent.AcceptedRegenerate("run-eof", 1, "assistant-1", "assistant-1"))
                emit(ChatStreamEvent.Delta("run-eof", "partial"))
            }
        }

        val result = repository.regenerateLatestAssistant("assistant-1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("terminal event")
        assertThat(messageDao.getById("assistant-1")?.content).isEqualTo("old answer")
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun staleStopReconciliationKey_cannotClearCurrentDraft() = runTest {
        seedConversation(version = 1)
        val events = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.continueHandler = { events }
        val job = backgroundScope.launch {
            repository.continueAssistant(CONVERSATION_ID)
        }
        val stream = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first { it != null })
        assertThat(repository.requestStop(CONVERSATION_ID, stream.draftKey)).isTrue()

        repository.reconcileStoppedStream(CONVERSATION_ID, "stale-draft-key").getOrThrow()

        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.draftKey)
            .isEqualTo(stream.draftKey)
        job.cancel()
        job.join()
    }

    @Test
    fun observeConversation_characterLikeUpdate_emitsUpdatedCharacter() = runTest {
        seedConversation(version = 1)

        repository.observeConversation(CONVERSATION_ID, messageLimit = 20).test {
            val initial = awaitItem()
            assertThat(initial?.character?.likedByMe).isFalse()
            assertThat(initial?.character?.likeCount).isEqualTo(0)

            characterDao.updateLikeState(
                characterId = CHARACTER_ID,
                likedByMe = true,
                likeCount = 1
            )

            val updated = awaitItem()
            assertThat(updated?.character?.likedByMe).isTrue()
            assertThat(updated?.character?.likeCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshConversation_replacesCommittedTranscriptAndKeepsLocalOnlyMessages() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll(
            listOf(
                sentMessage(
                    id = "stale-user",
                    position = 1,
                    role = "USER",
                    content = "old",
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                sentMessage(
                    id = "stale-assistant",
                    position = 2,
                    role = "ASSISTANT",
                    content = "old answer",
                    createdAt = 200L,
                    updatedAt = 200L
                ),
                MessageEntity(
                    id = "local-failed",
                    conversationId = CONVERSATION_ID,
                    position = -1,
                    role = "USER",
                    content = "try again",
                    edited = false,
                    createdAt = 300L,
                    updatedAt = 300L,
                    selectedRegenerationId = null,
                    sendState = MessageSendState.FAILED.name
                )
            )
        )
        database.assistantRegenerationDao().insert(
            AssistantRegenerationEntity(
                id = "stale-regen",
                messageId = "stale-assistant",
                content = "stale variant",
                createdAt = 201L
            )
        )
        conversationApi.detail = conversationDetail(
            messages = listOf(
                remoteMessage(
                    id = "user-1",
                    position = 1,
                    role = "user",
                    content = "hello",
                    createdAt = 400L,
                    updatedAt = 400L
                ),
                remoteMessage(
                    id = "assistant-1",
                    position = 2,
                    role = "assistant",
                    content = "fresh answer",
                    createdAt = 500L,
                    updatedAt = 500L
                )
            )
        )

        repository.refreshConversation(CONVERSATION_ID).getOrThrow()

        val messages = messageDao.getMessages(CONVERSATION_ID)
        val regenerations = database.assistantRegenerationDao().getConversationRegenerations(CONVERSATION_ID)

        assertThat(messages.map { it.id }).containsExactly("user-1", "assistant-1", "local-failed").inOrder()
        assertThat(messages.first { it.id == "assistant-1" }.content).isEqualTo("fresh answer")
        assertThat(regenerations.map { it.id }).doesNotContain("stale-regen")
    }

    @Test
    fun refreshConversation_preservesLocallyGeneratedCharacterScene() = runTest {
        seedConversation(version = 1)
        val character = requireNotNull(characterDao.getById(CHARACTER_ID))
        characterDao.upsert(
            character.copy(
                initialSceneUrl = "https://images.example/scene.jpg",
                initialSceneKey = "scene-key"
            )
        )

        repository.refreshConversation(CONVERSATION_ID).getOrThrow()

        val refreshed = characterDao.getById(CHARACTER_ID)
        assertThat(refreshed?.initialSceneUrl).isEqualTo("https://images.example/scene.jpg")
        assertThat(refreshed?.initialSceneKey).isEqualTo("scene-key")
    }

    private suspend fun seedConversation(version: Long) {
        conversationDao.upsert(
            ConversationEntity(
                id = CONVERSATION_ID,
                ownerUserId = USER_ID,
                characterId = CHARACTER_ID,
                version = version,
                updatedAt = 100L,
                startedAt = 100L,
                lastMessageAt = null,
                previewText = "",
                unreadCount = 0,
                hasUnreadBadge = false
            )
        )
        characterDao.upsert(
            com.example.aichat.core.db.CharacterEntity(
                id = CHARACTER_ID,
                ownerUserId = USER_ID,
                authorUsername = "astra",
                name = "Astra",
                tagline = "",
                greeting = "",
                bio = "",
                systemPrompt = "Be helpful",
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
    }

    private fun sentMessage(
        id: String,
        position: Int,
        role: String,
        content: String,
        createdAt: Long,
        updatedAt: Long
    ) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        position = position,
        role = role,
        content = content,
        edited = false,
        createdAt = createdAt,
        updatedAt = updatedAt,
        selectedRegenerationId = null,
        sendState = MessageSendState.SENT.name
    )

    private fun remoteMessage(
        id: String,
        position: Int,
        role: String,
        content: String,
        createdAt: Long,
        updatedAt: Long,
        conversationId: String = CONVERSATION_ID,
        selectedRegenerationId: String? = null,
        regenerations: List<AssistantRegenerationDto> = emptyList()
    ) = MessageDto(
        id = id,
        conversationId = conversationId,
        position = position,
        role = role,
        content = content,
        edited = false,
        createdAt = createdAt,
        updatedAt = updatedAt,
        selectedRegenerationId = selectedRegenerationId,
        regenerations = regenerations
    )

    private fun conversationSummary(version: Long, preview: String) = ConversationSummaryDto(
        id = CONVERSATION_ID,
        characterId = CHARACTER_ID,
        characterName = "Astra",
        characterAvatarUrl = null,
        updatedAt = 100L + version,
        startedAt = 100L,
        lastMessageAt = 100L + version,
        lastPreview = preview
    )

    private fun conversationDetail(messages: List<MessageDto>) = ConversationDetailDto(
        id = CONVERSATION_ID,
        ownerUserId = USER_ID,
        conversationVersion = 2,
        character = CharacterDto(
            id = CHARACTER_ID,
            ownerUserId = USER_ID,
            authorUsername = "astra",
            name = "Astra",
            tagline = "",
            bio = "",
            systemPrompt = "Be helpful",
            visibility = "PUBLIC",
            avatarUrl = null,
            publicChatCount = 0,
            likeCount = 0,
            likedByMe = false,
            lastActiveAt = 100L,
            createdAt = 100L,
            updatedAt = 100L
        ),
        messages = messages
    )

    private class FakeChatApi : ChatApi {
        val stoppedReplies = mutableListOf<com.example.aichat.core.network.StopChatRequestDto>()
        override suspend fun stopReply(conversationId: String, body: com.example.aichat.core.network.StopChatRequestDto) {
            stoppedReplies += body
        }
        override suspend fun editMessage(messageId: String, body: EditMessageRequestDto) = Unit

        override suspend fun rewind(messageId: String) = Unit

        override suspend fun selectRegeneration(messageId: String, body: SelectRegenerationRequestDto) = Unit
    }

    private class FakeConversationApi : ConversationApi {
        var detail: ConversationDetailDto? = null
        var failure: Throwable? = null

        override suspend fun getConversations(cursor: String?): CursorPageDto<ConversationSummaryDto> {
            return CursorPageDto(emptyList())
        }

        override suspend fun createConversation(body: CreateConversationRequestDto): ConversationSummaryDto {
            error("Not needed")
        }

        override suspend fun getConversation(conversationId: String): ConversationDetailDto {
            failure?.let { throw it }
            return detail ?: ConversationDetailDto(
                id = conversationId,
                ownerUserId = USER_ID,
                conversationVersion = 1,
                character = CharacterDto(
                    id = CHARACTER_ID,
                    ownerUserId = USER_ID,
                    authorUsername = "astra",
                    name = "Astra",
                    tagline = "",
                    bio = "",
                    systemPrompt = "Be helpful",
                    visibility = "PUBLIC",
                    avatarUrl = null,
                    publicChatCount = 0,
                    likeCount = 0,
                    likedByMe = false,
                    lastActiveAt = 100L,
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                messages = emptyList()
            )
        }

        override suspend fun getCharacterMemory(conversationId: String): CharacterMemoryDto {
            return CharacterMemoryDto(conversationId, "", "", 0L)
        }

        override suspend fun updateCharacterMemory(
            conversationId: String,
            body: UpdateCharacterMemoryRequestDto
        ): CharacterMemoryDto {
            return CharacterMemoryDto(conversationId, body.shortTerm, body.longTerm, 0L)
        }

        override suspend fun markConversationRead(conversationId: String) = Unit
    }

    private class FakeStreamingClient : ChatStreamingClient {
        var sendHandler: (String, String, String) -> Flow<ChatStreamEvent> = { _, _, _ -> emptyFlow() }
        var regenerateHandler: (String) -> Flow<ChatStreamEvent> = { emptyFlow() }
        var continueHandler: (String) -> Flow<ChatStreamEvent> = { emptyFlow() }

        override fun sendMessage(conversationId: String, userMessageId: String, content: String): Flow<ChatStreamEvent> {
            return sendHandler(conversationId, userMessageId, content)
        }

        override fun continueAssistant(conversationId: String): Flow<ChatStreamEvent> {
            return continueHandler(conversationId)
        }

        override fun regenerateLatestAssistant(messageId: String): Flow<ChatStreamEvent> {
            return regenerateHandler(messageId)
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val CHARACTER_ID = "character-1"
        const val CONVERSATION_ID = "conversation-1"
    }
}
