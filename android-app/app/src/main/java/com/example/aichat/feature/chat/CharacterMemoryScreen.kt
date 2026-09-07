package com.example.aichat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.example.aichat.core.design.AppTextField
import com.example.aichat.core.design.PrimaryButton
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.feature.character.PsychologySection
import com.example.aichat.feature.character.EmotionEditor
import com.example.aichat.feature.character.PersonalityEditor
import com.example.aichat.feature.character.MindEditor
import com.example.aichat.core.network.CharacterPsychologyDto
import com.example.aichat.core.network.CharacterEmotionDto
import com.example.aichat.core.network.CharacterMemoryDto
import com.example.aichat.core.network.CharacterPersonalityDto
import com.example.aichat.core.network.MemoryLimitsDto
import com.example.aichat.core.network.MemorySceneDto
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.ui.ShimmerBox
import com.example.aichat.core.ui.ShimmerTextLine
import com.example.aichat.core.ui.pageContentFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CharacterMemoryUiState(
    val shortTerm: String = "",
    val midTerm: String = "",
    val longTerm: String = "",
    val originalShortTerm: String = "",
    val originalMidTerm: String = "",
    val originalLongTerm: String = "",
    val limits: MemoryLimitsDto = MemoryLimitsDto(),
    val tier: String = "standard",
    val scene: MemorySceneDto = MemorySceneDto(),
    val emotion: CharacterEmotionDto? = null,
    val personality: CharacterPersonalityDto? = null,
    val psychology: CharacterPsychologyDto? = null,
    val emotionEdited: Boolean = false,
    val personalityEdited: Boolean = false,
    val psychologyEdited: Boolean = false,
    val hasServerDetails: Boolean = false,
    val loadFailed: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false
) {
    val hasChanges: Boolean get() = shortTerm != originalShortTerm || midTerm != originalMidTerm || longTerm != originalLongTerm || emotionEdited || personalityEdited || psychologyEdited
    val withinLimits: Boolean get() = shortTerm.length <= limits.shortTerm && midTerm.length <= limits.midTerm && longTerm.length <= limits.longTerm

    fun withMemory(memory: CharacterMemoryDto, serverDetails: Boolean, replaceDraft: Boolean = !hasChanges): CharacterMemoryUiState = copy(
        shortTerm = if (replaceDraft) memory.shortTerm else shortTerm,
        midTerm = if (replaceDraft) memory.midTerm else midTerm,
        longTerm = if (replaceDraft) memory.longTerm else longTerm,
        originalShortTerm = if (replaceDraft) memory.shortTerm else originalShortTerm,
        originalMidTerm = if (replaceDraft) memory.midTerm else originalMidTerm,
        originalLongTerm = if (replaceDraft) memory.longTerm else originalLongTerm,
        limits = memory.limits, tier = memory.tier, scene = memory.scene,
        emotion = if (replaceDraft || !emotionEdited) memory.emotion else emotion,
        personality = if (replaceDraft || !personalityEdited) memory.personality else personality,
        psychology = if (replaceDraft || !psychologyEdited) memory.psychology else psychology,
        emotionEdited = if (replaceDraft) false else emotionEdited,
        personalityEdited = if (replaceDraft) false else personalityEdited,
        psychologyEdited = if (replaceDraft) false else psychologyEdited,
        hasServerDetails = serverDetails, isLoading = false, loadFailed = false
    )
}

