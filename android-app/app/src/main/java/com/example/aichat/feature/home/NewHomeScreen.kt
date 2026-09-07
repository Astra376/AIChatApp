package com.example.aichat.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.design.CircleAvatar
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.ConversationSummary
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterSummaryCardPlaceholder
import com.example.aichat.core.ui.CharacterSummaryCard
import com.example.aichat.core.ui.CircleAvatarPlaceholder
import com.example.aichat.core.ui.rememberCharacterChatLauncher
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.ShimmerTextBlock
import com.example.aichat.core.ui.ShimmerBox
import com.example.aichat.core.ui.screenContentPadding
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewHomeUiState(
    val recentChats: List<ConversationSummary> = emptyList(),
    val totalUnreadCount: Int = 0,
    val topPicks: List<CharacterSummary> = emptyList(),
    val recommendedFeed: List<CharacterSummary> = emptyList(),
    val recommendedCursor: String? = null,
    val isFeedLoading: Boolean = true,
    val errorMessage: String? = null
)

internal fun mostRecentChatPerCharacter(
    chats: List<ConversationSummary>
): List<ConversationSummary> = chats
    .groupBy(ConversationSummary::characterId)
    .values
    .mapNotNull { characterChats ->
        characterChats.maxWithOrNull(
            compareBy<ConversationSummary> { it.updatedAt }
                .thenBy { it.startedAt }
                .thenBy { it.id }
        )?.copy(
            unreadCount = characterChats.sumOf { it.unreadCount },
            hasUnreadBadge = characterChats.any { it.hasUnreadBadge }
        )
    }

@HiltViewModel
class NewHomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val conversationRepository: ConversationRepository,
    private val homeRepository: HomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewHomeUiState())
    val uiState: StateFlow<NewHomeUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    init {
        val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
        viewModelScope.launch {
            conversationRepository.observeConversations(userId).collect { chats ->
                val latestChats = mostRecentChatPerCharacter(chats)
                val unreadCount = latestChats.sumOf { it.unreadCount }
                val sortedChats = latestChats.sortedWith(
                    compareByDescending<ConversationSummary> { it.unreadCount }
                        .thenByDescending { it.hasUnreadBadge }
                        .thenByDescending { it.updatedAt }
                )
                _uiState.value = _uiState.value.copy(
                    recentChats = sortedChats,
                    totalUnreadCount = unreadCount
                )
            }
        }
        refreshFeed()
    }

    fun refreshFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFeedLoading = true, errorMessage = null)
            runCatching { homeRepository.loadFeed(cursor = null) }
                .onSuccess { page ->
                    val picks = page.items.take(4)
                    val recs = page.items.drop(4)
                    _uiState.value = _uiState.value.copy(
                        topPicks = picks,
                        recommendedFeed = recs,
                        recommendedCursor = page.nextCursor,
                        isFeedLoading = false
                    )
                    if (page.nextCursor != null) {
                        loadMore()
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isFeedLoading = false,
                        errorMessage = "Failed to load the feed."
                    )
                }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.recommendedCursor ?: return
        if (_uiState.value.isFeedLoading) return
        _uiState.value = _uiState.value.copy(isFeedLoading = true)
        viewModelScope.launch {
            try {
                val page = homeRepository.loadFeed(cursor = cursor)
                _uiState.value = _uiState.value.copy(
                    recommendedFeed = (_uiState.value.recommendedFeed + page.items).distinctBy { it.id },
                    recommendedCursor = page.nextCursor
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _events.emit("Couldn't load more characters.")
            } finally {
                _uiState.value = _uiState.value.copy(isFeedLoading = false)
            }
        }
    }

    suspend fun ensureConversation(characterId: String): Result<String> {
        val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
        return conversationRepository.ensureConversation(userId, characterId)
    }
}

