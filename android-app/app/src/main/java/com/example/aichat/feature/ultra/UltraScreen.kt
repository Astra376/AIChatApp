package com.example.aichat.feature.ultra

import com.example.aichat.core.ui.DelayedCircularProgressIndicator as CircularProgressIndicator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.aichat.core.design.AppIcon
import com.example.aichat.core.design.AppIconGlyph
import com.example.aichat.core.design.AppIcons
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
    val billingProvider: String? = null, val playAvailable: Boolean = false,
    val previewAvailable: Boolean = false, val previewActive: Boolean = false
)
@Serializable data class CheckoutRequest(val requestKey: String, val cadence: String = "monthly")
@Serializable data class PreviewRequest(val enabled: Boolean, val cadence: String = "monthly")
@Serializable data class BillingUrl(val url: String)
interface UltraApi {
    @GET("v1/ultra") suspend fun status(): UltraDto
    @POST("v1/ultra/preview") suspend fun preview(@Body request: PreviewRequest): UltraDto
    @POST("v1/ultra/checkout") suspend fun checkout(@Body request: CheckoutRequest): BillingUrl
    @POST("v1/ultra/portal") suspend fun portal(): BillingUrl
}
@HiltViewModel class UltraViewModel @Inject constructor(retrofit: Retrofit,
    private val appearance: com.example.aichat.feature.customization.AppearanceRepository
) : ViewModel() {
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
            try { val info = api.status(); appearance.updateUltraAccess(info.active); _state.value = _state.value.copy(info = info, loading = false, error = null) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(loading = false, error = error.userFacingMessage("Could not load Ultra.")) }
        }
    }
    fun setPreview(enabled: Boolean, cadence: String) {
        if (_state.value.busy || _state.value.info?.previewAvailable != true) return
        refreshJob?.cancel()
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val info = api.preview(PreviewRequest(enabled, cadence))
                appearance.updateUltraAccess(info.active)
                _state.value = _state.value.copy(info = info, busy = false, loading = false)
                runCatching { appearance.refresh() }
            }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(busy = false, error = error.userFacingMessage("Could not change the test subscription.")) }
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

internal data class UltraPlan(
    val cadence: String, val price: String, val interval: String,
    val available: Boolean, val savings: Int = 0
)

@Composable
fun UltraRoute(onBack: () -> Unit, modifier: Modifier = Modifier, onActivated: (() -> Unit)? = null) {
    val model: UltraViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val playInstallation = context.isPlayInstallation()
    val playModel: PlayBillingViewModel? = if (playInstallation) hiltViewModel() else null
    val play = playModel?.state?.collectAsStateWithLifecycle()?.value
    var cadence by rememberSaveable { mutableStateOf("annual") }
    LaunchedEffect(state.url) {
        state.url?.let { target -> model.openedUrl(runCatching { uri.openUri(target) }.isFailure) }
    }
    LaunchedEffect(play?.verified) { if ((play?.verified ?: 0) > 0) model.refresh() }
    LaunchedEffect(state.info?.active) { if (state.info?.active == true) onActivated?.invoke() }
    LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
    val preview = state.info?.previewAvailable == true
    val plans = if (playInstallation && !preview) play?.offers.orEmpty().map { offer ->
        UltraPlan(if (offer.label == "Annual") "annual" else "monthly", offer.price, if (offer.label == "Annual") "year" else "month", true)
    } else state.info?.offers.orEmpty().map { offer ->
        UltraPlan(offer.cadence, formatUltraPrice(offer.currency, offer.unitAmount), offer.interval, offer.available || preview, offer.annualSavingsPercent)
    }
    UltraScreenContent(
        state = state.copy(busy = state.busy || play?.busy == true, error = state.error ?: play?.message),
        plans = plans, cadence = cadence, onSelectPlan = { cadence = it }, onBack = onBack,
        onRefresh = { model.refresh(); if (playInstallation) playModel?.restore() },
        onSetPreview = { model.setPreview(it, cadence) },
        onPurchase = {
            when {
                preview -> model.setPreview(!state.info!!.previewActive, cadence)
                state.info?.active == true -> model.openBilling(cadence)
                playInstallation -> play?.offers?.firstOrNull { (it.label == "Annual") == (cadence == "annual") }?.let { offer ->
                    context.activity()?.let { playModel?.buy(it, offer) }
                }
                else -> model.openBilling(cadence)
            }
        }, modifier = modifier
    )
}

