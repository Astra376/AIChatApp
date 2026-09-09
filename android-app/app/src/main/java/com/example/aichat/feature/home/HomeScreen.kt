package com.example.aichat.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterSummaryCardPlaceholder
import com.example.aichat.core.ui.CharacterSummaryCard
import com.example.aichat.core.ui.rememberCharacterChatLauncher
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.screenContentPadding
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class HomeUiState(
    val feed: List<CharacterSummary> = emptyList(),
    val feedCursor: String? = null,
    val isFeedLoading: Boolean = true,
    val errorMessage: String? = null,
    val currentUserId: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val homeRepository: HomeRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {
    private val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
    private val _uiState = MutableStateFlow(HomeUiState(currentUserId = userId))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    init {
        refreshFeed()
    }

    fun refreshFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFeedLoading = true, errorMessage = null)
            runCatching { homeRepository.loadFeed(cursor = null) }
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        feed = page.items,
                        feedCursor = page.nextCursor,
                        isFeedLoading = false
                    )
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
        val state = _uiState.value
        if (state.isFeedLoading || state.feedCursor == null) return
        _uiState.value = state.copy(isFeedLoading = true)
        viewModelScope.launch {
            try {
                val page = homeRepository.loadFeed(cursor = state.feedCursor)
                _uiState.value = _uiState.value.copy(
                    feed = (state.feed + page.items).distinctBy { it.id },
                    feedCursor = page.nextCursor
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
        return conversationRepository.ensureConversation(userId, characterId)
    }
}

@Composable
fun HomeRoute(
    paddingValues: PaddingValues,
    onOpenSearch: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    onOpenConversation: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)
        ) {

            if (state.isFeedLoading && state.feed.isEmpty()) {
                items(8) {
                    CharacterSummaryCardPlaceholder(modifier = Modifier.fillMaxWidth(), imageAspectRatio = 1f)
                }
            }

            if (state.errorMessage != null && state.feed.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = state.errorMessage ?: "Failed to load the feed.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            items(state.feed, key = { it.id }) { character ->
                CharacterSummaryCard(
                    character = character,
                    isOpening = chatLauncher.openingCharacterId == character.id,
                    enabled = chatLauncher.openingCharacterId == null,
                    modifier = Modifier.fillMaxWidth(),
                    imageAspectRatio = 1f
                ) {
                    chatLauncher.open(character.id)
                }
            }
            if (state.feedCursor != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SecondaryButton(
                        text = if (state.isFeedLoading) "Loading..." else "Load More",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isFeedLoading,
                        onClick = viewModel::loadMore
                    )
                }
            }
        }
    }
}