@HiltViewModel
class CharacterMemoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CharacterMemoryRepository
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])
    private val mutableState = MutableStateFlow(CharacterMemoryUiState())
    private val mutableEvents = MutableSharedFlow<String>()
    val uiState = mutableState.asStateFlow()
    val events = mutableEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.observeMemory(conversationId).collect { snapshot ->
                snapshot?.let { memory -> mutableState.update { it.withMemory(memory.memory, memory.hasServerDetails) } }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh(conversationId).onFailure { error ->
                mutableState.update { it.copy(isLoading = false, loadFailed = true) }
                mutableEvents.emit(error.userFacingMessage("Couldn't load character memory."))
            }
        }
    }

    // Keep pasted and previously saved content intact. Over-limit text is clearly marked before saving.
    fun updateShortTerm(value: String) { mutableState.update { it.copy(shortTerm = value) } }
    fun updateMidTerm(value: String) { mutableState.update { it.copy(midTerm = value) } }
    fun updateLongTerm(value: String) { mutableState.update { it.copy(longTerm = value) } }

    fun updateEmotion(value: CharacterEmotionDto) { mutableState.update { it.copy(emotion=value,emotionEdited=true) } }
    fun updatePersonality(value: CharacterPersonalityDto) { mutableState.update { it.copy(personality=value,personalityEdited=true) } }
    fun updatePsychology(value: CharacterPsychologyDto) { mutableState.update { it.copy(psychology=value,psychologyEdited=true) } }

    fun save() {
        val state = mutableState.value
        if (state.isSaving || !state.hasChanges || !state.withinLimits || !state.hasServerDetails) return
        mutableState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.save(conversationId, state.shortTerm, state.longTerm, state.midTerm,
                    state.emotion.takeIf { state.emotionEdited }, state.personality.takeIf { state.personalityEdited },
                    state.psychology.takeIf { state.psychologyEdited })
                    .onSuccess { saved ->
                        mutableState.update { it.withMemory(saved, true, replaceDraft = true) }
                        mutableEvents.emit("Character psychology saved.")
                    }
                    .onFailure { error -> mutableEvents.emit(error.userFacingMessage("Couldn't save character memory.")) }
            } finally {
                mutableState.update { it.copy(isSaving = false) }
            }
        }
    }
}

@Composable
fun CharacterMemoryRoute(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    viewModel: CharacterMemoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collect { snackbarHostState.showSnackbar(it) } }
    CharacterMemoryScreen(paddingValues, state, snackbarHostState, onBack,
        viewModel::updateShortTerm, viewModel::updateMidTerm, viewModel::updateLongTerm,
        viewModel::save, viewModel::refresh, viewModel::updateEmotion, viewModel::updatePersonality, viewModel::updatePsychology)
}

@Composable
internal fun CharacterMemoryScreen(
    paddingValues: PaddingValues,
    state: CharacterMemoryUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onShortTermChanged: (String) -> Unit,
    onMidTermChanged: (String) -> Unit,
    onLongTermChanged: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onEmotionChanged: (CharacterEmotionDto) -> Unit = {},
    onPersonalityChanged: (CharacterPersonalityDto) -> Unit = {},
    onPsychologyChanged: (CharacterPsychologyDto) -> Unit = {}
) {
    ScreenBackgroundBox(snackbarHostState = snackbarHostState) {
        Column(Modifier.pageContentFrame(paddingValues = paddingValues, imeAware = true),
            verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppChrome.compactControlGap),
                verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onClick = onBack)
                Text("Character psychology", style = MaterialTheme.typography.titleLarge)
            }
            if (state.isLoading) {
                CharacterMemoryPlaceholder()
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing)) {
                    if (state.hasServerDetails) {
                        Text(if (state.tier == "ultra") "Ultra memory" else "Standard memory",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(if (state.loadFailed) "Memory details couldn't be loaded." else "Loading memory details…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.loadFailed) SecondaryButton(text = "Retry", onClick = onRetry)
                    if (state.hasServerDetails) {
                        SceneMemory(state.scene)
                        state.emotion?.let { emotion -> PsychologySection("Emotions & momentum") { EmotionEditor(emotion,!state.isSaving,onEmotionChanged) } }
                        state.personality?.let { personality -> PsychologySection("Personality") { PersonalityEditor(personality,!state.isSaving,onPersonalityChanged) } }
                        state.psychology?.let { psychology -> PsychologySection("Mind & life") { MindEditor(psychology,!state.isSaving,onPsychologyChanged) } }
                    }
                    PsychologySection("Memory", initiallyExpanded = true) {
                    MemoryEditor("Short-term memory", "What is happening now: the current scene, goals and recent developments.",
                        state.shortTerm, state.limits.shortTerm, 5, "Current scene and recent developments",
                        state.hasServerDetails && !state.isSaving, onShortTermChanged)
                    MemoryEditor("Mid-term memory", "The developing story: recent arcs, promises and unresolved threads.",
                        state.midTerm, state.limits.midTerm, 6, "Story arcs and unresolved threads",
                        state.hasServerDetails && !state.isSaving, onMidTermChanged)
                    MemoryEditor("Long-term memory", "Lasting facts, important events and relationships the character should remember.",
                        state.longTerm, state.limits.longTerm, 8, "Important events and lasting details",
                        state.hasServerDetails && !state.isSaving, onLongTermChanged)
                    }
                    if (!state.withinLimits && state.hasServerDetails) Text(
                        "Your saved memory is intact. Shorten the fields over their limit before saving.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                PrimaryButton(text = if (state.isSaving) "Saving…" else "Save changes", modifier = Modifier.fillMaxWidth(),
                    enabled = state.hasChanges && state.withinLimits && state.hasServerDetails && !state.isSaving, onClick = onSave)
            }
        }
    }
}

