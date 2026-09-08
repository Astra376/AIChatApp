package com.example.aichat.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.example.aichat.core.ui.rememberScrollChrome
import com.example.aichat.core.ui.ScrollChromeBar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.example.aichat.feature.group.*
import com.example.aichat.feature.persona.PersonaLibraryRoute
import com.example.aichat.feature.customization.AppearanceRoute
import com.example.aichat.feature.customization.AppearanceBackdrop
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
    val appearance by appViewModel.appearance.preferences.collectAsStateWithLifecycle()
    val appearanceReady by appViewModel.appearance.ready.collectAsStateWithLifecycle()
    val activeProfile = profile ?: session.profile
    val context = LocalContext.current
    LaunchedEffect(session.isSignedIn, session.isLoading, session.profile?.userId) {
        if (session.isLoading) return@LaunchedEffect
        if (session.isSignedIn) {
            com.example.aichat.feature.activity.NotificationWorker.schedule(context)
            runCatching { appViewModel.appearance.activate(session.profile?.userId.orEmpty()) }
        } else {
            com.example.aichat.feature.activity.NotificationWorker.cancel(context)
            appViewModel.appearance.reset()
        }
    }
    when {
        session.isLoading -> LoadingScreen()
        !session.isSignedIn -> SignInRoute()
        !appearanceReady -> LoadingScreen()
        else -> key(activeProfile?.userId) {
            CompositionLocalProvider(com.example.aichat.feature.customization.LocalAppearance provides appearance,
                LocalAppBackdrop provides { AppearanceBackdrop(appearance, Modifier.fillMaxSize()) }) {
                MainShell(activeProfile?.userId.orEmpty(), activeProfile?.displayName.orEmpty(), activeProfile?.avatarUrl, appViewModel, notificationUri, onNotificationConsumed)
            }
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    DisposableEffect(nav, keyboard, focus) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
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
                "group" -> id?.let { "groups/${Uri.encode(it)}" }
                "character" -> id?.let { "character-profile/${Uri.encode(it)}" }
                "profile" -> id?.let { "creator-profile/${Uri.encode(it)}" }
                "activity" -> "activity"
                else -> null
            } else null
            if (route != null) nav.navigate(route) { popUpTo("main_tabs"); launchSingleTop = true }
            onNotificationConsumed()
        }
    }
    NavHost(
        navController = nav, startDestination = "main_tabs",
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }
    ) {
        composable("main_tabs") { entry ->
            MainTabs(profileName, profileAvatarUrl, onOpen = { nav.openFrom(entry, it) })
        }
        composable("create-character") { entry ->
            CharacterStudioRoute(
                paddingValues = PaddingValues(), ownerUserId = ownerUserId,
                onBack = { nav.backFrom(entry) },
                onUpgradeUltra = { nav.openFrom(entry, "ultra") },
                onCreated = { id ->
                    if (nav.currentBackStackEntry?.id == entry.id) {
                        nav.navigate("chat/${Uri.encode(id)}") { popUpTo("main_tabs"); launchSingleTop = true }
                    }
                }
            )
        }
        composable("edit-character/{characterId}") { entry ->
            CharacterStudioRoute(paddingValues = PaddingValues(), ownerUserId = ownerUserId,
                characterId = entry.arguments?.getString("characterId"), onBack = { nav.backFrom(entry) },
                onUpgradeUltra = { nav.openFrom(entry, "ultra") },
                onCreated = { nav.backFrom(entry) })
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
                onUpgradeUltra = { nav.openFrom(entry, "ultra") },
                onOpenPersonas = { nav.openFrom(entry, "chat/${Uri.encode(entry.arguments?.getString("conversationId"))}/personas") }
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
                onOpenCreator = { nav.openFrom(entry, "creator-profile/${Uri.encode(it)}") },
                onEditCharacter = { nav.openFrom(entry, "edit-character/${Uri.encode(it)}") }
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
        composable("discover/{categoryId}") { entry ->
            com.example.aichat.feature.home.DiscoveryRoute(onBack = { nav.backFrom(entry) }, onOpenConversation = { nav.openFrom(entry, "chat/${Uri.encode(it)}") })
        }
        composable("search") { entry ->
            SearchRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) }, onOpenConversation = { nav.openFrom(entry, "chat/${Uri.encode(it)}") })
        }
        composable("settings") { entry ->
            SettingsRoute(paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) }, onOpenVoices = { nav.openFrom(entry, "voices") },
                onOpenAppearance = { nav.openFrom(entry, "appearance") }, onOpenPersonas = { nav.openFrom(entry, "personas") }, onOpenSubscription = { nav.openFrom(entry, "ultra") })
        }
        composable("activity") { entry ->
            ActivityRoute(
                paddingValues = PaddingValues(), onBack = { nav.backFrom(entry) },
                onOpenConversation = { id, _ -> nav.openFrom(entry, "chat/${Uri.encode(id)}") },
                onOpenCharacter = { nav.openFrom(entry, "character-profile/${Uri.encode(it)}") },
                onOpenProfile = { nav.openFrom(entry, "creator-profile/${Uri.encode(it)}") },
                onOpenGroup = { nav.openFrom(entry, "groups/${Uri.encode(it)}") }
            )
        }
        composable("ultra") { entry -> UltraRoute(onBack = { nav.backFrom(entry) }) }
        composable("voices") { entry -> VoiceLibraryRoute(onBack = { nav.backFrom(entry) }, onUpgradeUltra = { nav.openFrom(entry, "ultra") }) }
        composable("voices/create") { entry -> VoiceLibraryRoute(onBack = { nav.backFrom(entry) }, startCreating = true, onUpgradeUltra = { nav.openFrom(entry, "ultra") }) }
        composable("personas") { entry -> PersonaLibraryRoute(onBack = { nav.backFrom(entry) }) }
        composable("personas/create") { entry -> PersonaLibraryRoute(onBack = { nav.backFrom(entry) }, startCreating = true) }
        composable("chat/{conversationId}/personas") { entry ->
            PersonaLibraryRoute(onBack = { nav.backFrom(entry) }, conversationId = entry.arguments?.getString("conversationId"), onSelect = { nav.backFrom(entry) })
        }
        composable("appearance") { entry -> AppearanceRoute(onBack = { nav.backFrom(entry) }, onUpgradeUltra = { nav.openFrom(entry, "ultra") }) }
        composable("groups") { entry ->
            GroupListRoute(PaddingValues(), onBack = { nav.backFrom(entry) }, onCreate = { nav.openFrom(entry, "groups/create") }, onOpenGroup = { nav.openFrom(entry, "groups/${Uri.encode(it)}") })
        }
        composable("groups/create") { entry ->
            CreateGroupRoute(PaddingValues(), onBack = { nav.backFrom(entry) }, onCreated = { id ->
                if (nav.currentBackStackEntry?.id == entry.id) nav.navigate("groups/${Uri.encode(id)}") { popUpTo("groups/create") { inclusive = true }; launchSingleTop = true }
            })
        }
        composable("groups/{groupId}") { entry ->
            GroupChatRoute(PaddingValues(), onBack = { nav.backFrom(entry) }, onOpenPersonas = {
                entry.arguments?.getString("groupId")?.let { nav.openFrom(entry, "groups/${Uri.encode(it)}/personas") }
            })
        }
        composable("groups/{groupId}/personas") { entry ->
            PersonaLibraryRoute(onBack = { nav.backFrom(entry) }, groupId = entry.arguments?.getString("groupId"), onSelect = { nav.backFrom(entry) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(profileName: String, profileAvatarUrl: String?, onOpen: (String) -> Unit) {
    val isUltra = com.example.aichat.feature.customization.LocalAppearance.current.ultra
    val tabs = remember { bottomDestinations.filter { it != MainDestination.Studio && it != MainDestination.Ultra } }
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val current = tabs[pager.targetPage]
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val chatListViewModel: com.example.aichat.feature.chatlist.ChatListViewModel = hiltViewModel()
    val chats by chatListViewModel.uiState.collectAsStateWithLifecycle()
    val unread = chats.conversations.sumOf { it.unreadCount }
    val chrome = rememberScrollChrome()
    LaunchedEffect(pager.settledPage) { chrome.visible = true }
    Scaffold(
        modifier = Modifier.nestedScroll(chrome),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val background = MaterialTheme.colorScheme.background
            ScrollChromeBar(chrome, top = true) { Column {
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
            }
        },
        bottomBar = {
            ScrollChromeBar(chrome, top = false) { NavigationBar(
                containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.height(AppChrome.bottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            ) {
                bottomDestinations.filter { !isUltra || it != MainDestination.Ultra }.forEach { destination ->
                    val selected = current == destination
                    NavigationBarItem(
                        selected = selected, alwaysShowLabel = false,
                        onClick = {
                            when (destination) {
                                MainDestination.Studio -> { showCreate = true }
                                MainDestination.Ultra -> onOpen("ultra")
                                else -> scope.launch { pager.scrollToPage(tabs.indexOf(destination)) }
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
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            }
        }
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { tabs[it].route }) { page ->
            val openChat: (String) -> Unit = { if (pager.settledPage == page) onOpen("chat/${Uri.encode(it)}") }
            when (tabs[page]) {
                MainDestination.Home -> NewHomeRoute(paddingValues = padding, onOpenConversation = openChat,
                    onOpenStudio = { onOpen("create-character") }, onOpenChats = { scope.launch { pager.scrollToPage(tabs.indexOf(MainDestination.Chats)) } })
                MainDestination.Discover -> com.example.aichat.feature.home.DiscoveryRoute(paddingValues = padding, onOpenCategory = { onOpen("discover/${Uri.encode(it)}") }, onOpenConversation = openChat)
                MainDestination.Chats -> ChatListRoute(paddingValues = padding, onOpenConversation = openChat, onOpenGroups = { onOpen("groups") })
                MainDestination.Profile -> ProfileRoute(paddingValues = padding, onOpenActivity = { onOpen("activity") },
                    onOpenConversation = openChat, onOpenEditProfile = { onOpen("edit-profile") },
                    onOpenSettings = { onOpen("settings") }, onUpgradeUltra = { onOpen("ultra") }, onOpenAppearance = { onOpen("appearance") })
                else -> Unit
            }
        }
    }
    if (showCreate) CreateSheet(onDismiss = { showCreate = false }, onOpen = { route -> showCreate = false; onOpen(route) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateSheet(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Create", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            data class Choice(val title: String, val detail: String, val route: String, val icon: AppIconGlyph, val ultra: Boolean = false)
            listOf(
                Choice("Character", "Bring someone new to life", "create-character", AppIcons.create),
                Choice("Persona", "Choose who you are in your chats", "personas/create", AppIcons.profile),
                Choice("Group chat", "Bring your characters together", "groups/create", AppIcons.chats),
                Choice("Custom voice", "Design a voice or add a sample", "voices/create", AppIcons.sparkle, true)
            ).forEach { choice ->
                Surface(onClick = { onOpen(choice.route) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(choice.icon, contentDescription = null, size = 24.dp)
                        Column(Modifier.weight(1f)) {
                            Text(choice.title, style = MaterialTheme.typography.titleMedium)
                            Text(choice.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (choice.ultra) AssistChip(onClick = { onOpen(choice.route) }, label = { Text("Ultra") }, leadingIcon = { AppIcon(AppIcons.sparkle, null, size = 14.dp) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
