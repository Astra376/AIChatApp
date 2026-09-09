package com.example.aichat.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.db.toEntity
import com.example.aichat.core.db.toModel
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.design.*
import com.example.aichat.core.ui.*
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoveryState(
    val discovery: DiscoveryDto? = null, val selected: String? = null,
    val items: List<CharacterSummary> = emptyList(), val cursor: String? = null,
    val loading: Boolean = false, val error: String? = null
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val savedState: SavedStateHandle, auth: AuthRepository,
    private val repository: DiscoveryRepository, private val conversations: ConversationRepository
) : ViewModel() {
    private val userId = auth.sessionState.value.profile?.userId.orEmpty()
    private val mutable = MutableStateFlow(DiscoveryState(repository.cached(userId), savedState["categoryId"]))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var lastRefresh = 0L
    init { refresh() }
    fun refresh() {
        if (job?.isActive == true || (mutable.value.discovery != null && System.currentTimeMillis() - lastRefresh < 30 * 60_000)) return
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                val data = repository.refresh(userId)
                mutable.value = mutable.value.copy(discovery = data, loading = false)
                lastRefresh = System.currentTimeMillis()
                mutable.value.selected?.let { loadCategory(it, data) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(loading = false, error = "Couldn't refresh your recommendations.") }
        }
    }
    fun select(id: String) {
        val data = mutable.value.discovery ?: return
        savedState["categoryId"] = id
        job?.cancel()
        job = viewModelScope.launch { loadCategory(id, data) }
    }
    private suspend fun loadCategory(id: String, data: DiscoveryDto) {
        val category = data.categories.firstOrNull { it.id == id } ?: data.categories.firstOrNull() ?: return
        mutable.value = mutable.value.copy(selected = category.id, items = category.items.map { it.toEntity().toModel() }, cursor = null, loading = true, error = null)
        try {
            val page = repository.category(category.id, data.version, null)
            mutable.value = mutable.value.copy(items = page.items.map { it.toEntity().toModel() }, cursor = page.nextCursor, loading = false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(loading = false, error = "Couldn't load this category.") }
    }
    fun more() {
        val before = mutable.value
        val cursor = before.cursor ?: return
        if (before.loading) return
        job = viewModelScope.launch {
            mutable.value = before.copy(loading = true, error = null)
            try {
                val page = repository.category(before.selected ?: return@launch, before.discovery!!.version, cursor)
                mutable.value = mutable.value.copy(items = (before.items + page.items.map { it.toEntity().toModel() }).distinctBy { it.id }, cursor = page.nextCursor, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(loading = false, error = "Couldn't load more characters.") }
        }
    }
    suspend fun ensureConversation(id: String) = conversations.ensureConversation(userId, id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryRoute(paddingValues: PaddingValues = PaddingValues(), onOpenCategory: (String) -> Unit = {},
    onOpenConversation: (String) -> Unit, onBack: (() -> Unit)? = null, viewModel: DiscoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberCharacterChatLauncher(viewModel::ensureConversation, onOpenConversation, snackbar)
    val loading = rememberDelayedLoading(state.loading)
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }
    ScreenBackgroundBox(snackbarHostState = snackbar) {
        if (onBack == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = screenContentPadding(paddingValues), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading && state.discovery == null) items(3) { CharacterSummaryCardPlaceholder(Modifier.fillMaxWidth(), imageAspectRatio = 3f) }
                state.discovery?.categories?.forEach { section ->
                    item(key = section.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "View all ${section.title}") {
                                onOpenCategory(section.id)
                            }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(section.title, style = MaterialTheme.typography.titleLarge)
                                AppIcon(AppIcons.categoryArrow, null, size = 18.dp)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(section.items, key = { it.id }) { item ->
                                    CharacterSummaryCard(item.toEntity().toModel(), Modifier.width(120.dp), imageAspectRatio = .85f,
                                        compact = true, isOpening = launcher.openingCharacterId == item.id, enabled = launcher.openingCharacterId == null) { launcher.open(item.id) }
                                }
                            }
                        }
                    }
                }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::refresh) { Text("Retry") } } }
                if (!state.loading && state.error == null && state.discovery?.categories.isNullOrEmpty()) item { Text("Characters will appear here as the community grows.") }
            }
        } else {
            var menu by remember { mutableStateOf(false) }
            val selected = state.discovery?.categories?.firstOrNull { it.id == state.selected }
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = screenContentPadding(paddingValues),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Discover", style = MaterialTheme.typography.titleLarge)
                            Box(Modifier.align(Alignment.CenterStart)) { AppBackButton(onBack) }
                        }
                        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                            OutlinedTextField(
                                value = selected?.title ?: "Choose a category", onValueChange = {}, readOnly = true,
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                                singleLine = true, shape = RoundedCornerShape(14.dp),
                                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface)
                            )
                            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                state.discovery?.categories?.forEach { section ->
                                    DropdownMenuItem(
                                        text = { Text(section.title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                        onClick = { menu = false; viewModel.select(section.id) },
                                        colors = MenuDefaults.itemColors(textColor = if (section.id == selected?.id)
                                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    )
                                }
                            }
                        }
                    }
                }

                val items = state.items.ifEmpty { selected?.items?.map { it.toEntity().toModel() }.orEmpty() }
                items(items, key = { it.id }) { item ->
                    CharacterSummaryCard(item, Modifier.fillMaxWidth(), imageAspectRatio = .85f, compact = true,
                        isOpening = launcher.openingCharacterId == item.id, enabled = launcher.openingCharacterId == null) { launcher.open(item.id) }
                }
                if (loading && items.isEmpty()) items(9) { CharacterSummaryCardPlaceholder(Modifier.fillMaxWidth(), imageAspectRatio = .85f) }
                state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { state.selected?.let(viewModel::select) }) { Text("Retry") } } }
                if (state.cursor != null) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = viewModel::more, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
            }
        }
    }
}
