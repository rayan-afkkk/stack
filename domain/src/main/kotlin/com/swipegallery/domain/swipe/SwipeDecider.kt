package com.swipegallery.domain.swipe

import kotlin.math.abs
import kotlin.math.sign

/** LEFT = add to the pending deletion queue, RIGHT = keep. */
enum class SwipeOutcome { KEEP, REMOVE, CANCEL }

data class SwipeConfig(
    /** Distance, as a fraction of card width, that commits on release. */
    val commitFraction: Float = 0.30f,
    /** Release velocity (px/s) that can commit a shorter, deliberate fling. */
    val flingVelocityPxPerSec: Float,
    /** A fling must still travel this far, so tiny fast twitches never commit. */
    val minFlingFraction: Float = 0.12f,
    /** Maximum card tilt at full commit distance. */
    val maxRotationDegrees: Float = 7f,
)

object SwipeDecider {

    fun decide(offsetX: Float, velocityX: Float, cardWidth: Float, config: SwipeConfig): SwipeOutcome {
        if (cardWidth <= 0f || offsetX == 0f) return SwipeOutcome.CANCEL
        val distance = abs(offsetX)
        val direction = sign(offsetX)
        val fastFling = abs(velocityX) >= config.flingVelocityPxPerSec
        val flingAgrees = sign(velocityX) == direction
        val committed = when {
            // Past the threshold, but thrown back towards the centre: the user changed their mind.
            distance >= config.commitFraction * cardWidth -> !(fastFling && !flingAgrees)
            fastFling && flingAgrees -> distance >= config.minFlingFraction * cardWidth
            else -> false
        }
        return when {
            !committed -> SwipeOutcome.CANCEL
            direction < 0f -> SwipeOutcome.REMOVE
            else -> SwipeOutcome.KEEP
        }
    }

    /** Gentle tilt proportional to distance, capped at [SwipeConfig.maxRotationDegrees]. */
    fun rotationDegrees(offsetX: Float, cardWidth: Float, config: SwipeConfig): Float {
        if (cardWidth <= 0f) return 0f
        val raw = offsetX / (cardWidth * config.commitFraction) * config.maxRotationDegrees * 0.6f
        return raw.coerceIn(-config.maxRotationDegrees, config.maxRotationDegrees)
    }

    /** 0 → 1 as the card approaches the commit threshold; used for the KEEP/REMOVE labels. */
    fun progress(offsetX: Float, cardWidth: Float, config: SwipeConfig): Float {
        if (cardWidth <= 0f) return 0f
        return (abs(offsetX) / (cardWidth * config.commitFraction)).coerceIn(0f, 1f)
    }
}
