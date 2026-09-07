package com.example.aichat.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.*
import com.example.aichat.core.design.*
import com.example.aichat.core.ui.*
import com.example.aichat.feature.activity.ActivityRoute
import com.example.aichat.feature.character.CharacterStudioRoute
import com.example.aichat.feature.character.CharacterProfileRoute
import com.example.aichat.feature.chat.ChatRoute
import com.example.aichat.feature.chat.CharacterMemoryRoute
import com.example.aichat.feature.chatlist.ChatListRoute
import com.example.aichat.feature.home.HomeRoute
import com.example.aichat.feature.home.NewHomeRoute
import com.example.aichat.feature.home.SearchRoute
import com.example.aichat.feature.profile.*
import com.example.aichat.feature.signin.SignInRoute
import com.example.aichat.feature.ultra.UltraRoute
import com.example.aichat.feature.voice.VoiceLibraryRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed class MainDestination(
    val route: String,
    val contentDescription: String,
    val outlinedIcon: AppIconGlyph,
    val filledIcon: AppIconGlyph
) {
    data object Home : MainDestination(
        route = "home",
        contentDescription = "Home",
        outlinedIcon = AppIcons.homeNavOutline,
        filledIcon = AppIcons.homeNav
    )

    data object Discover : MainDestination(
        route = "discover",
        contentDescription = "Discover",
        outlinedIcon = AppIcons.discoverOutline,
        filledIcon = AppIcons.discover
    )

    data object Studio : MainDestination(
        route = "studio",
        contentDescription = "Create",
        outlinedIcon = AppIcons.createOutline,
        filledIcon = AppIcons.create
    )

    data object Chats : MainDestination(
        route = "chats",
        contentDescription = "Chats",
        outlinedIcon = AppIcons.chatsOutline,
        filledIcon = AppIcons.chats
    )

    data object Ultra : MainDestination(
        route = "ultra", contentDescription = "Ultra",
        outlinedIcon = AppIcons.sparkle, filledIcon = AppIcons.sparkle
    )

    data object Profile : MainDestination(
        route = "profile",
        contentDescription = "Profile",
        outlinedIcon = AppIcons.profileOutline,
        filledIcon = AppIcons.profile
    )
}

private val bottomDestinations = listOf(
    MainDestination.Home,
    MainDestination.Discover,
    MainDestination.Studio,
    MainDestination.Chats,
    MainDestination.Profile,
    MainDestination.Ultra
)

@Composable
fun AiChatApp(appViewModel: AppViewModel, notificationUri: Uri? = null, onNotificationConsumed: () -> Unit = {}) {
    val session by appViewModel.sessionState.collectAsStateWithLifecycle()
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val activeProfile = profile ?: session.profile
    val context = LocalContext.current
    LaunchedEffect(session.isSignedIn, session.isLoading) {
        if (session.isLoading) return@LaunchedEffect
        if (session.isSignedIn) com.example.aichat.feature.activity.NotificationWorker.schedule(context)
        else com.example.aichat.feature.activity.NotificationWorker.cancel(context)
    }
    when {
        session.isLoading -> LoadingScreen()
        !session.isSignedIn -> SignInRoute()
        else -> key(activeProfile?.userId) {
            MainShell(activeProfile?.userId.orEmpty(), activeProfile?.displayName.orEmpty(), activeProfile?.avatarUrl, appViewModel, notificationUri, onNotificationConsumed)
        }
    }
}

// Navigation callbacks belong to the screen that created them. A late response
// from a disposed screen or a repeated back tap must never reopen/pop another page.
private fun NavController.openFrom(source: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id != source.id || source.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route) { launchSingleTop = true }
}

private fun NavController.backFrom(source: NavBackStackEntry) {
    if (currentBackStackEntry?.id != source.id || source.lifecycle.currentState != Lifecycle.State.RESUMED) return
    if (previousBackStackEntry != null) popBackStack()
}

