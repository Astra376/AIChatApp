package com.example.aichat.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.userFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.*

@Serializable data class ChatModelPreferences(
    val conversationId: String = "", val mode: String = "auto", val effectiveModel: String = "Meek Standard",
    val modelId: String = "", val ultra: Boolean = false,
    val availableModes: List<String> = listOf("auto", "standard"), val chatFont: String = "default"
)
interface ChatPreferencesApi {
    @GET("v1/conversations/{id}/model") suspend fun get(@Path("id") id: String): ChatModelPreferences
    @PATCH("v1/conversations/{id}/model") suspend fun update(@Path("id") id: String, @Body values: Map<String,String>): ChatModelPreferences
}
@HiltViewModel class ChatPreferencesViewModel @Inject constructor(retrofit: Retrofit, savedState: SavedStateHandle) : ViewModel() {
    private val id: String = checkNotNull(savedState["conversationId"])
    private val api = retrofit.create(ChatPreferencesApi::class.java)
    private val _preferences = MutableStateFlow(ChatModelPreferences())
    val preferences = _preferences.asStateFlow()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    init { refresh() }
    fun refresh() = load { api.get(id) }
    fun update(key: String, value: String) = load { api.update(id, mapOf(key to value)) }
    private fun load(action: suspend () -> ChatModelPreferences) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { _preferences.value = action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.userFacingMessage("Couldn't update chat preferences.") }
            finally { busy = false }
        }
    }
}

internal fun chatFontFamily(choice: String): FontFamily? = when (choice) {
    "sans" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "rounded" -> FontFamily.Cursive
    else -> null
}
internal val LocalChatFont = staticCompositionLocalOf<FontFamily?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatPreferencesSheet(
    onDismiss: () -> Unit, onUpgrade: () -> Unit, model: ChatPreferencesViewModel = hiltViewModel(),
    artwork: com.example.aichat.core.network.EmotionPortraitsDto = com.example.aichat.core.network.EmotionPortraitsDto(),
    canManageArtwork: Boolean = false, artworkBusy: Boolean = false, artworkError: String? = null,
    onRetryArtwork: () -> Unit = {}
) {
    val prefs by model.preferences.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat preferences", style = MaterialTheme.typography.titleLarge)
            Text("Model", style = MaterialTheme.typography.titleMedium)
            listOf("auto" to "Automatic", "standard" to "Meek Standard", "ultra" to "Meek Ultra").forEach { (value, label) ->
                PreferenceChoice(label, prefs.mode == value, !model.busy) {
                    if (value == "ultra" && !prefs.ultra) { onDismiss(); onUpgrade() }
                    else model.update("mode", value)
                }
            }
            Text("Automatic uses fast replies for everyday conversation and extra reasoning when needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Chat font", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            listOf("default" to "Default", "sans" to "Clean", "serif" to "Book", "mono" to "Typewriter", "rounded" to "Handwritten").forEach { (value, label) ->
                PreferenceChoice(label + if (value != "default" && !prefs.ultra) " · Ultra" else "", prefs.chatFont == value, !model.busy) {
                    if (value != "default" && !prefs.ultra) { onDismiss(); onUpgrade() }
                    else model.update("chatFont", value)
                }
            }
            if (canManageArtwork) {
                Text("Character artwork", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(when {
                    artwork.generating || artworkBusy -> "Creating expressions · ${artwork.portraits.size} of 6 ready"
                    artwork.failed -> "Some expressions couldn't be created. Your ready artwork is saved."
                    artwork.portraits.isNotEmpty() -> "${artwork.portraits.size} expressions ready"
                    else -> "Add upper-body expressions behind the conversation."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (artwork.generating || artworkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (artwork.failed || artwork.portraits.isEmpty()) TextButton(onClick = onRetryArtwork) {
                    Text(if (artwork.failed) "Retry missing expressions" else "Create chat artwork")
                }
                artworkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::refresh) { Text("Retry") } }
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable private fun PreferenceChoice(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
