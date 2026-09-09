package com.example.aichat.feature.group

import com.example.aichat.core.network.GroupDetailDto
import com.example.aichat.core.network.GroupMessageDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroupStateTest {
    private fun message(id: String, position: Int, content: String) = GroupMessageDto(id, "group", position, "assistant", content, 1, status = "complete")
    @Test fun olderPagesMergeByIdentityWithoutMovingOrDuplicatingMessages() {
        val merged = mergeMessages(listOf(message("old", 0, "Earlier"), message("current", 1, "Partial")),
            listOf(message("current", 1, "Complete"), message("latest", 2, "New")))
        assertThat(merged.map { it.id }).containsExactly("old", "current", "latest").inOrder()
        assertThat(merged[1].content).isEqualTo("Complete")
    }
    @Test fun reloadingRemovesCancelledEmptyDraftAndRetainsEarlierPages() {
        val current = GroupDetailDto("group", "Friends", emptyList(),
            messages = listOf(message("old", 0, "Earlier"), message("user", 100, "Hello"), message("draft", 101, "")), nextBeforePosition = null)
        val fresh = GroupDetailDto("group", "Friends", emptyList(), messages = listOf(message("user", 100, "Hello")), nextBeforePosition = 100)
        val merged = mergeGroupSnapshot(current, fresh)
        assertThat(merged.messages.map { it.id }).containsExactly("old", "user").inOrder()
        assertThat(merged.nextBeforePosition).isNull()
    }
    @Test fun streamedTextIsStoredInAppStateSoReenteringDoesNotResetIt() {
        var state = GroupChatState(GroupDetailDto("group", "Friends", emptyList()), loading = false, sending = true)
        state = reduceGroupEvent(state, GroupStreamEvent.Accepted("run", null))
        state = reduceGroupEvent(state, GroupStreamEvent.Speaker("run", "reply", "astrid", "Astrid", null))
        state = reduceGroupEvent(state, GroupStreamEvent.Delta("run", "reply", "Hello"))
        state = reduceGroupEvent(state, GroupStreamEvent.Delta("run", "reply", " there"))
        assertThat(state.detail!!.messages.single().content).isEqualTo("Hello there")
        assertThat(state.busy).isTrue()
        state = reduceGroupEvent(state, GroupStreamEvent.MessageDone("run", message("reply", 0, "Hello there")))
        state = reduceGroupEvent(state, GroupStreamEvent.Done("run")).copy(sending = false)
        assertThat(state.busy).isFalse()
        assertThat(state.detail!!.messages.single().content).isEqualTo("Hello there")
    }
    @Test fun expiredRemoteLeaseDoesNotKeepComposerLocked() {
        val state = GroupChatState(GroupDetailDto("group", "Friends", emptyList(), activeRunId = "expired", activeRunExpiresAt = 1), loading = false)
        assertThat(state.busy).isFalse()
    }
    @Test fun terminalFailureReleasesRemoteBusyStateAndKeepsPartialText() {
        val state = GroupChatState(GroupDetailDto("group", "Friends", emptyList(), messages = listOf(message("reply", 0, "Partial")),
            activeRunId = "run", activeRunExpiresAt = Long.MAX_VALUE), loading = false)
        val failed = reduceGroupEvent(state, GroupStreamEvent.Failed("run", "Try again"))
        assertThat(failed.busy).isFalse()
        assertThat(failed.detail!!.messages.single().content).isEqualTo("Partial")
        assertThat(failed.error).isEqualTo("Try again")
    }
}
