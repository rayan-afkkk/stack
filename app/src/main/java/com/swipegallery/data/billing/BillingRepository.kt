package com.swipegallery.data.billing

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
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.swipegallery.domain.billing.BillingError
import com.swipegallery.domain.billing.EntitlementResolver
import com.swipegallery.domain.billing.EntitlementState
import com.swipegallery.domain.billing.PlanOption
import com.swipegallery.domain.billing.PlanSelector
import com.swipegallery.domain.billing.PricePhase
import com.swipegallery.domain.billing.PurchaseQueryResult
import com.swipegallery.domain.billing.PurchaseRecord
import com.swipegallery.domain.billing.PurchaseStatus
import com.swipegallery.domain.billing.SubscriptionOffer
import com.swipegallery.domain.billing.SubscriptionPlans
import com.swipegallery.domain.time.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Play Console identifiers for the Premium subscription. */
data class SubscriptionConfig(
    val productId: String,
    val monthlyBasePlanId: String,
    val yearlyBasePlanId: String,
)

sealed interface ProductState {
    data object Loading : ProductState

    /** Prices inside [plans] are the localized strings reported by Google Play. */
    data class Available(val plans: SubscriptionPlans, internal val details: ProductDetails) : ProductState

    data class Unavailable(val error: BillingError) : ProductState
}

sealed interface PurchaseEvent {
    data object Purchased : PurchaseEvent
    data object Pending : PurchaseEvent
    data object Cancelled : PurchaseEvent
    data class Failed(val error: BillingError) : PurchaseEvent
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data object NothingToRestore : RestoreOutcome
    data object StillPending : RestoreOutcome
    data class Failed(val error: BillingError) : RestoreOutcome
}

/**
 * Premium as an auto-renewing Google Play subscription with a monthly and a yearly base plan.
 *
 * - Entitlement is granted only for PURCHASED (never PENDING) and cached for offline use.
 * - New subscriptions are acknowledged (required by Play within 3 days). Nothing is consumed.
 * - A failed or offline query never revokes a cached entitlement. A successful full query is
 *   authoritative: Play only lists active subscriptions, so an expired, cancelled-and-lapsed,
 *   refunded or revoked subscription disappears and Premium is removed.
 * - Verification is client-side only; there is no backend. See README "Billing limitations".
 */
