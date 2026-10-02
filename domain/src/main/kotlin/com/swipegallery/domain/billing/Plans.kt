package com.swipegallery.domain.billing

import java.time.Period

/** One pricing phase of a Play subscription offer (free trial, intro price, or recurring price). */
data class PricePhase(
    /** Localized by Google Play, e.g. "$12.99" or "Rs 3,600". Never built by the app. */
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    /** ISO-8601 period, e.g. "P1M", "P1Y", "P7D". */
    val billingPeriod: String,
    val billingCycleCount: Int,
    /** True for the phase that repeats until the user cancels. */
    val recurring: Boolean,
)

/** A subscription offer as returned by Play. Play only returns offers this user is eligible for. */
data class SubscriptionOffer(
    val basePlanId: String,
    /** Null for the base plan itself; set for promotional offers such as free trials. */
    val offerId: String?,
    val offerToken: String,
    val phases: List<PricePhase>,
)

enum class PlanPeriod { MONTHLY, YEARLY }

data class PlanOption(
    val period: PlanPeriod,
    val basePlanId: String,
    /** Passed to the billing flow to buy exactly this offer. */
    val offerToken: String,
    /** The price charged every period after any trial. */
    val recurring: PricePhase,
    /** Free-trial length, if this offer starts with one. */
    val freeTrial: Period?,
)

data class SubscriptionPlans(val monthly: PlanOption?, val yearly: PlanOption?) {
    val isEmpty: Boolean get() = monthly == null && yearly == null

    /** Default selection: yearly when available. */
    val preferred: PlanOption? get() = yearly ?: monthly
}

object PlanSelector {

    /**
     * Picks one offer per base plan. If Play returns a free-trial offer (meaning this user is
     * eligible), it is preferred; otherwise the plain base-plan offer is used.
     */
    fun select(offers: List<SubscriptionOffer>, monthlyPlanId: String, yearlyPlanId: String): SubscriptionPlans =
        SubscriptionPlans(
            monthly = pick(offers, monthlyPlanId, PlanPeriod.MONTHLY),
            yearly = pick(offers, yearlyPlanId, PlanPeriod.YEARLY),
        )

    private fun pick(offers: List<SubscriptionOffer>, basePlanId: String, period: PlanPeriod): PlanOption? {
        val forPlan = offers.filter { it.basePlanId == basePlanId }
        val withTrial = forPlan.firstNotNullOfOrNull { offer -> toOption(offer, period)?.takeIf { it.freeTrial != null } }
        val basePlan = forPlan.firstOrNull { it.offerId == null }?.let { toOption(it, period) }
        return withTrial ?: basePlan ?: forPlan.firstNotNullOfOrNull { toOption(it, period) }
    }

    private fun toOption(offer: SubscriptionOffer, period: PlanPeriod): PlanOption? {
        val recurring = offer.phases.lastOrNull { it.recurring } ?: offer.phases.lastOrNull() ?: return null
        if (recurring.priceMicros <= 0L) return null
        val trial = offer.phases.firstOrNull { it.priceMicros == 0L && !it.recurring }
            ?.let { runCatching { Period.parse(it.billingPeriod) }.getOrNull() }
            ?.takeIf { !it.isZero && !it.isNegative }
        return PlanOption(period, offer.basePlanId, offer.offerToken, recurring, trial)
    }

    /**
     * Honest yearly saving versus paying monthly for 12 months, from Play's real prices.
     * Null when either plan is missing, currencies differ, or there is no saving.
     */
    fun yearlySavingsPercent(plans: SubscriptionPlans): Int? {
        val m = plans.monthly?.recurring ?: return null
        val y = plans.yearly?.recurring ?: return null
        if (m.currencyCode != y.currencyCode) return null
        val twelveMonths = m.priceMicros * 12
        if (twelveMonths <= 0L || y.priceMicros >= twelveMonths) return null
        return ((twelveMonths - y.priceMicros) * 100 / twelveMonths).toInt()
    }

    /** Yearly price spread over 12 months, in micros (for an "≈ x / month" hint). */
    fun yearlyPerMonthMicros(plans: SubscriptionPlans): Long? = plans.yearly?.recurring?.priceMicros?.div(12)
}
