package com.example.aichat.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.model.ThemeMode
import com.example.aichat.feature.character.CharacterProfileContent
import com.example.aichat.feature.character.CharacterProfileUiState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CharacterSubpageContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun details_rendersSharedMetricsDatesActionsAndPlainCloseAction() {
        val now = System.currentTimeMillis()
        val character = character(
            publicChatCount = 123,
            likeCount = 45,
            createdAt = now - 60_000L,
            updatedAt = now - 2 * 3_600_000L
        )
        val closed = mutableStateOf(false)
        val actions = mutableListOf<String>()

        composeRule.setContent {
            AppTheme(themeMode = ThemeMode.LIGHT) {
                CharacterDetailsContent(
                    state = CharacterProfileUiState(character, isLoading = false),
                    onClose = { closed.value = true },
                    onViewCharacter = { actions += "character" },
                    onViewCreator = { actions += "creator" },
                    onShare = {},
                    onToggleLike = {},
                    onRefreshChat = { actions += "refresh" },
                    onStartNewChat = { actions += "new" },
                    onChatPreferences = { actions += "preferences" },
                    onOpenPersonas = { actions += "persona" },
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("123 interactions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("45 likes").assertIsDisplayed()
        composeRule.onNodeWithText("Created").assertIsDisplayed()
        composeRule.onNodeWithText("1 minute ago").assertIsDisplayed()
        composeRule.onNodeWithText("Last Updated").assertIsDisplayed()
        composeRule.onNodeWithText("2 hours ago").assertIsDisplayed()
        // The action list scrolls on smaller screens and at larger font sizes.
        // Every action must remain reachable, visible and connected to its callback.
        listOf("View Character Profile", "View Creator Profile", "Persona", "Chat preferences", "Refresh this chat", "Start new chat").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("character", "creator", "persona", "preferences", "refresh", "new"), actions)
        }

        composeRule.onNodeWithContentDescription("Close character details").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(closed.value)
        }
    }

    @Test
    fun characterProfile_descriptionExpandsAndCollapsesWithMoreLessActions() {
        val character = character(
            bio = "A thoughtful explorer with a long history of crossing distant worlds, " +
                "mapping forgotten places, and helping every traveler she meets along the way."
        )

        composeRule.setContent {
            AppTheme(themeMode = ThemeMode.LIGHT) {
                CharacterProfileContent(
                    state = CharacterProfileUiState(character, isLoading = false),
                    onBack = {},
                    onShare = {},
                    onChat = {},
                    onOpenCreator = {},
                    onToggleLike = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithText("More", substring = true)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Less", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Less", substring = true).performClick()
        composeRule.onNodeWithText("More", substring = true).assertIsDisplayed()
    }

    private fun character(
        bio: String = "Character description",
        publicChatCount: Int = 0,
        likeCount: Int = 0,
        createdAt: Long = 100L,
        updatedAt: Long = 100L
    ) = CharacterSummary(
        id = "character-1",
        ownerUserId = "creator-1",
        authorUsername = "astra",
        name = "Astra",
        tagline = "A guide",
        greeting = "Hello",
        bio = bio,
        systemPrompt = "",
        visibility = CharacterVisibility.PUBLIC,
        avatarUrl = null,
        publicChatCount = publicChatCount,
        likeCount = likeCount,
        likedByMe = false,
        lastActiveAt = updatedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
