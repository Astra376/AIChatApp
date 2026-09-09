package com.example.aichat.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.AppTextField
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.core.model.CharacterSummary
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val trendingSearches: List<String> = emptyList(),
    val results: List<CharacterSummary> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val currentUserId: String = ""
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val homeRepository: HomeRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {
    private val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
    private val _uiState = MutableStateFlow(SearchUiState(currentUserId = userId))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()
    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            runCatching { homeRepository.trendingSearches() }
                .onSuccess { queries ->
                    _uiState.value = _uiState.value.copy(trendingSearches = queries.distinct().take(10))
                }
        }
        viewModelScope.launch {
            queryFlow.debounce(220).collectLatest { query ->
                if (query.isBlank()) {
                    _uiState.value = _uiState.value.copy(results = emptyList(), nextCursor = null, isLoading = false)
                } else {
                    search(reset = true)
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
        queryFlow.value = value
    }

    fun loadMore() {
        if (_uiState.value.isLoading || _uiState.value.nextCursor == null) return
        viewModelScope.launch { search(reset = false) }
    }

    suspend fun ensureConversation(characterId: String): Result<String> {
        return conversationRepository.ensureConversation(userId, characterId)
    }

    private suspend fun search(reset: Boolean) {
        val state = _uiState.value
        if (state.query.isBlank()) return
        _uiState.value = state.copy(isLoading = true)
        runCatching {
            homeRepository.search(
                query = state.query,
                cursor = if (reset) null else state.nextCursor
            )
        }.onSuccess { page ->
            if (_uiState.value.query != state.query) return@onSuccess
            _uiState.value = _uiState.value.copy(
                results = (if (reset) page.items else state.results + page.items).distinctBy { it.id },
                nextCursor = page.nextCursor,
                isLoading = false
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (_uiState.value.query != state.query) return@onFailure
            _uiState.value = _uiState.value.copy(isLoading = false)
            _events.emit("Search failed.")
        }
    }
}

@Composable
fun SearchRoute(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val chatLauncher = rememberCharacterChatLauncher(
        ensureConversation = viewModel::ensureConversation,
        onOpenConversation = onOpenConversation,
        snackbarHostState = snackbarHostState
    )
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ScreenBackgroundBox(
        snackbarHostState = snackbarHostState,
        modifier = Modifier.imePadding()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
                ) {
                    AppBackButton(onClick = onBack)
                    AppTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "Search",
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        shape = RoundedCornerShape(999.dp),
                        leadingIcon = {
                            AppIcon(AppIcons.search, contentDescription = null)
                        }
                    )
                }
            }
            if (state.query.isBlank() && state.trendingSearches.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Trending searches", style = MaterialTheme.typography.titleLarge)
                }
                items(state.trendingSearches.size, span = { GridItemSpan(maxLineSpan) }) { index ->
                    val query = state.trendingSearches[index]
                    val rankColor = when (index) {
                        0 -> Color(0xFFF16B6B)
                        1 -> Color(0xFFF3AA55)
                        2 -> Color(0xFF70CB92)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.onQueryChange(query) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            modifier = Modifier.width(24.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = rankColor
                        )
                        Text(
                            text = query,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = rankColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = com.github.yohannestz.iconsax_compose.iconsax.Iconsax.Linear.TrendUp,
                            contentDescription = null,
                            tint = rankColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (state.query.isNotBlank() && !state.isLoading && state.results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "No Characters Found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.query.isNotBlank() && state.isLoading && state.results.isEmpty()) {
                items(8) {
                    CharacterSummaryCardPlaceholder(modifier = Modifier.fillMaxWidth())
                }
            }
            items(state.results, key = { it.id }) { character ->
                CharacterSummaryCard(
                    character = character,
                    isOpening = chatLauncher.openingCharacterId == character.id,
                    enabled = chatLauncher.openingCharacterId == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    chatLauncher.open(character.id)
                }
            }
            if (state.nextCursor != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SecondaryButton(
                        text = if (state.isLoading) "Loading..." else "Load More",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading,
                        onClick = viewModel::loadMore
                    )
                }
            }
        }
    }
}