@Composable
fun NewHomeRoute(
    paddingValues: PaddingValues,
    onOpenConversation: (String) -> Unit,
    onOpenStudio: () -> Unit,
    onOpenChats: () -> Unit,
    viewModel: NewHomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val chatLauncher = rememberCharacterChatLauncher(
        ensureConversation = viewModel::ensureConversation,
        onOpenConversation = onOpenConversation,
        snackbarHostState = snackbarHostState
    )

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    ScreenBackgroundBox(
        snackbarHostState = snackbarHostState
    ) {
        val gridPadding = screenContentPadding(paddingValues)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = gridPadding.calculateTopPadding(),
                bottom = gridPadding.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing)
        ) {
            item {
                Column {
                    // Unread Header
                    SectionHeader(
                        title = "Unread (${state.totalUnreadCount})",
                        modifier = Modifier.padding(horizontal = AppChrome.screenHorizontalPadding),
                        onClick = onOpenChats
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = AppChrome.screenHorizontalPadding)
                    ) {
                        item {
                            CreateStoryNode(onClick = onOpenStudio)
                        }
                        if (state.isFeedLoading && state.recentChats.isEmpty()) {
                            items(3) {
                                StoryNodePlaceholder()
                            }
                        }
                        items(state.recentChats, key = { it.id }) { chat ->
                            StoryNode(
                                chat = chat,
                                onClick = { onOpenConversation(chat.id) }
                            )
                        }
                    }

                    if (state.topPicks.isNotEmpty() || state.isFeedLoading) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(
                            title = "Top Picks",
                            modifier = Modifier.padding(horizontal = AppChrome.screenHorizontalPadding),
                            onClick = null
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = AppChrome.screenHorizontalPadding)
                        ) {
                            if (state.isFeedLoading && state.topPicks.isEmpty()) {
                                items(3) {
                                    TopPickCardPlaceholder()
                                }
                            }
                            items(state.topPicks, key = { it.id }) { character ->
                                TopPickCard(
                                    character = character,
                                    isOpening = chatLauncher.openingCharacterId == character.id,
                                    enabled = chatLauncher.openingCharacterId == null,
                                    onClick = {
                                        chatLauncher.open(character.id)
                                    }
                                )
                            }
                        }
                    }

                    if (state.recentChats.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(
                            title = "Continue",
                            modifier = Modifier.padding(horizontal = AppChrome.screenHorizontalPadding),
                            onClick = onOpenChats
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = AppChrome.screenHorizontalPadding)
                        ) {
                            items(state.recentChats, key = { "continue_${it.id}" }) { chat ->
                                ContinueNode(
                                    chat = chat,
                                    onClick = { onOpenConversation(chat.id) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(
                        title = "Recommended",
                        modifier = Modifier.padding(horizontal = AppChrome.screenHorizontalPadding),
                        onClick = null
                    )
                }
            }

            val errorMessage = state.errorMessage
            if (errorMessage != null && state.recommendedFeed.isEmpty()) {
                item {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = AppChrome.screenHorizontalPadding)
                    )
                }
            }

            if (state.isFeedLoading && state.recommendedFeed.isEmpty()) {
                items(4) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppChrome.screenHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)
                    ) {
                        CharacterSummaryCardPlaceholder(modifier = Modifier.weight(1f))
                        CharacterSummaryCardPlaceholder(modifier = Modifier.weight(1f))
                    }
                }
            }

            items(state.recommendedFeed.chunked(2), key = { it.first().id }) { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppChrome.screenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)
                ) {
                    CharacterSummaryCard(
                        character = pair[0],
                        isOpening = chatLauncher.openingCharacterId == pair[0].id,
                        enabled = chatLauncher.openingCharacterId == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        chatLauncher.open(pair[0].id)
                    }
                    if (pair.size > 1) {
                        CharacterSummaryCard(
                            character = pair[1],
                            isOpening = chatLauncher.openingCharacterId == pair[1].id,
                            enabled = chatLauncher.openingCharacterId == null,
                            modifier = Modifier.weight(1f)
                        ) {
                            chatLauncher.open(pair[1].id)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (state.recommendedCursor != null) {
                item {
                    SecondaryButton(
                        text = if (state.isFeedLoading) "Loading..." else "Load More",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppChrome.screenHorizontalPadding),
                        enabled = !state.isFeedLoading,
                        onClick = viewModel::loadMore
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        if (onClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View all",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun CreateStoryNode(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(
                icon = AppIcons.createAction,
                contentDescription = "Create Character",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 40.dp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StoryNode(chat: ConversationSummary, onClick: () -> Unit) {
    val hasUnread = chat.unreadCount > 0
    val outlineColor = when {
        hasUnread -> MaterialTheme.colorScheme.error
        chat.hasUnreadBadge -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(modifier = Modifier.size(76.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(2.dp, outlineColor, CircleShape)
                    .clickable(onClick = onClick)
                    .padding(5.dp)
            ) {
                CircleAvatar(
                    name = chat.characterName,
                    avatarUrl = chat.characterAvatarUrl,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (hasUnread) {
                Text(
                    text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = chat.characterName,
            style = MaterialTheme.typography.labelMedium,
            color = if (hasUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StoryNodePlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        CircleAvatarPlaceholder(size = 76.dp)
        Spacer(modifier = Modifier.height(8.dp))
        ShimmerTextBlock(
            style = MaterialTheme.typography.labelMedium,
            lineWidths = listOf(0.68f),
            modifier = Modifier.width(54.dp)
        )
    }
}

@Composable
fun TopPickCard(
    character: CharacterSummary,
    isOpening: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
        ) {
            CharacterPortrait(
                name = character.name,
                avatarUrl = character.avatarUrl,
                modifier = Modifier.fillMaxSize(),
                alignment = BiasAlignment(0f, -0.8f)
            )
            if (isOpening) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape).padding(6.dp),
                    strokeWidth = 2.dp
                )
            }
        }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        lineHeight = 26.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = character.tagline.ifBlank { character.greeting },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.94f),
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
    }
}

@Composable
fun TopPickCardPlaceholder() {
    Column(modifier = Modifier.width(220.dp)) {
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(176.dp),
            shape = RoundedCornerShape(12.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)
        ) {
            ShimmerTextBlock(
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 26.sp),
                lineWidths = listOf(0.68f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            ShimmerTextBlock(
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                lineWidths = listOf(0.94f, 0.86f, 0.62f)
            )
        }
    }
}

@Composable
fun ContinueNode(chat: ConversationSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        CharacterPortrait(
            name = chat.characterName,
            avatarUrl = chat.characterAvatarUrl,
            modifier = Modifier.size(76.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = chat.characterName,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
