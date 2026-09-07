package com.example.aichat.feature.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIcons
import com.example.aichat.core.network.userFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel class ReadAloudViewModel @Inject constructor(
    private val voices: VoiceRepository, @ApplicationContext private val context: Context
) : ViewModel() {
    data class State(val id: String? = null, val preparing: Boolean = false, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var request: Job? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    fun read(conversationId: String, messageId: String) = play(messageId) { voices.speak(conversationId, messageId) }
    fun preview(voiceId: String) = play(voiceId) { voices.preview(voiceId) }
    private fun play(id: String, load: suspend () -> String) {
        if (_state.value.id == id) { stop(); return }
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        // Check media volume before any network request or paid generation.
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            _state.value = State(error = "Turn up media volume to read aloud."); return
        }
        _state.value = State(id = id, preparing = true)
        request = viewModelScope.launch {
            try {
                val url = load()
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) { stop(); return@launch }
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(context, Uri.parse(url))
                    setOnPreparedListener { prepared ->
                        if (player !== prepared) return@setOnPreparedListener
                        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 && audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { prepared.start(); _state.value = State(id = id) } else stop()
                    }
                    setOnCompletionListener { stop() }
                    setOnErrorListener { _, _, _ -> stop(); _state.value = State(error = "Audio could not be played."); true }
                    prepareAsync()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { stop(); _state.value = State(error = error.userFacingMessage("Voice could not be played.")) }
        }
    }
    fun takeError(): String? { val error = _state.value.error; _state.value = _state.value.copy(error = null); return error }
    fun stop() { request?.cancel(); request = null; player?.release(); player = null; audioManager.abandonAudioFocusRequest(focus); _state.value = State() }
    override fun onCleared() { stop(); super.onCleared() }
}

@Composable fun ReadAloudButton(conversationId: String, messageId: String, modifier: Modifier = Modifier, onError: ((String) -> Unit)? = null) {
    val model: ReadAloudViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.error) { model.takeError()?.let {
        if (onError != null) onError(it) else android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
    } }
    IconButton(onClick = { model.read(conversationId, messageId) }, modifier = modifier) {
        if (state.id == messageId && state.preparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else if (state.id == messageId) AppIcon(AppIcons.stop, "Stop reading")
        else Icon(Icons.Default.PlayArrow, "Read aloud")
    }
}

@Composable fun ReadAloudLifecycle() {
    val model: ReadAloudViewModel = hiltViewModel()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
}
