package com.swipegallery.domain.allowance

import java.time.LocalDate

/** Free users may review this many distinct photo versions per local calendar day. */
const val FREE_DAILY_REVIEWS: Int = 50

enum class ChargeDecision {
    /** A new review: record it in today's ledger. */
    CHARGE,

    /** This photo version was already charged today; recommitting is free. */
    ALREADY_CHARGED,

    /** A free user has used today's allowance. */
    BLOCKED,
}

object AllowancePolicy {
    /**
     * Premium users are still recorded in the ledger (so the count is accurate if the
     * entitlement is later revoked) but are never blocked.
     */
    fun evaluate(
        isPremium: Boolean,
        usedToday: Int,
        alreadyChargedToday: Boolean,
        limit: Int = FREE_DAILY_REVIEWS,
    ): ChargeDecision = when {
        alreadyChargedToday -> ChargeDecision.ALREADY_CHARGED
        isPremium -> ChargeDecision.CHARGE
        usedToday >= limit -> ChargeDecision.BLOCKED
        else -> ChargeDecision.CHARGE
    }
}

data class AllowanceSnapshot(
    val day: LocalDate,
    val used: Int,
    val isPremium: Boolean,
    val resetsAtMillis: Long,
    val limit: Int = FREE_DAILY_REVIEWS,
) {
    /** Free reviews left today. Meaningless for Premium; check [isPremium] first. */
    val remaining: Int get() = (limit - used).coerceAtLeast(0)

    val isExhausted: Boolean get() = !isPremium && used >= limit

    /** Display value clamped to the free limit (0f..1f). */
    val progress: Float get() = (used.coerceAtMost(limit).toFloat() / limit).coerceIn(0f, 1f)

    /** Used count shown next to "/ 50"; never shows more than the limit. */
    val usedForDisplay: Int get() = used.coerceAtMost(limit)
}
