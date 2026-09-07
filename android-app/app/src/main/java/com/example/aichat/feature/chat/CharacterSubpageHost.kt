package com.example.aichat.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.design.DraggableSubpage
import com.example.aichat.core.design.IconPillButton
import com.example.aichat.core.design.PrimaryButton
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.CharacterCountBadge
import com.example.aichat.core.ui.ProfileDateStat
import com.example.aichat.core.util.formatRelativeTimeAgo
import com.example.aichat.feature.character.CharacterProfileUiState
import com.example.aichat.feature.character.CharacterProfileViewModel

private const val CharacterIdArgument = "characterId"
private const val DetailsRoute = "details/{$CharacterIdArgument}"

@Composable
fun CharacterSubpageHost(
    characterId: String,
    onDismissRequest: () -> Unit,
    onViewCharacterProfile: (String) -> Unit,
    onViewCreatorProfile: (String) -> Unit,
    onRefreshChat: () -> Unit,
    onStartNewChat: () -> Unit,
    onChatPreferences: () -> Unit = {},
    onOpenPersonas: () -> Unit = {},
    onError: (String) -> Unit = {},
    onShareCharacter: ((CharacterSummary) -> Unit)? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val shareCharacter = onShareCharacter ?: { character ->
        context.shareCharacter(character)
    }

    fun openCharacterProfile(targetCharacterId: String) {
        if (targetCharacterId.isBlank()) {
            onError("This character profile isn't available yet.")
            return
        }
        onDismissRequest()
        onViewCharacterProfile(targetCharacterId)
    }

    fun openCreatorProfile(targetUserId: String) {
        if (targetUserId.isBlank()) {
            onError("This creator profile isn't available yet.")
            return
        }
        onDismissRequest()
        onViewCreatorProfile(targetUserId)
    }

    DraggableSubpage(onDismissRequest = onDismissRequest) {
        NavHost(
            navController = navController,
            startDestination = "details/${Uri.encode(characterId)}",
            modifier = Modifier.fillMaxSize()
        ) {
            composable(
                route = DetailsRoute,
                arguments = listOf(
                    navArgument(CharacterIdArgument) { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val viewModel: CharacterProfileViewModel = hiltViewModel(backStackEntry)
                CharacterDetailsRoute(
                    onClose = onDismissRequest,
                    onViewCharacter = ::openCharacterProfile,
                    onViewCreator = ::openCreatorProfile,
                    onShare = shareCharacter,
                    onRefreshChat = {
                        onDismissRequest()
                        onRefreshChat()
                    },
                    onChatPreferences = { onDismissRequest(); onChatPreferences() },
                    onOpenPersonas = { onDismissRequest(); onOpenPersonas() },
                    onStartNewChat = {
                        onDismissRequest()
                        onStartNewChat()
                    },
                    onError = onError,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun CharacterDetailsRoute(
    onClose: () -> Unit,
    onViewCharacter: (String) -> Unit,
    onViewCreator: (String) -> Unit,
    onShare: (CharacterSummary) -> Unit,
    onRefreshChat: () -> Unit,
    onStartNewChat: () -> Unit,
    onChatPreferences: () -> Unit = {},
    onOpenPersonas: () -> Unit = {},
    onError: (String) -> Unit,
    viewModel: CharacterProfileViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect(onError)
    }

    CharacterDetailsContent(
        state = state,
        onClose = onClose,
        onViewCharacter = onViewCharacter,
        onViewCreator = onViewCreator,
        onShare = onShare,
        onToggleLike = viewModel::toggleLike,
        onRefreshChat = onRefreshChat,
        onStartNewChat = onStartNewChat,
        onChatPreferences = onChatPreferences,
        onOpenPersonas = onOpenPersonas,
        onRetry = viewModel::refresh
    )
}

@Composable
internal fun CharacterDetailsContent(
    state: CharacterProfileUiState,
    onClose: () -> Unit,
    onViewCharacter: (String) -> Unit,
    onViewCreator: (String) -> Unit,
    onShare: (CharacterSummary) -> Unit,
    onToggleLike: () -> Unit,
    onRefreshChat: () -> Unit,
    onStartNewChat: () -> Unit,
    onChatPreferences: () -> Unit = {},
    onOpenPersonas: () -> Unit = {},
    onRetry: () -> Unit
) {
    val character = state.character
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text(
                text = "Character Details",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onClose) {
                AppIcon(
                    icon = AppIcons.close,
                    contentDescription = "Close character details",
                    size = AppChrome.headerActionIconSize
                )
            }
        }

        when {
            character != null -> CharacterDetailsBody(
                character = character,
                onViewCharacter = onViewCharacter,
                onViewCreator = onViewCreator,
                onShare = onShare,
                onToggleLike = onToggleLike,
                onRefreshChat = onRefreshChat,
                onStartNewChat = onStartNewChat,
                onChatPreferences = onChatPreferences,
                onOpenPersonas = onOpenPersonas
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

@Composable
private fun CharacterDetailsBody(
    character: CharacterSummary,
    onViewCharacter: (String) -> Unit,
    onViewCreator: (String) -> Unit,
    onShare: (CharacterSummary) -> Unit,
    onToggleLike: () -> Unit,
    onRefreshChat: () -> Unit,
    onStartNewChat: () -> Unit,
    onChatPreferences: () -> Unit = {},
    onOpenPersonas: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CharacterPortrait(
            name = character.name,
            avatarUrl = character.avatarUrl,
            modifier = Modifier.size(104.dp)
        )
        Text(
            text = character.name,
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
        ) {
            CharacterCountBadge(
                icon = AppIcons.chats,
                count = character.publicChatCount,
                label = "interactions"
            )
            CharacterCountBadge(
                icon = AppIcons.likedFilled,
                count = character.likeCount,
                label = "likes"
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppChrome.sectionSpacing),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileDateStat(
                label = "Created",
                value = formatRelativeTimeAgo(character.createdAt),
                modifier = Modifier.weight(1f)
            )
            ProfileDateStat(
                label = "Last Updated",
                value = formatRelativeTimeAgo(character.updatedAt),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
        ) {
            IconPillButton(
                text = if (character.likedByMe) "Liked" else "Like",
                selected = character.likedByMe,
                onClick = onToggleLike,
                leadingIcon = {
                    AppIcon(
                        icon = if (character.likedByMe) AppIcons.likedFilled else AppIcons.liked,
                        contentDescription = null,
                        size = 20.dp
                    )
                }
            )
            IconPillButton(
                text = "Share",
                onClick = { onShare(character) },
                leadingIcon = {
                    AppIcon(AppIcons.share, contentDescription = null, size = 20.dp)
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppChrome.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap)
        ) {
            SecondaryButton(
                text = "View Character Profile",
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    AppIcon(AppIcons.profileOutline, contentDescription = null)
                },
                onClick = { onViewCharacter(character.id) }
            )
            SecondaryButton(
                text = "View Creator Profile",
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    AppIcon(AppIcons.profile, contentDescription = null)
                },
                onClick = { onViewCreator(character.ownerUserId) }
            )
            SecondaryButton(text = "Persona", modifier = Modifier.fillMaxWidth(), onClick = onOpenPersonas)
            SecondaryButton(text = "Chat preferences", modifier = Modifier.fillMaxWidth(), onClick = onChatPreferences)
            SecondaryButton(
                text = "Refresh this chat",
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    AppIcon(AppIcons.refresh, contentDescription = null)
                },
                onClick = onRefreshChat
            )
            SecondaryButton(
                text = "Start new chat",
                modifier = Modifier.fillMaxWidth(),
                contentColor = Color(0xFF3485F6),
                leadingIcon = {
                    AppIcon(AppIcons.newChat, contentDescription = null)
                },
                onClick = onStartNewChat
            )
        }
    }
}

private fun Context.shareCharacter(character: CharacterSummary) {
    val author = character.authorUsername.ifBlank { "creator" }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "Check out ${character.name} by @$author on Meek."
        )
    }
    startActivity(Intent.createChooser(shareIntent, "Share character"))
}
