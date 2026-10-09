package dev.dreamz.llmwizard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Google Play Billing for the one-time Pro unlock (no subscription). Serverless: purchases from
 * the library are signature-checked by Play, and every purchase is acknowledged within Play's
 * three days ([refresh] retries). The pattern is the one SpeedEQ shipped and tested with a real
 * purchase and refund.
 *
 * Only testable from a build on a Play testing track (PLAN.md section 8).
 */
class PlayBilling(context: Context, private val entitlements: Entitlements) {
    /** Placeholder until the product exists in Play Console. **(Jay: set the real id.)** */
    companion object { const val PRODUCT_ID = "llmwizard_pro" }

    sealed interface State {
        data object Idle : State
        data object Pending : State
        data object Canceled : State
        data class Failed(val message: String) : State
        data object Owned : State
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price
    private var details: ProductDetails? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(app)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingResponseCode.OK -> handle(purchases.orEmpty(), authoritative = false)
                    BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                    BillingResponseCode.USER_CANCELED -> _state.value = State.Canceled
                    else -> _state.value = State.Failed(message(result))
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private fun whenReady(onFail: (BillingResult) -> Unit = {}, block: () -> Unit) {
        if (client.isReady) { block(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) { scope.launch { if (r.responseCode == BillingResponseCode.OK) block() else onFail(r) } }
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Re-reads what this Google account owns. A failure keeps the cached answer (offline is fine). */
    fun refresh() = whenReady {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, purchases ->
            scope.launch { if (r.responseCode == BillingResponseCode.OK) handle(purchases, authoritative = true) }
        }
    }

    fun loadPrice() = productDetails { _price.value = it?.oneTimePurchaseOfferDetails?.formattedPrice }

    fun buy(activity: Activity) = productDetails { d ->
        if (d == null) { _state.value = State.Failed("Pro isn't available from Google Play right now."); return@productDetails }
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        d.oneTimePurchaseOfferDetails?.offerToken?.let { p.setOfferToken(it) }
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p.build())).build())
        if (r.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) refresh() else if (r.responseCode != BillingResponseCode.OK) _state.value = State.Failed(message(r))
    }

    private fun productDetails(onResult: (ProductDetails?) -> Unit) {
        details?.let { onResult(it); return }
        whenReady(onFail = { onResult(null) }) {
            val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { _, res ->
                scope.launch { details = res.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }; onResult(details) }
            }
        }
    }

    /** [authoritative]: [purchases] is everything owned, so absence revokes (a refund). */
    private fun handle(purchases: List<Purchase>, authoritative: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val bought = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        bought.filterNot { it.isAcknowledged }.forEach { p ->
            whenReady { client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { } }
        }
        val owned = bought.isNotEmpty()
        if (owned || authoritative) scope.launch { entitlements.setOwned(owned) }
        _state.value = when {
            owned -> State.Owned
            ours.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> State.Pending
            else -> _state.value
        }
    }

    private fun message(r: BillingResult) = when (r.responseCode) {
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.NETWORK_ERROR ->
            "Can't reach Google Play right now. Try again when you have a connection."
        BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play billing isn't available on this device or account."
        BillingResponseCode.ITEM_UNAVAILABLE -> "Pro isn't available from Google Play right now."
        else -> "The purchase didn't complete. If you were charged, use Restore."
    }
}
