package com.example.aichat.feature.chat

import androidx.room.withTransaction
import com.example.aichat.core.db.AppDatabase
import com.example.aichat.core.db.AssistantRegenerationDao
import com.example.aichat.core.db.AssistantRegenerationEntity
import com.example.aichat.core.db.CharacterDao
import com.example.aichat.core.db.CharacterEntity
import com.example.aichat.core.db.ConversationDao
import com.example.aichat.core.db.ConversationEntity
import com.example.aichat.core.db.ConversationSceneDao
import com.example.aichat.core.db.MessageDao
import com.example.aichat.core.db.MessageEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.network.AssistantRegenerationDto
import com.example.aichat.core.network.CharacterDto
import com.example.aichat.core.model.ConversationDetail
import com.example.aichat.core.model.MessageRole
import com.example.aichat.core.model.MessageSendState
import com.example.aichat.core.network.ChatApi
import com.example.aichat.core.network.ChatStreamEvent
import com.example.aichat.core.network.ChatStreamingClient
import com.example.aichat.core.network.ConversationApi
import com.example.aichat.core.network.ConversationDetailDto
import com.example.aichat.core.network.ConversationSummaryDto
import com.example.aichat.core.network.EditMessageRequestDto
import com.example.aichat.core.network.MessageDto
import com.example.aichat.core.network.SelectRegenerationRequestDto
import com.example.aichat.core.util.generateUlid
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private class StreamFailedException(
    override val message: String
) : IllegalStateException(message)

private class StreamProtocolException(
    override val message: String
) : IllegalStateException(message)

