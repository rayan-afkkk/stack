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
import com.swipegallery.domain.billing.PurchaseQueryResult
import com.swipegallery.domain.billing.PurchaseRecord
import com.swipegallery.domain.billing.PurchaseStatus
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

sealed interface ProductState {
    data object Loading : ProductState

    /** [formattedPrice] is the localized price reported by Google Play. */
    data class Available(val formattedPrice: String, internal val details: ProductDetails) : ProductState

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
 * One-time, non-consumable Premium through Google Play Billing.
 *
 * - Entitlement is granted only for PURCHASED (never PENDING) and cached for offline use.
 * - Purchases are acknowledged; the product is never consumed.
 * - A failed or offline query never revokes a cached entitlement; a successful full query is
 *   authoritative (refunds/revocations remove the purchase from Play's list).
 * - Verification is client-side only; there is no backend. See README "Billing limitations".
 */
class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val cache: EntitlementCache,
    private val productId: String,
    private val clock: AppClock,
) : PurchasesUpdatedListener {

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
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        _product.value = suspendCancellableCoroutine<ProductState> { cont ->
            client.queryProductDetailsAsync(params) { billingResult, detailsResult ->
                val state = if (billingResult.responseCode == BillingResponseCode.OK) {
                    val details = detailsResult.productDetailsList.firstOrNull { it.productId == productId }
                    val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
                    if (details != null && price != null) {
                        ProductState.Available(price, details)
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
    fun launchPurchase(activity: Activity): Boolean {
        val product = _product.value as? ProductState.Available ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product.details)
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

    private suspend fun currentCached(): EntitlementState = _entitlement.value ?: EntitlementState()

    private suspend fun queryPurchases(): PurchaseQueryResult {
        val connection = ensureConnected()
        if (connection.responseCode != BillingResponseCode.OK) {
            return PurchaseQueryResult.Failure(connection.responseCode.toBillingError())
        }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
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
