package com.swipegallery.domain

import com.swipegallery.domain.billing.PlanPeriod
import com.swipegallery.domain.billing.PlanSelector
import com.swipegallery.domain.billing.PricePhase
import com.swipegallery.domain.billing.SubscriptionOffer
import java.time.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanSelectorTest {
    private fun phase(price: String, micros: Long, period: String, recurring: Boolean = true, currency: String = "USD") =
        PricePhase(price, micros, currency, period, if (recurring) 0 else 1, recurring)

    private val monthlyBase = SubscriptionOffer("monthly", null, "tok-m", listOf(phase("$2.99", 2_990_000, "P1M")))
    private val yearlyBase = SubscriptionOffer("yearly", null, "tok-y", listOf(phase("$12.99", 12_990_000, "P1Y")))
    private val yearlyTrial = SubscriptionOffer(
        "yearly",
        "trial-7d",
        "tok-y-trial",
        listOf(phase("Free", 0, "P1W", recurring = false), phase("$12.99", 12_990_000, "P1Y")),
    )

    @Test
    fun `selects one option per base plan with the real recurring price`() {
        val plans = PlanSelector.select(listOf(monthlyBase, yearlyBase), "monthly", "yearly")
        assertEquals(PlanPeriod.MONTHLY, plans.monthly?.period)
        assertEquals("$2.99", plans.monthly?.recurring?.formattedPrice)
        assertEquals("$12.99", plans.yearly?.recurring?.formattedPrice)
        assertEquals(plans.yearly, plans.preferred)
        assertNull(plans.yearly?.freeTrial)
    }

    @Test
    fun `an eligible free trial offer is preferred and its length parsed`() {
        val plans = PlanSelector.select(listOf(yearlyBase, yearlyTrial, monthlyBase), "monthly", "yearly")
        assertEquals("tok-y-trial", plans.yearly?.offerToken)
        assertEquals(Period.ofDays(7), plans.yearly?.freeTrial)
        assertEquals("$12.99", plans.yearly?.recurring?.formattedPrice)
    }

    @Test
    fun `yearly savings are computed from actual prices`() {
        val plans = PlanSelector.select(listOf(monthlyBase, yearlyBase), "monthly", "yearly")
        // 12 x 2.99 = 35.88; 12.99 is 63.8% less.
        assertEquals(63, PlanSelector.yearlySavingsPercent(plans))
        assertEquals(1_082_500L, PlanSelector.yearlyPerMonthMicros(plans))
    }

    @Test
    fun `no savings claim across currencies or without both plans`() {
        val otherCurrency = SubscriptionOffer("yearly", null, "tok", listOf(phase("€12", 12_000_000, "P1Y", currency = "EUR")))
        assertNull(PlanSelector.yearlySavingsPercent(PlanSelector.select(listOf(monthlyBase, otherCurrency), "monthly", "yearly")))
        assertNull(PlanSelector.yearlySavingsPercent(PlanSelector.select(listOf(monthlyBase), "monthly", "yearly")))
    }

    @Test
    fun `missing plans are reported as empty`() {
        val plans = PlanSelector.select(emptyList(), "monthly", "yearly")
        assertTrue(plans.isEmpty)
        assertNull(plans.preferred)
    }
}
