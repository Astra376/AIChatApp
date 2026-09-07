package com.example.aichat.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class WorkerStreamingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var json: Json
    private lateinit var client: WorkerStreamingClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        client = WorkerStreamingClient(
            okHttpClient = OkHttpClient(),
            json = json,
            baseUrl = server.url("/")
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reasoningStatusIsOptedInAndDoesNotInterruptDeltas() = runTest {
        server.enqueue(sseResponse(listOf(
            StreamEventDto(type = "accepted_continue", runId = RUN_ID, conversationVersion = 1, assistantMessageId = ASSISTANT_MESSAGE_ID),
            StreamEventDto(type = "status", runId = RUN_ID, status = "Thinking", model = "Meek Ultra"),
            StreamEventDto(type = "delta", runId = RUN_ID, textDelta = "Hello"),
            completedSendEvent()
        )))
        val events = client.continueAssistant(CONVERSATION_ID).toList()
        assertThat(events.filterIsInstance<ChatStreamEvent.Status>().single().status).isEqualTo("Thinking")
        assertThat(events.filterIsInstance<ChatStreamEvent.Delta>().single().textDelta).isEqualTo("Hello")
        assertThat(server.takeRequest().getHeader("X-Chat-Status")).isEqualTo("1")
    }

    @Test
    fun reasoningStatusForAnotherRunFailsValidation() = runTest {
        server.enqueue(sseResponse(listOf(
            StreamEventDto(type = "accepted_continue", runId = RUN_ID, conversationVersion = 1, assistantMessageId = ASSISTANT_MESSAGE_ID),
            StreamEventDto(type = "status", runId = "wrong-run", status = "Thinking"),
            completedSendEvent()
        )))
        val error = runCatching { client.continueAssistant(CONVERSATION_ID).toList() }.exceptionOrNull()
        assertThat(error).hasMessageThat().contains("changed run identifiers")
    }

    @Test
    fun continueAssistant_moreThanChannelCapacity_deliversEveryDeltaAndTerminal() = runTest {
        val deltas = List(128) { index ->
            StreamEventDto(type = "delta", runId = RUN_ID, textDelta = "$index,")
        }
        server.enqueue(
            sseResponse(
                listOf(
                    StreamEventDto(
                        type = "accepted_continue",
                        runId = RUN_ID,
                        conversationVersion = 1,
                        assistantMessageId = ASSISTANT_MESSAGE_ID
                    )
                ) + deltas + completedSendEvent()
            )
        )

        val events = client.continueAssistant(CONVERSATION_ID).toList()

        assertThat(events).hasSize(130)
        assertThat(
            events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.textDelta }
        ).isEqualTo(deltas.joinToString("") { it.textDelta.orEmpty() })
        assertThat(events.last()).isInstanceOf(ChatStreamEvent.CompletedSend::class.java)
    }

    @Test
    fun continueAssistant_eofWithoutTerminal_failsProtocolValidation() = runTest {
        server.enqueue(
            sseResponse(
                listOf(
                    StreamEventDto(
                        type = "accepted_continue",
                        runId = RUN_ID,
                        conversationVersion = 1,
                        assistantMessageId = ASSISTANT_MESSAGE_ID
                    ),
                    StreamEventDto(type = "delta", runId = RUN_ID, textDelta = "partial")
                )
            )
        )

        val error = runCatching {
            client.continueAssistant(CONVERSATION_ID).toList()
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("terminal event")
    }

    @Test
    fun continueAssistant_wrongAcceptanceKind_failsProtocolValidation() = runTest {
        server.enqueue(
            sseResponse(
                listOf(
                    StreamEventDto(
                        type = "accepted_send",
                        runId = RUN_ID,
                        conversationVersion = 1,
                        userMessage = message(
                            id = USER_MESSAGE_ID,
                            position = 1,
                            role = "user",
                            content = "hello"
                        ),
                        assistantMessageId = ASSISTANT_MESSAGE_ID
                    ),
                    completedSendEvent()
                )
            )
        )

        val error = runCatching {
            client.continueAssistant(CONVERSATION_ID).toList()
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("Unexpected send acceptance")
    }

    @Test
    fun continueAssistant_collectorCancellation_stopsWithoutWaitingForTerminal() = runTest {
        val events = buildList {
            add(
                StreamEventDto(
                    type = "accepted_continue",
                    runId = RUN_ID,
                    conversationVersion = 1,
                    assistantMessageId = ASSISTANT_MESSAGE_ID
                )
            )
            repeat(128) { index ->
                add(StreamEventDto(type = "delta", runId = RUN_ID, textDelta = "$index"))
            }
        }
        server.enqueue(sseResponse(events))

        val received = client.continueAssistant(CONVERSATION_ID).take(2).toList()

        assertThat(received).hasSize(2)
        assertThat(server.takeRequest().path)
            .isEqualTo("/v1/conversations/$CONVERSATION_ID/continue/stream")
    }

    @Test
    fun deltasAreDeliveredBeforeRemainingResponseBytesArrive() = runBlocking {
        val initial = listOf(
            StreamEventDto(type = "accepted_continue", runId = RUN_ID,
                conversationVersion = 1, assistantMessageId = ASSISTANT_MESSAGE_ID),
            StreamEventDto(type = "delta", runId = RUN_ID, textDelta = "already visible")
        ).joinToString("\n\n", postfix = "\n\n") {
            "data: ${json.encodeToString(StreamEventDto.serializer(), it)}"
        }
        val terminal = "data: ${json.encodeToString(StreamEventDto.serializer(), completedSendEvent())}\n\n"
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(initial + terminal)
            .throttleBody(initial.toByteArray().size.toLong(), 3, TimeUnit.SECONDS))

        // The terminal bytes cannot arrive for three seconds. A buffering client times out here.
        val received = withTimeout(2_000) {
            client.continueAssistant(CONVERSATION_ID).take(2).toList()
        }
        assertThat((received.last() as ChatStreamEvent.Delta).textDelta).isEqualTo("already visible")
        val request = server.takeRequest()
        assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream")
    }

    @Test
    fun terminalEventCompletesWithoutWaitingForSocketEof() = runBlocking {
        val response = sseResponse(listOf(
            StreamEventDto(type = "accepted_continue", runId = RUN_ID,
                conversationVersion = 1, assistantMessageId = ASSISTANT_MESSAGE_ID),
            completedSendEvent()
        )).setHeader("Content-Length", "1000000")
        server.enqueue(response)

        val received = withTimeout(2_000) { client.continueAssistant(CONVERSATION_ID).toList() }
        assertThat(received.last()).isInstanceOf(ChatStreamEvent.CompletedSend::class.java)
    }

    private fun sseResponse(events: List<StreamEventDto>): MockResponse {
        val body = events.joinToString(separator = "\n\n", postfix = "\n\n") { event ->
            "data: ${json.encodeToString(StreamEventDto.serializer(), event)}"
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun completedSendEvent() = StreamEventDto(
        type = "completed_send",
        runId = RUN_ID,
        conversationVersion = 2,
        assistantMessage = message(
            id = ASSISTANT_MESSAGE_ID,
            position = 2,
            role = "assistant",
            content = "complete"
        ),
        conversationSummary = ConversationSummaryDto(
            id = CONVERSATION_ID,
            characterId = "character-1",
            characterName = "Astra",
            characterAvatarUrl = null,
            updatedAt = 2,
            startedAt = 1,
            lastMessageAt = 2,
            lastPreview = "complete"
        )
    )

    private fun message(
        id: String,
        position: Int,
        role: String,
        content: String
    ) = MessageDto(
        id = id,
        conversationId = CONVERSATION_ID,
        position = position,
        role = role,
        content = content,
        edited = false,
        createdAt = position.toLong(),
        updatedAt = position.toLong()
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
        const val USER_MESSAGE_ID = "user-message-1"
        const val ASSISTANT_MESSAGE_ID = "assistant-message-1"
        const val RUN_ID = "run-1"
    }
}
