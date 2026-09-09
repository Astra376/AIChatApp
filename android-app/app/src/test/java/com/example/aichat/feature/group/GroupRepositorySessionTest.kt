package com.example.aichat.feature.group

import com.example.aichat.core.network.*
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Test

class GroupRepositorySessionTest {
    private class Api : GroupApi {
        val detail = GroupDetailDto("group", "Friends", emptyList())
        val stopEntered = CompletableDeferred<Unit>()
        val stopRelease = CompletableDeferred<Unit>()
        val createEntered = CompletableDeferred<Unit>()
        val createRelease = CompletableDeferred<Unit>()
        override suspend fun list(cursor: String?) = GroupPageDto(listOf(detail))
        override suspend fun create(body: CreateGroupRequestDto): GroupDetailDto {
            createEntered.complete(Unit)
            createRelease.await()
            return detail
        }
        override suspend fun detail(groupId: String, beforePosition: Int?) = detail
        override suspend fun stop(groupId: String, body: GroupStopRequestDto) {
            stopEntered.complete(Unit)
            // Simulate a response that arrives after an account switch, even
            // though the request's coroutine has already been cancelled.
            withContext(NonCancellable) { stopRelease.await() }
        }
        override suspend fun presence(groupId: String, body: GroupPresenceRequestDto) {}
        override suspend fun delete(groupId: String) {}
    }
    private class Streams : GroupStreamingClient {
        val starts = AtomicInteger()
        override fun send(groupId: String, userMessageId: String, content: String): Flow<GroupStreamEvent> = flow {
            val number = starts.incrementAndGet()
            emit(GroupStreamEvent.Accepted("run$number", null))
            awaitCancellation()
        }
        override fun continueChat(groupId: String, whileTyping: Boolean, whileIdle: Boolean): Flow<GroupStreamEvent> = flow { awaitCancellation() }
    }
    @Test fun logoutClearsExistingObserversAndLateStopCannotCancelNextSessionsReply() = runBlocking {
        val api = Api()
        val streams = Streams()
        val repository = GroupRepository(api, streams)
        try {
            repository.refresh("group")
            val oldObserver = repository.observe("group")
            assertThat(repository.send("group", "Hello")).isTrue()
            withTimeout(2_000) { while (oldObserver.value.detail?.activeRunId != "run1") delay(5) }
            repository.stop("group")
            withTimeout(2_000) { api.stopEntered.await() }
            repository.cancelAllOperations()
            assertThat(oldObserver.value.detail).isNull()
            repository.refresh("group")
            assertThat(repository.send("group", "A new session")).isTrue()
            val newObserver = repository.observe("group")
            withTimeout(2_000) { while (newObserver.value.detail?.activeRunId != "run2") delay(5) }
            api.stopRelease.complete(Unit)
            delay(50)
            assertThat(newObserver.value.sending).isTrue()
            assertThat(newObserver.value.detail?.activeRunId).isEqualTo("run2")
        } finally { api.stopRelease.complete(Unit); repository.cancelAllOperations() }
    }
    @Test fun lateCreationCannotNavigateOrPopulateAChangedAccount() = runBlocking {
        val api = Api()
        val repository = GroupRepository(api, Streams())
        try {
            val creation = async { runCatching { repository.create("Friends", listOf("a", "b")) } }
            api.createEntered.await()
            repository.cancelAllOperations()
            api.createRelease.complete(Unit)
            assertThat(creation.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(repository.groups.value).isEmpty()
        } finally { api.createRelease.complete(Unit); repository.cancelAllOperations() }
    }
}
