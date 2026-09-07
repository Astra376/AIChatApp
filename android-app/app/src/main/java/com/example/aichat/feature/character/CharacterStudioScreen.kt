package com.example.aichat.feature.character

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.aichat.feature.voice.VoicePickerDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.design.AppTextField
import com.example.aichat.core.design.CharacterPortrait
import com.example.aichat.core.design.PrimaryButton
import com.example.aichat.core.design.SecondaryButton
import com.example.aichat.core.design.SelectionButton
import com.example.aichat.core.model.CharacterDraft
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.AppChrome
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.feature.chatlist.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class CharacterCreateStep {
    NAME,
    APPEARANCE,
    GREETING,
    VISIBILITY,
    DETAILS,
    TAGLINE,
    DESCRIPTION,
    DEFINITION
}

data class CharacterStudioUiState(
    val draft: CharacterDraft = CharacterDraft(),
    val step: CharacterCreateStep = CharacterCreateStep.NAME,
    val portraitOptions: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isGeneratingPortraits: Boolean = false,
    val isGeneratingGreeting: Boolean = false,
    val isEnhancingPortrait: Boolean = false,
    val selectedPreview: String? = null
)

@HiltViewModel
class CharacterStudioViewModel @Inject constructor(
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val draftStore: CharacterDraftStore,
    authRepository: com.example.aichat.core.auth.AuthRepository
) : ViewModel() {
    private val ownerId = authRepository.sessionState.value.profile?.userId.orEmpty()
    private val restored = draftStore.read(ownerId)
    private val _uiState = MutableStateFlow(CharacterStudioUiState(
        draft = restored.draft, step = restored.step, portraitOptions = restored.portraitOptions,
        selectedPreview = restored.selectedPreview
    ))
    private var portraitJob: kotlinx.coroutines.Job? = null
    val uiState: StateFlow<CharacterStudioUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            _uiState.collect { state ->
                draftStore.save(ownerId, SavedCharacterDraft(state.draft, state.step, state.portraitOptions, state.selectedPreview))
            }
        }
    }

    override fun onCleared() {
        val state = _uiState.value
        draftStore.save(ownerId, SavedCharacterDraft(state.draft, state.step, state.portraitOptions, state.selectedPreview))
        super.onCleared()
    }

    fun updateDraft(transform: (CharacterDraft) -> CharacterDraft) {
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft))
    }

    fun goBack(onExit: () -> Unit) {
        _uiState.value = when (_uiState.value.step) {
            CharacterCreateStep.NAME -> {
                onExit()
                return
            }
            CharacterCreateStep.APPEARANCE -> _uiState.value.copy(step = CharacterCreateStep.NAME)
            CharacterCreateStep.GREETING -> _uiState.value.copy(step = CharacterCreateStep.APPEARANCE)
            CharacterCreateStep.VISIBILITY -> _uiState.value.copy(step = CharacterCreateStep.GREETING)
            CharacterCreateStep.DETAILS -> _uiState.value.copy(step = CharacterCreateStep.VISIBILITY)
            CharacterCreateStep.TAGLINE,
            CharacterCreateStep.DESCRIPTION,
            CharacterCreateStep.DEFINITION -> _uiState.value.copy(step = CharacterCreateStep.DETAILS)
        }
    }

    fun goNext() {
        val current = _uiState.value
        val nextStep = when (current.step) {
            CharacterCreateStep.NAME -> CharacterCreateStep.APPEARANCE
            CharacterCreateStep.APPEARANCE -> CharacterCreateStep.GREETING
            CharacterCreateStep.GREETING -> CharacterCreateStep.VISIBILITY
            CharacterCreateStep.VISIBILITY -> CharacterCreateStep.DETAILS
            CharacterCreateStep.DETAILS -> CharacterCreateStep.DETAILS
            CharacterCreateStep.TAGLINE,
            CharacterCreateStep.DESCRIPTION,
            CharacterCreateStep.DEFINITION -> CharacterCreateStep.DETAILS
        }
        _uiState.value = current.copy(step = nextStep)
    }

    fun openDetailsStep(step: CharacterCreateStep) {
        _uiState.value = _uiState.value.copy(step = step)
    }

    fun generatePortraits() {
        val state = _uiState.value
        if (state.isGeneratingPortraits || state.isEnhancingPortrait) return
        val prompt = state.draft.appearance.ifBlank { state.draft.name }
        if (prompt.isBlank()) return
        _uiState.value = state.copy(isGeneratingPortraits = true)
        viewModelScope.launch {
            try {
                // Keep successful options even if one provider request fails.
                val results = kotlinx.coroutines.supervisorScope {
                    (1..4).map { index -> async {
                        characterRepository.generatePortrait(
                            "$prompt\nPortrait option $index. Square full-bleed character art, no borders.",
                            preview = true
                        )
                    } }.awaitAll()
                }
                val portraits = results.mapNotNull { it.getOrNull() }
                if (portraits.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(portraitOptions = portraits,
                        draft = _uiState.value.draft.copy(avatarUrl = null), selectedPreview = null)
                } else {
                    _events.emit(results.first().exceptionOrNull()?.userFacingMessage("Couldn't generate portraits. Try again.")
                        ?: "Couldn't generate portraits. Try again.")
                }
            } finally {
                _uiState.value = _uiState.value.copy(isGeneratingPortraits = false)
            }
        }
    }

    fun selectPortrait(url: String) {
        if (_uiState.value.isEnhancingPortrait || _uiState.value.isGeneratingPortraits) return
        if (url !in _uiState.value.portraitOptions) return
        if (_uiState.value.selectedPreview == url && _uiState.value.draft.avatarUrl != url) return
        _uiState.value = _uiState.value.copy(selectedPreview = url, isEnhancingPortrait = true,
            draft = _uiState.value.draft.copy(avatarUrl = url))
        portraitJob = viewModelScope.launch {
            try {
                characterRepository.generatePortrait("Enhance the selected character portrait. Preserve its identity and composition.",
                    sourceAvatarUrl = url)
                    .onSuccess { fullResolution -> updateDraft { it.copy(avatarUrl = fullResolution) } }
                    .onFailure { _events.emit("Preview saved. Tap it again to retry full quality.") }
            } finally {
                _uiState.value = _uiState.value.copy(isEnhancingPortrait = false)
            }
        }
    }

    fun uploadPortrait(uri: Uri) {
        portraitJob?.cancel()
        _uiState.value = _uiState.value.copy(selectedPreview = null)
        updateDraft { it.copy(avatarUrl = uri.toString()) }
    }

    fun generateGreeting() {
        val state = _uiState.value
        if (state.isGeneratingGreeting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingGreeting = true)
            characterRepository.generateGreeting(state.draft.name, state.draft.appearance)
                .onSuccess { greeting ->
                    _uiState.value = _uiState.value.copy(
                        isGeneratingGreeting = false,
                        draft = _uiState.value.draft.copy(greeting = greeting)
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isGeneratingGreeting = false)
                    _events.emit(it.userFacingMessage("Greeting generation failed."))
                }
        }
    }

    fun createCharacter(ownerUserId: String, onCreated: (String) -> Unit) {
        if (_uiState.value.isSaving || _uiState.value.isEnhancingPortrait) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val draft = _uiState.value.draft
            val finalDraft = draft.copy(
                systemPrompt = characterRepository.buildSystemPrompt(draft)
            )
            characterRepository.saveCharacter(finalDraft)
                .onSuccess { characterId ->
                    _uiState.value = _uiState.value.copy(
                        draft = finalDraft.copy(id = characterId)
                    )
                    conversationRepository.ensureConversation(ownerUserId, characterId)
                        .onSuccess { conversationId ->
                            _uiState.value = CharacterStudioUiState()
                            onCreated(conversationId)
                        }
                        .onFailure {
                            _uiState.value = _uiState.value.copy(isSaving = false)
                            _events.emit(it.userFacingMessage("Failed to open chat."))
                        }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    _events.emit(it.userFacingMessage("Failed to save character."))
                }
        }
    }
}