private data class UltraBenefit(val icon: AppIconGlyph, val title: String, val detail: String)
private val ultraBenefits = listOf(
    UltraBenefit(AppIcons.sparkle, "Meek Ultra model", "More capable replies, with your choice of chat model."),
    UltraBenefit(AppIcons.memory, "Deeper memory", "64k long-term + 16k short-term memory, and richer scene summaries."),
    UltraBenefit(AppIcons.chats, "Create custom voices", "Describe a voice or use an audio or video sample."),
    UltraBenefit(AppIcons.create, "Richer characters & portraits", "Advanced personality controls and upgraded image generation."),
    UltraBenefit(AppIcons.discover, "More discovery", "Give your public characters a boost in recommendations and search."),
    UltraBenefit(AppIcons.profile, "A profile that feels like you", "Frames, banners, fonts, featured characters and profile widgets."),
    UltraBenefit(AppIcons.edit, "Your chat, your style", "Custom chat fonts and generated or uploaded backgrounds."),
    UltraBenefit(AppIcons.created, "Make Meek yours", "Alternative app icons and custom home screen shortcuts.")
)

@Composable
internal fun UltraScreenContent(
    state: UltraViewModel.State, plans: List<UltraPlan>, cadence: String,
    onSelectPlan: (String) -> Unit, onBack: () -> Unit, onRefresh: () -> Unit,
    onSetPreview: (Boolean) -> Unit, onPurchase: () -> Unit, modifier: Modifier = Modifier
) {
    val info = state.info
    val selected = plans.firstOrNull { it.cadence == cadence }
    val preview = info?.previewAvailable == true
    val active = info?.active == true
    ScreenBackgroundBox(modifier = modifier) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onClick = onBack)
                    Text("Meek Ultra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    AppIcon(AppIcons.sparkle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            onClick = onPurchase, enabled = !state.loading && !state.busy && (active || selected?.available == true),
                            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("ultra-purchase"), shape = RoundedCornerShape(16.dp)
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(when {
                                state.busy -> "Updating…"
                                preview && info?.previewActive == true -> "Disable test Ultra"
                                preview -> "Mock purchase · ${if (cadence == "annual") "Annual" else "Monthly"}"
                                active -> "Manage subscription"
                                else -> "Get Ultra · ${selected?.price ?: "Choose a plan"}"
                            }, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            when {
                                preview -> "Testing only · No payment or automatic renewal"
                                active -> "Your subscription is active."
                                selected?.available == true -> "${selected.price} / ${selected.interval}. Renews automatically. Cancel anytime."
                                else -> "Purchases aren't available yet."
                            }, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("ultra-content"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(if (active) "Your world, upgraded." else "Go beyond ordinary.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("More imagination. More memory. More you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!active && plans.isNotEmpty()) item {
                    BoxWithConstraints {
                        val stack = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.25f
                        val ordered = plans.sortedBy { if (it.cadence == "annual") 0 else 1 }
                        if (stack) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ordered.forEach { plan -> UltraPlanCard(plan, cadence == plan.cadence, !state.busy, { onSelectPlan(plan.cadence) }, Modifier.fillMaxWidth()) }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ordered.forEach { plan -> UltraPlanCard(plan, cadence == plan.cadence, !state.busy, { onSelectPlan(plan.cadence) }, Modifier.weight(1f)) }
                        }
                    }
                }
                if (preview) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Test subscription", fontWeight = FontWeight.Bold)
                            Text(if (info?.previewActive == true) "Ultra enabled for this account" else "Enable Ultra without paying", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = info?.previewActive == true, onCheckedChange = onSetPreview, enabled = !state.busy, modifier = Modifier.testTag("ultra-test-toggle"))
                    }
                }
                item { Text("Everything included", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                items(ultraBenefits, key = { it.title }) { benefit ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(benefit.icon, null, size = 24.dp, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(benefit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(benefit.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Restore or refresh subscription") } }
            }
        }
    }
}

@Composable
private fun UltraPlanCard(plan: UltraPlan, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.testTag("ultra-plan-${plan.cadence}"),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp).heightIn(min = 150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (plan.cadence == "annual") "Annual" else "Monthly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(22.dp))
            }
            Text(if (plan.savings > 0) "SAVE ${plan.savings}%" else "FLEXIBLE BILLING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(plan.price, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("per ${plan.interval}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
