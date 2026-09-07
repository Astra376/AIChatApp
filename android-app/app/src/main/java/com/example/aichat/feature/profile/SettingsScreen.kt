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

private enum class SettingsCategory(val title: String, val detail: String, val icon: AppIconGlyph) {
    APPEARANCE("Appearance", "Theme, profile, backgrounds and app icon", AppIcons.edit),
    CHATS("Chats & voices", "Streaming feedback, personas and voices", AppIcons.chats),
    NOTIFICATIONS("Notifications", "Phone, email and activity preferences", AppIcons.activity),
    ACCOUNT("Account", "Subscription, app version and sign out", AppIcons.profile)
}

@Composable
fun SettingsRoute(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenVoices: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenPersonas: () -> Unit = {},
    onOpenSubscription: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearance = com.example.aichat.feature.customization.LocalAppearance.current
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    androidx.activity.compose.BackHandler(category != null) { category = null }
    ScreenBackgroundBox(snackbarHostState = snackbar) {
        Column(Modifier.pageContentFrame(paddingValues = paddingValues)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onClick = { if (category == null) onBack() else category = null })
                Text(category?.title ?: "Settings", style = MaterialTheme.typography.titleLarge)
            }
            key(category) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (category) {
                        null -> items(SettingsCategory.entries.size) { index ->
                            val item = SettingsCategory.entries[index]
                            SettingLink(item.title, item.detail, item.icon) { category = item }
                        }
                        SettingsCategory.APPEARANCE -> {
                            item { Text("Color theme", style = MaterialTheme.typography.titleSmall) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeMode.entries.forEach { mode -> FilterChip(selected = state.themeMode == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                            } }
                            item { SettingLink("Your Meek", "Profile styles, backgrounds, frames and icons", AppIcons.edit, onOpenAppearance) }
                        }
                        SettingsCategory.CHATS -> {
                            item { SettingSwitch("Streaming vibration", "Tactile feedback as characters appear", state.streamingHaptics, onChanged = viewModel::setHaptics) }
                            item { SettingLink("Personas", "Manage the identities you use in chats", AppIcons.profile, onOpenPersonas) }
                            item { SettingLink("Voices", "Listen, choose or create a voice", AppIcons.chats, onOpenVoices) }
                        }
                        SettingsCategory.NOTIFICATIONS -> {
                            item { Text("Delivery", style = MaterialTheme.typography.titleSmall) }
                            item { SettingSwitch("Phone notifications", "Messages and activity on this phone", state.notifications.pushEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(pushEnabled = it)) } }
                            item { SettingSwitch("Email notifications", "Updates sent to your account email", state.notifications.emailEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(emailEnabled = it)) } }
                            item { Text("Activity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                            item { SettingSwitch("Character messages", "Let characters continue your conversations", state.notifications.chatMessagesEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(chatMessagesEnabled = it)) } }
                            item { SettingSwitch("New followers", "Busy periods are grouped together", state.notifications.followersEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(followersEnabled = it)) } }
                            item { SettingSwitch("New characters", "Updates from creators and characters you may like", state.notifications.characterUpdatesEnabled, !state.isSaving) { viewModel.setNotifications(state.notifications.copy(characterUpdatesEnabled = it)) } }
                        }
                        SettingsCategory.ACCOUNT -> {
                            item { SettingLink("Subscription", if (appearance.ultra) "Ultra active · Manage or change test access" else "Meek Standard · View plans and test access", AppIcons.sparkle, onOpenSubscription) }
                            item { Text("Meek ${com.example.aichat.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp)) }
                            item { SecondaryButton("Log out", modifier = Modifier.fillMaxWidth(), onClick = viewModel::signOut) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingLink(title: String, detail: String, icon: AppIconGlyph, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(icon, null, size = 22.dp)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChanged: (Boolean) -> Unit) {
    Surface(onClick = { onChanged(!checked) }, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