@Composable
private fun MainShell(ownerUserId: String, profileName: String, profileAvatarUrl: String?, appViewModel: AppViewModel, notificationUri: Uri?, onNotificationConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val conversationId = if (entry?.destination?.route == "chat/{conversationId}") entry?.arguments?.getString("conversationId") else null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(ownerUserId) {
        val prefs = context.getSharedPreferences("notification-permission", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            permissions.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(lifecycle, conversationId, ownerUserId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            appViewModel.notifications.setAppVisible(true)
            try {
                while (isActive) {
                    runCatching { appViewModel.notifications.presence(conversationId) }
                    delay(60_000)
                }
            } finally { appViewModel.notifications.setAppVisible(false) }
        }
    }
    LaunchedEffect(notificationUri) {
        notificationUri?.let { uri ->
            val id = uri.pathSegments.singleOrNull()
            val route = if (uri.scheme == "meek") when (uri.host) {
                "chat" -> id?.let { "chat/${Uri.encode(it)}" }
                "character" -> id?.let { "character-profile/${Uri.encode(it)}" }
                "profile" -> id?.let { "creator-profile/${Uri.encode(it)}" }
                "activity" -> "activity"
                else -> null
            } else null
            if (route != null) nav.navigate(route) { popUpTo("main_tabs"); launchSingleTop = true }
            onNotificationConsumed()
        }
    }
    NavHost(navController = nav, startDestination = "main_tabs") {
        composable("main_tabs") { entry ->
            MainTabs(profileName, profileAvatarUrl, onOpen = { nav.openFrom(entry, it) })
        }
        composable("create-character") { entry ->
            CharacterStudioRoute(
                paddingValues = PaddingValues(), ownerUserId = ownerUserId,
                onBack = { nav.backFrom(entry) },
                onCreated = { id ->
                    if (nav.currentBackStackEntry?.id == entry.id) {
                        nav.navigate("chat/${Uri.encode(id)}") { popUpTo("main_tabs"); launchSingleTop = true }
                    }
                }
            )
        }
        composable("chat/{conversationId}") { entry ->
            ChatRoute(
                paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) },
                onOpenMemory = { nav.openFrom(entry, "chat/${Uri.encode(entry.arguments?.getString("conversationId"))}/memory") },
                onStartNewChat = { id ->
                    if (nav.currentBackStackEntry?.id == entry.id) {
                        nav.navigate("chat/${Uri.encode(id)}") { popUpTo("main_tabs"); launchSingleTop = true }
                    }
                },
                onOpenCharacterProfile = { nav.openFrom(entry, "character-profile/${Uri.encode(it)}") },
                onOpenCreatorProfile = { nav.openFrom(entry, "creator-profile/${Uri.encode(it)}") },
                onUpgradeUltra = { nav.openFrom(entry, "ultra") }
            )
        }
        composable("character-profile/{characterId}") { entry ->
            CharacterProfileRoute(
                ownerUserId = ownerUserId, onBack = { nav.backFrom(entry) },
                onOpenConversation = { id ->
                    if (nav.currentBackStackEntry?.id == entry.id && entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        nav.navigate("chat/${Uri.encode(id)}") { popUpTo("main_tabs"); launchSingleTop = true }
                    }
                },
                onOpenCreator = { nav.openFrom(entry, "creator-profile/${Uri.encode(it)}") }
            )
        }
        composable("creator-profile/{userId}") { entry ->
            CreatorProfileRoute(onBack = { nav.backFrom(entry) }, onOpenCharacter = { nav.openFrom(entry, "character-profile/${Uri.encode(it)}") })
        }
        composable("chat/{conversationId}/memory") { entry ->
            CharacterMemoryRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) })
        }
        composable("edit-profile") { entry ->
            EditProfileRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) })
        }
        composable("search") { entry ->
            SearchRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) }, onOpenConversation = { nav.openFrom(entry, "chat/${Uri.encode(it)}") })
        }
        composable("settings") { entry ->
            SettingsRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) }, onOpenVoices = { nav.openFrom(entry, "voices") })
        }
        composable("activity") { entry ->
            ActivityRoute(
                paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) },
                onOpenConversation = { id, _ -> nav.openFrom(entry, "chat/${Uri.encode(id)}") },
                onOpenCharacter = { nav.openFrom(entry, "character-profile/${Uri.encode(it)}") },
                onOpenProfile = { nav.openFrom(entry, "creator-profile/${Uri.encode(it)}") }
            )
        }
        composable("ultra") { entry -> UltraRoute(onBack = { nav.backFrom(entry) }) }
        composable("voices") { entry -> VoiceLibraryRoute(onBack = { nav.backFrom(entry) }) }
    }
}

