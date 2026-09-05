package com.example.aichat.core.network

import com.example.aichat.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface ChatStreamEvent {
    data class AcceptedSend(
        val runId: String,
        val conversationVersion: Long,
        val userMessage: MessageDto,
        val assistantMessageId: String
    ) : ChatStreamEvent

    data class AcceptedRegenerate(
        val runId: String,
        val conversationVersion: Long,
        val messageId: String,
        val assistantMessageId: String
    ) : ChatStreamEvent

    data class AcceptedContinue(
        val runId: String,
        val conversationVersion: Long,
        val assistantMessageId: String
    ) : ChatStreamEvent

    data class Delta(
        val runId: String,
        val textDelta: String
    ) : ChatStreamEvent

    data class CompletedSend(
        val runId: String,
        val conversationVersion: Long,
        val assistantMessage: MessageDto,
        val conversationSummary: ConversationSummaryDto
    ) : ChatStreamEvent

    data class CompletedRegenerate(
        val runId: String,
        val conversationVersion: Long,
        val messageId: String,
        val regeneration: AssistantRegenerationDto,
        val selectedRegenerationId: String,
        val conversationSummary: ConversationSummaryDto
    ) : ChatStreamEvent

    data class Failed(
        val runId: String?,
        val code: String,
        val message: String
    ) : ChatStreamEvent
}

interface ChatStreamingClient {
    fun sendMessage(conversationId: String, userMessageId: String, content: String): Flow<ChatStreamEvent>
    fun continueAssistant(conversationId: String): Flow<ChatStreamEvent>
    fun regenerateLatestAssistant(messageId: String): Flow<ChatStreamEvent>
}

