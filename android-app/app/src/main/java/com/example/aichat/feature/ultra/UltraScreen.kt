package com.example.aichat.feature.ultra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.ScreenBackgroundBox
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.*
import java.text.NumberFormat
import java.util.Currency
import java.util.UUID

@Serializable data class UltraOffer(
    val cadence: String, val available: Boolean = false, val currency: String,
    val unitAmount: Long, val interval: String, val annualSavingsPercent: Int = 0
)
@Serializable data class UltraDto(
    val active: Boolean = false, val available: Boolean = false, val model: String = "",
    val currency: String? = null, val unitAmount: Long? = null, val interval: String? = null,
    val intervalCount: Int = 1, val offers: List<UltraOffer> = emptyList(),
    val billingProvider: String? = null, val playAvailable: Boolean = false
)
@Serializable data class CheckoutRequest(val requestKey: String, val cadence: String = "monthly")
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
    private val requestKey = UUID.randomUUID().toString()
    private var refreshJob: Job? = null
    init { refresh() }
    fun refresh() {
        if (_state.value.busy || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try { _state.value = _state.value.copy(info = api.status(), loading = false, error = null) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(loading = false, error = error.userFacingMessage("Could not load Ultra.")) }
        }
    }
    fun openBilling(cadence: String) {
        if (_state.value.busy) return
        val info = _state.value.info ?: return
        if (info.active && info.billingProvider == "play") {
            _state.value = _state.value.copy(url = "https://play.google.com/store/account/subscriptions")
            return
        }
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = if (info.active) api.portal() else api.checkout(CheckoutRequest(requestKey, cadence))
                _state.value = _state.value.copy(busy = false, url = result.url)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(busy = false, error = error.userFacingMessage("Could not open billing.")) }
        }
    }
    fun openedUrl(error: Boolean = false) {
        _state.value = _state.value.copy(url = null, error = if (error) "No browser is available to open billing." else _state.value.error)
    }
}
internal fun formatUltraPrice(code: String, amount: Long): String = runCatching {
    val currency = Currency.getInstance(code.uppercase())
    val format = NumberFormat.getCurrencyInstance().apply { this.currency = currency }
    val divisor = Math.pow(10.0, currency.defaultFractionDigits.coerceAtLeast(0).toDouble())
    format.format(amount / divisor)
}.getOrDefault("${code.uppercase()} $amount")

@Composable fun UltraRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val model: UltraViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val playInstallation = LocalContext.current.isPlayInstallation()
    var cadence by rememberSaveable { mutableStateOf("annual") }
    LaunchedEffect(state.url) {
        state.url?.let { target -> model.openedUrl(runCatching { uri.openUri(target) }.isFailure) }
    }
    LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
    ScreenBackgroundBox(modifier = modifier) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row { AppBackButton(onClick = onBack); Text("Ultra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(10.dp)) }
            Text(if (state.info?.active == true) "You're on Ultra" else "Make it your world", style = MaterialTheme.typography.headlineLarge)
            Text("Deeper conversations. Characters with more to remember. More ways to make Meek yours.", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Meek Ultra" to "Choose our most capable chat model whenever you want.",
                "More room to remember" to "16,000 characters of short-term memory and 64,000 of long-term memory, plus a richer mid-term summary.",
                "Create a voice" to "Design voices with a description or a clear audio or video sample. Share them with the community or keep them private.",
                "Bring characters to life" to "More detailed character creation and upgraded portraits. Give your creations greater visibility in discovery.",
                "Your profile, your style" to "Choose fonts, frames, banners, featured characters and profile widgets.",
                "Make Meek yours" to "Chat fonts, app backgrounds and alternative app icons."
            ).forEach { (title, detail) ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.info?.let { info ->
                if (info.active) {
                    Button(onClick = { model.openBilling(cadence) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "Opening…" else "Manage subscription")
                    }
                } else if (playInstallation) {
                    PlaySubscriptionControls(onVerified = model::refresh)
                    if (!info.playAvailable) Text("Subscriptions are not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    info.offers.forEach { offer ->
                        Surface(
                            onClick = { cadence = offer.cadence }, shape = MaterialTheme.shapes.large,
                            color = if (cadence == offer.cadence) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                RadioButton(selected = cadence == offer.cadence, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(if (offer.cadence == "annual") "Annual · save 30%" else "Monthly", style = MaterialTheme.typography.titleMedium)
                                    Text("${formatUltraPrice(offer.currency, offer.unitAmount)} / ${offer.interval}")
                                }
                            }
                        }
                    }
                    val available = info.offers.firstOrNull { it.cadence == cadence }?.available == true
                    if (!available) Text("Subscriptions are not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { model.openBilling(cadence) }, enabled = !state.busy && available, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "Opening…" else "Get Ultra")
                    }
                    if (available) Text("Renews automatically. Annual plans are billed once per year. Review the total and payment terms at checkout; manage or cancel here.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh subscription status") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
