package com.example.aichat.feature.ultra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.ScreenBackgroundBox
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.*
import java.text.NumberFormat
import java.util.Currency
import java.util.UUID

@Serializable data class UltraDto(val active: Boolean = false, val available: Boolean = false, val model: String = "",
    val currency: String? = null, val unitAmount: Long? = null, val interval: String? = null, val intervalCount: Int = 1)
@Serializable data class CheckoutRequest(val requestKey: String)
@Serializable data class BillingUrl(val url: String)
interface UltraApi {
    @GET("v1/ultra") suspend fun status(): UltraDto
    @POST("v1/ultra/checkout") suspend fun checkout(@Body request: CheckoutRequest): BillingUrl
    @POST("v1/ultra/portal") suspend fun portal(): BillingUrl
}
@HiltViewModel class UltraViewModel @Inject constructor(retrofit: Retrofit) : ViewModel() {
    data class State(val info: UltraDto? = null, val loading: Boolean = true, val busy: Boolean = false, val error: String? = null, val url: String? = null)
    private val api = retrofit.create(UltraApi::class.java)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var requestKey = UUID.randomUUID().toString()
    init { refresh() }
    fun refresh() { if (_state.value.busy) return; viewModelScope.launch {
        try { _state.value = _state.value.copy(info = api.status(), loading = false, error = null) }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { _state.value = _state.value.copy(loading = false, error = error.userFacingMessage("Could not load Ultra.")) }
    } }
    fun openBilling() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = if (_state.value.info?.active == true) api.portal() else api.checkout(CheckoutRequest(requestKey))
                _state.value = _state.value.copy(busy = false, url = result.url)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(busy = false, error = error.userFacingMessage("Could not open billing.")) }
        }
    }
    fun openedUrl() { _state.value = _state.value.copy(url = null) }
}
private fun UltraDto.priceText(): String? {
    val code = currency ?: return null
    val amount = unitAmount ?: return null
    return runCatching {
        val currency = Currency.getInstance(code.uppercase())
        val format = NumberFormat.getCurrencyInstance().apply { this.currency = currency }
        val divisor = Math.pow(10.0, currency.defaultFractionDigits.coerceAtLeast(0).toDouble())
        "${format.format(amount / divisor)} / ${if (intervalCount > 1) "$intervalCount " else ""}${interval.orEmpty()}${if (intervalCount > 1) "s" else ""}"
    }.getOrNull()
}
@Composable fun UltraRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val model: UltraViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    LaunchedEffect(state.url) { state.url?.let { uri.openUri(it); model.openedUrl() } }
    ScreenBackgroundBox(modifier = modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row { AppBackButton(onClick = onBack); Text("Ultra", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp)) }
            Text(if (state.info?.active == true) "You're on Ultra" else "Meet Meek Ultra", style = MaterialTheme.typography.headlineLarge)
            Text("Deeper conversations with DeepSeek V4 Pro 0813.", style = MaterialTheme.typography.titleLarge)
            Text("Your subscription unlocks the Pro model across your chats. Keep your characters, conversations and saved history.")
            if (state.loading) CircularProgressIndicator()
            state.info?.let { info ->
                info.priceText()?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                if (!info.available && !info.active) Text("Subscriptions are not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = model::openBilling, enabled = !state.busy && (info.available || info.active), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Opening…" else if (info.active) "Manage subscription" else "Upgrade to Ultra")
                }
                if (info.available && !info.active) Text("Renews automatically. Review the price and billing terms at checkout; manage or cancel from this screen.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh subscription status") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