@Composable
private fun MainTabs(profileName: String, profileAvatarUrl: String?, onOpen: (String) -> Unit) {
    val tabs = remember { bottomDestinations.filter { it != MainDestination.Studio && it != MainDestination.Ultra } }
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val current = tabs[pager.targetPage]
    val chatListViewModel: com.example.aichat.feature.chatlist.ChatListViewModel = hiltViewModel()
    val chats by chatListViewModel.uiState.collectAsStateWithLifecycle()
    val unread = chats.conversations.sumOf { it.unreadCount }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val background = MaterialTheme.colorScheme.background
            Column {
                MainPageHeader(
                    title = current.contentDescription,
                    onOpenSearch = { onOpen("search") }, onOpenActivity = { onOpen("activity") },
                    modifier = Modifier.fillMaxWidth().background(background).statusBarsPadding()
                        .padding(horizontal = AppChrome.screenHorizontalPadding, vertical = 2.dp),
                    titlePrefix = if (current == MainDestination.Chats && unread > 0) {
                        { Badge { Text(unread.toString()) } }
                    } else null
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.height(AppChrome.bottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            ) {
                bottomDestinations.forEach { destination ->
                    val selected = current == destination
                    NavigationBarItem(
                        selected = selected, alwaysShowLabel = false,
                        onClick = {
                            when (destination) {
                                MainDestination.Studio -> onOpen("create-character")
                                MainDestination.Ultra -> onOpen("ultra")
                                else -> scope.launch { pager.animateScrollToPage(tabs.indexOf(destination)) }
                            }
                        },
                        icon = {
                            if (destination == MainDestination.Profile) {
                                CircleAvatar(profileName.ifBlank { "User" }, profileAvatarUrl,
                                    Modifier.size(AppChrome.bottomBarIconSize).then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
                            } else {
                                AppIcon(if (selected) destination.filledIcon else destination.outlinedIcon,
                                    contentDescription = destination.contentDescription, size = AppChrome.bottomBarIconSize)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surface,
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { tabs[it].route }) { page ->
            val openChat: (String) -> Unit = { if (pager.settledPage == page) onOpen("chat/${Uri.encode(it)}") }
            when (tabs[page]) {
                MainDestination.Home -> NewHomeRoute(paddingValues = padding, onOpenConversation = openChat,
                    onOpenStudio = { onOpen("create-character") }, onOpenChats = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MainDestination.Chats)) } })
                MainDestination.Discover -> HomeRoute(paddingValues = padding, onOpenActivity = { onOpen("activity") }, onOpenConversation = openChat)
                MainDestination.Chats -> ChatListRoute(paddingValues = padding, onOpenConversation = openChat)
                MainDestination.Profile -> ProfileRoute(paddingValues = padding, onOpenActivity = { onOpen("activity") },
                    onOpenConversation = openChat, onOpenEditProfile = { onOpen("edit-profile") },
                    onOpenSettings = { onOpen("settings") }, onUpgradeUltra = { onOpen("ultra") })
                else -> Unit
            }
        }
    }
}
