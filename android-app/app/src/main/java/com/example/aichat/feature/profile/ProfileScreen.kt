package com.example.aichat.feature.profile

import com.example.aichat.feature.customization.AppearanceRepository
import com.example.aichat.feature.customization.ShowcaseDto
import com.example.aichat.feature.customization.AppearanceProfileHeader
import com.example.aichat.feature.customization.ShowcaseWidgets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.feature.activity.FollowStateDto
import com.example.aichat.feature.activity.NotificationRepository
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.IconCircleButton
import com.example.aichat.core.design.IconPillButton
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterSummaryCardPlaceholder
import com.example.aichat.core.ui.CharacterSummaryCard
import com.example.aichat.core.ui.rememberCharacterChatLauncher
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.ProfileCountStat
import com.example.aichat.core.ui.ProfileHeader
import com.example.aichat.core.ui.ProfileHeaderPlaceholder
import com.example.aichat.core.ui.screenContentPadding
import com.example.aichat.feature.character.CharacterRepository
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProfileSection {
    OWNED,
    LIKED,
    RECENT,
    INTERACTED
}

data class ProfileUiState(
    val displayName: String = "",
    val avatarUrl: String? = null,
    val bio: String? = null,
    val userId: String = "",
    val owned: List<CharacterSummary> = emptyList(),
    val liked: List<CharacterSummary> = emptyList(),
    val recent: List<CharacterSummary> = emptyList(),
    val interacted: List<CharacterSummary> = emptyList(),
    val isLoading: Boolean = true,
    val followerCount: Int? = null,
    val followingCount: Int? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val notificationRepository: NotificationRepository,
    private val appearanceRepository: AppearanceRepository
) : ViewModel() {
    private val userId = authRepository.sessionState.value.profile?.userId.orEmpty()
    private val isLoading = MutableStateFlow(true)
    private val followState = MutableStateFlow<FollowStateDto?>(null)
    val showcase = MutableStateFlow(appearanceRepository.cachedShowcase(userId))
    private var followRefresh: kotlinx.coroutines.Job? = null

    val uiState: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        characterRepository.observeOwnedCharacters(userId),
        characterRepository.observeLikedCharacters(),
        conversationRepository.observeConversations(userId),
        combine(isLoading, followState) { loading, social -> loading to social }
    ) { profile, owned, liked, conversations, loadingState ->
        val (loading, social) = loadingState
        // For Recent and Interacted, we need to map conversations back to CharacterSummary.
        // We'll use the ones we already have in owned/liked or fetch missing ones if possible.
        // For simplicity in this UI refactor, we'll build the list from available data.
        val charMap = (owned + liked).associateBy { it.id }.toMutableMap()
        
        val recentChars = conversations
            .sortedByDescending { it.lastMessageAt ?: it.updatedAt }
            .mapNotNull { conv ->
                charMap[conv.characterId] ?: CharacterSummary(
                    id = conv.characterId,
                    ownerUserId = "",
                    name = conv.characterName,
                    tagline = "",
                    greeting = "",
                    bio = "",
                    systemPrompt = "",
                    visibility = com.example.aichat.core.model.CharacterVisibility.PUBLIC,
                    avatarUrl = conv.characterAvatarUrl,
                    publicChatCount = 0,
                    likeCount = 0,
                    likedByMe = false,
                    lastActiveAt = conv.lastMessageAt ?: conv.updatedAt,
                    createdAt = conv.startedAt,
                    updatedAt = conv.updatedAt
                )
            }
            .distinctBy { it.id }

        ProfileUiState(
            displayName = profile?.displayName.orEmpty(),
            avatarUrl = profile?.avatarUrl,
            bio = profile?.bio,
            userId = userId,
            owned = owned,
            liked = liked,
            recent = recentChars,
            interacted = recentChars, // Temporary proxy until message count is implemented
            isLoading = loading,
            followerCount = social?.followerCount,
            followingCount = social?.followingCount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState(displayName = authRepository.sessionState.value.profile?.displayName.orEmpty(), avatarUrl = authRepository.sessionState.value.profile?.avatarUrl, userId = userId)
    )

    init {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { characterRepository.refreshOwnedCharacters() }
                    launch { characterRepository.refreshLikedCharacters() }
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    fun refreshFollowCounts() {
        if (followRefresh?.isActive == true) return
        followRefresh = viewModelScope.launch {
            launch {
                try { showcase.value = appearanceRepository.showcase(userId) } catch(error: Exception) { if(error is kotlinx.coroutines.CancellationException) throw error }
            }
            try {
                followState.value = notificationRepository.followState(userId)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    suspend fun ensureConversation(characterId: String): Result<String> {
        return conversationRepository.ensureConversation(userId, characterId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(
    paddingValues: PaddingValues,
    onOpenSearch: () -> Unit = {},
    onOpenActivity: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpgradeUltra: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showcase by viewModel.showcase.collectAsStateWithLifecycle()
    val appearance = com.example.aichat.feature.customization.LocalAppearance.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val chatLauncher = rememberCharacterChatLauncher(
        ensureConversation = viewModel::ensureConversation,
        onOpenConversation = onOpenConversation,
        snackbarHostState = snackbarHostState
    )
    var section by remember { mutableStateOf(ProfileSection.OWNED) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshFollowCounts()
        onPauseOrDispose { }
    }

    ScreenBackgroundBox(snackbarHostState = snackbarHostState) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)
        ) {

            item(span = { GridItemSpan(maxLineSpan) }) {
                if (state.displayName.isBlank()) {
                    ProfileHeaderPlaceholder()
                } else {
                    AppearanceProfileHeader(
                        appearance = appearance,
                        name = state.displayName,
                        avatarUrl = state.avatarUrl,
                        stats = buildList {
                            add(ProfileCountStat(state.owned.size, "characters"))
                            state.followerCount?.let { add(ProfileCountStat(it, "followers")) }
                            state.followingCount?.let { add(ProfileCountStat(it, "following")) }
                        }
                    )
                }
            }
            showcase?.takeIf { appearance.ultra }?.let { published ->
                item(span = { GridItemSpan(maxLineSpan) }) { ShowcaseWidgets(published, onCharacter = { chatLauncher.open(it) }) }
            }
            state.bio?.takeIf { it.isNotBlank() }?.let { bio ->
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
                ) {
                    IconPillButton(
                        text = "Edit Profile",
                        onClick = onOpenEditProfile,
                        modifier = Modifier.weight(1f)
                    )
                    IconPillButton(
                        text = "Share Profile",
                        onClick = {
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "${state.displayName} on Meek\nmeek://profile/${state.userId}")
                            }
                            context.startActivity(android.content.Intent.createChooser(share, "Share profile"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconCircleButton(onClick = onOpenSettings) {
                        AppIcon(AppIcons.settings, contentDescription = "Settings")
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                IconPillButton(text = "Customize profile & appearance", onClick = onOpenAppearance, modifier = Modifier.fillMaxWidth())
            }
            if (!appearance.ultra) item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    onClick = onUpgradeUltra,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Meek Ultra", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Explore premium models", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Upgrade", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SecondaryTabRow(
                    selectedTabIndex = ProfileSection.entries.indexOf(section),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(ProfileSection.entries.indexOf(section)),
                            color = MaterialTheme.colorScheme.primary,
                            height = 2.dp
                        )
                    },
                    divider = {}
                ) {
                    ProfileSection.entries.forEach { s ->
                        val isSelected = section == s
                        val icon = when (s) {
                            ProfileSection.OWNED -> if (isSelected) AppIcons.createdFilled else AppIcons.created
                            ProfileSection.LIKED -> if (isSelected) AppIcons.likedFilled else AppIcons.liked
                            ProfileSection.RECENT -> AppIcons.activity // No bold version available
                            ProfileSection.INTERACTED -> if (isSelected) AppIcons.chats else AppIcons.chatsOutline
                        }
                        Tab(
                            selected = isSelected,
                            onClick = { section = s },
                            selectedContentColor = MaterialTheme.colorScheme.onBackground,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = {
                                AppIcon(icon = icon, contentDescription = s.name, size = 24.dp)
                            }
                        )
                    }
                }
            }
            val characters = when (section) {
                ProfileSection.OWNED -> state.owned
                ProfileSection.LIKED -> state.liked
                ProfileSection.RECENT -> state.recent
                ProfileSection.INTERACTED -> state.interacted
            }
            if (state.isLoading && characters.isEmpty()) {
                items(6) {
                    CharacterSummaryCardPlaceholder(modifier = Modifier.fillMaxWidth())
                }
            } else if (characters.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = when (section) {
                            ProfileSection.OWNED -> "No Characters yet."
                            ProfileSection.LIKED -> "No liked characters yet."
                            ProfileSection.RECENT -> "No Recent Activity."
                            ProfileSection.INTERACTED -> "No Interacted characters."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(characters, key = { it.id }) { character ->
                CharacterSummaryCard(
                    character = character,
                    isOpening = chatLauncher.openingCharacterId == character.id,
                    enabled = chatLauncher.openingCharacterId == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    chatLauncher.open(character.id)
                }
            }
        }
    }
}
