package com.example.aichat.feature.persona

import com.example.aichat.core.auth.AuthRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import retrofit2.Retrofit

/** Shared identity labels update immediately after selection without restarting the chat or polling. */
@Singleton class PersonaIdentityRepository @Inject constructor(retrofit: Retrofit, private val auth: AuthRepository) {
    private data class Key(val userId: String, val conversationId: String)
    private val api = retrofit.create(PersonaApi::class.java)
    private val selections = MutableStateFlow<Map<Key, ConversationPersonaDto>>(emptyMap())
    private val loading = ConcurrentHashMap.newKeySet<Key>()

    fun observeName(conversationId: String) = combine(auth.sessionState, selections) { session, values ->
        session.profile?.userId?.let { values[Key(it, conversationId)]?.effectiveName }
    }.distinctUntilChanged()

    suspend fun ensureLoaded(conversationId: String) {
        val userId = auth.sessionState.value.profile?.userId ?: return
        val key = Key(userId, conversationId)
        if (selections.value.containsKey(key) || !loading.add(key)) return
        try {
            val result = api.selection(conversationId)
            if (auth.sessionState.value.profile?.userId == userId) selections.update { it + (key to result) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Account name remains a usable offline fallback. */ }
        finally { loading.remove(key) }
    }

    fun remember(selection: ConversationPersonaDto) {
        val userId = auth.sessionState.value.profile?.userId ?: return
        if (selection.conversationId.isBlank()) return
        selections.update { it + (Key(userId, selection.conversationId) to selection) }
    }

    fun rememberLibrary(library: PersonaLibraryDto) {
        val userId = auth.sessionState.value.profile?.userId ?: return
        val items = library.items.associateBy { it.id }
        selections.update { values -> values.mapValues { (key, previous) ->
            if (key.userId != userId) previous else {
                val personal = previous.personaId?.let(items::get)
                val deleted = previous.mode == "personal" && personal == null
                val name = when {
                    previous.mode == "account" || deleted -> library.accountName
                    previous.mode == "personal" -> personal!!.name
                    else -> previous.characterDefault?.name ?: library.defaultPersonaId?.let { items[it]?.name } ?: library.accountName
                }
                previous.copy(effectiveName = name, accountName = library.accountName, defaultPersonaId = library.defaultPersonaId,
                    mode = if (deleted) "account" else previous.mode, personaId = if (deleted) null else previous.personaId)
            }
        } }
    }
}
