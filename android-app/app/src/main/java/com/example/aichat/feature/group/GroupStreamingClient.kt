package com.example.aichat.feature.group

import com.example.aichat.BuildConfig
import com.example.aichat.core.network.ErrorResponseDto
import com.example.aichat.core.network.GroupMessageDto
import com.example.aichat.core.network.GroupSendRequestDto
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface GroupStreamEvent {
    val runId: String
    data class Accepted(override val runId: String, val userMessage: GroupMessageDto?) : GroupStreamEvent
    data class Speaker(
        override val runId: String,
        val messageId: String,
        val characterId: String,
        val characterName: String,
        val avatarUrl: String?
    ) : GroupStreamEvent
    data class Delta(override val runId: String, val messageId: String, val text: String) : GroupStreamEvent
    data class MessageDone(override val runId: String, val message: GroupMessageDto) : GroupStreamEvent
    data class Done(override val runId: String) : GroupStreamEvent
    data class Failed(override val runId: String, val message: String) : GroupStreamEvent
}

interface GroupStreamingClient {
    fun send(groupId: String, userMessageId: String, content: String): Flow<GroupStreamEvent>
    fun continueChat(groupId: String, whileTyping: Boolean = false): Flow<GroupStreamEvent>
}

class WorkerGroupStreamingClient(
    client: OkHttpClient,
    private val json: Json,
    baseUrl: String = BuildConfig.API_BASE_URL
) : GroupStreamingClient {
    private val baseUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val client = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json".toMediaType()

    override fun send(groupId: String, userMessageId: String, content: String): Flow<GroupStreamEvent> = stream(
        groupId,
        "messages/stream",
        json.encodeToString(GroupSendRequestDto.serializer(), GroupSendRequestDto(userMessageId, content))
    )

    override fun continueChat(groupId: String, whileTyping: Boolean): Flow<GroupStreamEvent> = stream(
        groupId,
        "continue/stream",
        if (whileTyping) "{\"reason\":\"typing\"}" else "{\"reason\":\"continue\"}"
    )

    private fun stream(groupId: String, suffix: String, body: String): Flow<GroupStreamEvent> = callbackFlow {
        val url = baseUrl.newBuilder().addPathSegments("v1/groups").addPathSegment(groupId)
            .addPathSegments(suffix).build()
        val call = client.newCall(Request.Builder().url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(body.toRequestBody(mediaType)).build())
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = response.body?.string()?.let { raw ->
                            runCatching { json.decodeFromString(ErrorResponseDto.serializer(), raw).message }.getOrNull()
                        }
                        error(error ?: "Couldn't connect to this group. Please try again.")
                    }
                    val source = checkNotNull(response.body) { "The group response was empty." }.source()
                    var runId: String? = null
                    var speakerMessageId: String? = null
                    var terminal = false
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        val event = decodeEvent(payload)
                        if (event is GroupStreamEvent.Accepted) {
                            check(runId == null) { "The group accepted the same request twice." }
                            runId = event.runId
                        } else {
                            check(runId != null && runId == event.runId) { "The group response changed requests." }
                        }
                        when (event) {
                            is GroupStreamEvent.Speaker -> {
                                check(speakerMessageId == null) { "The previous speaker did not finish." }
                                speakerMessageId = event.messageId
                            }
                            is GroupStreamEvent.Delta -> check(event.messageId == speakerMessageId) {
                                "The group response changed speakers unexpectedly."
                            }
                            is GroupStreamEvent.MessageDone -> {
                                check(event.message.id == speakerMessageId) { "The group finished a different message." }
                                speakerMessageId = null
                            }
                            is GroupStreamEvent.Done -> {
                                check(speakerMessageId == null) { "A group message was interrupted." }
                                terminal = true
                            }
                            is GroupStreamEvent.Failed -> terminal = true
                            else -> Unit
                        }
                        send(event)
                        if (terminal) break
                    }
                    check(terminal) { "The group response was interrupted. Please retry." }
                }
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose { call.cancel(); reader.cancel() }
    }

    internal fun decodeEvent(payload: String): GroupStreamEvent {
        val value = json.parseToJsonElement(payload).jsonObject
        val run = value.requiredString("runId")
        return when (value.requiredString("type")) {
            "accepted_send" -> GroupStreamEvent.Accepted(run,
                json.decodeFromJsonElement(GroupMessageDto.serializer(), checkNotNull(value["userMessage"])))
            "accepted_continue" -> GroupStreamEvent.Accepted(run, null)
            "speaker" -> GroupStreamEvent.Speaker(run, value.requiredString("messageId"),
                value.requiredString("characterId"), value.requiredString("characterName"),
                value["avatarUrl"]?.jsonPrimitive?.contentOrNull)
            "delta" -> GroupStreamEvent.Delta(run, value.requiredString("messageId"), value.requiredString("textDelta", allowEmpty = true))
            "message_done" -> GroupStreamEvent.MessageDone(run,
                json.decodeFromJsonElement(GroupMessageDto.serializer(), checkNotNull(value["message"])))
            "done" -> GroupStreamEvent.Done(run)
            "error" -> GroupStreamEvent.Failed(run, value["message"]?.jsonPrimitive?.contentOrNull
                ?: "The group response was interrupted.")
            else -> error("Unexpected group response.")
        }
    }

    private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String =
        checkNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "Missing group response field: $key" }.also {
            check(allowEmpty || it.isNotEmpty()) { "Empty group response field: $key" }
        }
}
