package com.example.aichat.feature.ultra

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.*
import com.example.aichat.core.network.userFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.*

@Serializable data class PlayConfig(val available: Boolean = false, val productId: String? = null, val obfuscatedAccountId: String = "")
@Serializable data class PlayReceipt(val purchaseToken: String)
@Serializable data class PlayVerified(val active: Boolean)
interface PlayApi {
    @GET("v1/ultra/play-config") suspend fun config(): PlayConfig
    @POST("v1/ultra/play-verify") suspend fun verify(@Body receipt: PlayReceipt): PlayVerified
}
internal fun Context.isPlayInstallation(): Boolean = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName == "com.android.vending"
    else @Suppress("DEPRECATION") (packageManager.getInstallerPackageName(packageName) == "com.android.vending")
}.getOrDefault(false)
internal tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

@HiltViewModel class PlayBillingViewModel @Inject constructor(@ApplicationContext private val context: Context, retrofit: Retrofit) : ViewModel() {
    data class Offer(val label: String, val price: String, val detail: ProductDetails, val token: String)
    data class State(val available: Boolean = false, val busy: Boolean = false, val offers: List<Offer> = emptyList(), val message: String? = null, val verified: Int = 0)
    private val api = retrofit.create(PlayApi::class.java)
    private var config = PlayConfig()
    private val verifyingTokens = mutableSetOf<String>()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) verify(purchases.orEmpty())
            else _state.value = _state.value.copy(busy = false, message = if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) null else "Google Play could not complete the purchase.")
        }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection().build()
    init { viewModelScope.launch {
        try {
            config = api.config()
            if (config.available && context.isPlayInstallation()) connect()
        } catch (error: CancellationException) { throw error }
        catch (_: Throwable) { _state.value = State(message = "Google Play subscriptions could not be loaded.") }
    } }
    private fun connect() {
        _state.value = _state.value.copy(available = true, busy = true)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() { _state.value = _state.value.copy(busy = false) }
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) { _state.value = _state.value.copy(busy = false, message = "Google Play billing is not available on this device."); return }
                loadOffers(); restore()
            }
        })
    }
    private fun loadOffers() {
        val id = config.productId ?: return
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(BillingClient.ProductType.SUBS).build())).build()
        client.queryProductDetailsAsync(params) { result, details ->
            val offers = details.productDetailsList.flatMap { product -> product.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.mapNotNull { offer ->
                val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                val label = when (phase.billingPeriod) { "P1M" -> "Monthly"; "P1Y" -> "Annual"; else -> phase.billingPeriod }
                Offer(label, phase.formattedPrice, product, offer.offerToken)
            } }
            _state.value = _state.value.copy(busy = false, offers = offers, message = if (result.responseCode == BillingClient.BillingResponseCode.OK && offers.isNotEmpty()) null else "No Google Play subscription plans are available yet.")
        }
    }
    fun buy(activity: Activity, offer: Offer) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        val params = BillingFlowParams.newBuilder().setObfuscatedAccountId(config.obfuscatedAccountId)
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.detail).setOfferToken(offer.token).build())).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) _state.value = _state.value.copy(busy = false, message = "Google Play could not open checkout.")
    }
    fun restore() {
        if (!client.isReady) { connect(); return }
        _state.value = _state.value.copy(busy = true, message = null)
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) verify(purchases)
            else _state.value = _state.value.copy(busy = false, message = "Could not restore Google Play purchases.")
        }
    }
    private fun verify(purchases: List<Purchase>) {
        val relevant = purchases.filter { config.productId in it.products }
        if (relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING }) _state.value = _state.value.copy(busy = false, message = "Payment is pending. Ultra activates after Google Play confirms it.")
        val complete = relevant.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.purchaseToken !in verifyingTokens }
        if (complete.isEmpty()) {
            if (verifyingTokens.isEmpty()) _state.value = _state.value.copy(busy = false)
            return
        }
        verifyingTokens.addAll(complete.map { it.purchaseToken })
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                var active = false
                for (purchase in complete) active = api.verify(PlayReceipt(purchase.purchaseToken)).active || active
                _state.value = _state.value.copy(busy = false, verified = _state.value.verified + if (active) 1 else 0, message = if (active) "Ultra is active." else "No active subscription was found.")
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _state.value = _state.value.copy(busy = false, message = error.userFacingMessage("Could not verify your subscription. Restore purchases to try again.")) }
            finally { verifyingTokens.removeAll(complete.map { it.purchaseToken }.toSet()) }
        }
    }
    override fun onCleared() { client.endConnection(); super.onCleared() }
}
@Composable fun PlaySubscriptionControls(onVerified: () -> Unit) {
    val context = LocalContext.current
    if (!context.isPlayInstallation()) return
    val model: PlayBillingViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.verified) { if (state.verified > 0) onVerified() }
    if (state.available) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Play", style = MaterialTheme.typography.titleMedium)
        state.offers.forEach { offer -> Button(onClick = { context.activity()?.let { model.buy(it, offer) } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${offer.label} · ${offer.price}") } }
        TextButton(onClick = model::restore, enabled = !state.busy) { Text("Restore Google Play purchases") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
