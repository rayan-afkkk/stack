package com.swipegallery.domain

import com.swipegallery.domain.swipe.SwipeConfig
import com.swipegallery.domain.swipe.SwipeDecider
import com.swipegallery.domain.swipe.SwipeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeDeciderTest {
    private val width = 1000f
    private val config = SwipeConfig(flingVelocityPxPerSec = 1500f)

    private fun decide(offset: Float, velocity: Float = 0f) = SwipeDecider.decide(offset, velocity, width, config)

    @Test
    fun `left past the threshold removes and right keeps`() {
        assertEquals(SwipeOutcome.REMOVE, decide(-300f))
        assertEquals(SwipeOutcome.KEEP, decide(300f))
    }

    @Test
    fun `short slow drags snap back`() {
        assertEquals(SwipeOutcome.CANCEL, decide(-290f))
        assertEquals(SwipeOutcome.CANCEL, decide(250f, 400f))
        assertEquals(SwipeOutcome.CANCEL, decide(0f, 5000f))
    }

    @Test
    fun `a deliberate fling commits a shorter swipe`() {
        assertEquals(SwipeOutcome.REMOVE, decide(-150f, -2000f))
        assertEquals(SwipeOutcome.KEEP, decide(150f, 2000f))
    }

    @Test
    fun `tiny fast twitches never commit`() {
        assertEquals(SwipeOutcome.CANCEL, decide(-40f, -8000f))
        assertEquals(SwipeOutcome.CANCEL, decide(100f, 9000f))
    }

    @Test
    fun `throwing the card back towards the centre cancels`() {
        assertEquals(SwipeOutcome.CANCEL, decide(-350f, 2500f))
        assertEquals(SwipeOutcome.CANCEL, decide(-150f, 2500f))
    }

    @Test
    fun `rotation is capped and label progress is proportional`() {
        assertEquals(7f, SwipeDecider.rotationDegrees(5000f, width, config))
        assertEquals(-7f, SwipeDecider.rotationDegrees(-5000f, width, config))
        assertTrue(SwipeDecider.rotationDegrees(100f, width, config) in 0f..3f)
        assertEquals(0.5f, SwipeDecider.progress(-150f, width, config))
        assertEquals(1f, SwipeDecider.progress(900f, width, config))
    }
}
