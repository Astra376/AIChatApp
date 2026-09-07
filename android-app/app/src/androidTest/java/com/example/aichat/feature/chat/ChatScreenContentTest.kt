package com.example.aichat.feature.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.AssistantRegeneration
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.model.ChatMessage
import com.example.aichat.core.model.ConversationDetail
import com.example.aichat.core.model.MessageRole
import com.example.aichat.core.model.MessageSendState
import com.example.aichat.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatChromeScreenshot() {
        val state = ChatUiState(conversation = conversationDetail(listOf(
            message(0, "How was your day?").copy(role = MessageRole.USER),
            message(1, "*I settle into the chair, smiling.*\n\n\"Better now you're here. How about yours?\"")
        )))
        composeRule.setContent { TestChat(state) }
        composeRule.waitForIdle()
        // Connected tests can remove app-owned files during cleanup. Native
        // screencap writes the fixture to shell storage that CI can still pull.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/meek-chat-dark.png"
            )
        ).use { it.readBytes() }
    }

    @Test
    fun smartFollow_stopsOnManualScroll_andResumesFromJumpToLatest() {
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(
            messages = List(30) { index -> message(index, "Message $index") }
        )))
        composeRule.setContent { TestChat(chatState.value) }

        composeRule.onNodeWithText("Message 29").assertIsDisplayed()
        // A semantics scroll does not represent user intent. Exercise the real drag source.
        composeRule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        composeRule.onNodeWithTag("jump-to-latest").assertExists()
        composeRule.runOnIdle {
            chatState.value = chatState.value.copy(
                activeStream = stream("Streaming reply").copy(status = ActiveStreamStatus.STOPPED)
            )
        }
        composeRule.onNodeWithTag("jump-to-latest").assertExists()
        composeRule.onNodeWithText("Streaming reply").assertIsNotDisplayed()

        composeRule.onNodeWithTag("jump-to-latest").performClick()
        composeRule.onNodeWithText("Streaming reply").assertIsDisplayed()
    }

    @Test
    fun sendingNewUserMessage_removesPreviousReplyPager_andPreventsVersionChanges() {
        val assistant = message(1, "Original answer").copy(
            regenerations = listOf(AssistantRegeneration("variant-1", "message-1", "Different answer", 3))
        )
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(assistant))))
        var selectedVersions = 0
        composeRule.setContent {
            TestChat(chatState.value, onSelectVariant = { _, _ -> selectedVersions++ })
        }
        composeRule.onNodeWithContentDescription("Next variant").assertExists()

        composeRule.runOnIdle {
            chatState.value = chatState.value.copy(conversation = conversationDetail(listOf(
                assistant,
                message(2, "New question").copy(position = -1, sendState = MessageSendState.PENDING)
            )))
        }
        composeRule.onNodeWithContentDescription("Next variant").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Previous variant").assertDoesNotExist()
        composeRule.onNodeWithText("Original answer").performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Original answer").assertIsDisplayed()
        composeRule.onNodeWithText("Different answer").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, selectedVersions) }
    }

    @Test
    fun streamingRegeneration_growsBeyondThePreviousReplyHeightWhileControlsAreLocked() {
        val assistant = message(1, "Short original")
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(assistant))))
        composeRule.setContent { TestChat(chatState.value) }
        val originalHeight = composeRule.onNodeWithText("Short original").fetchSemanticsNode().boundsInRoot.height
        val regenerated = (1..6).joinToString("\n") { "Regenerated line $it" }

        composeRule.runOnIdle {
            chatState.value = chatState.value.copy(activeStream = stream(regenerated).copy(
                mode = ActiveStreamMode.REGENERATE,
                targetMessageId = assistant.id
            ))
        }
        composeRule.waitForIdle()

        val streamingHeight = composeRule.onNodeWithText(regenerated).fetchSemanticsNode().boundsInRoot.height
        assertTrue("A locked generation page must expand instead of clipping new lines", streamingHeight > originalHeight * 2)
    }

    @Test
    fun activeReply_revealsReceivedTextBeforeCompletion() {
        composeRule.mainClock.autoAdvance = false
        val chatState = mutableStateOf(ChatUiState(
            conversation = conversationDetail(listOf(message(0, "Question"))),
            activeStream = stream("")
        ))
        composeRule.setContent { TestChat(chatState.value) }
        composeRule.mainClock.advanceTimeByFrame()
        val receivedText = "Streaming reply continues with more text. ".repeat(8)
        composeRule.runOnIdle { chatState.value = chatState.value.copy(activeStream = stream(receivedText)) }
        composeRule.mainClock.advanceTimeBy(128)

        val displayed = displayedTextStartingWith("Stream")
        assertTrue("A partial reply must render while generation is still active", displayed.isNotEmpty())
        assertTrue("A provider burst should reveal smoothly", displayed.length < receivedText.length)
        assertEquals(ActiveStreamStatus.STREAMING, chatState.value.activeStream?.status)
    }

    @Test
    fun continuousTokenUpdates_doNotRestartAndStarveReveal() {
        composeRule.mainClock.autoAdvance = false
        val chatState = mutableStateOf(ChatUiState(
            conversation = conversationDetail(listOf(message(0, "Question"))),
            activeStream = stream("")
        ))
        composeRule.setContent { TestChat(chatState.value) }
        composeRule.mainClock.advanceTimeByFrame()
        repeat(12) { index ->
            composeRule.runOnIdle {
                chatState.value = chatState.value.copy(activeStream = stream("Streaming " + "token ".repeat(index + 1)))
            }
            composeRule.mainClock.advanceTimeByFrame()
        }

        assertTrue("New token arrivals must not postpone every reveal frame", displayedTextStartingWith("Stream").isNotEmpty())
        assertEquals(ActiveStreamStatus.STREAMING, chatState.value.activeStream?.status)
    }

    @Test
    fun reopeningChat_immediatelyShowsAlreadyReceivedText_withoutReplayingIt() {
        composeRule.mainClock.autoAdvance = false
        val receivedText = "This complete received prefix should already be visible when returning."
        val chatState = mutableStateOf(ChatUiState(
            conversation = conversationDetail(listOf(message(0, "Question"))),
            activeStream = stream(receivedText)
        ))
        val visible = mutableStateOf(true)
        composeRule.setContent { if (visible.value) TestChat(chatState.value) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(receivedText).assertIsDisplayed()
        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        val textReceivedWhileAway = "$receivedText More arrived while away."
        composeRule.runOnIdle {
            chatState.value = chatState.value.copy(activeStream = stream(textReceivedWhileAway))
            visible.value = true
        }
        composeRule.mainClock.advanceTimeByFrame()

        // No typing-clock catch-up: the first returned frame contains the entire received prefix.
        composeRule.onNodeWithText(textReceivedWhileAway).assertIsDisplayed()
    }

    @Test
    fun recoveredRemoteGeneration_canBeStoppedWithoutReplacingVisibleHistory() {
        val state = ChatUiState(
            conversation = conversationDetail(listOf(message(1, "Existing answer"))),
            activeStream = stream("").copy(runId = "remote-run", remoteOnly = true)
        )
        var stops = 0
        composeRule.setContent { TestChat(state, onStop = { stops++ }) }
        composeRule.onNodeWithText("Existing answer").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop response").performClick()
        composeRule.runOnIdle { assertEquals(1, stops) }
    }

    private fun displayedTextStartingWith(prefix: String): String = composeRule
        .onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .map { it.text }
        .firstOrNull { it.startsWith(prefix) }.orEmpty()

    @Composable
    private fun TestChat(
        state: ChatUiState,
        onSelectVariant: (ChatMessage, Int) -> Unit = { _, _ -> },
        onStop: () -> Unit = {}
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        AppTheme(themeMode = ThemeMode.LIGHT) {
            ChatScreenContent(
                paddingValues = PaddingValues(), onBack = {}, onOpenMemory = {},
                state = state.copy(streamingHaptics = false), snackbarHostState = snackbarHostState,
                onComposerChanged = {}, onSend = {}, onContinue = {}, onStop = onStop, onLoadOlderMessages = {},
                onMessageLongPress = {}, onSelectVariant = onSelectVariant,
                onSelectPreviousVariant = {}, onSelectNextVariant = {}
            )
        }
    }

    private fun stream(text: String) = ActiveAssistantStream(
        conversationId = "conversation-1", draftKey = "send-draft-1", mode = ActiveStreamMode.SEND,
        userMessageId = "message-0", text = text, status = ActiveStreamStatus.STREAMING
    )

    private fun message(index: Int, content: String) = ChatMessage(
        id = "message-$index", conversationId = "conversation-1", position = index + 1,
        role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
        content = content, edited = false, createdAt = index.toLong(), updatedAt = index.toLong(),
        selectedRegenerationId = null, sendState = MessageSendState.SENT
    )

    private fun conversationDetail(messages: List<ChatMessage>) = ConversationDetail(
        id = "conversation-1", ownerUserId = "user-1", conversationVersion = 1,
        character = CharacterSummary(
            id = "character-1", ownerUserId = "user-1", name = "Astra", tagline = "", greeting = "",
            bio = "", systemPrompt = "Be helpful", visibility = CharacterVisibility.PUBLIC,
            avatarUrl = null, publicChatCount = 0, likeCount = 0, likedByMe = false,
            lastActiveAt = 0L, createdAt = 0L, updatedAt = 0L
        ),
        messages = messages
    )
}
