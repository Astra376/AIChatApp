package com.example.aichat.feature.group

import com.example.aichat.core.network.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** App-owned streams survive navigating away; each group has at most one operation. */
@Singleton
class GroupRepository @Inject constructor(private val api: GroupApi, private val streaming: GroupStreamingClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<GroupChatState>>()
    private val operations = mutableMapOf<String, Job>()
    private val epoch = AtomicLong()
    private val list = MutableStateFlow<List<GroupDetailDto>>(emptyList())
    val groups: StateFlow<List<GroupDetailDto>> = list.asStateFlow()
    private fun state(id: String) = sessions.getOrPut(id) { MutableStateFlow(GroupChatState()) }
    fun observe(id: String): StateFlow<GroupChatState> = state(id).asStateFlow()

    suspend fun refreshGroups() {
        val token = epoch.get()
        val all = mutableListOf<GroupDetailDto>()
        var cursor: String? = null
        do {
            val page = api.list(cursor)
            all += page.items
            cursor = page.nextCursor
        } while (cursor != null && all.size < 500)
        if (epoch.get() == token) list.value = all.distinctBy { it.id }
    }
    suspend fun create(name: String, ids: List<String>): String {
        val token = epoch.get()
        val result = api.create(CreateGroupRequestDto(name.trim(), ids))
        if (epoch.get() == token) {
            state(result.id).value = GroupChatState(detail = result, loading = false)
            list.update { listOf(result) + it.filterNot { row -> row.id == result.id } }
        }
        return result.id
    }
    suspend fun refresh(id: String) {
        val target = state(id)
        val token = epoch.get()
        val requestedAt = target.value.revision
        try {
            val detail = api.detail(id)
            if (epoch.get() != token || sessions[id] !== target) return
            target.update { current ->
                // A GET started before a new streamed chunk must never rewind it.
                if (current.sending || current.revision != requestedAt) current.copy(loading = false)
                else current.copy(detail = mergeGroupSnapshot(current.detail, detail), loading = false)
            }
            list.update { (it.filterNot { row -> row.id == id } + detail).sortedByDescending { row -> row.updatedAt } }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (epoch.get() == token) target.update { it.copy(loading = false, error = error.userFacingMessage("Couldn't load the group.")) } }
    }
    suspend fun older(id: String) {
        val target = state(id)
        val cursor = target.value.detail?.nextBeforePosition ?: return
        if (target.value.loadingOlder) return
        target.update { it.copy(loadingOlder = true) }
        val token = epoch.get()
        try {
            val page = api.detail(id, cursor)
            if (epoch.get() == token) target.update { current -> current.copy(loadingOlder = false,
                detail = current.detail?.copy(messages = mergeMessages(page.messages, current.detail.messages), nextBeforePosition = page.nextBeforePosition)) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (epoch.get() == token) target.update { it.copy(loadingOlder = false, error = "Couldn't load older messages.") } }
    }
    fun send(id: String, content: String): Boolean {
        if (content.isBlank() || content.length > 12_000) return false
        val messageId = state(id).value.failedDraft?.takeIf { it.content == content.trim() }?.id ?: "group_user_${UUID.randomUUID()}"
        return launchOperation(id, messageId, content.trim(), false)
    }
    fun continueChat(id: String, typing: Boolean = false, idle: Boolean = false): Boolean = launchOperation(id, null, null, typing, idle)
    private fun launchOperation(id: String, userMessageId: String?, content: String?, typing: Boolean, idle: Boolean = false): Boolean = synchronized(operations) {
        val target = state(id)
        if (operations[id]?.isActive == true || target.value.busy || target.value.detail == null) return@synchronized false
        val token = epoch.get()
        target.update { current ->
            val optimistic = userMessageId?.let { GroupMessageDto(it, id, (current.detail?.messages?.maxOfOrNull { m -> m.position } ?: -1) + 1,
                "user", content.orEmpty(), System.currentTimeMillis()) }
            current.copy(sending = true, error = null, failedDraft = null, revision = current.revision + 1,
                detail = current.detail?.let { detail -> if (optimistic == null) detail else detail.copy(messages = detail.messages + optimistic) })
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var accepted = false
            try {
                val source = if (userMessageId != null) streaming.send(id, userMessageId, content.orEmpty()) else streaming.continueChat(id, typing, idle)
                source.collect { event ->
                    if (epoch.get() != token) return@collect
                    target.update { current -> reduceGroupEvent(current, event) }
                    if (event is GroupStreamEvent.Accepted) accepted = true
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch.get() == token) target.update { current -> current.copy(error = if (typing || idle) null else error.userFacingMessage("The group reply was interrupted."),
                    failedDraft = if (!accepted && userMessageId != null) PendingGroupSend(userMessageId, content.orEmpty()) else null,
                    detail = current.detail?.let { detail -> if (!accepted && userMessageId != null) detail.copy(messages = detail.messages.filterNot { it.id == userMessageId }) else detail }) }
            } finally {
                synchronized(operations) {
                    if (operations[id] === coroutineContext[Job]) operations.remove(id)
                }
                if (epoch.get() == token && sessions[id] === target) {
                    target.update { it.copy(sending = false) }
                    withContext(NonCancellable) { refresh(id) }
                }
            }
        }
        operations[id] = job
        job.start()
        true
    }
    fun stop(id: String) {
        val target = state(id)
        val runId = target.value.detail?.activeRunId ?: return
        if (target.value.stopping) return
        target.update { it.copy(stopping = true) }
        val token = epoch.get()
        scope.launch {
            try {
                api.stop(id, GroupStopRequestDto(runId))
                synchronized(operations) { operations[id]?.cancel() }
                if (epoch.get() == token) target.update { it.copy(sending = false, stopping = false,
                    detail = it.detail?.copy(activeRunId = null, activeRunExpiresAt = null,
                        messages = it.detail.messages.filterNot { m -> m.role == "assistant" && m.content.isBlank() }
                            .map { m -> if (m.status == "streaming") m.copy(status = "interrupted") else m })) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch.get() == token) target.update { it.copy(stopping = false, error = "Couldn't stop this reply. Try again.") } }
        }
    }
    suspend fun presence(id: String, typing: Boolean) {
        try { api.presence(id, GroupPresenceRequestDto(typing)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Presence is best effort and should not interrupt chatting. */ }
    }
    suspend fun delete(id: String) {
        api.delete(id)
        synchronized(operations) { operations.remove(id)?.cancel() }
        sessions.remove(id)
        list.update { it.filterNot { row -> row.id == id } }
    }
    fun reportError(id: String, message: String) { state(id).update { it.copy(error = message) } }
    fun clearError(id: String) { state(id).update { it.copy(error = null) } }
    fun cancelAllOperations() {
        epoch.incrementAndGet()
        synchronized(operations) { operations.values.forEach { it.cancel() }; operations.clear() }
        sessions.clear()
        list.value = emptyList()
    }
}

data class PendingGroupSend(val id: String, val content: String)

data class GroupChatState(val detail: GroupDetailDto? = null, val loading: Boolean = true, val loadingOlder: Boolean = false,
    val sending: Boolean = false, val stopping: Boolean = false, val error: String? = null, val revision: Long = 0, val reasoning: Boolean = false, val failedDraft: PendingGroupSend? = null) {
    val busy: Boolean get() = sending || stopping || (detail?.activeRunId != null && (detail.activeRunExpiresAt ?: 0) > System.currentTimeMillis())
}
internal fun mergeMessages(older: List<GroupMessageDto>, newer: List<GroupMessageDto>) =
    (older + newer).associateBy { it.id }.values.sortedBy { it.position }
internal fun reduceGroupEvent(state: GroupChatState, event: GroupStreamEvent): GroupChatState {
    val detail = state.detail ?: return state
    val changed = when (event) {
        is GroupStreamEvent.Accepted -> detail.copy(activeRunId = event.runId, activeRunExpiresAt = System.currentTimeMillis() + 90_000,
            messages = if (event.userMessage != null) mergeMessages(detail.messages, listOf(event.userMessage)) else detail.messages)
        is GroupStreamEvent.Speaker -> detail.copy(messages = detail.messages + GroupMessageDto(event.messageId, detail.id,
            (detail.messages.maxOfOrNull { it.position } ?: -1) + 1, "assistant", "", System.currentTimeMillis(),
            event.characterId, event.characterName, event.avatarUrl, "streaming"))
        is GroupStreamEvent.Delta -> detail.copy(messages = detail.messages.map { if (it.id == event.messageId) it.copy(content = it.content + event.text) else it })
        is GroupStreamEvent.MessageDone -> detail.copy(messages = mergeMessages(detail.messages, listOf(event.message)))
        is GroupStreamEvent.Done, is GroupStreamEvent.Failed -> detail.copy(activeRunId = null, activeRunExpiresAt = null)
    }
    return state.copy(detail = changed, reasoning = if (event is GroupStreamEvent.Speaker) event.reasoning else if (event is GroupStreamEvent.Delta || event is GroupStreamEvent.Done || event is GroupStreamEvent.Failed) false else state.reasoning, error = (event as? GroupStreamEvent.Failed)?.message ?: state.error, revision = state.revision + 1)
}

/** Replace the server's current window while retaining already loaded older pages.
 * Deleted empty drafts must disappear; a refresh must not resurrect them. */
internal fun mergeGroupSnapshot(current: GroupDetailDto?, fresh: GroupDetailDto): GroupDetailDto {
    if (current == null) return fresh
    val boundary = fresh.messages.minOfOrNull { it.position } ?: 0
    val older = current.messages.filter { it.position < boundary }
    return fresh.copy(messages = mergeMessages(older, fresh.messages),
        nextBeforePosition = if (older.isNotEmpty()) current.nextBeforePosition else fresh.nextBeforePosition)
}
