package com.example.aichat.feature.character

import com.example.aichat.core.ui.DelayedCircularProgressIndicator as CircularProgressIndicator

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.design.PrimaryButton
import com.example.aichat.core.design.SelectionButton
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterCountBadge
import com.example.aichat.core.ui.ExpandableText
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class CharacterProfileUiState(
    val character: CharacterSummary? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class CharacterProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {
    private val characterId: String = checkNotNull(savedStateHandle["characterId"])
    private val isLoading = MutableStateFlow(true)
    private val _events = MutableSharedFlow<String>()
    private var openChatJob: Job? = null
    val events = _events.asSharedFlow()

    val uiState: StateFlow<CharacterProfileUiState> = combine(
        characterRepository.observeCharacter(characterId),
        isLoading
    ) { character, loading ->
        CharacterProfileUiState(
            character = character,
            isLoading = loading && character == null
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CharacterProfileUiState()
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            characterRepository.refreshCharacter(characterId)
                .onFailure {
                    if (uiState.value.character == null) {
                        _events.emit(it.userFacingMessage("Couldn't load character."))
                    }
                }
            isLoading.value = false
        }
    }

    fun toggleLike() {
        viewModelScope.launch {
            characterRepository.toggleLike(characterId)
                .onFailure { _events.emit(it.userFacingMessage("Couldn't update like.")) }
        }
    }

    fun openChat(ownerUserId: String, onReady: (String) -> Unit) {
        if (ownerUserId.isBlank()) {
            viewModelScope.launch {
                _events.emit("Couldn't open chat. Your profile is still loading.")
            }
            return
        }
        if (openChatJob?.isActive == true) return

        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                conversationRepository.ensureConversation(ownerUserId, characterId)
                    .onSuccess(onReady)
                    .onFailure { _events.emit(it.userFacingMessage("Couldn't open chat.")) }
            } finally {
                if (openChatJob === job) {
                    openChatJob = null
                }
            }
        }
        openChatJob = job
        job.start()
    }
}

@Composable
fun CharacterProfileRoute(
    ownerUserId: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenCreator: (String) -> Unit,
    onShare: ((CharacterSummary) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    viewModel: CharacterProfileViewModel = hiltViewModel(),
    onEditCharacter: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareCharacter = onShare ?: context::shareCharacter

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
        CharacterProfileContent(
            state = state,
            onBack = onBack,
            onShare = shareCharacter,
            onChat = { viewModel.openChat(ownerUserId, onOpenConversation) },
            onOpenCreator = onOpenCreator,
            onToggleLike = viewModel::toggleLike,
            onRetry = viewModel::refresh,
            onEdit = state.character?.takeIf { it.ownerUserId == ownerUserId }?.let { character -> { onEditCharacter(character.id) } }
        )
    }
}

@Composable
internal fun CharacterProfileContent(
    state: CharacterProfileUiState,
    onBack: () -> Unit,
    onShare: (CharacterSummary) -> Unit,
    onChat: (String) -> Unit,
    onOpenCreator: (String) -> Unit,
    onToggleLike: () -> Unit,
    onRetry: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    val character = state.character
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                start = AppChrome.screenHorizontalPadding,
                end = AppChrome.screenHorizontalPadding,
                bottom = AppChrome.screenBottomPadding
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBackButton(onClick = onBack)
            Spacer(modifier = Modifier.weight(1f))
            if (onEdit != null) androidx.compose.material3.TextButton(onClick = onEdit) { Text("Edit") }
            IconButton(
                enabled = character != null,
                onClick = { character?.let(onShare) }
            ) {
                AppIcon(
                    icon = AppIcons.share,
                    contentDescription = "Share character",
                    size = AppChrome.headerActionIconSize
                )
            }
        }

        when {
            character != null -> CharacterProfileBody(
                character = character,
                onChat = onChat,
                onOpenCreator = onOpenCreator,
                onToggleLike = onToggleLike
            )

            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing)
            ) {
                Text(
                    text = "Character unavailable.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrimaryButton(text = "Retry", onClick = onRetry)
            }
        }
    }
}

private fun Context.shareCharacter(character: CharacterSummary) {
    val author = character.authorUsername.ifBlank { "creator" }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "Check out ${character.name} by @$author on Meek."
        )
    }
    startActivity(Intent.createChooser(intent, "Share character"))
}

@Composable
private fun CharacterProfileBody(
    character: CharacterSummary,
    onChat: (String) -> Unit,
    onOpenCreator: (String) -> Unit,
    onToggleLike: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CharacterPortrait(
            name = character.name,
            avatarUrl = character.avatarUrl,
            modifier = Modifier.size(132.dp)
        )
        Text(
            text = character.name,
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "By @${character.authorUsername.ifBlank { "creator" }}",
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { onOpenCreator(character.ownerUserId) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
        ) {
            CharacterCountBadge(
                icon = AppIcons.chats,
                count = character.publicChatCount,
                label = "interactions",
                containerColor = Color.Transparent
            )
            CharacterCountBadge(
                icon = AppIcons.likedFilled,
                count = character.likeCount,
                label = "likes",
                containerColor = Color.Transparent
            )
        }

        if (character.bio.isNotBlank()) {
            ExpandableText(
                text = character.bio,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppChrome.sectionSpacing),
                collapsedMaxLines = 2
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(
                text = "Chat",
                modifier = Modifier.weight(1f),
                leadingIcon = {
                    AppIcon(AppIcons.chatsOutline, contentDescription = null)
                },
                onClick = { onChat(character.id) }
            )
            SelectionButton(
                text = if (character.likedByMe) "Liked" else "Like",
                selected = character.likedByMe,
                modifier = Modifier.weight(1f),
                height = 54.dp,
                leadingIcon = {
                    AppIcon(
                        icon = if (character.likedByMe) AppIcons.likedFilled else AppIcons.liked,
                        contentDescription = null
                    )
                },
                onClick = onToggleLike
            )
        }
    }
}