class SendMessageFailedException(
    val accepted: Boolean,
    override val message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private class ChatRuleViolation(message: String) : IllegalStateException(message)

private suspend inline fun <T> captureResult(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

@Singleton
class ChatRepository @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val characterDao: CharacterDao,
    private val conversationSceneDao: ConversationSceneDao,
    private val messageDao: MessageDao,
    private val regenerationDao: AssistantRegenerationDao,
    private val chatApi: ChatApi,
    private val conversationApi: ConversationApi,
    private val streamingClient: ChatStreamingClient
) {
    companion object {
        const val ORIGINAL_VARIANT_ID = "__original__"
        private val STOP_RECONCILIATION_DELAYS_MS = longArrayOf(0L, 100L, 200L, 400L, 800L, 1_600L, 2_400L)
    }
    private val activeStreams = MutableStateFlow<Map<String, ActiveAssistantStream>>(emptyMap())
    private val stopRequests = ConcurrentHashMap.newKeySet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeConversation(conversationId: String, messageLimit: Int): Flow<ConversationDetail?> {
        return conversationDao.observeById(conversationId).flatMapLatest { conversation ->
            if (conversation == null) {
                flowOf(null)
            } else {
                combine(
                    characterDao.observeById(conversation.characterId),
                    messageDao.observeNewestMessages(conversationId, messageLimit),
                    regenerationDao.observeConversationRegenerations(conversationId),
                    conversationSceneDao.observeByConversation(conversationId)
                ) { character, messages, regenerations, scene ->
                    if (character == null) return@combine null
                    val groupedRegenerations = regenerations.groupBy { it.messageId }
                    ConversationDetail(
                        id = conversation.id,
                        ownerUserId = conversation.ownerUserId,
                        conversationVersion = conversation.version,
                        character = character.toModel(),
                        messages = messages.map { message ->
                            message.toModel(groupedRegenerations[message.id].orEmpty())
                        },
                        backgroundSceneUrl = scene?.imageUrl ?: character.initialSceneUrl,
                        backgroundSceneKey = scene?.sceneKey ?: character.initialSceneKey
                    )
                }
            }
        }
    }

    fun observeMessageCount(conversationId: String): Flow<Int> {
        return messageDao.observeMessageCount(conversationId)
    }

    fun observeActiveStream(conversationId: String): Flow<ActiveAssistantStream?> {
        return activeStreams.map { streams -> streams[conversationId] }
    }

    suspend fun refreshConversation(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val detail = conversationApi.getConversation(conversationId)
            if (currentActiveStream(conversationId)?.status.isTransportBusy) return@captureResult
            database.withTransaction {
                if (currentActiveStream(conversationId)?.status.isTransportBusy) return@withTransaction
                applyRemoteConversationDetail(detail)
            }
            val stoppedStream = currentActiveStream(conversationId)
            if (
                stoppedStream?.status == ActiveStreamStatus.STOPPED &&
                stoppedResultIsCommitted(stoppedStream, detail)
            ) {
                clearActiveStream(conversationId, stoppedStream.draftKey)
            }
        }
    }

    suspend fun reconcileStoppedStream(conversationId: String, draftKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(12_000) {
                    captureResult {
                        STOP_RECONCILIATION_DELAYS_MS.forEachIndexed { attempt, delayMillis ->
                            if (delayMillis > 0L) delay(delayMillis)
                            val stream = currentActiveStream(conversationId)
                            if (stream?.draftKey != draftKey || stream.status != ActiveStreamStatus.STOPPING) {
                                return@captureResult
                            }
                            val detail = conversationApi.getConversation(conversationId)
                            database.withTransaction {
                                val current = currentActiveStream(conversationId)
                                if (current?.draftKey == draftKey && current.status == ActiveStreamStatus.STOPPING) {
                                    applyRemoteConversationDetail(detail)
                                }
                            }
                            if (stoppedResultIsCommitted(stream, detail)) {
                                clearActiveStream(conversationId, draftKey)
                                return@captureResult
                            }

                            if (
                                stream.text.isBlank() &&
                                stream.mode == ActiveStreamMode.SEND &&
                                stream.userMessageId != null &&
                                detail.messages.none { it.id == stream.userMessageId } &&
                                attempt >= 2
                            ) {
                                clearActiveStream(conversationId, draftKey)
                                return@captureResult
                            }

                            if (
                                stream.text.isBlank() &&
                                attempt == STOP_RECONCILIATION_DELAYS_MS.lastIndex
                            ) {
                                clearActiveStream(conversationId, draftKey)
                                return@captureResult
                            }
                        }
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(status = ActiveStreamStatus.STOPPED)
                        }
                    }
                }
            } finally {
                val stream = currentActiveStream(conversationId)
                if (stream?.draftKey == draftKey && stream.status == ActiveStreamStatus.STOPPING) {
                    if (stream.text.isBlank()) clearActiveStream(conversationId, draftKey)
                    else updateActiveStream(conversationId, draftKey) { it.copy(status = ActiveStreamStatus.STOPPED) }
                }
            }
        }

    private fun stoppedResultIsCommitted(
        stream: ActiveAssistantStream,
        detail: ConversationDetailDto
    ): Boolean {
        return when (stream.mode) {
            ActiveStreamMode.SEND,
            ActiveStreamMode.CONTINUE -> {
                val assistantMessageId = stream.assistantMessageId ?: return false
                detail.messages.any { message -> message.id == assistantMessageId }
            }

            ActiveStreamMode.REGENERATE -> {
                val stoppedText = stream.text.trim()
                if (stoppedText.isEmpty()) return false
                val message = detail.messages
                    .firstOrNull { candidate -> candidate.id == stream.targetMessageId }
                    ?: return false
                val selectedRegenerationId = message.selectedRegenerationId ?: return false
                message.regenerations
                    .firstOrNull { regeneration -> regeneration.id == selectedRegenerationId }
                    ?.content
                    ?.trim()
                    ?.startsWith(stoppedText) == true
            }
        }
    }

    fun requestStop(conversationId: String, draftKey: String): Boolean {
        if (conversationId.isBlank() || draftKey.isBlank()) return false
        var requested = false
        updateActiveStream(conversationId) { stream ->
            if (
                stream == null ||
                stream.draftKey != draftKey ||
                stream.status != ActiveStreamStatus.STREAMING
            ) {
                return@updateActiveStream stream
            }
            requested = true
            stream.copy(status = ActiveStreamStatus.STOPPING)
        }
        if (requested) {
            stopRequests.add(draftKey)
        }
        return requested
    }

    suspend fun sendMessage(conversationId: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val normalized = text.trim()
            if (normalized.isBlank()) throw IllegalArgumentException("Message can't be empty.")
            ensureNotStreaming(conversationId)

            val now = System.currentTimeMillis()
            val userMessageId = generateUlid(now)
            messageDao.insert(
                MessageEntity(
                    id = userMessageId,
                    conversationId = conversationId,
                    position = nextTemporaryPosition(conversationId),
                    role = MessageRole.USER.name,
                    content = normalized,
                    edited = false,
                    createdAt = now,
                    updatedAt = now,
                    selectedRegenerationId = null,
                    sendState = MessageSendState.PENDING.name
                )
            )
            setActiveStream(
                conversationId,
                ActiveAssistantStream(
                    conversationId = conversationId,
                    draftKey = "send-draft-$userMessageId",
                    mode = ActiveStreamMode.SEND,
                    userMessageId = userMessageId
                )
            )

            consumeSendStream(
                conversationId = conversationId,
                draftKey = "send-draft-$userMessageId",
                userMessageId = userMessageId,
                content = normalized
            )
        }
    }

    suspend fun editMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val normalized = newContent.trim()
            if (normalized.isBlank()) throw IllegalArgumentException("Message can't be empty.")

            val message = requireMutableMessage(messageId)
            chatApi.editMessage(messageId, EditMessageRequestDto(normalized))

            database.withTransaction {
                applyLocalEdit(message, normalized)
                updateConversationMetadataFromTranscript(message.conversationId)
            }
        }
    }

    suspend fun rewind(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val message = requireMutableMessage(messageId)
            chatApi.rewind(messageId)

            database.withTransaction {
                messageDao.deleteAfter(message.conversationId, message.position)
                deleteLocalOnlyMessages(message.conversationId)
                updateConversationMetadataFromTranscript(message.conversationId)
            }
        }
    }

    suspend fun regenerateLatestAssistant(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val message = requireMutableMessage(messageId)
            ensureLatestAssistant(message)
            val draftKey = "regenerate-draft-${generateUlid()}"

            setActiveStream(
                message.conversationId,
                ActiveAssistantStream(
                    conversationId = message.conversationId,
                    draftKey = draftKey,
                    mode = ActiveStreamMode.REGENERATE,
                    assistantMessageId = messageId,
                    targetMessageId = messageId
                )
            )

            consumeRegenerateStream(message.conversationId, draftKey, messageId)
        }
    }

    suspend fun continueAssistant(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            ensureNotStreaming(conversationId)
            val draftKey = "continue-draft-${generateUlid()}"

            setActiveStream(
                conversationId,
                ActiveAssistantStream(
                    conversationId = conversationId,
                    draftKey = draftKey,
                    mode = ActiveStreamMode.CONTINUE
                )
            )

            consumeContinueStream(conversationId, draftKey)
        }
    }

    suspend fun selectRegeneration(messageId: String, regenerationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        captureResult {
            val message = requireMutableMessage(messageId)
            ensureLatestAssistant(message)
            chatApi.selectRegeneration(
                messageId,
                SelectRegenerationRequestDto(regenerationId.takeUnless { it == ORIGINAL_VARIANT_ID })
            )

            database.withTransaction {
                messageDao.update(
                    message.copy(
                        selectedRegenerationId = regenerationId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                updateConversationMetadataFromTranscript(message.conversationId)
            }
        }
    }

    private suspend fun finishInterruptedRun(conversationId: String, draftKey: String, runId: String) {
        withContext(NonCancellable) {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                val stream = currentActiveStream(conversationId)?.takeIf { it.draftKey == draftKey && it.runId == runId }
                val partial = stream?.takeIf { it.text.isNotBlank() && it.assistantMessageId != null }?.let {
                    com.example.aichat.core.network.StoppedReplyDto(
                        messageId = it.assistantMessageId!!,
                        text = it.text.take(64_000),
                        regenerate = it.mode == ActiveStreamMode.REGENERATE
                    )
                }
                // Save text and unlock together even when disconnect propagation fails.
                runCatching { chatApi.stopReply(conversationId, com.example.aichat.core.network.StopChatRequestDto(runId, partial)) }
            }
        }
    }

    private suspend fun consumeSendStream(
        conversationId: String,
        draftKey: String,
        userMessageId: String,
        content: String
    ) {
        var accepted = false
        var acceptedRunId: String? = null
        var terminalReceived = false
        var stopped = false

        try {
            streamingClient.sendMessage(conversationId, userMessageId, content).collect { event ->
                if (terminalReceived) {
                    throw StreamProtocolException("The send stream emitted data after its terminal event.")
                }
                when (event) {
                    is ChatStreamEvent.AcceptedSend -> {
                        if (accepted) {
                            throw StreamProtocolException("The send stream was accepted more than once.")
                        }
                        if (event.userMessage.id != userMessageId) {
                            throw StreamProtocolException("The send stream accepted a different user message.")
                        }
                        if (event.userMessage.conversationId != conversationId) {
                            throw StreamProtocolException("The send stream accepted a message from another conversation.")
                        }
                        accepted = true
                        acceptedRunId = event.runId
                        applyAcceptedSend(conversationId, draftKey, event)
                    }

                    is ChatStreamEvent.Delta -> {
                        if (!accepted) {
                            throw StreamProtocolException("The send stream emitted text before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The send stream changed run identifiers.")
                        }
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(text = stream.text + event.textDelta)
                        }
                    }

                    is ChatStreamEvent.CompletedSend -> {
                        if (!accepted) {
                            throw StreamProtocolException("The send stream completed before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The send stream changed run identifiers.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        if (
                            event.assistantMessage.id != active.assistantMessageId ||
                            event.assistantMessage.conversationId != conversationId ||
                            event.conversationSummary.id != conversationId
                        ) {
                            throw StreamProtocolException("The send stream completed a different conversation reply.")
                        }
                        applyCompletedSend(conversationId, draftKey, event)
                    }

                    is ChatStreamEvent.Failed -> {
                        if (!accepted) {
                            throw StreamProtocolException("The send stream failed before it was accepted.")
                        }
                        if (event.runId != null && event.runId != acceptedRunId) {
                            throw StreamProtocolException("The send stream changed run identifiers.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId)
                        if (
                            active?.draftKey != draftKey ||
                            (event.runId != null && active.runId != event.runId)
                        ) {
                            return@collect
                        }
                        throw StreamFailedException(event.message)
                    }

                    else -> throw StreamProtocolException("Unexpected event in the send stream.")
                }
            }
            if (!terminalReceived) {
                throw StreamProtocolException("The send stream ended before a terminal event was received.")
            }
        } catch (error: CancellationException) {
            stopped = consumeStopRequest(draftKey)
            if (stopped) {
                withContext(NonCancellable) {
                    updateActiveStream(conversationId, draftKey) { stream ->
                        stream.copy(status = ActiveStreamStatus.STOPPING)
                    }
                }
            }
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            clearStopRequest(draftKey)
            if (!accepted) {
                markMessageFailed(userMessageId)
            }
            throw SendMessageFailedException(
                accepted = accepted,
                message = error.message ?: "Message send failed.",
                cause = error
            )
        } finally {
            if (!terminalReceived && acceptedRunId != null) {
                finishInterruptedRun(conversationId, draftKey, acceptedRunId!!)
            }
            if (!stopped) {
                clearActiveStream(conversationId, draftKey)
            }
        }
    }

    private suspend fun consumeRegenerateStream(conversationId: String, draftKey: String, messageId: String) {
        var accepted = false
        var acceptedRunId: String? = null
        var terminalReceived = false
        var stopped = false
        try {
            streamingClient.regenerateLatestAssistant(messageId).collect { event ->
                if (terminalReceived) {
                    throw StreamProtocolException("The regeneration stream emitted data after its terminal event.")
                }
                when (event) {
                    is ChatStreamEvent.AcceptedRegenerate -> {
                        if (accepted) {
                            throw StreamProtocolException("The regeneration stream was accepted more than once.")
                        }
                        if (event.messageId != messageId) {
                            throw StreamProtocolException("The regeneration stream accepted a different message.")
                        }
                        accepted = true
                        acceptedRunId = event.runId
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(
                                runId = event.runId,
                                assistantMessageId = event.assistantMessageId,
                                targetMessageId = event.messageId,
                                accepted = true
                            )
                        }
                    }

                    is ChatStreamEvent.Delta -> {
                        if (!accepted) {
                            throw StreamProtocolException("The regeneration stream emitted text before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The regeneration stream changed run identifiers.")
                        }
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(text = stream.text + event.textDelta)
                        }
                    }

                    is ChatStreamEvent.CompletedRegenerate -> {
                        if (!accepted) {
                            throw StreamProtocolException("The regeneration stream completed before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The regeneration stream changed run identifiers.")
                        }
                        if (event.messageId != messageId) {
                            throw StreamProtocolException("The regeneration stream completed a different message.")
                        }
                        if (
                            event.regeneration.messageId != messageId ||
                            event.conversationSummary.id != conversationId
                        ) {
                            throw StreamProtocolException("The regeneration stream completed a different conversation reply.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        applyCompletedRegenerate(conversationId, draftKey, event)
                    }

                    is ChatStreamEvent.Failed -> {
                        if (!accepted) {
                            throw StreamProtocolException("The regeneration stream failed before it was accepted.")
                        }
                        if (event.runId != null && event.runId != acceptedRunId) {
                            throw StreamProtocolException("The regeneration stream changed run identifiers.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId)
                        if (
                            active?.draftKey != draftKey ||
                            (event.runId != null && active.runId != event.runId)
                        ) {
                            return@collect
                        }
                        throw StreamFailedException(event.message)
                    }

                    else -> throw StreamProtocolException("Unexpected event in the regeneration stream.")
                }
            }
            if (!terminalReceived) {
                throw StreamProtocolException("The regeneration stream ended before a terminal event was received.")
            }
        } catch (error: CancellationException) {
            stopped = consumeStopRequest(draftKey)
            if (stopped) {
                withContext(NonCancellable) {
                    updateActiveStream(conversationId, draftKey) { stream ->
                        stream.copy(status = ActiveStreamStatus.STOPPING)
                    }
                }
            }
            throw error
        } catch (error: Throwable) {
            clearStopRequest(draftKey)
            throw error
        } finally {
            if (!terminalReceived && acceptedRunId != null) {
                finishInterruptedRun(conversationId, draftKey, acceptedRunId!!)
            }
            if (!stopped) {
                clearActiveStream(conversationId, draftKey)
            }
        }
    }

    private suspend fun consumeContinueStream(conversationId: String, draftKey: String) {
        var accepted = false
        var acceptedRunId: String? = null
        var terminalReceived = false
        var stopped = false
        try {
            streamingClient.continueAssistant(conversationId).collect { event ->
                if (terminalReceived) {
                    throw StreamProtocolException("The continuation stream emitted data after its terminal event.")
                }
                when (event) {
                    is ChatStreamEvent.AcceptedContinue -> {
                        if (accepted) {
                            throw StreamProtocolException("The continuation stream was accepted more than once.")
                        }
                        accepted = true
                        acceptedRunId = event.runId
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(
                                runId = event.runId,
                                assistantMessageId = event.assistantMessageId,
                                accepted = true,
                                status = ActiveStreamStatus.STREAMING
                            )
                        }
                    }

                    is ChatStreamEvent.Delta -> {
                        if (!accepted) {
                            throw StreamProtocolException("The continuation stream emitted text before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The continuation stream changed run identifiers.")
                        }
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        updateActiveStream(conversationId, draftKey) { stream ->
                            stream.copy(text = stream.text + event.textDelta)
                        }
                    }

                    is ChatStreamEvent.CompletedSend -> {
                        if (!accepted) {
                            throw StreamProtocolException("The continuation stream completed before it was accepted.")
                        }
                        if (event.runId != acceptedRunId) {
                            throw StreamProtocolException("The continuation stream changed run identifiers.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId) ?: return@collect
                        if (active.draftKey != draftKey || active.runId != event.runId) return@collect
                        if (
                            event.assistantMessage.id != active.assistantMessageId ||
                            event.assistantMessage.conversationId != conversationId ||
                            event.conversationSummary.id != conversationId
                        ) {
                            throw StreamProtocolException("The continuation stream completed a different conversation reply.")
                        }
                        applyCompletedSend(conversationId, draftKey, event)
                    }

                    is ChatStreamEvent.Failed -> {
                        if (!accepted) {
                            throw StreamProtocolException("The continuation stream failed before it was accepted.")
                        }
                        if (event.runId != null && event.runId != acceptedRunId) {
                            throw StreamProtocolException("The continuation stream changed run identifiers.")
                        }
                        terminalReceived = true
                        val active = currentActiveStream(conversationId)
                        if (
                            active?.draftKey != draftKey ||
                            (event.runId != null && active.runId != event.runId)
                        ) {
                            return@collect
                        }
                        throw StreamFailedException(event.message)
                    }

                    else -> throw StreamProtocolException("Unexpected event in the continuation stream.")
                }
            }
            if (!terminalReceived) {
                throw StreamProtocolException("The continuation stream ended before a terminal event was received.")
            }
        } catch (error: CancellationException) {
            stopped = consumeStopRequest(draftKey)
            if (stopped) {
                withContext(NonCancellable) {
                    updateActiveStream(conversationId, draftKey) { stream ->
                        stream.copy(status = ActiveStreamStatus.STOPPING)
                    }
                }
            }
            throw error
        } catch (error: Throwable) {
            clearStopRequest(draftKey)
            throw error
        } finally {
            if (!terminalReceived && acceptedRunId != null) {
                finishInterruptedRun(conversationId, draftKey, acceptedRunId!!)
            }
            if (!stopped) {
                clearActiveStream(conversationId, draftKey)
            }
        }
    }

    private suspend fun applyAcceptedSend(
        conversationId: String,
        draftKey: String,
        event: ChatStreamEvent.AcceptedSend
    ) {
        if (currentActiveStream(conversationId)?.draftKey != draftKey) return
        upsertMessageFromDto(event.userMessage, sendState = MessageSendState.SENT)
        updateActiveStream(conversationId, draftKey) { stream ->
            stream.copy(
                runId = event.runId,
                assistantMessageId = event.assistantMessageId,
                accepted = true,
                status = ActiveStreamStatus.STREAMING
            )
        }
    }

    private suspend fun applyCompletedSend(
        conversationId: String,
        draftKey: String,
        event: ChatStreamEvent.CompletedSend
    ) {
        database.withTransaction {
            upsertMessageFromDto(event.assistantMessage, sendState = MessageSendState.SENT)
            updateConversationMetadataFromSummary(conversationId, event.conversationSummary, event.conversationVersion)
        }
        clearActiveStream(conversationId, draftKey)
    }

    private suspend fun applyCompletedRegenerate(
        conversationId: String,
        draftKey: String,
        event: ChatStreamEvent.CompletedRegenerate
    ) {
        database.withTransaction {
            regenerationDao.insert(
                AssistantRegenerationEntity(
                    id = event.regeneration.id,
                    messageId = event.regeneration.messageId,
                    content = event.regeneration.content,
                    createdAt = event.regeneration.createdAt
                )
            )
            val message = messageDao.getById(event.messageId)
                ?: throw IllegalStateException("Message not found.")
            messageDao.update(
                message.copy(
                    selectedRegenerationId = event.selectedRegenerationId,
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateConversationMetadataFromSummary(conversationId, event.conversationSummary, event.conversationVersion)
        }
        clearActiveStream(conversationId, draftKey)
    }

    private suspend fun applyRemoteConversationDetail(detail: ConversationDetailDto) {
        val existingConversation = conversationDao.getById(detail.id)
        val existingCharacter = characterDao.getById(detail.character.id)
        val latestTimestamp = latestMessageTimestamp(detail.messages)
        val remoteCharacter = detail.character.toEntity()
        characterDao.upsert(
            remoteCharacter.copy(
                initialSceneUrl = remoteCharacter.initialSceneUrl ?: existingCharacter?.initialSceneUrl,
                initialSceneKey = remoteCharacter.initialSceneKey ?: existingCharacter?.initialSceneKey
            )
        )
        conversationDao.upsert(
            ConversationEntity(
                id = detail.id,
                ownerUserId = detail.ownerUserId,
                characterId = detail.character.id,
                version = detail.conversationVersion,
                updatedAt = maxOf(existingConversation?.updatedAt ?: 0L, latestTimestamp),
                startedAt = existingConversation?.startedAt ?: earliestMessageTimestamp(detail.messages),
                lastMessageAt = latestTimestamp.takeIf { detail.messages.isNotEmpty() },
                previewText = latestAssistantPreview(detail.messages),
                unreadCount = existingConversation?.unreadCount ?: 0,
                hasUnreadBadge = existingConversation?.hasUnreadBadge ?: false
            )
        )

        regenerationDao.deleteForCommittedMessages(detail.id)
        messageDao.deleteCommittedByConversation(detail.id)
        messageDao.insertAll(detail.messages.map { it.toEntity(MessageSendState.SENT) })
        regenerationDao.insertAll(detail.messages.flatMap { message -> message.regenerations.map { it.toEntity() } })
    }

    private suspend fun markMessageFailed(messageId: String) {
        val message = messageDao.getById(messageId) ?: return
        messageDao.update(
            message.copy(
                sendState = MessageSendState.FAILED.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun upsertMessageFromDto(message: MessageDto, sendState: MessageSendState) {
        messageDao.insert(message.toEntity(sendState))
    }

    private suspend fun applyLocalEdit(message: MessageEntity, newContent: String) {
        val now = System.currentTimeMillis()
        if (message.role == MessageRole.ASSISTANT.name && message.selectedRegenerationId != null) {
            val regeneration = regenerationDao.getById(message.selectedRegenerationId)
                ?: throw IllegalStateException("Selected regeneration not found.")
            regenerationDao.insert(
                regeneration.copy(
                    content = newContent,
                    createdAt = regeneration.createdAt
                )
            )
            messageDao.update(message.copy(edited = true, updatedAt = now))
            return
        }

        messageDao.update(
            message.copy(
                content = newContent,
                edited = true,
                updatedAt = now
            )
        )
    }

    private suspend fun updateConversationMetadataFromSummary(
        conversationId: String,
        summary: ConversationSummaryDto,
        conversationVersion: Long
    ) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.upsert(
            conversation.copy(
                version = conversationVersion,
                updatedAt = summary.updatedAt,
                lastMessageAt = summary.lastMessageAt,
                previewText = summary.lastPreview
            )
        )
    }

    private suspend fun updateConversationMetadataFromTranscript(conversationId: String) {
        val conversation = conversationDao.getById(conversationId) ?: return
        val latestMessage = messageDao.getLatestMessage(conversationId)
        val latestAssistantMessage = messageDao.getLatestAssistantMessage(conversationId)
        val now = System.currentTimeMillis()
        val preview = if (latestAssistantMessage == null) "" else visibleContent(latestAssistantMessage)
        conversationDao.upsert(
            conversation.copy(
                version = conversation.version + 1,
                updatedAt = now,
                lastMessageAt = if (latestMessage == null) null else now,
                previewText = preview
            )
        )
    }

    private suspend fun visibleContent(message: MessageEntity): String {
        val selectedId = message.selectedRegenerationId ?: return message.content
        return regenerationDao.getById(selectedId)?.content ?: message.content
    }

    private suspend fun deleteLocalOnlyMessages(conversationId: String) {
        messageDao.getLocalOnlyMessages(conversationId).forEach { localMessage ->
            messageDao.deleteById(localMessage.id)
        }
    }

    private suspend fun nextTemporaryPosition(conversationId: String): Int {
        val minimumPosition = messageDao.getMinimumPosition(conversationId)
        return minOf(minimumPosition, 0) - 1
    }

    private fun setActiveStream(conversationId: String, stream: ActiveAssistantStream) {
        activeStreams.update { it + (conversationId to stream) }
    }

    private fun updateActiveStream(conversationId: String, transform: (ActiveAssistantStream?) -> ActiveAssistantStream?) {
        activeStreams.update { streams ->
            val updated = transform(streams[conversationId])
            if (updated == null) {
                streams - conversationId
            } else {
                streams + (conversationId to updated)
            }
        }
    }

    private fun updateActiveStream(
        conversationId: String,
        draftKey: String,
        transform: (ActiveAssistantStream) -> ActiveAssistantStream
    ) {
        activeStreams.update { streams ->
            val current = streams[conversationId]
            if (current?.draftKey != draftKey) {
                streams
            } else {
                streams + (conversationId to transform(current))
            }
        }
    }

    private fun clearActiveStream(conversationId: String, draftKey: String) {
        if (conversationId.isBlank() || draftKey.isBlank()) return
        stopRequests.remove(draftKey)
        activeStreams.update { streams ->
            if (streams[conversationId]?.draftKey == draftKey) {
                streams - conversationId
            } else {
                streams
            }
        }
    }

    private fun currentActiveStream(conversationId: String): ActiveAssistantStream? {
        return activeStreams.value[conversationId]
    }

    private fun consumeStopRequest(draftKey: String): Boolean {
        return stopRequests.remove(draftKey)
    }

    private fun clearStopRequest(draftKey: String) {
        stopRequests.remove(draftKey)
    }

    private suspend fun requireMutableMessage(messageId: String): MessageEntity {
        val message = messageDao.getById(messageId)
            ?: throw IllegalArgumentException("Message not found.")
        ensureMessageMutable(message)
        return message
    }

    private suspend fun ensureLatestAssistant(message: MessageEntity) {
        val latestMessage = messageDao.getLatestMessage(message.conversationId)
            ?: throw ChatRuleViolation("Conversation is empty.")
        if (latestMessage.role != MessageRole.ASSISTANT.name || latestMessage.id != message.id) {
            throw ChatRuleViolation("Only the latest assistant reply can be regenerated.")
        }
    }

    private fun ensureNotStreaming(conversationId: String) {
        val activeStream = activeStreams.value[conversationId] ?: return
        when (activeStream.status) {
            ActiveStreamStatus.STREAMING,
            ActiveStreamStatus.STOPPING -> {
                throw ChatRuleViolation("Wait for the current reply to finish before changing the transcript.")
            }

            ActiveStreamStatus.STOPPED -> {
                clearActiveStream(conversationId, activeStream.draftKey)
            }
        }
    }

    private val ActiveStreamStatus?.isTransportBusy: Boolean
        get() = this == ActiveStreamStatus.STREAMING || this == ActiveStreamStatus.STOPPING

    private fun ensureMessageMutable(message: MessageEntity) {
        ensureNotStreaming(message.conversationId)
        if (message.sendState != MessageSendState.SENT.name) {
            throw ChatRuleViolation("Only committed messages can be changed.")
        }
    }

    private fun CharacterDto.toEntity(): CharacterEntity = CharacterEntity(
        id = id,
        ownerUserId = ownerUserId,
        authorUsername = authorUsername,
        name = name,
        tagline = tagline,
        greeting = greeting.ifBlank { tagline },
        bio = bio,
        systemPrompt = systemPrompt,
        definitionPrivate = definitionPrivate,
        visibility = visibility.uppercase(),
        avatarUrl = avatarUrl,
        initialSceneUrl = initialSceneUrl,
        initialSceneKey = initialSceneKey,
        publicChatCount = publicChatCount,
        likeCount = likeCount,
        likedByMe = likedByMe,
        lastActiveAt = lastActiveAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun MessageDto.toEntity(sendState: MessageSendState): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        position = position,
        role = role.uppercase(),
        content = content,
        edited = edited,
        createdAt = createdAt,
        updatedAt = updatedAt,
        selectedRegenerationId = selectedRegenerationId,
        sendState = sendState.name
    )

    private fun AssistantRegenerationDto.toEntity(): AssistantRegenerationEntity = AssistantRegenerationEntity(
        id = id,
        messageId = messageId,
        content = content,
        createdAt = createdAt
    )

    private fun latestMessageTimestamp(messages: List<MessageDto>): Long {
        return messages.maxOfOrNull { it.updatedAt } ?: System.currentTimeMillis()
    }

    private fun earliestMessageTimestamp(messages: List<MessageDto>): Long {
        return messages.minOfOrNull { it.createdAt } ?: System.currentTimeMillis()
    }

    private fun latestAssistantPreview(messages: List<MessageDto>): String {
        val assistant = messages.lastOrNull { it.role.equals(MessageRole.ASSISTANT.name, ignoreCase = true) }
            ?: return ""
        val selectedId = assistant.selectedRegenerationId
        return assistant.regenerations.firstOrNull { it.id == selectedId }?.content ?: assistant.content
    }
}
