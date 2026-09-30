package com.swipegallery

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.Decision
import com.swipegallery.ui.session.SwipeDeck
import com.swipegallery.ui.session.SwipeDeckState
import com.swipegallery.ui.theme.SwipeGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeDeckTest {
    @get:Rule
    val rule = createComposeRule()

    private val decisions = mutableListOf<Pair<Long, Decision>>()
    private val deck = SwipeDeckState()

    private fun photo(id: Long) = PhotoRef(
        mediaId = id,
        volume = "external_primary",
        key = "external_primary:$id:g$id.$id",
        dateTakenMillis = 1_700_000_000_000L,
        dateAddedMillis = 1_700_000_000_000L,
        sizeBytes = 1_000L,
        width = 100,
        height = 100,
        bucketId = "b",
        bucketName = "B",
        mimeType = "image/jpeg",
    )

    private fun setDeck(accept: Boolean = true) {
        val photos = mutableStateListOf(photo(1), photo(2), photo(3), photo(4))
        rule.setContent {
            SwipeGalleryTheme(dark = true) {
                SwipeDeck(
                    state = deck,
                    top = photos.getOrNull(0),
                    behind = photos.getOrNull(1),
                    afterBehind = photos.getOrNull(2),
                    enabled = true,
                    undoReturn = null,
                    topUnavailable = false,
                    reducedMotion = false,
                    hapticsEnabled = false,
                    describe = { "photo-${it.mediaId}" },
                    onDecide = { p, d ->
                        if (accept) {
                            decisions += p.mediaId to d
                            photos.remove(p)
                        }
                        accept
                    },
                    onImageError = {},
                    onSkipUnavailable = {},
                    modifier = Modifier.size(320.dp, 440.dp),
                )
            }
        }
    }

    @Test
    fun leftSwipeRemoves_rightSwipeKeeps() {
        setDeck()
        rule.onNodeWithContentDescription("photo-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("photo-2").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(listOf(1L to Decision.REMOVE, 2L to Decision.KEEP), decisions)
    }

    @Test
    fun shortAndVerticalGesturesDoNotDecide() {
        setDeck()
        rule.onNodeWithContentDescription("photo-1").performTouchInput {
            swipe(start = center, end = center + Offset(-40f, 0f), durationMillis = 400)
        }
        rule.onNodeWithContentDescription("photo-1").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Long, Decision>>(), decisions)
    }

    @Test
    fun buttonsMatchGestures() {
        setDeck()
        rule.runOnIdle { deck.swipe(Decision.REMOVE) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("photo-2").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(listOf(1L to Decision.REMOVE, 2L to Decision.REMOVE), decisions)
    }

    @Test
    fun rapidButtonTapsCommitOnce() {
        setDeck()
        rule.runOnIdle {
            deck.swipe(Decision.KEEP)
            deck.swipe(Decision.KEEP)
            deck.swipe(Decision.REMOVE)
        }
        rule.waitForIdle()
        assertEquals(listOf(1L to Decision.KEEP), decisions)
    }

    @Test
    fun refusedDecisionSnapsBackWithoutCommitting() {
        setDeck(accept = false)
        rule.onNodeWithContentDescription("photo-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("photo-1").assertExists()
        assertEquals(emptyList<Pair<Long, Decision>>(), decisions)
    }
}