@Composable
private fun SceneMemory(scene: MemorySceneDto) {
    if (scene.summary.isBlank() && scene.location.isNullOrBlank() && scene.fictionalTime.isNullOrBlank() && scene.timeline.isEmpty()) return
    PsychologySection("Scene & story time") {
        if (scene.summary.isNotBlank()) Text(scene.summary, style = MaterialTheme.typography.bodyMedium)
        scene.location?.takeIf { it.isNotBlank() }?.let { Text("Location · $it", style = MaterialTheme.typography.bodySmall) }
        scene.fictionalTime?.takeIf { it.isNotBlank() }?.let { Text("Story time · $it", style = MaterialTheme.typography.bodySmall) }
        scene.timeline.forEach { event ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                event.fictionalTime?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(event.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmotionMemory(emotion: CharacterEmotionDto) {
    MemoryCard("Character state") {
        if (emotion.mood.isNotBlank()) Text(emotion.mood, style = MaterialTheme.typography.titleMedium)
        if (emotion.reason.isNotBlank()) Text(emotion.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Trust" to emotion.trust, "Affection" to emotion.affection, "Stress" to emotion.stress,
            "Energy" to emotion.energy, "Openness" to emotion.openness).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { value.coerceIn(0, 100) / 100f }, modifier = Modifier.weight(1.4f))
            }
        }
    }
}

@Composable
private fun PersonalityMemory(personality: CharacterPersonalityDto) {
    MemoryCard("Personality") {
        if (personality.description.isNotBlank()) Text(personality.description, style = MaterialTheme.typography.bodyMedium)
        listOf("Warmth" to personality.warmth, "Confidence" to personality.confidence,
            "Playfulness" to personality.playfulness, "Formality" to personality.formality,
            "Assertiveness" to personality.assertiveness).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { value.coerceIn(0, 100) / 100f }, modifier = Modifier.weight(1.4f))
            }
        }
    }
}

@Composable
private fun MemoryCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MemoryEditor(title: String, description: String, value: String, limit: Int, minLines: Int,
    placeholder: String, enabled: Boolean, onValueChanged: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextField(value = value, onValueChange = onValueChanged, placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(), enabled = enabled, minLines = minLines, maxLines = minLines + 6,
            shape = RoundedCornerShape(20.dp))
        Text("${value.length} / $limit characters", Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall,
            color = if (value.length > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CharacterMemoryPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(AppChrome.sectionSpacing)) {
        repeat(3) {
            ShimmerTextLine(width = 176.dp, height = 22.dp)
            ShimmerBox(Modifier.fillMaxWidth().height(160.dp), shape = RoundedCornerShape(20.dp))
        }
    }
}