class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val cache: EntitlementCache,
    val config: SubscriptionConfig,
    private val clock: AppClock,
) : PurchasesUpdatedListener {

    private val packageName = context.packageName
    private val productId get() = config.productId

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Null until the cached entitlement has been read from disk. */
    private val _entitlement = MutableStateFlow<EntitlementState?>(null)
    val entitlement: StateFlow<EntitlementState?> = _entitlement.asStateFlow()

    private val _product = MutableStateFlow<ProductState>(ProductState.Loading)
    val product: StateFlow<ProductState> = _product.asStateFlow()

    private val _events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PurchaseEvent> = _events.asSharedFlow()

    private val connectMutex = Mutex()
    private val stateMutex = Mutex()

    /** Google Play's own page for changing plan or cancelling. */
    val manageSubscriptionUrl: String
        get() = "https://play.google.com/store/account/subscriptions?sku=$productId&package=$packageName"

    fun start() {
        scope.launch {
            cache.state.collect { cached -> _entitlement.value = cached }
        }
        scope.launch {
            refreshPurchases()
            loadProduct()
        }
    }

    /** Called on app resume, after reconnects and from "Restore purchase". */
    suspend fun refreshPurchases(): PurchaseQueryResult {
        val result = queryPurchases()
        stateMutex.withLock {
            val cached = currentCached()
            val next = EntitlementResolver.resolveQuery(cached, result, productId, clock.nowMillis())
            if (next != cached) cache.save(next)
            _entitlement.value = next
        }
        if (result is PurchaseQueryResult.Success) acknowledge(result.purchases)
        return result
    }

    suspend fun restore(): RestoreOutcome = when (val result = refreshPurchases()) {
        is PurchaseQueryResult.Failure -> RestoreOutcome.Failed(result.error)
        is PurchaseQueryResult.Success -> {
            val mine = result.purchases.filter { productId in it.productIds }
            when {
                mine.any { it.status == PurchaseStatus.PURCHASED } -> RestoreOutcome.Restored
                mine.any { it.status == PurchaseStatus.PENDING } -> RestoreOutcome.StillPending
                else -> RestoreOutcome.NothingToRestore
            }
        }
    }

    suspend fun loadProduct() {
        if (_product.value is ProductState.Available) return
        _product.value = ProductState.Loading
        val connection = ensureConnected()
        if (connection.responseCode != BillingResponseCode.OK) {
            _product.value = ProductState.Unavailable(connection.responseCode.toBillingError())
            return
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        _product.value = suspendCancellableCoroutine<ProductState> { cont ->
            client.queryProductDetailsAsync(params) { billingResult, detailsResult ->
                val state = if (billingResult.responseCode == BillingResponseCode.OK) {
                    val details = detailsResult.productDetailsList.firstOrNull { it.productId == productId }
                    val plans = details?.let {
                        PlanSelector.select(it.toSubscriptionOffers(), config.monthlyBasePlanId, config.yearlyBasePlanId)
                    }
                    if (details != null && plans != null && !plans.isEmpty) {
                        ProductState.Available(plans, details)
                    } else {
                        ProductState.Unavailable(BillingError.ITEM_UNAVAILABLE)
                    }
                } else {
                    ProductState.Unavailable(billingResult.responseCode.toBillingError())
                }
                if (cont.isActive) cont.resume(state)
            }
        }
    }

    /** Must be called on the main thread with a foreground Activity. */
    fun launchPurchase(activity: Activity, plan: PlanOption): Boolean {
        val product = _product.value as? ProductState.Available ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product.details)
                        .setOfferToken(plan.offerToken)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return when (result.responseCode) {
            BillingResponseCode.OK -> true
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                scope.launch { refreshPurchases() }
                _events.tryEmit(PurchaseEvent.Failed(BillingError.ITEM_ALREADY_OWNED))
                false
            }

            else -> {
                _events.tryEmit(PurchaseEvent.Failed(result.responseCode.toBillingError()))
                false
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val records = purchases.orEmpty().map { it.toRecord() }
                scope.launch {
                    stateMutex.withLock {
                        val cached = currentCached()
                        val next = EntitlementResolver.applyUpdate(cached, records, productId, clock.nowMillis())
                        if (next != cached) cache.save(next)
                        _entitlement.value = next
                        _events.emit(
                            when {
                                next.isPremium -> PurchaseEvent.Purchased
                                next.hasPendingPurchase -> PurchaseEvent.Pending
                                else -> PurchaseEvent.Failed(BillingError.UNKNOWN)
                            },
                        )
                    }
                    acknowledge(records)
                }
            }

            BillingResponseCode.USER_CANCELED -> _events.tryEmit(PurchaseEvent.Cancelled)
            BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch {
                refreshPurchases()
                _events.emit(PurchaseEvent.Failed(BillingError.ITEM_ALREADY_OWNED))
            }

            else -> _events.tryEmit(PurchaseEvent.Failed(result.responseCode.toBillingError()))
        }
    }

    private fun currentCached(): EntitlementState = _entitlement.value ?: EntitlementState()

    private suspend fun queryPurchases(): PurchaseQueryResult {
        val connection = ensureConnected()
        if (connection.responseCode != BillingResponseCode.OK) {
            return PurchaseQueryResult.Failure(connection.responseCode.toBillingError())
        }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine<PurchaseQueryResult> { cont ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                val value = if (billingResult.responseCode == BillingResponseCode.OK) {
                    PurchaseQueryResult.Success(purchases.map { it.toRecord() })
                } else {
                    PurchaseQueryResult.Failure(billingResult.responseCode.toBillingError())
                }
                if (cont.isActive) cont.resume(value)
            }
        }
    }

    private suspend fun acknowledge(records: List<PurchaseRecord>) {
        for (record in EntitlementResolver.needingAcknowledgement(records, productId)) {
            if (ensureConnected().responseCode != BillingResponseCode.OK) return
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(record.purchaseToken).build()
            // A failed acknowledgement is retried on the next refresh (it is still unacknowledged).
            suspendCancellableCoroutine<Unit> { cont ->
                client.acknowledgePurchase(params) { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    private suspend fun ensureConnected(): BillingResult = connectMutex.withLock {
        if (client.isReady) return@withLock ok()
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<BillingResult> { cont ->
                val resumed = AtomicBoolean(false)
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(billingResult)
                    }

                    override fun onBillingServiceDisconnected() {
                        // The next call reconnects. Resolve a pending wait instead of hanging.
                        if (resumed.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(result(BillingResponseCode.SERVICE_DISCONNECTED))
                        }
                    }
                })
            }
        } ?: result(BillingResponseCode.SERVICE_UNAVAILABLE)
    }

    private fun ok() = result(BillingResponseCode.OK)

    private fun result(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).build()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

private fun ProductDetails.toSubscriptionOffers(): List<SubscriptionOffer> = subscriptionOfferDetails.orEmpty().map { offer ->
    SubscriptionOffer(
        basePlanId = offer.basePlanId,
        offerId = offer.offerId,
        offerToken = offer.offerToken,
        phases = offer.pricingPhases.pricingPhaseList.map { phase ->
            PricePhase(
                formattedPrice = phase.formattedPrice,
                priceMicros = phase.priceAmountMicros,
                currencyCode = phase.priceCurrencyCode,
                billingPeriod = phase.billingPeriod,
                billingCycleCount = phase.billingCycleCount,
                recurring = phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING,
            )
        },
    )
}

private fun Purchase.toRecord() = PurchaseRecord(
    productIds = products,
    status = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PurchaseStatus.PURCHASED
        Purchase.PurchaseState.PENDING -> PurchaseStatus.PENDING
        else -> PurchaseStatus.UNSPECIFIED
    },
    acknowledged = isAcknowledged,
    purchaseToken = purchaseToken,
)

private fun Int.toBillingError(): BillingError = when (this) {
    BillingResponseCode.USER_CANCELED -> BillingError.USER_CANCELED
    BillingResponseCode.NETWORK_ERROR -> BillingError.NETWORK
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.SERVICE_DISCONNECTED,
    -> BillingError.SERVICE_UNAVAILABLE

    BillingResponseCode.BILLING_UNAVAILABLE -> BillingError.BILLING_UNAVAILABLE
    BillingResponseCode.ITEM_UNAVAILABLE -> BillingError.ITEM_UNAVAILABLE
    BillingResponseCode.ITEM_ALREADY_OWNED -> BillingError.ITEM_ALREADY_OWNED
    BillingResponseCode.DEVELOPER_ERROR -> BillingError.DEVELOPER_ERROR
    else -> BillingError.UNKNOWN
}
