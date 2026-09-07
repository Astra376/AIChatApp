package com.example.aichat.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.design.*
import com.example.aichat.core.model.ThemeMode
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.*
import com.example.aichat.feature.activity.NotificationRepository
import com.example.aichat.feature.activity.NotificationSettingsDto
import com.example.aichat.feature.chat.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val streamingHaptics: Boolean = true,
    val notifications: NotificationSettingsDto = NotificationSettingsDto(),
    val isSaving: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val notifications: NotificationRepository,
    private val chatRepository: ChatRepository,
    private val groupRepository: com.example.aichat.feature.group.GroupRepository
) : ViewModel() {
    private val saving = MutableStateFlow(false)
    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()
    val uiState = combine(settingsRepository.themeMode, settingsRepository.streamingHaptics, notifications.settings, saving) { theme, haptics, prefs, busy ->
        SettingsUiState(theme, haptics, prefs, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
    init { viewModelScope.launch { runCatching { notifications.refreshSettings() }.onFailure { _events.emit(it.userFacingMessage("Couldn't load notification settings.")) } } }
    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settingsRepository.setThemeMode(mode) } }
    fun setHaptics(enabled: Boolean) { viewModelScope.launch { settingsRepository.setStreamingHaptics(enabled) } }
    fun setNotifications(value: NotificationSettingsDto) {
        if (!saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try { notifications.updateSettings(value) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _events.emit(error.userFacingMessage("Couldn't save notification settings.")) }
            finally { saving.value = false }
        }
    }
    fun signOut() { viewModelScope.launch { chatRepository.cancelAllOperations(); groupRepository.cancelAllOperations(); authRepository.signOut() } }
}

@Composable
fun SettingsRoute(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenVoices: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenPersonas: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    ScreenBackgroundBox(snackbarHostState = snackbar) {
        LazyColumn(
            modifier = Modifier.pageContentFrame(paddingValues = paddingValues, imeAware = true),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onClick = onBack)
                Text("Settings", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            } }
            item { Text("Appearance", style = MaterialTheme.typography.titleMedium) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = state.themeMode == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            } }
            item { SecondaryButton("Customize appearance · Ultra", modifier = Modifier.fillMaxWidth(), onClick = onOpenAppearance) }
            item { SecondaryButton("Personas", modifier = Modifier.fillMaxWidth(), onClick = onOpenPersonas) }
            item { SettingSwitch("Streaming vibration", "Light feedback as a reply arrives", state.streamingHaptics, onChanged = viewModel::setHaptics) }
            item { Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            item { SettingSwitch("Phone notifications", "Messages and activity on this phone", state.notifications.pushEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(pushEnabled = it)) } }
            item { SettingSwitch("Email notifications", "Updates sent to your account email", state.notifications.emailEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(emailEnabled = it)) } }
            item { SettingSwitch("Character messages", "Let characters continue your conversations", state.notifications.chatMessagesEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(chatMessagesEnabled = it)) } }
            item { SettingSwitch("New followers", "Individual updates with busy periods grouped", state.notifications.followersEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(followersEnabled = it)) } }
            item { SettingSwitch("New characters", "Updates from creators and characters you may like", state.notifications.characterUpdatesEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(characterUpdatesEnabled = it)) } }
            item { SecondaryButton("Voices", modifier = Modifier.fillMaxWidth(), onClick = onOpenVoices) }
            item { Text("Meek ${com.example.aichat.BuildConfig.VERSION_NAME} · ${com.example.aichat.BuildConfig.BUILD_SHA}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SecondaryButton("Log Out", modifier = Modifier.fillMaxWidth(), onClick = viewModel::signOut) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().appOutlineSurface(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onChanged(!checked) }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChanged, enabled = enabled)
    }
}
