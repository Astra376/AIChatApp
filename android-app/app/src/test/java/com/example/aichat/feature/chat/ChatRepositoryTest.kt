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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
        repository.cancelAllOperations()
        database.close()
    }

    @Test
    fun cachedConversationIsHiddenFromAnotherSignedInAccount() = runTest {
        seedConversation(version = 1)
        assertThat(repository.observeConversation(CONVERSATION_ID, 20, "another-account").first()).isNull()
        assertThat(repository.observeConversation(CONVERSATION_ID, 20, USER_ID).first()?.ownerUserId).isEqualTo(USER_ID)
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
        assertThat(activeStream?.status).isEqualTo(ActiveStreamStatus.COMPLETED)
        assertThat(activeStream?.text).isNotEmpty()
        repository.finishDisplaying(CONVERSATION_ID, checkNotNull(activeStream).draftKey)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
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
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.status)
            .isEqualTo(ActiveStreamStatus.STOPPING)
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        sendJob.join()

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
        conversationApi.failure = java.io.IOException("offline")
        assertThat(repository.stopStreaming(CONVERSATION_ID, stream.draftKey).isFailure).isTrue()
        job.join()
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
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.status)
            .isEqualTo(ActiveStreamStatus.STOPPING)
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        sendJob.join()

        assertThat(messageDao.getById(pendingMessage.id)?.content).isEqualTo("don't remove this")
        assertThat(messageDao.getById(pendingMessage.id)?.sendState).isEqualTo(MessageSendState.FAILED.name)
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
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        continueJob.join()

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
        assertThat(activeStream?.status).isEqualTo(ActiveStreamStatus.COMPLETED)
        assertThat(activeStream?.text).isNotEmpty()
        repository.finishDisplaying(CONVERSATION_ID, checkNotNull(activeStream).draftKey)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
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
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        regenerateJob.join()

        val message = messageDao.getById("assistant-1")
        assertThat(message?.selectedRegenerationId).isEqualTo("regen-partial")
        assertThat(database.assistantRegenerationDao().getById("regen-partial")?.content)
            .isEqualTo("partial variant")
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun sendMessage_eofAfterPartialDelta_failsAndClearsOnlyItsDraft() = runTest {
        conversationApi.failure = java.io.IOException("offline")
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
        conversationApi.failure = java.io.IOException("offline")
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
        conversationApi.failure = java.io.IOException("offline")
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
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        job.join()
    }

    @Test
    fun observeConversation_characterLikeUpdate_emitsUpdatedCharacter() = runTest {
        seedConversation(version = 1)

        repository.observeConversation(CONVERSATION_ID, messageLimit = 20, ownerUserId = USER_ID).test {
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

    @Test
    fun leavingChat_keepsReceivingDeltas_andNewObserverStartsAtReceivedText() = runTest {
        seedConversation(version = 1)
        val events = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.continueHandler = { events }
        val screenJob = backgroundScope.launch { repository.continueAssistant(CONVERSATION_ID) }
        repository.observeActiveStream(CONVERSATION_ID).first { it != null }
        events.emit(ChatStreamEvent.AcceptedContinue("run-live", 1, "assistant-live"))
        events.emit(ChatStreamEvent.Delta("run-live", "First"))
        repository.observeActiveStream(CONVERSATION_ID).first { it?.text == "First" }

        screenJob.cancelAndJoin()
        events.emit(ChatStreamEvent.Delta("run-live", " second"))
        val resumed = repository.observeActiveStream(CONVERSATION_ID).first { it?.text == "First second" }
        assertThat(resumed?.runId).isEqualTo("run-live")
        assertThat(chatApi.stoppedReplies).isEmpty()

        events.emit(ChatStreamEvent.CompletedSend("run-live", 2,
            remoteMessage("assistant-live", 1, "assistant", "First second", 100, 100),
            conversationSummary(2, "First second")))
        val completed = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first { it?.status == ActiveStreamStatus.COMPLETED })
        repository.finishDisplaying(CONVERSATION_ID, completed.draftKey)
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
        assertThat(messageDao.getById("assistant-live")?.content).isEqualTo("First second")
    }

    @Test
    fun editMessage_showsImmediately_andRestoresOriginalOnFailure() = runTest {
        conversationApi.failure = java.io.IOException("offline")
        seedConversation(version = 1)
        val original = sentMessage("assistant-1", 1, "ASSISTANT", "original", 100, 100)
        messageDao.insert(original)
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        chatApi.editHandler = { _, _ ->
            requested.complete(Unit)
            finish.await()
            throw java.io.IOException("offline")
        }
        val edit = async { repository.editMessage("assistant-1", "edited") }
        requested.await()
        assertThat(messageDao.getById("assistant-1")?.content).isEqualTo("edited")
        assertThat(repository.observeMutationBusy(CONVERSATION_ID).first()).isTrue()
        finish.complete(Unit)
        assertThat(edit.await().isFailure).isTrue()
        assertThat(messageDao.getById("assistant-1")).isEqualTo(original)
        assertThat(repository.observeMutationBusy(CONVERSATION_ID).first()).isFalse()
    }

    @Test
    fun rewind_showsImmediately_ignoresAdditionalTaps_withoutQueuingAnotherTarget() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll((1..4).map {
            sentMessage("message-$it", it, "ASSISTANT", "message $it", it.toLong(), it.toLong())
        })
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        chatApi.rewindHandler = {
            requested.complete(Unit)
            finish.await()
        }
        val rewind = async { repository.rewind("message-3") }
        requested.await()
        assertThat(messageDao.getMessages(CONVERSATION_ID).map { it.id })
            .containsExactly("message-1", "message-2", "message-3").inOrder()
        assertThat(repository.rewind("message-3").isFailure).isTrue()
        assertThat(repository.rewind("message-1").isFailure).isTrue()
        finish.complete(Unit)
        rewind.await().getOrThrow()
        assertThat(chatApi.rewindTargets).containsExactly("message-3")
        assertThat(messageDao.getLatestMessage(CONVERSATION_ID)?.id).isEqualTo("message-3")
    }

    @Test
    fun rewindFailure_restoresRemovedMessagesAndVariants() = runTest {
        conversationApi.failure = java.io.IOException("offline")
        seedConversation(version = 1)
        val messages = (1..3).map {
            sentMessage("message-$it", it, "ASSISTANT", "message $it", it.toLong(), it.toLong())
        }
        messageDao.insertAll(messages)
        val regeneration = AssistantRegenerationEntity("variant-3", "message-3", "variant text", 4)
        database.assistantRegenerationDao().insert(regeneration)
        chatApi.rewindHandler = { throw java.io.IOException("offline") }

        assertThat(repository.rewind("message-1").isFailure).isTrue()
        assertThat(messageDao.getMessages(CONVERSATION_ID)).containsExactlyElementsIn(messages).inOrder()
        assertThat(database.assistantRegenerationDao().getById("variant-3")).isEqualTo(regeneration)
    }

    @Test
    fun refreshStartedBeforeRewind_cannotRestoreDeletedMessages() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll((1..3).map {
            sentMessage("message-$it", it, "ASSISTANT", "message $it", it.toLong(), it.toLong())
        })
        conversationApi.detail = conversationDetail((1..3).map {
            remoteMessage("message-$it", it, "assistant", "message $it", it.toLong(), it.toLong())
        })
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        conversationApi.beforeGet = {
            requested.complete(Unit)
            finish.await()
        }
        val refresh = async { repository.refreshConversation(CONVERSATION_ID) }
        requested.await()
        repository.rewind("message-1").getOrThrow()
        finish.complete(Unit)
        refresh.await().getOrThrow()
        assertThat(messageDao.getMessages(CONVERSATION_ID).map { it.id }).containsExactly("message-1")
    }

    @Test
    fun previousReplyVariant_cannotChangeAfterNewMessageIsSent() = runTest {
        seedConversation(version = 1)
        messageDao.insert(sentMessage("assistant-1", 1, "ASSISTANT", "answer", 100, 100))
        val events = MutableSharedFlow<ChatStreamEvent>(replay = 8)
        streamingClient.sendHandler = { _, _, _ -> events }
        val send = backgroundScope.launch { repository.sendMessage(CONVERSATION_ID, "new question") }
        val stream = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first { it != null })

        assertThat(repository.selectRegeneration("assistant-1", ChatRepository.ORIGINAL_VARIANT_ID).isFailure).isTrue()
        assertThat(chatApi.selectionTargets).isEmpty()
        repository.stopStreaming(CONVERSATION_ID, stream.draftKey).getOrThrow()
        send.join()
    }

    @Test
    fun selectOriginalVariant_storesNull_andAllowsEditingOriginal() = runTest {
        seedConversation(version = 1)
        messageDao.insert(sentMessage("assistant-1", 1, "ASSISTANT", "original", 100, 100)
            .copy(selectedRegenerationId = "variant-1"))
        database.assistantRegenerationDao().insert(AssistantRegenerationEntity("variant-1", "assistant-1", "variant", 101))
        repository.selectRegeneration("assistant-1", ChatRepository.ORIGINAL_VARIANT_ID).getOrThrow()
        repository.editMessage("assistant-1", "changed original").getOrThrow()

        assertThat(messageDao.getById("assistant-1")?.selectedRegenerationId).isNull()
        assertThat(messageDao.getById("assistant-1")?.content).isEqualTo("changed original")
        assertThat(database.assistantRegenerationDao().getById("variant-1")?.content).isEqualTo("variant")
    }

    @Test
    fun remoteRunAfterProcessRestart_isExplicitlyStoppable_withoutAutomaticCancellation() = runTest {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(emptyList()).copy(
            activeRunId = "remote-run", activeRunExpiresAt = System.currentTimeMillis() + 60_000)
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        val active = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first())
        assertThat(active.remoteOnly).isTrue()
        assertThat(active.status).isEqualTo(ActiveStreamStatus.STREAMING)
        assertThat(chatApi.stoppedReplies).isEmpty()
        chatApi.stopHandler = {
            conversationApi.detail = checkNotNull(conversationApi.detail).copy(activeRunId = null, activeRunExpiresAt = null)
        }

        repository.stopStreaming(CONVERSATION_ID, active.draftKey).getOrThrow()

        assertThat(chatApi.stoppedReplies.single().runId).isEqualTo("remote-run")
        assertThat(chatApi.stoppedReplies.single().partialReply).isNull()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun refreshAfterRemoteRunCompletes_clearsRecoveredGeneratingState() = runTest {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(emptyList()).copy(
            activeRunId = "remote-run", activeRunExpiresAt = System.currentTimeMillis() + 60_000)
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("assistant-completed", 1, "assistant", "Completed elsewhere", 100, 100)))

        repository.refreshConversation(CONVERSATION_ID).getOrThrow()

        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
        assertThat(messageDao.getById("assistant-completed")?.content).isEqualTo("Completed elsewhere")
        assertThat(chatApi.stoppedReplies).isEmpty()
    }

    @Test
    fun expiredRemoteRun_doesNotLockTheComposer() = runTest {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(emptyList()).copy(
            activeRunId = "expired-run", activeRunExpiresAt = System.currentTimeMillis() - 1)
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun delayedRemoteStop_cannotCancelNewerRemoteRun() = runTest {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(emptyList()).copy(
            activeRunId = "old-run", activeRunExpiresAt = System.currentTimeMillis() + 60_000)
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        val old = checkNotNull(repository.observeActiveStream(CONVERSATION_ID).first())
        conversationApi.detail = checkNotNull(conversationApi.detail).copy(activeRunId = "new-run")
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()

        repository.stopStreaming(CONVERSATION_ID, old.draftKey).getOrThrow()

        assertThat(chatApi.stoppedReplies).isEmpty()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.runId).isEqualTo("new-run")
    }

    @Test
    fun recoveredRun_pollsCompletionAndUnlocksEditing_withoutWaitingForLeaseExpiry() = kotlinx.coroutines.runBlocking {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(emptyList()).copy(
            activeRunId = "remote-run", activeRunExpiresAt = System.currentTimeMillis() + 60_000)
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()?.remoteOnly).isTrue()
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("recovered-reply", 1, "assistant", "Finished on server", 100, 100)))

        kotlinx.coroutines.withTimeout(8_000) {
            repository.observeActiveStream(CONVERSATION_ID).first { it == null }
        }
        assertThat(messageDao.getById("recovered-reply")?.content).isEqualTo("Finished on server")
        repository.editMessage("recovered-reply", "Edited").getOrThrow()
    }

    @Test
    fun lostCompletion_recoversSavedReplyInsideRepository() = runTest {
        seedConversation(version = 1)
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("recovered-reply", 1, "assistant", "Full saved reply", 100, 100)))
        streamingClient.continueHandler = {
            flow {
                emit(ChatStreamEvent.AcceptedContinue("run-lost", 1, "recovered-reply"))
                emit(ChatStreamEvent.Delta("run-lost", "Full"))
                throw java.io.IOException("connection lost")
            }
        }
        assertThat(repository.continueAssistant(CONVERSATION_ID).isFailure).isTrue()
        assertThat(messageDao.getById("recovered-reply")?.content).isEqualTo("Full saved reply")
        assertThat(repository.observeActiveStream(CONVERSATION_ID).first()).isNull()
    }

    @Test
    fun lostAcceptance_recognizesCommittedUserMessage_insteadOfOfferingDuplicateSend() = runTest {
        seedConversation(version = 1)
        streamingClient.sendHandler = { _, userMessageId, content ->
            flow {
                conversationApi.detail = conversationDetail(listOf(
                    remoteMessage(userMessageId, 1, "user", content, 100, 100)))
                throw java.io.IOException("acceptance lost")
            }
        }
        val error = repository.sendMessage(CONVERSATION_ID, "Hello").exceptionOrNull() as SendMessageFailedException
        assertThat(error.accepted).isTrue()
        assertThat(messageDao.getMessages(CONVERSATION_ID).single().sendState).isEqualTo(MessageSendState.SENT.name)
    }

    @Test
    fun interruptedPendingSend_becomesRetryableAfterRefresh_andDoesNotBlockTranscript() = runTest {
        seedConversation(version = 1)
        messageDao.insert(sentMessage("orphan", -1, "USER", "unsent draft", 100, 100)
            .copy(sendState = MessageSendState.PENDING.name))
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("assistant-1", 0, "assistant", "Hello", 50, 50)))
        repository.refreshConversation(CONVERSATION_ID).getOrThrow()
        assertThat(messageDao.getById("orphan")?.sendState).isEqualTo(MessageSendState.FAILED.name)
        repository.selectRegeneration("assistant-1", ChatRepository.ORIGINAL_VARIANT_ID).getOrThrow()
    }

    @Test
    fun rewindWithLostResponse_keepsServerResult_insteadOfRestoringDeletedHistory() = runTest {
        seedConversation(version = 1)
        messageDao.insertAll((1..3).map {
            sentMessage("message-$it", it, "ASSISTANT", "message $it", it.toLong(), it.toLong())
        })
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("message-1", 1, "assistant", "message 1", 1, 1)))
        chatApi.rewindHandler = { throw java.io.IOException("response lost after commit") }
        repository.rewind("message-1").getOrThrow()
        assertThat(messageDao.getMessages(CONVERSATION_ID).map { it.id }).containsExactly("message-1")
        assertThat(repository.observeMutationBusy(CONVERSATION_ID).first()).isFalse()
    }

    @Test
    fun editWithLostResponse_keepsCommittedText() = runTest {
        seedConversation(version = 1)
        messageDao.insert(sentMessage("assistant-1", 1, "ASSISTANT", "original", 100, 100))
        conversationApi.detail = conversationDetail(listOf(
            remoteMessage("assistant-1", 1, "assistant", "edited", 100, 200)))
        chatApi.editHandler = { _, _ -> throw java.io.IOException("response lost after commit") }
        repository.editMessage("assistant-1", "edited").getOrThrow()
        assertThat(messageDao.getById("assistant-1")?.content).isEqualTo("edited")
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
        var stopHandler: suspend (com.example.aichat.core.network.StopChatRequestDto) -> Unit = { }
        override suspend fun stopReply(conversationId: String, body: com.example.aichat.core.network.StopChatRequestDto) {
            stoppedReplies += body
            stopHandler(body)
        }
        var editHandler: suspend (String, EditMessageRequestDto) -> Unit = { _, _ -> }
        var rewindHandler: suspend (String) -> Unit = { }
        val rewindTargets = mutableListOf<String>()
        val selectionTargets = mutableListOf<String>()
        override suspend fun editMessage(messageId: String, body: EditMessageRequestDto) = editHandler(messageId, body)

        override suspend fun rewind(messageId: String) {
            rewindTargets += messageId
            rewindHandler(messageId)
        }

        override suspend fun selectRegeneration(messageId: String, body: SelectRegenerationRequestDto) {
            selectionTargets += messageId
        }
    }

    private class FakeConversationApi : ConversationApi {
        var detail: ConversationDetailDto? = null
        var failure: Throwable? = null
        var beforeGet: suspend () -> Unit = { }

        override suspend fun getConversations(cursor: String?): CursorPageDto<ConversationSummaryDto> {
            return CursorPageDto(emptyList())
        }

        override suspend fun createConversation(body: CreateConversationRequestDto): ConversationSummaryDto {
            error("Not needed")
        }

        override suspend fun getConversation(conversationId: String): ConversationDetailDto {
            beforeGet()
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
