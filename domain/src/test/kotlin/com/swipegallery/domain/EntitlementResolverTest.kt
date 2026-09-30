package com.swipegallery.domain

import com.swipegallery.domain.billing.BillingError
import com.swipegallery.domain.billing.EntitlementResolver
import com.swipegallery.domain.billing.EntitlementState
import com.swipegallery.domain.billing.PurchaseQueryResult
import com.swipegallery.domain.billing.PurchaseRecord
import com.swipegallery.domain.billing.PurchaseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementResolverTest {
    private val product = "swipe_gallery_premium"
    private fun purchase(status: PurchaseStatus, ack: Boolean = false, id: String = product) =
        PurchaseRecord(listOf(id), status, ack, "token-$status-$id")

    @Test
    fun `billing failure does not revoke cached confirmed premium`() {
        val cached = EntitlementState(isPremium = true, lastVerifiedAtMillis = 1L)
        for (error in BillingError.entries) {
            val next = EntitlementResolver.resolveQuery(cached, PurchaseQueryResult.Failure(error), product, 99L)
            assertEquals(cached, next, "error $error")
        }
    }

    @Test
    fun `pending purchases do not unlock premium`() {
        val q = EntitlementResolver.resolveQuery(
            EntitlementState(),
            PurchaseQueryResult.Success(listOf(purchase(PurchaseStatus.PENDING))),
            product,
            5L,
        )
        assertFalse(q.isPremium)
        assertTrue(q.hasPendingPurchase)

        val u = EntitlementResolver.applyUpdate(EntitlementState(), listOf(purchase(PurchaseStatus.PENDING)), product, 5L)
        assertFalse(u.isPremium)
        assertTrue(u.hasPendingPurchase)
    }

    @Test
    fun `purchased unlocks premium and needs acknowledgement once`() {
        val p = purchase(PurchaseStatus.PURCHASED, ack = false)
        val state = EntitlementResolver.applyUpdate(EntitlementState(hasPendingPurchase = true), listOf(p), product, 7L)
        assertTrue(state.isPremium)
        assertFalse(state.hasPendingPurchase)
        assertEquals(listOf(p), EntitlementResolver.needingAcknowledgement(listOf(p), product))
        assertTrue(
            EntitlementResolver.needingAcknowledgement(listOf(purchase(PurchaseStatus.PURCHASED, ack = true)), product).isEmpty(),
        )
        assertTrue(
            EntitlementResolver.needingAcknowledgement(listOf(purchase(PurchaseStatus.PENDING)), product).isEmpty(),
        )
    }

    @Test
    fun `an authoritative empty query revokes premium after a refund`() {
        val cached = EntitlementState(isPremium = true, lastVerifiedAtMillis = 1L)
        val next = EntitlementResolver.resolveQuery(cached, PurchaseQueryResult.Success(emptyList()), product, 10L)
        assertFalse(next.isPremium)
        assertEquals(10L, next.lastVerifiedAtMillis)
    }

    @Test
    fun `other products are ignored`() {
        val next = EntitlementResolver.resolveQuery(
            EntitlementState(),
            PurchaseQueryResult.Success(listOf(purchase(PurchaseStatus.PURCHASED, id = "something_else"))),
            product,
            1L,
        )
        assertFalse(next.isPremium)
    }

    @Test
    fun `an incremental update never revokes`() {
        val cached = EntitlementState(isPremium = true)
        assertEquals(cached, EntitlementResolver.applyUpdate(cached, emptyList(), product, 3L))
    }
}
