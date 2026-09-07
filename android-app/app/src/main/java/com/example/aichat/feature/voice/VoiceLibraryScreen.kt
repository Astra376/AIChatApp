package com.example.aichat.feature.voice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.feature.ultra.UltraRoute
import androidx.lifecycle.compose.LifecycleResumeEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel class VoiceLibraryViewModel @Inject constructor(private val repository: VoiceRepository) : ViewModel() {
    data class State(val voices: List<VoiceDto> = emptyList(), val available: Boolean = false, val canCreate: Boolean = false, val loading: Boolean = true, val creating: Boolean = false, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var requestKey = UUID.randomUUID().toString()
    init { refresh() }
    fun refresh() { viewModelScope.launch {
        try { val result = repository.list(); _state.value = _state.value.copy(voices = result.items, available = result.available, canCreate = result.canCreate, loading = false, error = null) }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { _state.value = _state.value.copy(loading = false, error = error.userFacingMessage("Could not load voices.")) }
    } }
    fun create(name: String, description: String, public: Boolean, sample: Uri?, onCreated: (String) -> Unit) {
        if (_state.value.creating || !_state.value.canCreate) return
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            try { val result = repository.create(name.trim(), description.trim(), public, sample, requestKey)
                requestKey = UUID.randomUUID().toString()
                _state.value = _state.value.copy(creating = false, voices = _state.value.voices + result)
                onCreated(result.id)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(creating = false, error = error.userFacingMessage("Could not create this voice.")) }
        }
    }
}
@Composable fun VoicePickerDialog(onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { VoiceLibraryRoute(onBack = onDismiss, onSelected = onSelected) }
    }
}
@Composable fun VoiceLibraryRoute(onBack: () -> Unit, onSelected: ((String) -> Unit)? = null, modifier: Modifier = Modifier, startCreating: Boolean = false, onUpgradeUltra: (() -> Unit)? = null) {
    ReadAloudLifecycle()
    val model: VoiceLibraryViewModel = hiltViewModel()
    val player: ReadAloudViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val isUltra = com.example.aichat.feature.customization.LocalAppearance.current.ultra
    val playback by player.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var upgrade by rememberSaveable { mutableStateOf(false) }
    var handledCreate by rememberSaveable { mutableStateOf(false) }
    fun leaveCreate() { if (startCreating) onBack() else creating = false }
    BackHandler(enabled = creating || upgrade) {
        if (upgrade && !startCreating) { upgrade = false; model.refresh() } else leaveCreate()
    }
    fun beginCreate() {
        if (isUltra) creating = true
        else if (onUpgradeUltra != null && !startCreating) onUpgradeUltra() else upgrade = true
    }
    LaunchedEffect(startCreating, state.loading) {
        if (startCreating && !state.loading && !handledCreate) { handledCreate = true; beginCreate() }
    }
    LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var sampleUri by rememberSaveable { mutableStateOf<String?>(null) }
    var public by rememberSaveable { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sampleUri = it?.toString() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    if (upgrade) {
        UltraRoute(onBack = { if (startCreating) onBack() else { upgrade = false; model.refresh() } }, modifier = modifier, onActivated = { upgrade = false; creating = true; model.refresh() })
        return
    }
    ScreenBackgroundBox(modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row { AppBackButton(onClick = { if (creating) leaveCreate() else onBack() }); Text(if (creating) "Create voice" else "Voices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(10.dp)) } }
            if (creating) {
                item { OutlinedTextField(name, { name = it.take(60) }, label = { Text("Voice name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(description, { description = it.take(1000) }, label = { Text("Describe the voice") }, placeholder = { Text("Warm, expressive, lightly raspy, relaxed Australian accent…") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item { Text("Describe a voice, or use 3 seconds to 5 minutes of clear speech. Video uploads use only their audio.", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !state.creating) { Text(if (sampleUri == null) "Choose audio or video sample" else "Replace selected sample") } }
                if (sampleUri != null) item { TextButton(onClick = { sampleUri = null }) { Text("Remove sample; use description") } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text("Community voice"); Text("Other people can use it with their characters.", style = MaterialTheme.typography.bodySmall) }; Switch(public, { public = it }) } }
                item { Button(onClick = { model.create(name, description, public, sampleUri?.let(Uri::parse)) { id -> creating = false; if (onSelected != null) onSelected(id) else if (startCreating) onBack() } }, enabled = !state.creating && state.available && state.canCreate && name.isNotBlank() && (description.trim().length >= 10 || sampleUri != null), modifier = Modifier.fillMaxWidth()) { Text(if (state.creating) "Creating voice…" else "Create voice") } }
            } else {
                item { Button(onClick = ::beginCreate, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Create a voice  ✦ Ultra") } }
                item { Text("Everyone can use official and community voices. Ultra includes custom voice creation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.loading) item { CircularProgressIndicator() }
                if (!state.loading && !state.available) item { Text("Voices are not available yet.") }
                items(state.voices, key = { it.id }) { voice ->
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = onSelected != null) { onSelected?.invoke(voice.id) }.padding(12.dp)) {
                            Column(Modifier.weight(1f)) { Text(voice.name, style = MaterialTheme.typography.titleMedium); Text(if (voice.official) "Official" else if (voice.mine) "Your voice" else "Community", style = MaterialTheme.typography.labelSmall); if (voice.description.isNotBlank() && !voice.official) Text(voice.description, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { player.preview(voice.id) }, enabled = state.available) { Text(if (playback.id == voice.id) "Stop" else "Listen") }
                        }
                    }
                }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::refresh) { Text("Try again") } } }
            playback.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
