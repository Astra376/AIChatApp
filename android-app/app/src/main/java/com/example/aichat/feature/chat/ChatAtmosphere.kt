package com.example.aichat.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.CharacterEmotionDto
import com.example.aichat.core.network.EmotionPortraitsDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.example.aichat.core.network.userFacingMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.coroutineScope
import com.example.aichat.core.network.CharacterApi

@HiltViewModel
class ChatAtmosphereViewModel @Inject constructor(
    private val api: CharacterApi,
    private val memory: CharacterMemoryRepository,
    savedState: SavedStateHandle
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedState["conversationId"])
    private val portraits = MutableStateFlow(EmotionPortraitsDto())
    private var loadedCharacter: String? = null
    private var lastPortraitRefresh = 0L
    val portrait: StateFlow<String?> = combine(memory.observeMemory(conversationId), portraits) { snapshot, images ->
        if (images.format != "transparent-upper-body-v1") return@combine null
        val emotion = emotionPortraitKey(snapshot?.memory?.emotion)
        images.portraits[emotion] ?: images.portraits["neutral"]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artwork = portraits.asStateFlow()
    var retryingArtwork by mutableStateOf(false); private set
    var artworkError by mutableStateOf<String?>(null); private set
    fun retryArtwork(characterId: String) {
        if (retryingArtwork || portraits.value.generating) return
        retryingArtwork = true
        viewModelScope.launch {
            try {
                portraits.value = api.generateEmotionPortraits(characterId)
                artworkError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { artworkError = error.userFacingMessage("Couldn't start character artwork. Try again.") }
            finally { retryingArtwork = false }
        }
    }
    val portraitsGenerating: Boolean get() = portraits.value.generating

    suspend fun refresh(characterId: String) = coroutineScope {
        val refreshMemory = async { memory.refresh(conversationId) }
        refreshPortraits(characterId)
        refreshMemory.await()
    }

    suspend fun refreshPortraits(characterId: String) {
        if (loadedCharacter != characterId || portraits.value.generating || android.os.SystemClock.elapsedRealtime() - lastPortraitRefresh > 30_000) {
            try {
                portraits.value = api.emotionPortraits(characterId)
                loadedCharacter = characterId
                lastPortraitRefresh = android.os.SystemClock.elapsedRealtime()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional scenery never blocks a conversation. */ }
        }
    }
}

internal fun emotionPortraitKey(emotion: CharacterEmotionDto?): String {
    if (emotion == null) return "neutral"
    val strongest = listOf("joy" to emotion.joy, "sadness" to emotion.sadness,
        "anger" to emotion.anger, "fear" to emotion.fear, "affection" to emotion.affection).maxBy { it.second }
    return if (strongest.second >= 58) strongest.first else "neutral"
}