@Singleton
class WorkerStreamingClient private constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    configuredBaseUrl: String
) : ChatStreamingClient {
    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        json: Json
    ) : this(okHttpClient, json, BuildConfig.API_BASE_URL)

    internal constructor(
        okHttpClient: OkHttpClient,
        json: Json,
        baseUrl: HttpUrl
    ) : this(okHttpClient, json, baseUrl.toString())

    private val streamingHttpClient = okHttpClient.newBuilder()
        .readTimeout(55, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(125, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val baseUrl = if (configuredBaseUrl.endsWith("/")) {
        configuredBaseUrl
    } else {
        "$configuredBaseUrl/"
    }

    override fun sendMessage(conversationId: String, userMessageId: String, content: String): Flow<ChatStreamEvent> {
        val url = "${baseUrl}v1/conversations/$conversationId/messages/stream"
        val body = json.encodeToString(
            SendMessageRequestDto.serializer(),
            SendMessageRequestDto(userMessageId = userMessageId, content = content)
        ).toRequestBody(jsonMediaType)
        return stream(
            request = Request.Builder().url(url).post(body).build(),
            expectedStream = ExpectedStream.SEND
        )
    }

    override fun regenerateLatestAssistant(messageId: String): Flow<ChatStreamEvent> {
        val url = "${baseUrl}v1/messages/$messageId/regenerate/stream"
        return stream(
            request = Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType)).build(),
            expectedStream = ExpectedStream.REGENERATE
        )
    }

    override fun continueAssistant(conversationId: String): Flow<ChatStreamEvent> {
        val url = "${baseUrl}v1/conversations/$conversationId/continue/stream"
        return stream(
            request = Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType)).build(),
            expectedStream = ExpectedStream.CONTINUE
        )
    }

    private fun stream(request: Request, expectedStream: ExpectedStream): Flow<ChatStreamEvent> = callbackFlow {
        val call = streamingHttpClient.newCall(request)
        val readerJob = launch(Dispatchers.IO) {
            val response = try {
                call.execute()
            } catch (error: Throwable) {
                close(error)
                return@launch
            }

            try {
                if (!response.isSuccessful) {
                    val errorMessage = response.body?.string()?.let { body ->
                        runCatching {
                            json.decodeFromString(ErrorResponseDto.serializer(), body).message
                        }.getOrNull()
                    }
                    close(
                        IllegalStateException(
                            errorMessage ?: httpStatusMessage(response.code, "The chat request failed. Please retry.")
                        )
                    )
                    return@launch
                }

                val body = response.body ?: run {
                    close(IllegalStateException("Streaming response was empty."))
                    return@launch
                }

                body.source().use { source ->
                    var acceptedRunId: String? = null
                    var terminalReceived = false
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isBlank()) continue
                        val event = json.decodeFromString(StreamEventDto.serializer(), payload).toModel()
                        acceptedRunId = validateStreamEvent(
                            expectedStream = expectedStream,
                            event = event,
                            acceptedRunId = acceptedRunId,
                            terminalReceived = terminalReceived
                        )
                        terminalReceived = event.isTerminal
                        send(event)
                        // Terminal events finish the operation even if the server keeps its socket open.
                        if (terminalReceived) break
                    }
                    check(terminalReceived) {
                        "The chat stream ended before a terminal event was received."
                    }
                }
                close()
            } catch (error: Throwable) {
                close(error)
            } finally {
                response.close()
            }
        }

        awaitClose {
            call.cancel()
            readerJob.cancel()
        }
    }

    private fun validateStreamEvent(
        expectedStream: ExpectedStream,
        event: ChatStreamEvent,
        acceptedRunId: String?,
        terminalReceived: Boolean
    ): String {
        check(!terminalReceived) { "The chat stream emitted data after its terminal event." }

        val acceptedId = when (event) {
            is ChatStreamEvent.AcceptedSend -> {
                check(expectedStream == ExpectedStream.SEND) { "Unexpected send acceptance event." }
                check(acceptedRunId == null) { "The chat stream was accepted more than once." }
                event.runId
            }

            is ChatStreamEvent.AcceptedContinue -> {
                check(expectedStream == ExpectedStream.CONTINUE) { "Unexpected continue acceptance event." }
                check(acceptedRunId == null) { "The chat stream was accepted more than once." }
                event.runId
            }

            is ChatStreamEvent.AcceptedRegenerate -> {
                check(expectedStream == ExpectedStream.REGENERATE) { "Unexpected regeneration acceptance event." }
                check(acceptedRunId == null) { "The chat stream was accepted more than once." }
                event.runId
            }

            is ChatStreamEvent.Delta -> {
                checkNotNull(acceptedRunId) { "The chat stream emitted text before it was accepted." }
                check(event.runId == acceptedRunId) { "The chat stream changed run identifiers." }
                acceptedRunId
            }

            is ChatStreamEvent.CompletedSend -> {
                check(expectedStream != ExpectedStream.REGENERATE) { "Unexpected send completion event." }
                checkNotNull(acceptedRunId) { "The chat stream completed before it was accepted." }
                check(event.runId == acceptedRunId) { "The chat stream changed run identifiers." }
                acceptedRunId
            }

            is ChatStreamEvent.CompletedRegenerate -> {
                check(expectedStream == ExpectedStream.REGENERATE) { "Unexpected regeneration completion event." }
                checkNotNull(acceptedRunId) { "The chat stream completed before it was accepted." }
                check(event.runId == acceptedRunId) { "The chat stream changed run identifiers." }
                acceptedRunId
            }

            is ChatStreamEvent.Failed -> {
                checkNotNull(acceptedRunId) { "The chat stream failed before it was accepted." }
                check(event.runId == null || event.runId == acceptedRunId) {
                    "The chat stream changed run identifiers."
                }
                acceptedRunId
            }
        }
        return acceptedId
    }

    private val ChatStreamEvent.isTerminal: Boolean
        get() = this is ChatStreamEvent.CompletedSend ||
            this is ChatStreamEvent.CompletedRegenerate ||
            this is ChatStreamEvent.Failed

    private enum class ExpectedStream {
        SEND,
        CONTINUE,
        REGENERATE
    }

    private fun StreamEventDto.toModel(): ChatStreamEvent = when (type) {
        "accepted_send" -> ChatStreamEvent.AcceptedSend(
            runId = requireNotNull(runId),
            conversationVersion = requireNotNull(conversationVersion),
            userMessage = requireNotNull(userMessage),
            assistantMessageId = requireNotNull(assistantMessageId)
        )

        "accepted_regenerate" -> ChatStreamEvent.AcceptedRegenerate(
            runId = requireNotNull(runId),
            conversationVersion = requireNotNull(conversationVersion),
            messageId = requireNotNull(messageId),
            assistantMessageId = requireNotNull(assistantMessageId)
        )

        "accepted_continue" -> ChatStreamEvent.AcceptedContinue(
            runId = requireNotNull(runId),
            conversationVersion = requireNotNull(conversationVersion),
            assistantMessageId = requireNotNull(assistantMessageId)
        )

        "delta" -> ChatStreamEvent.Delta(
            runId = requireNotNull(runId),
            textDelta = textDelta.orEmpty()
        )

        "completed_send" -> ChatStreamEvent.CompletedSend(
            runId = requireNotNull(runId),
            conversationVersion = requireNotNull(conversationVersion),
            assistantMessage = requireNotNull(assistantMessage),
            conversationSummary = requireNotNull(conversationSummary)
        )

        "completed_regenerate" -> ChatStreamEvent.CompletedRegenerate(
            runId = requireNotNull(runId),
            conversationVersion = requireNotNull(conversationVersion),
            messageId = requireNotNull(messageId),
            regeneration = requireNotNull(regeneration),
            selectedRegenerationId = requireNotNull(selectedRegenerationId),
            conversationSummary = requireNotNull(conversationSummary)
        )

        "failed" -> ChatStreamEvent.Failed(
            runId = runId,
            code = code ?: "STREAM_FAILED",
            message = message ?: "Streaming failed."
        )

        else -> throw IllegalStateException("Unsupported stream event type: $type")
    }
}
