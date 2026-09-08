package com.example.aichat.feature.profile

import com.example.aichat.feature.customization.AppearanceRepository
import com.example.aichat.feature.customization.ShowcaseDto
import com.example.aichat.feature.customization.AppearanceProfileHeader
import com.example.aichat.feature.customization.ShowcaseWidgets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.feature.activity.NotificationRepository
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.PublicProfile
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.design.PrimaryButton
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterSummaryCard
import com.example.aichat.core.ui.CharacterSummaryCardPlaceholder
import com.example.aichat.core.ui.ProfileCountStat
import com.example.aichat.core.ui.ProfileHeader
import com.example.aichat.core.ui.ProfileHeaderPlaceholder
import com.example.aichat.core.ui.ScreenBackgroundBox
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class CreatorProfileUiState(
    val profile: PublicProfile? = null,
    val showcase: ShowcaseDto? = null,
    val characters: List<CharacterSummary> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isOwnProfile: Boolean = false,
    val following: Boolean = false,
    val followerCount: Int? = null,
    val followingCount: Int? = null,
    val isFollowLoading: Boolean = true,
    val isChangingFollow: Boolean = false
)

@HiltViewModel
class CreatorProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CreatorProfileRepository,
    private val notificationRepository: NotificationRepository,
    private val appearanceRepository: AppearanceRepository,
    authRepository: AuthRepository
) : ViewModel() {
    private val userId: String = checkNotNull(savedStateHandle["userId"])
    private val isOwnProfile = userId == authRepository.sessionState.value.profile?.userId
    private val _uiState = MutableStateFlow(CreatorProfileUiState(isOwnProfile = isOwnProfile, showcase = appearanceRepository.cachedShowcase(userId)))
    private var refreshJob: Job? = null
    val uiState: StateFlow<CreatorProfileUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(isLoading = true, isFollowLoading = true)
        refreshJob = viewModelScope.launch {
            val showcaseRequest = async {
                try { _uiState.value = _uiState.value.copy(showcase = appearanceRepository.showcase(userId)) }
                catch(error: Exception) { if(error is CancellationException) throw error }
            }
            launch {
                try {
                    val follow = notificationRepository.followState(userId)
                    _uiState.value = _uiState.value.copy(following = follow.following, followerCount = follow.followerCount, followingCount = follow.followingCount)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                } finally {
                    _uiState.value = _uiState.value.copy(isFollowLoading = false)
                }
            }
            coroutineScope {
                val profileRequest = async { runCatching { repository.getProfile(userId) } }
                val charactersRequest = async { runCatching { repository.getCharacters(userId) } }
                val profileResult = profileRequest.await()
                val charactersResult = charactersRequest.await()
                showcaseRequest.await()
                _uiState.value = _uiState.value.copy(
                    profile = profileResult.getOrNull() ?: _uiState.value.profile,
                    characters = charactersResult.getOrNull()?.items ?: _uiState.value.characters,
                    nextCursor = if (charactersResult.isSuccess) charactersResult.getOrNull()?.nextCursor else _uiState.value.nextCursor,
                    isLoading = false
                )
                val error = profileResult.exceptionOrNull() ?: charactersResult.exceptionOrNull()
                if (error is CancellationException) throw error
                error?.let { _events.emit(it.userFacingMessage("Couldn't load creator profile.")) }
            }
        }
    }

    fun toggleFollow() {
        val previous = _uiState.value
        if (previous.isOwnProfile || previous.isFollowLoading || previous.isChangingFollow) return
        val following = !previous.following
        _uiState.value = previous.copy(
            following = following,
            followerCount = previous.followerCount?.let { (it + if (following) 1 else -1).coerceAtLeast(0) },
            isChangingFollow = true
        )
        viewModelScope.launch {
            try {
                val result = notificationRepository.setFollow(userId, following)
                _uiState.value = _uiState.value.copy(following = result.following, followerCount = result.followerCount, followingCount = result.followingCount)
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(following = previous.following, followerCount = previous.followerCount)
                if (error is CancellationException) throw error
                _events.emit(error.userFacingMessage("Couldn't update following."))
            } finally {
                _uiState.value = _uiState.value.copy(isChangingFollow = false)
            }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (_uiState.value.isLoadingMore) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            runCatching { repository.getCharacters(userId, cursor) }
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        characters = (_uiState.value.characters + page.items).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                        isLoadingMore = false
                    )
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                    _events.emit(it.userFacingMessage("Couldn't load more characters."))
                }
        }
    }
}

@Composable
fun CreatorProfileRoute(
    onBack: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onError: ((String) -> Unit)? = null,
    viewModel: CreatorProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, onError) {
        viewModel.events.collect { message ->
            if (onError != null) {
                onError(message)
            } else {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    ScreenBackgroundBox(snackbarHostState = snackbarHostState.takeIf { onError == null }) {
        state.showcase?.appearance?.let { com.example.aichat.feature.customization.ProfileBackdrop(it, Modifier.fillMaxSize()) }
        CreatorProfileContent(
            state = state,
            onBack = onBack,
            onOpenCharacter = onOpenCharacter,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::refresh,
            onToggleFollow = viewModel::toggleFollow
        )
    }
}

@Composable
internal fun CreatorProfileContent(
    state: CreatorProfileUiState,
    onBack: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = AppChrome.screenHorizontalPadding,
            end = AppChrome.screenHorizontalPadding,
            bottom = AppChrome.screenBottomPadding
        ),
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
                Text(
                    text = "Creator Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            val profile = state.profile
            if (profile != null) {
                AppearanceProfileHeader(
                    appearance = state.showcase?.appearance ?: com.example.aichat.feature.customization.AppearanceDto(),
                    name = profile.displayName,
                    avatarUrl = profile.avatarUrl,
                    stats = listOf(
                        ProfileCountStat(profile.characterCount, "characters"),
                        ProfileCountStat(profile.interactionCount, "interactions"),
                        ProfileCountStat(profile.likeCount, "likes")
                    )
                )
            } else if (state.isLoading) {
                ProfileHeaderPlaceholder()
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Creator profile unavailable.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PrimaryButton(
                        text = "Retry",
                        onClick = onRetry
                    )
                }
            }
        }

        state.showcase?.let { published ->
            item(span = { GridItemSpan(maxLineSpan) }) { ShowcaseWidgets(published, onCharacter = onOpenCharacter) }
        }
        if (state.profile != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.followerCount?.let {
                            val followers = if (it == 1) "1 follower" else "$it followers"
                            "$followers · ${state.followingCount ?: 0} following"
                        } ?: "Followers",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!state.isOwnProfile) {
                        PrimaryButton(
                            text = when {
                                state.isChangingFollow -> "Updating…"
                                state.isFollowLoading -> "Loading…"
                                state.following -> "Following"
                                else -> "Follow"
                            },
                            enabled = !state.isFollowLoading && !state.isChangingFollow,
                            onClick = onToggleFollow
                        )
                    }
                }
            }
        }

        state.profile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Characters",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (state.isLoading && state.characters.isEmpty()) {
            items(6) {
                CharacterSummaryCardPlaceholder(modifier = Modifier.fillMaxWidth())
            }
        } else if (state.characters.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No public characters yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.characters, key = { it.id }) { character ->
            CharacterSummaryCard(
                character = character,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenCharacter(character.id) }
            )
        }

        if (state.nextCursor != null && !state.isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(state.nextCursor) {
                    onLoadMore()
                }
            }
        }

        if (state.isLoadingMore) {
            items(2) {
                CharacterSummaryCardPlaceholder(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
