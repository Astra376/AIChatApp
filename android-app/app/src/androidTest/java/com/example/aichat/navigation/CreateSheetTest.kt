package com.example.aichat.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import com.example.aichat.core.design.AppTheme
import com.example.aichat.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CreateSheetTest {
    @get:Rule val composeRule = createComposeRule()
    private val opened = mutableListOf<String>()
    private var dismissals = 0

    private fun show() {
        val visible = mutableStateOf(true)
        composeRule.setContent {
            AppTheme(themeMode = ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    if (visible.value) CreateSheet(
                        onDismiss = { dismissals++; visible.value = false },
                        onOpen = { opened += it; visible.value = false }
                    )
                }
            }
        }
        composeRule.onNodeWithText("Character").assertIsDisplayed()
        composeRule.onNodeWithText("Persona").assertIsDisplayed()
        composeRule.onNodeWithText("Group chat").assertIsDisplayed()
        composeRule.onNodeWithText("Custom voice").assertIsDisplayed()
    }

    private fun select(label: String, route: String) {
        show()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText("Bring someone new to life").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(route), opened)
            assertEquals(0, dismissals)
        }
    }

    @Test fun characterChoice_opensCharacterCreatorOnce() = select("Character", "create-character")
    @Test fun personaChoice_opensPersonaCreatorOnce() = select("Persona", "personas/create")
    @Test fun groupChoice_opensGroupCreatorOnce() = select("Group chat", "groups/create")
    @Test fun voiceChoice_remainsClickableForTheUpgradeFlow() = select("Custom voice", "voices/create")
    @Test fun ultraBadge_opensTheSameVoiceDestinationOnce() = select("Ultra", "voices/create")

    @Test fun tappingOutside_dismissesWithoutOpeningACreator() {
        show()
        // The scrim spans the whole window. Its centre is behind the sheet,
        // so tap its exposed upper area to exercise real outside dismissal.
        composeRule.onNodeWithContentDescription("Close sheet").performTouchInput {
            click(Offset(center.x, 48.dp.toPx()))
        }
        composeRule.onNodeWithText("Character").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), opened)
            assertEquals(1, dismissals)
        }
    }

    @Test fun systemBack_dismissesWithoutOpeningACreator() {
        show()
        pressBack()
        composeRule.onNodeWithText("Character").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), opened)
            assertEquals(1, dismissals)
        }
    }
}