@Composable
fun CharacterStudioRoute(
    paddingValues: PaddingValues,
    ownerUserId: String = "",
    onBack: () -> Unit = {},
    onCreated: (String) -> Unit = {},
    viewModel: CharacterStudioViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showVoices by rememberSaveable { mutableStateOf(false) }
    if (showVoices) VoicePickerDialog(onDismiss = { showVoices = false }, onSelected = { id ->
        viewModel.updateDraft { it.copy(voiceId = id) }; showVoices = false
    })

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    ScreenBackgroundBox(snackbarHostState = snackbarHostState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CharacterCreateHeader(
                onBack = { viewModel.goBack(onBack) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(
                        start = AppChrome.screenHorizontalPadding,
                        top = AppChrome.compactHeaderVerticalPadding
                    )
            )

            CharacterCreateStepContent(
                state = state,
                onNameChanged = { value -> viewModel.updateDraft { it.copy(name = value.take(CHARACTER_NAME_LIMIT)) } },
                onAppearanceChanged = { value ->
                    viewModel.updateDraft { it.copy(appearance = value.take(CHARACTER_APPEARANCE_LIMIT)) }
                },
                onGreetingChanged = { value ->
                    viewModel.updateDraft { it.copy(greeting = value.take(CHARACTER_GREETING_LIMIT)) }
                },
                onVisibilityChanged = { value -> viewModel.updateDraft { it.copy(visibility = value) } },
                onTaglineChanged = { value -> viewModel.updateDraft { it.copy(tagline = value.take(50)) } },
                onPublicDescriptionChanged = { value -> viewModel.updateDraft { it.copy(bio = value.take(500)) } },
                onDefinitionChanged = { value -> viewModel.updateDraft { it.copy(characterDefinition = value.take(32_000)) } },
                onDefinitionPrivateChanged = { value -> viewModel.updateDraft { it.copy(definitionPrivate = value) } },
                onOpenDetailsStep = viewModel::openDetailsStep,
                onChooseVoice = { showVoices = true },
                onGeneratePortraits = viewModel::generatePortraits,
                onSelectPortrait = viewModel::selectPortrait,
                onUploadPortrait = viewModel::uploadPortrait,
                onGenerateGreeting = viewModel::generateGreeting,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = AppChrome.screenHorizontalPadding,
                        top = 104.dp,
                        end = AppChrome.screenHorizontalPadding,
                        bottom = 98.dp
                    )
            )

            CharacterCreateBottomAction(
                state = state,
                onNext = {
                    if (state.step == CharacterCreateStep.DETAILS) {
                        viewModel.createCharacter(ownerUserId, onCreated)
                    } else if (
                        state.step == CharacterCreateStep.TAGLINE ||
                        state.step == CharacterCreateStep.DESCRIPTION ||
                        state.step == CharacterCreateStep.DEFINITION
                    ) {
                        viewModel.openDetailsStep(CharacterCreateStep.DETAILS)
                    } else {
                        viewModel.goNext()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
            )
        }
    }
}

@Composable
private fun CharacterCreateHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppBackButton(onClick = onBack)
        Spacer(modifier = Modifier.width(AppChrome.compactControlGap))
        Text(
            text = "Create character",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CharacterCreateStepContent(
    state: CharacterStudioUiState,
    onNameChanged: (String) -> Unit,
    onAppearanceChanged: (String) -> Unit,
    onGreetingChanged: (String) -> Unit,
    onVisibilityChanged: (CharacterVisibility) -> Unit,
    onTaglineChanged: (String) -> Unit,
    onPublicDescriptionChanged: (String) -> Unit,
    onDefinitionChanged: (String) -> Unit,
    onDefinitionPrivateChanged: (Boolean) -> Unit,
    onOpenDetailsStep: (CharacterCreateStep) -> Unit,
    onChooseVoice: () -> Unit,
    onGeneratePortraits: () -> Unit,
    onSelectPortrait: (String) -> Unit,
    onUploadPortrait: (Uri) -> Unit,
    onGenerateGreeting: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state.step) {
        CharacterCreateStep.NAME -> NameStep(
            name = state.draft.name,
            onNameChanged = onNameChanged,
            modifier = modifier
        )
        CharacterCreateStep.APPEARANCE -> AppearanceStep(
            name = state.draft.name,
            description = state.draft.appearance,
            isEnhancing = state.isEnhancingPortrait,
            selectedAvatarUrl = state.selectedPreview ?: state.draft.avatarUrl,
            portraitOptions = state.portraitOptions,
            isGenerating = state.isGeneratingPortraits || state.isEnhancingPortrait,
            onDescriptionChanged = onAppearanceChanged,
            onGenerate = onGeneratePortraits,
            onSelectPortrait = onSelectPortrait,
            onUploadPortrait = onUploadPortrait,
            modifier = modifier
        )
        CharacterCreateStep.GREETING -> GreetingStep(
            greeting = state.draft.greeting,
            isGenerating = state.isGeneratingGreeting,
            onGreetingChanged = onGreetingChanged,
            onGenerateGreeting = onGenerateGreeting,
            modifier = modifier
        )
        CharacterCreateStep.VISIBILITY -> VisibilityStep(
            visibility = state.draft.visibility,
            onVisibilityChanged = onVisibilityChanged,
            modifier = modifier
        )
        CharacterCreateStep.DETAILS -> OptionalDetailsStep(
            onAddTagline = { onOpenDetailsStep(CharacterCreateStep.TAGLINE) },
            onAddDescription = { onOpenDetailsStep(CharacterCreateStep.DESCRIPTION) },
            onAddDefinition = { onOpenDetailsStep(CharacterCreateStep.DEFINITION) },
            onChooseVoice = onChooseVoice,
            voiceSelected = state.draft.voiceId != null,
            modifier = modifier
        )
        CharacterCreateStep.TAGLINE -> DetailTextStep(
            value = state.draft.tagline,
            onValueChanged = { onTaglineChanged(it.take(50)) },
            placeholder = "A dangerous prince with a soft spot for trouble.",
            description = "This is that people see before they tap to chat with your character",
            limit = 50,
            minLines = 2,
            maxLines = 3,
            modifier = modifier
        )
        CharacterCreateStep.DESCRIPTION -> DetailTextStep(
            value = state.draft.bio,
            onValueChanged = { onPublicDescriptionChanged(it.take(500)) },
            placeholder = "Introduce who they are, what kind of story they invite, and why someone might want to meet them.",
            description = "This helps introduce your Character to people and will appear on their profile.",
            limit = 500,
            minLines = 5,
            maxLines = 9,
            modifier = modifier
        )
        CharacterCreateStep.DEFINITION -> DefinitionStep(
            value = state.draft.characterDefinition,
            privateDefinition = state.draft.definitionPrivate,
            onValueChanged = { onDefinitionChanged(it.take(32_000)) },
            onPrivateChanged = onDefinitionPrivateChanged,
            modifier = modifier
        )
    }
}

@Composable
private fun NameStep(
    name: String,
    onNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        StepTitle("What's your character's name?")
        AppTextField(
            value = name,
            onValueChange = onNameChanged,
            placeholder = "",
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            shape = RoundedCornerShape(999.dp)
        )
    }
}

