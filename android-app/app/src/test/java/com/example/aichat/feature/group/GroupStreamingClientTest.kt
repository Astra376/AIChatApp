package com.example.aichat.feature.group

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class GroupStreamingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WorkerGroupStreamingClient
    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = WorkerGroupStreamingClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/").toString())
    }
    @After fun cleanup() { server.shutdown() }
    private val accepted = """{"type":"accepted_continue","runId":"run"}"""
    private fun speaker(id: String, character: String) = """{"type":"speaker","runId":"run","messageId":"$id","characterId":"$character","characterName":"$character","avatarUrl":null}"""
    private fun delta(id: String, text: String) = """{"type":"delta","runId":"run","messageId":"$id","textDelta":"$text"}"""
    private fun completed(id: String, character: String, content: String, position: Int) = """{"type":"message_done","runId":"run","message":{"id":"$id","groupId":"group","position":$position,"role":"assistant","content":"$content","createdAt":1,"characterId":"$character","status":"complete"}}"""
    private val done = """{"type":"done","runId":"run"}"""
    private fun enqueue(vararg events: String) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("\n\n") { "data: $it" } + "\n\n")) }

    @Test fun streamsIndividualCharactersWithoutWaitingForEverySpeaker() = runTest {
        enqueue(accepted, speaker("m1", "Astrid"), delta("m1", "Hi"), completed("m1", "Astrid", "Hi", 0),
            speaker("m2", "Leo"), delta("m2", "Hey Astrid"), completed("m2", "Leo", "Hey Astrid", 1), done)
        val events = client.continueChat("group").toList()
        assertThat(events.filterIsInstance<GroupStreamEvent.Delta>().map { it.text }).containsExactly("Hi", "Hey Astrid").inOrder()
        assertThat(events.filterIsInstance<GroupStreamEvent.MessageDone>().map { it.message.characterId }).containsExactly("Astrid", "Leo").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/v1/groups/group/continue/stream")
    }
    @Test fun acceptsSilenceAsACompletedNaturalTurn() = runTest {
        enqueue(accepted, done)
        assertThat(client.continueChat("group", whileTyping = true).toList()).hasSize(2)
        assertThat(server.takeRequest().body.readUtf8()).contains("typing")
    }
    @Test fun rejectsDifferentRunAndUnfinishedSpeakers() = runTest {
        enqueue(accepted, """{"type":"done","runId":"other"}""")
        assertThat(runCatching { client.continueChat("group").toList() }.exceptionOrNull()).isNotNull()
        enqueue(accepted, speaker("m1", "Astrid"), done)
        assertThat(runCatching { client.continueChat("group").toList() }.exceptionOrNull()).isNotNull()
    }
    @Test fun rejectsDeltasForWrongCharacterMessage() = runTest {
        enqueue(accepted, speaker("m1", "Astrid"), delta("m2", "Wrong"), done)
        assertThat(runCatching { client.continueChat("group").toList() }.exceptionOrNull()).hasMessageThat().contains("changed speakers")
    }
    @Test fun deliversAllDeltasEvenWhenMoreThanChannelCapacityArrive() = runTest {
        val deltas = List(160) { delta("m1", "$it,") }
        enqueue(accepted, speaker("m1", "Astrid"), *deltas.toTypedArray(), completed("m1", "Astrid", "Full reply", 0), done)
        assertThat(client.continueChat("group").toList().filterIsInstance<GroupStreamEvent.Delta>()).hasSize(160)
    }
    @Test fun unexpectedEofIsRecoverableFailure() = runTest {
        enqueue(accepted, speaker("m1", "Astrid"), delta("m1", "Partial"))
        assertThat(runCatching { client.continueChat("group").toList() }.exceptionOrNull()).hasMessageThat().contains("interrupted")
    }
}
