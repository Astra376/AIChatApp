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
import androidx.compose.ui.test.swipeRight
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

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ChatScreenHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun smartFollow_stopsOnManualScroll_andResumesFromJumpToLatest() {
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(
            messages = List(30) { index -> message(index, "Message $index") }
        )))
        composeRule.setContent { TestChat(chatState.value) }

        composeRule.onNodeWithText("Message 29").assertIsDisplayed()
        // A semantics scroll does not represent user intent. Exercise the real drag source.
        composeRule.onNodeWithTag("chat-transcript").performTouchInput {
            // The transcript extends behind the top bar. Start inside visible
            // chat content, not on the overlaid header at y = 0.
            swipeDown(startY = center.y, endY = bottom)
        }
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
    fun replyPager_staysCenteredWhileSaving_andReachesEveryVariant() {
        val texts = listOf("Original answer", "First alternate\nWith another line", "Second alternate", "Third alternate\nWith a different ending")
        var assistant = message(1, texts[0]).copy(regenerations = texts.drop(1).mapIndexed { index, text ->
            AssistantRegeneration("variant-$index", "message-1", text, index.toLong() + 3)
        })
        val state = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(assistant))))
        var selected = 0
        composeRule.setContent {
            TestChat(state.value, onSelectVariant = { _, index ->
                selected = index
                state.value = state.value.copy(isMutating = true)
            })
        }
        val left = composeRule.onNodeWithText(texts[0]).fetchSemanticsNode().boundsInRoot.left
        for (page in listOf(1, 2, 3, 2, 1, 0)) {
            composeRule.onNodeWithTag("reply-variants").performTouchInput {
                if (page > selected) swipeLeft() else swipeRight()
            }
            composeRule.onNodeWithText(texts[page]).assertIsDisplayed()
            assertEquals("The selected reply must settle fully on screen while its save is pending", left,
                composeRule.onNodeWithText(texts[page]).fetchSemanticsNode().boundsInRoot.left, 1f)
            composeRule.runOnIdle {
                assertEquals(page, selected)
                assistant = assistant.copy(selectedRegenerationId = if (page == 0) null else "variant-${page - 1}")
                state.value = state.value.copy(isMutating = false, conversation = conversationDetail(listOf(assistant)))
            }
            composeRule.onNodeWithText(texts[page]).assertIsDisplayed()
        }
    }

    @Test
    fun tallerVariantChangesHeightOnlyAfterSwipeSettles() {
        val assistant = message(1, "Short original").copy(regenerations = listOf(
            AssistantRegeneration("tall", "message-1", (1..6).joinToString("\n") { "A longer reply line $it" }, 4)
        ))
        composeRule.setContent { TestChat(ChatUiState(conversation = conversationDetail(listOf(assistant)))) }
        val pager = composeRule.onNodeWithTag("reply-variants")
        val originalHeight = pager.fetchSemanticsNode().boundsInRoot.height
        val pagerWidth = pager.fetchSemanticsNode().boundsInRoot.width
        composeRule.mainClock.autoAdvance = false
        pager.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(pagerWidth * .9f, center.y))
            moveBy(androidx.compose.ui.geometry.Offset(-pagerWidth * .65f, 0f), delayMillis = 160)
        }
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals("The incoming page must not expand the transcript during the drag", originalHeight,
            pager.fetchSemanticsNode().boundsInRoot.height, 1f)
        pager.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(600)
        assertTrue("The selected longer reply expands after the swipe settles",
            pager.fetchSemanticsNode().boundsInRoot.height > originalHeight)
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
    fun newReply_withReceivedBurstOnItsFirstFrameStillRevealsGradually() {
        composeRule.mainClock.autoAdvance = false
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(message(0, "Question")))))
        composeRule.setContent { TestChat(chatState.value) }
        composeRule.mainClock.advanceTimeByFrame()
        val received = "Streaming reply received in a single burst. ".repeat(8)
        updateChatState {
            chatState.value = chatState.value.copy(activeStream = stream(received))
        }
        composeRule.mainClock.advanceTimeBy(160)
        val displayed = displayedTextStartingWith("Stream")
        assertTrue("A new reply should start revealing even when its first packet is large", displayed.isNotEmpty())
        assertTrue("Only reopening an existing stream may skip the reveal", displayed.length < received.length)
        composeRule.mainClock.advanceTimeBy(received.length * 20L)
        composeRule.onNodeWithText(received).assertIsDisplayed()
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
        updateChatState { chatState.value = chatState.value.copy(activeStream = stream(receivedText)) }
        composeRule.mainClock.advanceTimeBy(128)

        val displayed = displayedTextStartingWith("Str")
        assertTrue("A partial reply must render while generation is still active", displayed.isNotEmpty())
        assertTrue("A provider burst should reveal smoothly", displayed.length < receivedText.length)
        assertEquals(ActiveStreamStatus.STREAMING, chatState.value.activeStream?.status)
    }

    @Test
    fun completedResponse_keepsRevealingUntilEveryCharacterIsVisible() {
        composeRule.mainClock.autoAdvance = false
        val finalText = "Streaming reply continues after the network has finished."
        val chatState = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(message(0, "Question"))), activeStream = stream("").copy(assistantMessageId = "message-1")))
        composeRule.setContent { TestChat(chatState.value) }
        composeRule.mainClock.advanceTimeByFrame()
        updateChatState { chatState.value = chatState.value.copy(activeStream = chatState.value.activeStream!!.copy(text = finalText)) }
        composeRule.mainClock.advanceTimeBy(160)
        val before = displayedTextStartingWith("Stream")
        updateChatState {
            chatState.value = chatState.value.copy(conversation = conversationDetail(listOf(message(0, "Question"), message(1, finalText))), activeStream = chatState.value.activeStream!!.copy(status = ActiveStreamStatus.COMPLETED))
        }
        composeRule.mainClock.advanceTimeBy(64)
        val after = displayedTextStartingWith("Stream")
        assertTrue("Completion must not flush the pending character queue", after.length < finalText.length)
        assertTrue("The reveal should keep advancing", after.length > before.length)
        composeRule.mainClock.advanceTimeBy(finalText.length * 20L)
        composeRule.onNodeWithText(finalText).assertIsDisplayed()
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
        // Fully dispose the screen before simulating a return. With the
        // virtual clock paused, hide/show can otherwise coalesce into one frame.
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(receivedText).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false
        val textReceivedWhileAway = "$receivedText More arrived while away."
        composeRule.runOnIdle {
            chatState.value = chatState.value.copy(activeStream = stream(textReceivedWhileAway))
            visible.value = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(64)

        // The first composition after returning contains the received prefix.
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

    @Test
    fun continueImmediatelyRemovesEarlierVariantControls() {
        val assistant = message(1, "Earlier reply").copy(regenerations = listOf(AssistantRegeneration("v1", "message-1", "Other reply", 4)))
        val state = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(assistant))))
        composeRule.setContent { TestChat(state.value) }
        composeRule.onNodeWithContentDescription("Next variant").assertExists()
        composeRule.runOnIdle { state.value = state.value.copy(activeStream = stream("").copy(mode = ActiveStreamMode.CONTINUE)) }
        composeRule.onNodeWithContentDescription("Next variant").assertDoesNotExist()
        composeRule.onNodeWithText("Variant 1/2").assertDoesNotExist()
    }

    @Test
    fun firstReplyShowsRegenerateWithoutVariantLabel() {
        composeRule.setContent { TestChat(ChatUiState(conversation = conversationDetail(listOf(message(1, "First reply"))))) }
        composeRule.onNodeWithContentDescription("Regenerate reply").assertIsDisplayed()
        composeRule.onNodeWithText("Variant 1/1").assertDoesNotExist()
    }

    @Test
    fun completedRegenerationKeepsDraftTextUntilRevealFinishesAndCentersPager() {
        composeRule.mainClock.autoAdvance = false
        val assistant = message(1, "Original reply")
        val state = mutableStateOf(ChatUiState(conversation = conversationDetail(listOf(assistant))))
        composeRule.setContent { TestChat(state.value) }
        composeRule.mainClock.advanceTimeBy(300)
        val left = composeRule.onNodeWithText("Original reply").fetchSemanticsNode().boundsInRoot.left
        updateChatState { state.value = state.value.copy(activeStream = stream("").copy(mode = ActiveStreamMode.REGENERATE, targetMessageId = assistant.id)) }
        composeRule.mainClock.advanceTimeBy(32)
        val final = "Streaming alternate text must continue typing after completion. ".repeat(5)
        updateChatState { state.value = state.value.copy(
            activeStream = state.value.activeStream!!.copy(text = final, regenerationId = "new", status = ActiveStreamStatus.COMPLETED),
            conversation = conversationDetail(listOf(assistant.copy(selectedRegenerationId = "new", regenerations = listOf(AssistantRegeneration("new",assistant.id,final,5))))) ) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(displayedTextStartingWith("Stream").length < final.length)
        composeRule.mainClock.advanceTimeBy(final.length * 20L)
        composeRule.onNodeWithText(final).assertIsDisplayed()
        updateChatState { state.value = state.value.copy(activeStream = null) }
        composeRule.mainClock.advanceTimeBy(400)
        assertTrue(kotlin.math.abs(composeRule.onNodeWithText(final).fetchSemanticsNode().boundsInRoot.left-left)<2f)
        composeRule.onNodeWithText("Another reply").assertDoesNotExist()
    }

    // With the virtual clock paused, publish the simulated network/Room
    // update before advancing frames. runOnIdle only synchronizes before block.
    private fun updateChatState(block: () -> Unit) {
        composeRule.runOnIdle {
            block()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
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
        onStop: () -> Unit = {},
        themeMode: ThemeMode = ThemeMode.LIGHT
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        AppTheme(themeMode = themeMode) {
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
