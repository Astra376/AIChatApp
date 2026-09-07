package com.example.aichat.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichat.core.db.toEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.network.CharacterApi
import com.example.aichat.core.network.GroupDetailDto
import com.example.aichat.core.network.GroupMessageDto
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.feature.chat.formatRoleplayText
import com.example.aichat.feature.chat.rememberTypedStreamText
import com.example.aichat.feature.chat.ReasoningStatusWord
import com.example.aichat.feature.home.HomeRepository
import com.example.aichat.feature.profile.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltViewModel
class GroupListViewModel @Inject constructor(private val repository: GroupRepository) : ViewModel() {
    val groups = repository.groups
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun refresh() { if (loading.value) return; loading.value = true; viewModelScope.launch {
        try { repository.refreshGroups() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "Couldn't load your groups. Pull them up again with Retry." }
        finally { loading.value = false }
    } }
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(private val repository: GroupRepository, private val home: HomeRepository,
    private val characterApi: CharacterApi, private val saved: SavedStateHandle) : ViewModel() {
    val name = saved.getStateFlow("name", "")
    val query = saved.getStateFlow("query", "")
    val selected = saved.getStateFlow("selected", arrayListOf<String>())
    val characters = MutableStateFlow<List<CharacterSummary>>(emptyList())
    val loading = MutableStateFlow(true)
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var owned = emptyList<CharacterSummary>()
    init { search() }
    fun name(value: String) { saved["name"] = value.take(80) }
    fun query(value: String) { saved["query"] = value; search() }
    fun toggle(id: String) {
        val current = selected.value.toMutableList()
        if (!current.remove(id) && current.size < 6) current.add(id)
        saved["selected"] = ArrayList(current)
    }
    private fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            loading.value = true
            try {
                if (owned.isEmpty()) owned = characterApi.getOwnedCharacters().items.map { it.toEntity().toModel() }
                val query = query.value.trim()
                val page = if (query.isBlank()) home.loadFeed(null) else home.search(query, null)
                characters.value = (owned.filter { query.isBlank() || it.name.contains(query, true) || it.tagline.contains(query, true) } + page.items).distinctBy { it.id }
                error.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Couldn't load characters. Try searching again." }
            finally { loading.value = false }
        }
    }
    fun create(onCreated: (String) -> Unit) {
        if (saving.value || name.value.isBlank() || selected.value.size !in 2..6) return
        saving.value = true
        viewModelScope.launch {
            try { onCreated(repository.create(name.value, selected.value)); saved["name"] = ""; saved["selected"] = arrayListOf<String>() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Couldn't create the group. Your choices are saved." }
            finally { saving.value = false }
        }
    }
}

@HiltViewModel
class GroupChatViewModel @Inject constructor(private val repository: GroupRepository, settings: SettingsRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    val groupId = checkNotNull(saved.get<String>("groupId"))
    val state = repository.observe(groupId)
    val input = saved.getStateFlow("input", "")
    init {
        viewModelScope.launch {
            state.map { it.failedDraft }.distinctUntilChanged().collect { draft ->
                if (draft != null && input.value.isBlank()) saved["input"] = draft.content
            }
        }
    }
    val haptics = settings.streamingHaptics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun input(value: String) { saved["input"] = value.take(12_000) }
    fun send(): Boolean = repository.send(groupId, input.value).also { if (it) saved["input"] = "" }
    fun continueChat() { repository.continueChat(groupId) }
    fun stop() { repository.stop(groupId) }
    fun clearError() { repository.clearError(groupId) }
    val deleting = MutableStateFlow(false)
    fun delete(onDeleted: () -> Unit) {
        if (deleting.value) return
        deleting.value = true
        viewModelScope.launch {
            try { repository.delete(groupId); onDeleted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { repository.reportError(groupId, "Couldn't delete the group. Try again.") }
            finally { deleting.value = false }
        }
    }
    fun older() { viewModelScope.launch { repository.older(groupId) } }
    fun refresh() { viewModelScope.launch { repository.refresh(groupId) } }
    suspend fun visibleSession() = coroutineScope {
        repository.refresh(groupId)
        launch {
            while (isActive) {
                repository.presence(groupId, input.value.isNotBlank())
                delay(10_000)
            }
        }
        launch {
            input.map { it.isNotBlank() }.distinctUntilChanged().collectLatest { typing ->
                repository.presence(groupId, typing)
                if (typing) { delay(35_000); repository.continueChat(groupId, typing = true) }
            }
        }
        while (isActive) {
            delay(if (state.value.busy) 1_500 else 15_000)
            if (!state.value.sending && state.value.detail != null) repository.refresh(groupId)
            val last = state.value.detail?.messages?.lastOrNull()
            val anchor = state.value.detail?.messages?.lastOrNull { it.role == "user" }?.id
            if (anchor != null && saved.get<String>("quietAnchor") != anchor && !state.value.busy && input.value.isBlank()
                && last?.role == "assistant" && System.currentTimeMillis() - last.createdAt > 60_000) {
                if (repository.continueChat(groupId, idle = true)) saved["quietAnchor"] = anchor
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListRoute(paddingValues: PaddingValues, onBack: () -> Unit, onCreate: () -> Unit, onOpenGroup: (String) -> Unit,
    viewModel: GroupListViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh(); awaitCancellation() } }
    ScreenBackgroundBox {
        Scaffold(modifier = Modifier.padding(paddingValues), containerColor = MaterialTheme.colorScheme.background,
            topBar = { GroupTopBar("Group chats", onBack, { IconButton(onClick = onCreate) { AppIcon(AppIcons.createAction, "Create group") } }) }) { insets ->
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                if (loading && groups.isEmpty()) items(5) { GroupPlaceholder() }
                if (error != null) item { Row(verticalAlignment = Alignment.CenterVertically) { Text(error.orEmpty(), Modifier.weight(1f)); TextButton(onClick = viewModel::refresh) { Text("Retry") } } }
                if (!loading && groups.isEmpty()) item {
                    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Bring your characters together", style = MaterialTheme.typography.headlineSmall)
                        Text("Choose two to six characters. They'll join in when they have something to say.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onCreate) { Text("Create a group") }
                    }
                }
                items(groups, key = { it.id }) { group ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpenGroup(group.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GroupAvatar(group)
                        Column(Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(group.characters.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (group.unreadCount > 0) Badge { Text(group.unreadCount.toString()) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupRoute(paddingValues: PaddingValues, onBack: () -> Unit, onCreated: (String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Scaffold(modifier = Modifier.padding(paddingValues), topBar = { GroupTopBar("Create group", onBack) },
        bottomBar = { Surface { Button(onClick = { viewModel.create(onCreated) }, enabled = !saving && name.isNotBlank() && selected.size in 2..6,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create group · ${selected.size}/6")
        } } }) { insets ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item { OutlinedTextField(name, viewModel::name, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text("Choose two to six characters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            item { OutlinedTextField(query, viewModel::query, placeholder = { Text("Find a character") }, singleLine = true,
                leadingIcon = { AppIcon(AppIcons.search, null) }, modifier = Modifier.fillMaxWidth()) }
            if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            if (loading && characters.isEmpty()) items(5) { GroupPlaceholder() }
            items(characters, key = { it.id }) { character ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !saving) { viewModel.toggle(character.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterPortrait(name = character.name, avatarUrl = character.avatarUrl, modifier = Modifier.size(58.dp))
                    Column(Modifier.weight(1f)) {
                        Text(character.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(character.tagline, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(character.id in selected, onCheckedChange = { viewModel.toggle(character.id) }, enabled = !saving && (selected.size < 6 || character.id in selected))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatRoute(paddingValues: PaddingValues, onBack: () -> Unit, viewModel: GroupChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val haptics by viewModel.haptics.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    var showGroupInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var followBottom by rememberSaveable(viewModel.groupId) { mutableStateOf(true) }
    LaunchedEffect(lifecycle, viewModel.groupId) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.visibleSession() } }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) followBottom = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (moving, index, offset) -> if (!moving && index == 0 && offset < 32) followBottom = true }
    }
    val messages = state.detail?.messages.orEmpty()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (followBottom && messages.isNotEmpty()) listState.scrollToItem(0)
    }
    ScreenBackgroundBox {
        Scaffold(modifier = Modifier.padding(paddingValues), containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column { GroupTopBar(state.detail?.name ?: "Group chat", onBack,
                onTitleClick = { showGroupInfo = true }, actions = { if (state.detail != null) TextButton(onClick = viewModel::continueChat, enabled = !state.busy) { Text("Continue") } })
                if (state.detail != null) Text(state.detail!!.characters.joinToString { it.name }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(14.dp).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0f)))))
            } },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Box(Modifier.fillMaxWidth().height(18.dp).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0f), MaterialTheme.colorScheme.background))))
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(input, viewModel::input, placeholder = { Text("Message the group") }, maxLines = 5,
                            shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                        FilledIconButton(onClick = { if (state.busy) viewModel.stop() else { viewModel.send() } },
                            enabled = if (state.busy) !state.stopping && state.detail?.activeRunId != null else input.isNotBlank(), modifier = Modifier.size(48.dp)) {
                            AppIcon(if (state.busy) AppIcons.stop else AppIcons.send, if (state.busy) "Stop reply" else "Send message")
                        }
                    }
                }
            }) { insets ->
            if (state.loading && state.detail == null) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (state.detail == null) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { TextButton(onClick = viewModel::refresh) { Text("Retry loading group") } }
            else LazyColumn(reverseLayout = true, state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding(), start = 14.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy && messages.lastOrNull()?.role != "assistant") item("group-thinking") {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Choosing a reply", style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(messages.asReversed(), key = { it.id }) { message -> GroupMessage(message, haptics, state.reasoning && message.id == messages.lastOrNull()?.id) }
                if (state.detail?.nextBeforePosition != null) item("older") { TextButton(onClick = viewModel::older, enabled = !state.loadingOlder,
                    modifier = Modifier.fillMaxWidth()) { Text(if (state.loadingOlder) "Loading…" else "Earlier messages") } }
                if (messages.isEmpty()) item("empty") { Text("Say hello to the group.", Modifier.fillMaxWidth().padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (showGroupInfo) ModalBottomSheet(onDismissRequest = { showGroupInfo = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.detail?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            state.detail?.characters.orEmpty().forEach { character ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterPortrait(name = character.name, avatarUrl = character.avatarUrl, modifier = Modifier.size(42.dp))
                    Text(character.name, style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onClick = { showGroupInfo = false; confirmDelete = true }, enabled = !deleting) { Text("Delete group", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("Delete this group?") }, text = { Text("This removes the group and its messages. The characters stay in your library.") },
        confirmButton = { TextButton(onClick = { viewModel.delete { confirmDelete = false; onBack() } }, enabled = !deleting) { Text(if (deleting) "Deleting…" else "Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text("Cancel") } })
}

@Composable
private fun GroupMessage(message: GroupMessageDto, haptics: Boolean, reasoning: Boolean) {
    val own = message.role == "user"
    val displayed = rememberTypedStreamText(if (message.status == "streaming") message.id else null, message.content,
        animate = message.status == "streaming", hapticsEnabled = haptics)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        if (!own) Row(Modifier.padding(start = 3.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CharacterPortrait(name = message.characterName.orEmpty(), avatarUrl = message.avatarUrl, modifier = Modifier.size(22.dp))
            Text(message.characterName ?: "Character", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Surface(shape = RoundedCornerShape(18.dp), color = if (own) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 340.dp)) {
            if (reasoning && displayed.isBlank()) ReasoningStatusWord(Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
            else Text(if (displayed.isEmpty()) androidx.compose.ui.text.AnnotatedString("…") else formatRoleplayText(displayed),
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, onTitleClick: (() -> Unit)? = null) {
    TopAppBar(title = { Text(title, modifier = Modifier.clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { AppIcon(AppIcons.back, "Back") } }, actions = actions,
        expandedHeight = 52.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}
@Composable
private fun GroupAvatar(group: GroupDetailDto) {
    Box(Modifier.size(58.dp)) {
        group.characters.take(2).forEachIndexed { index, member ->
            CharacterPortrait(name = member.name, avatarUrl = member.avatarUrl, modifier = Modifier.size(42.dp).align(if (index == 0) Alignment.TopStart else Alignment.BottomEnd))
        }
    }
}
@Composable
private fun GroupPlaceholder() {
    Row(Modifier.fillMaxWidth().height(74.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(18.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(.9f).height(14.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)))
        }
    }
}
