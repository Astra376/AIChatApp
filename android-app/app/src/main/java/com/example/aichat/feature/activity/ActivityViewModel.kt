package com.example.aichat.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityUiState(
    val items: List<ActivityNotificationDto> = emptyList(),
    val loading: Boolean = true,
    val nextCursor: String? = null,
    val error: String? = null
)

@HiltViewModel
class ActivityViewModel @Inject constructor(private val repository: NotificationRepository) : ViewModel() {
    private val _state = MutableStateFlow(ActivityUiState())
    val state = _state.asStateFlow()
    private val pendingRemovals = mutableSetOf<String>()
    private var clearing = false
    init { load() }

    fun load(more: Boolean = false) {
        if (more && (_state.value.loading || _state.value.nextCursor == null)) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = repository.page(if (more) _state.value.nextCursor else null)
                _state.update { current -> current.copy(
                    items = ((if (more) current.items else emptyList()) + page.items).distinctBy { it.id }.filterNot { it.id in pendingRemovals },
                    loading = false, nextCursor = page.nextCursor
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = "Activity couldn't load. Try again.") } }
        }
    }
    fun markRead(item: ActivityNotificationDto) {
        _state.update { it.copy(items = it.items.map { row -> if (row.id == item.id) row.copy(read = true) else row }) }
        viewModelScope.launch { runCatching { repository.markRead(item) } }
    }
    fun clear(item: ActivityNotificationDto) {
        if (!pendingRemovals.add(item.id)) return
        _state.update { it.copy(items = it.items.filterNot { row -> row.id == item.id }) }
        viewModelScope.launch {
            try { repository.clear(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { it.copy(items = (it.items + item).distinctBy { row -> row.id }.sortedByDescending { row -> row.updatedAt }, error = "Couldn't clear this notification.") }
            } finally { pendingRemovals.remove(item.id) }
        }
    }
    fun clearAll() {
        if (clearing) return
        clearing = true
        val previous = _state.value.items
        _state.update { it.copy(items = emptyList()) }
        viewModelScope.launch {
            try { repository.clearAll() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(items = previous, error = "Couldn't clear activity. Try again.") } }
            finally { clearing = false }
        }
    }
    fun clearError() { _state.update { it.copy(error = null) } }
}