@Composable
private fun AppearanceStep(
    name: String,
    isEnhancing: Boolean,
    description: String,
    selectedAvatarUrl: String?,
    portraitOptions: List<String>,
    isGenerating: Boolean,
    onDescriptionChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onSelectPortrait: (String) -> Unit,
    onUploadPortrait: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onUploadPortrait(it)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        StepTitle("What do they look like?")
        Text(if (isEnhancing) "Enhancing selected portrait…" else "Choose a preview to create the full-quality portrait.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (portraitOptions.isNotEmpty() || isGenerating) {
            PortraitOptionGrid(
                options = portraitOptions,
                selectedAvatarUrl = selectedAvatarUrl,
                isGenerating = isGenerating,
                onSelectPortrait = onSelectPortrait
            )
        }
        AppTextField(
            value = description,
            onValueChange = onDescriptionChanged,
            placeholder = "Describe what ${name.ifBlank { "your character" }} looks like, then tap Generate.",
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            minLines = 4,
            maxLines = 7,
            shape = RoundedCornerShape(24.dp)
        )
        PrimaryButton(
            text = if (isGenerating) "Generating..." else "Generate",
            enabled = !isGenerating && description.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { AppIcon(AppIcons.sparkle, contentDescription = null) },
            onClick = onGenerate
        )
        Text(
            text = "or",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SecondaryButton(
            text = "Upload an image",
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { AppIcon(AppIcons.createAction, contentDescription = null) },
            onClick = { launcher.launch(arrayOf("image/*")) }
        )
    }
}

