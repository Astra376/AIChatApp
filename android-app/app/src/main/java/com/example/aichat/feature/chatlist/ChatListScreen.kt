package com.example.aichat.feature.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.model.ConversationSummary
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.ChatListRowPlaceholder
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.screenContentPadding
import com.example.aichat.core.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = true
)

private val previewWhitespace = "\\s+".toRegex()

private fun formatConversationPreview(preview: String): String {
    return previewWhitespace.replace(preview.trim(), " ")
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    authRepository: AuthRepository,
    conversationRepository: ConversationRepository
) : ViewModel() {
    private val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
    private val isLoading = MutableStateFlow(true)
    val uiState: StateFlow<ChatListUiState> =
        combine(
            conversationRepository.observeConversations(userId),
            isLoading
        ) { conversations, loading ->
            ChatListUiState(conversations = conversations, isLoading = loading)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatListUiState()
        )

    init {
        viewModelScope.launch {
            conversationRepository.refreshConversations(userId)
            isLoading.value = false
        }
    }
}

@Composable
fun ChatListRoute(
    paddingValues: PaddingValues,
    onOpenSearch: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    onOpenConversation: (String) -> Unit,
    onOpenGroups: () -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val conversations = state.conversations

    ScreenBackgroundBox {
        LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenContentPadding(paddingValues)
    ) {

        item(key = "groups") {
            androidx.compose.material3.TextButton(onClick = onOpenGroups, modifier = Modifier.fillMaxWidth()) { Text("Group chats") }
        }
        if (state.isLoading && conversations.isEmpty()) {
            items(8) {
                ChatListRowPlaceholder()
            }
        } else if (conversations.isEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = AppChrome.bottomBarVerticalPadding)) {
                    Text("No Conversations Yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Start a conversation from Home or Create and it will appear here.",
                        modifier = Modifier.padding(top = AppChrome.compactHeaderVerticalPadding),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(conversations, key = { it.id }) { conversation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenConversation(conversation.id) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    CharacterPortrait(
                        name = conversation.characterName,
                        avatarUrl = conversation.characterAvatarUrl,
                        modifier = Modifier.size(64.dp)
                    )
                    
                    if (conversation.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .background(
                                    MaterialTheme.colorScheme.error,
                                    CircleShape
                                )
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.background,
                                    CircleShape
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onError,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = conversation.characterName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 21.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatRelativeTime(conversation.lastMessageAt ?: conversation.updatedAt),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = formatConversationPreview(conversation.lastPreview)
                            .ifBlank { "Conversation ready." },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }
    }
}
