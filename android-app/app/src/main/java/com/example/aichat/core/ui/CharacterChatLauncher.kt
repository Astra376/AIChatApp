package com.example.aichat.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aichat.core.network.userFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class CharacterChatLauncher(
    val openingCharacterId: String?,
    val open: (String) -> Unit
)

/** One pending open per screen; leaving the screen cancels its navigation request. */
@Composable
internal fun rememberCharacterChatLauncher(
    ensureConversation: suspend (String) -> Result<String>,
    onOpenConversation: (String) -> Unit,
    snackbarHostState: SnackbarHostState
): CharacterChatLauncher {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEnsure by rememberUpdatedState(ensureConversation)
    val currentOpen by rememberUpdatedState(onOpenConversation)
    var openingCharacterId by remember { mutableStateOf<String?>(null) }
    return CharacterChatLauncher(openingCharacterId) { characterId ->
        if (openingCharacterId == null && lifecycle.currentState == Lifecycle.State.RESUMED) {
            openingCharacterId = characterId
            scope.launch {
                try {
                    currentEnsure(characterId)
                        .onSuccess { conversationId ->
                            if (lifecycle.currentState == Lifecycle.State.RESUMED) currentOpen(conversationId)
                        }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            snackbarHostState.showSnackbar(error.userFacingMessage("Couldn't open chat."))
                        }
                } finally {
                    openingCharacterId = null
                }
            }
        }
    }
}