@Composable
private fun PortraitOptionGrid(
    options: List<String>,
    selectedAvatarUrl: String?,
    isGenerating: Boolean,
    onSelectPortrait: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppChrome.gridSpacing)) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    val url = options.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                    ) {
                        when {
                            url != null -> {
                                CharacterPortrait(
                                    name = "Portrait ${index + 1}",
                                    avatarUrl = url,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(enabled = !isGenerating) { onSelectPortrait(url) }
                                        .border(
                                            width = if (url == selectedAvatarUrl) 3.dp else 0.dp,
                                            color = if (url == selectedAvatarUrl) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Transparent
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                )
                            }
                            isGenerating -> {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GreetingStep(
    greeting: String,
    isGenerating: Boolean,
    onGreetingChanged: (String) -> Unit,
    onGenerateGreeting: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Greeting",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box {
                Column {
                    AppTextField(
                        value = greeting,
                        onValueChange = onGreetingChanged,
                        enabled = !isGenerating,
                        placeholder = "Example: Took you long enough. I've been waiting.",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 7,
                        shape = RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp,
                            bottomEnd = 0.dp,
                            bottomStart = 0.dp
                        )
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isGenerating, onClick = onGenerateGreeting)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(
                            AppIcons.sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Write for me",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (isGenerating) {
                    Box(
                        modifier = Modifier
                            .matchParentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun VisibilityStep(
    visibility: CharacterVisibility,
    onVisibilityChanged: (CharacterVisibility) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StepTitle("Visibility")
        VisibilityOption(
            title = "Public",
            body = "Anyone can find, view, and chat.",
            selected = visibility == CharacterVisibility.PUBLIC,
            onClick = { onVisibilityChanged(CharacterVisibility.PUBLIC) }
        )
        VisibilityOption(
            title = "Unlisted",
            body = "Only people with a link can view and chat.",
            selected = visibility == CharacterVisibility.UNLISTED,
            onClick = { onVisibilityChanged(CharacterVisibility.UNLISTED) }
        )
        VisibilityOption(
            title = "Private",
            body = "Only you can view and chat.",
            selected = visibility == CharacterVisibility.PRIVATE,
            onClick = { onVisibilityChanged(CharacterVisibility.PRIVATE) }
        )
    }
}

@Composable
private fun VisibilityOption(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SelectionButton(
        text = title,
        selected = selected,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    )
    Text(
        text = body,
        modifier = Modifier.padding(start = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OptionalDetailsStep(
    onAddTagline: () -> Unit,
    onAddDescription: () -> Unit,
    onAddDefinition: () -> Unit,
    onChooseVoice: () -> Unit,
    voiceSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepTitle("Add details")
        DetailOptionButton(title = if (voiceSelected) "Change voice" else "Choose voice", body = "Official and community voices, or create your own.", onClick = onChooseVoice)
        DetailOptionButton(
            title = "Add Tagline",
            body = "This is that people see before they tap to chat with your character",
            onClick = onAddTagline
        )
        DetailOptionButton(
            title = "Add Description",
            body = "This helps introduce your Character to people and will appear on their profile.",
            onClick = onAddDescription
        )
        DetailOptionButton(
            title = "Add Character Definition",
            body = "The Character Definition shapes how your character thinks, speaks, or behaves. These details can be shown or hidden.",
            onClick = onAddDefinition
        )
    }
}

@Composable
private fun DetailOptionButton(
    title: String,
    body: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            AppIcon(
                AppIcons.createAction,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailTextStep(
    value: String,
    onValueChanged: (String) -> Unit,
    placeholder: String,
    description: String,
    limit: Int,
    minLines: Int,
    maxLines: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppTextField(
            value = value,
            onValueChange = { onValueChanged(it.take(limit)) },
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            shape = RoundedCornerShape(24.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${value.length}/$limit",
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DefinitionStep(
    value: String,
    privateDefinition: Boolean,
    onValueChanged: (String) -> Unit,
    onPrivateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Keep definition private",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "This will hide definition from everyone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = privateDefinition, onCheckedChange = onPrivateChanged)
        }
        AppTextField(
            value = value,
            onValueChange = { onValueChanged(it.take(32_000)) },
            placeholder = "Example: Speaks in clipped sentences, distrusts easy kindness, and softens only when the user proves they are honest.",
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 18,
            shape = RoundedCornerShape(24.dp)
        )
        Text(
            text = "The Character Definition shapes how your character thinks, speaks, or behaves. These details can be shown or hidden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${value.length}/32000",
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun CharacterCreateBottomAction(
    state: CharacterStudioUiState,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = when (state.step) {
        CharacterCreateStep.NAME -> state.draft.name.isNotBlank()
        CharacterCreateStep.APPEARANCE -> !state.draft.avatarUrl.isNullOrBlank() && !state.isEnhancingPortrait && !state.isGeneratingPortraits
        CharacterCreateStep.GREETING -> state.draft.greeting.isNotBlank() && !state.isGeneratingGreeting
        CharacterCreateStep.VISIBILITY -> true
        CharacterCreateStep.DETAILS -> !state.isSaving
        CharacterCreateStep.TAGLINE,
        CharacterCreateStep.DESCRIPTION,
        CharacterCreateStep.DEFINITION -> true
    } && !state.isGeneratingPortraits

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppChrome.screenHorizontalPadding,
                        vertical = AppChrome.screenBottomPadding
                    )
            ) {
                PrimaryButton(
                    text = when {
                        state.isSaving -> "Creating..."
                        state.step == CharacterCreateStep.DETAILS -> "Create"
                        state.step == CharacterCreateStep.TAGLINE ||
                            state.step == CharacterCreateStep.DESCRIPTION ||
                            state.step == CharacterCreateStep.DEFINITION -> "Done"
                        else -> "Next"
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = if (state.step == CharacterCreateStep.DETAILS) {
                        { AppIcon(AppIcons.createAction, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = onNext
                )
            }
        }
    }
}
