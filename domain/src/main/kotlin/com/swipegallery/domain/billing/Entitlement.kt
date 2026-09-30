package com.swipegallery.domain.billing

enum class PurchaseStatus { PURCHASED, PENDING, UNSPECIFIED }

/** Platform-neutral view of a Google Play purchase. */
data class PurchaseRecord(
    val productIds: List<String>,
    val status: PurchaseStatus,
    val acknowledged: Boolean,
    val purchaseToken: String,
)

enum class BillingError {
    USER_CANCELED,
    NETWORK,
    SERVICE_UNAVAILABLE,
    BILLING_UNAVAILABLE,
    ITEM_UNAVAILABLE,
    ITEM_ALREADY_OWNED,
    DEVELOPER_ERROR,
    UNKNOWN,
}

sealed interface PurchaseQueryResult {
    /** A complete, authoritative list of the user's current in-app purchases. */
    data class Success(val purchases: List<PurchaseRecord>) : PurchaseQueryResult

    data class Failure(val error: BillingError) : PurchaseQueryResult
}

/** Locally cached entitlement. Only ever set to Premium from a PURCHASED record. */
data class EntitlementState(
    val isPremium: Boolean = false,
    val hasPendingPurchase: Boolean = false,
    /** When Play last answered authoritatively; null if never. */
    val lastVerifiedAtMillis: Long? = null,
)

/**
 * Client-side entitlement rules. There is no backend, so verification is limited to what the
 * Play Billing Library reports on this device. See README "Billing limitations".
 */
object EntitlementResolver {

    private fun List<PurchaseRecord>.forProduct(productId: String) = filter { productId in it.productIds }

    /** Apply the result of a full purchases query (on start, resume, reconnect or restore). */
    fun resolveQuery(
        cached: EntitlementState,
        result: PurchaseQueryResult,
        productId: String,
        nowMillis: Long,
    ): EntitlementState = when (result) {
        // Offline or Play unavailable: never revoke a previously confirmed entitlement.
        is PurchaseQueryResult.Failure -> cached
        is PurchaseQueryResult.Success -> {
            val mine = result.purchases.forProduct(productId)
            EntitlementState(
                // Authoritative: a refund or revocation removes the purchase from this list.
                isPremium = mine.any { it.status == PurchaseStatus.PURCHASED },
                hasPendingPurchase = mine.any { it.status == PurchaseStatus.PENDING },
                lastVerifiedAtMillis = nowMillis,
            )
        }
    }

    /**
     * Apply an incremental purchase update (PurchasesUpdatedListener). The list only contains
     * the purchases that changed, so it can grant but never revoke.
     */
    fun applyUpdate(
        cached: EntitlementState,
        updated: List<PurchaseRecord>,
        productId: String,
        nowMillis: Long,
    ): EntitlementState {
        val mine = updated.forProduct(productId)
        if (mine.isEmpty()) return cached
        val purchased = mine.any { it.status == PurchaseStatus.PURCHASED }
        return EntitlementState(
            isPremium = cached.isPremium || purchased,
            hasPendingPurchase = !purchased && mine.any { it.status == PurchaseStatus.PENDING },
            lastVerifiedAtMillis = if (purchased) nowMillis else cached.lastVerifiedAtMillis,
        )
    }

    /** Non-consumable: acknowledge PURCHASED items that are not yet acknowledged. Never consume. */
    fun needingAcknowledgement(purchases: List<PurchaseRecord>, productId: String): List<PurchaseRecord> =
        purchases.forProduct(productId).filter { it.status == PurchaseStatus.PURCHASED && !it.acknowledged }
}
