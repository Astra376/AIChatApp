package com.example.aichat.feature.persona

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.ScreenBackgroundBox
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Retrofit

@HiltViewModel class PersonaLibraryViewModel @Inject constructor(
    retrofit: Retrofit, private val savedState: SavedStateHandle, private val identity: PersonaIdentityRepository
) : ViewModel() {
    data class State(
        val library: PersonaLibraryDto = PersonaLibraryDto(), val selection: ConversationPersonaDto? = null,
        val loading: Boolean = true, val busy: Boolean = false, val error: String? = null
    )
    private val api = retrofit.create(PersonaApi::class.java)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var conversationId: String? = null
    private var loaded = false
    var editing by mutableStateOf(savedState.get<Boolean>("persona.editing") ?: false); private set
    var editId by mutableStateOf(savedState.get<String>("persona.id") ?: ""); private set
    var name by mutableStateOf(savedState.get<String>("persona.name") ?: ""); private set
    var backstory by mutableStateOf(savedState.get<String>("persona.backstory") ?: ""); private set
    var appearance by mutableStateOf(savedState.get<String>("persona.appearance") ?: ""); private set
    var pronouns by mutableStateOf(savedState.get<String>("persona.pronouns") ?: ""); private set

    fun bind(id: String?) { if (!loaded || conversationId != id) { loaded = true; conversationId = id; refresh() } }
    fun refresh() = operation {
        val result = coroutineScope {
            val library = async { api.list() }
            val selection = async { conversationId?.let { api.selection(it) } }
            library.await() to selection.await()
        }
        _state.value = _state.value.copy(library = result.first, selection = result.second, loading = false)
        identity.rememberLibrary(result.first)
        result.second?.let(identity::remember)
    }
    fun beginCreate() {
        if (editId.isNotEmpty()) { editId = ""; name = ""; backstory = ""; appearance = ""; pronouns = "" }
        editing = true; storeDraft()
    }
    fun beginEdit(persona: PersonaDto) {
        editId = persona.id; name = persona.name; backstory = persona.backstory
        appearance = persona.appearance; pronouns = persona.pronouns; editing = true; storeDraft()
    }
    fun closeEditor() { editing = false; storeDraft() }
    fun update(field: String, value: String) {
        when (field) {
            "name" -> name = value.take(80)
            "backstory" -> backstory = value.take(6000)
            "appearance" -> appearance = value.take(2000)
            "pronouns" -> pronouns = value.take(80)
        }
        storeDraft()
    }
    private fun storeDraft() {
        savedState["persona.editing"] = editing; savedState["persona.id"] = editId
        savedState["persona.name"] = name; savedState["persona.backstory"] = backstory
        savedState["persona.appearance"] = appearance; savedState["persona.pronouns"] = pronouns
    }
    fun save(onSaved: (String) -> Unit) = operation {
        val values = mapOf("name" to name.trim(), "backstory" to backstory.trim(),
            "appearance" to appearance.trim(), "pronouns" to pronouns.trim())
        val result = if (editId.isEmpty()) api.create(values) else api.update(editId, values)
        val items = listOf(result) + _state.value.library.items.filterNot { it.id == result.id }
        _state.value = _state.value.copy(library = _state.value.library.copy(items = items))
        identity.rememberLibrary(_state.value.library)
        editing = false; editId = ""; name = ""; backstory = ""; appearance = ""; pronouns = ""; storeDraft()
        onSaved(result.id)
    }
    fun select(mode: String, id: String?, onSelected: () -> Unit) = operation {
        conversationId?.let { chatId ->
            val request = mutableMapOf("mode" to mode)
            if (id != null) request["personaId"] = id
            _state.value = _state.value.copy(selection = api.select(chatId, request))
            _state.value.selection?.let(identity::remember)
        }
        onSelected()
    }
    fun setDefault(id: String?) = operation {
        // JsonNull remains explicit even when the app serializer omits nullable default fields.
        api.setDefault(mapOf("personaId" to (id?.let(::JsonPrimitive) ?: JsonNull)))
        _state.value = _state.value.copy(library = _state.value.library.copy(defaultPersonaId = id))
        identity.rememberLibrary(_state.value.library)
        conversationId?.let { _state.value = _state.value.copy(selection = api.selection(it)) }
        _state.value.selection?.let(identity::remember)
    }
    fun delete(id: String) = operation {
        api.delete(id)
        val library = _state.value.library
        _state.value = _state.value.copy(library = library.copy(items = library.items.filterNot { it.id == id },
            defaultPersonaId = library.defaultPersonaId?.takeUnless { it == id }))
        identity.rememberLibrary(_state.value.library)
        conversationId?.let { _state.value = _state.value.copy(selection = api.selection(it)) }
        _state.value.selection?.let(identity::remember)
    }
    private fun operation(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(error = error.userFacingMessage("Couldn't update your personas.")) }
            finally { _state.value = _state.value.copy(busy = false, loading = false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PersonaLibraryRoute(
    onBack: () -> Unit,
    onSelect: ((String?) -> Unit)? = null,
    conversationId: String? = null,
    modifier: Modifier = Modifier,
    startCreating: Boolean = false,
    model: PersonaLibraryViewModel = hiltViewModel()
) {
    val state by model.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<PersonaDto?>(null) }
    LaunchedEffect(conversationId) { model.bind(conversationId) }
    LaunchedEffect(startCreating) { if (startCreating) model.beginCreate() }
    BackHandler(enabled = model.editing) { model.closeEditor() }
    val choose: (String, String?) -> Unit = { mode, id -> model.select(mode, id) { onSelect?.invoke(id) } }
    ScreenBackgroundBox(modifier = modifier) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onClick = { if (model.editing) model.closeEditor() else onBack() })
                    Text(if (model.editing) if (model.editId.isEmpty()) "Create persona" else "Edit persona" else "Personas",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (model.editing) {
                item { Text("Choose who you are in the story. Your personas are private and can be used in any chat.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { PersonaField("Name", model.name, 80, singleLine = true) { model.update("name", it) } }
                item { PersonaField("Pronouns", model.pronouns, 80, singleLine = true, placeholder = "e.g. she/her, he/him, they/them") { model.update("pronouns", it) } }
                item { PersonaField("Backstory", model.backstory, 6000, placeholder = "Your life, personality and place in the story…") { model.update("backstory", it) } }
                item { PersonaField("Appearance", model.appearance, 2000, placeholder = "Details characters should notice or remember…") { model.update("appearance", it) } }
                item { Button(onClick = { model.save { } }, enabled = !state.busy && model.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Saving…" else "Save persona")
                } }
            } else {
                item {
                    Text(if (conversationId == null) "Create different identities for your stories. Only you can see these personas."
                        else "You are ${state.selection?.effectiveName ?: state.library.accountName} in this chat. Changes apply to future replies.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Button(onClick = model::beginCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Create persona") } }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (conversationId != null && !state.loading) {
                    item { PersonaChoice("Chat default", state.selection?.characterDefault?.name ?: "Your default persona or account name",
                        state.selection?.mode == "auto", !state.busy) { choose("auto", null) } }
                    item { PersonaChoice(state.library.accountName, "Use your account identity", state.selection?.mode == "account", !state.busy) { choose("account", null) } }
                } else if (!state.loading && onSelect == null) {
                    item { PersonaChoice(state.library.accountName, "Account identity for new chats",
                        state.library.defaultPersonaId == null, !state.busy) { model.setDefault(null) } }
                }
                items(state.library.items, key = { it.id }) { persona ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(persona.name, style = MaterialTheme.typography.titleMedium)
                                    if (persona.pronouns.isNotBlank()) Text(persona.pronouns, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (conversationId != null || onSelect != null) RadioButton(
                                    selected = state.selection?.mode == "personal" && state.selection?.personaId == persona.id,
                                    onClick = { choose("personal", persona.id) }, enabled = !state.busy
                                )
                            }
                            if (persona.backstory.isNotBlank()) Text(persona.backstory, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (state.library.defaultPersonaId == persona.id) Text("Default for new chats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { model.beginEdit(persona) }, enabled = !state.busy) { Text("Edit") }
                                if (state.library.defaultPersonaId != persona.id) TextButton(onClick = { model.setDefault(persona.id) }, enabled = !state.busy) { Text("Set default") }
                                TextButton(onClick = { deleting = persona }, enabled = !state.busy) { Text("Delete") }
                            }
                        }
                    }
                }
                if (!state.loading && state.library.items.isEmpty()) item { Text("No personas yet. Create one to give your next story a different perspective.", style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.busy && !state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (!model.editing) TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Try again") }
            } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    deleting?.let { persona ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${persona.name}?") },
            text = { Text("Chats using this persona will return to your account identity. Your messages will stay.") },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(persona.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable private fun PersonaChoice(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun PersonaField(label: String, value: String, limit: Int, singleLine: Boolean = false, placeholder: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth(),
        supportingText = { if (!singleLine || value.length > limit * 0.8) Text("${value.length} / $limit") })
}
