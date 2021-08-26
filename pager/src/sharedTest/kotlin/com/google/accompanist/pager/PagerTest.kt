/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.accompanist.pager

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.google.accompanist.internal.test.exists
import com.google.accompanist.internal.test.isLaidOut
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

private const val LongSwipeDistance = 0.95f
private const val MediumSwipeDistance = 0.8f
private const val ShortSwipeDistance = 0.45f

private const val FastVelocity = 4000f
private const val MediumVelocity = 1500f
private const val SlowVelocity = 300f

@OptIn(ExperimentalPagerApi::class) // Pager is currently experimental
abstract class PagerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * This is a workaround for https://issuetracker.google.com/issues/179492185.
     * Ideally we would have a way to get the applier scope from the rule
     */
    protected lateinit var applierScope: CoroutineScope

    @Test
    fun layout() {
        val pagerState = setPagerContent(pageCount = 10)
        composeTestRule.waitForIdle()
        assertPagerLayout(0, pagerState.pageCount)
    }

    @Test
    fun layout_initialEmpty() {
        // Initially lay out with a count of 0
        val pagerState = setPagerContent(pageCount = 0)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("0").assertDoesNotExist()

        // Now update to have a count of 10 and assert the layout.
        // This models a count which is driven by dynamic data
        pagerState.pageCount = 10
        composeTestRule.waitForIdle()
        assertPagerLayout(0, pagerState.pageCount)
    }

    @Test
    fun swipe() {
        val pagerState = setPagerContent(pageCount = 10)

        // First test swiping towards end, from 0 to -1, which should no-op
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(distancePercentage = LongSwipeDistance)
        // ...and assert that nothing happened
        assertPagerLayout(0, pagerState.pageCount)

        // Now swipe towards start, from page 0 to page 1
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(distancePercentage = -LongSwipeDistance)
        // ...and assert that we now laid out from page 1
        assertPagerLayout(1, pagerState.pageCount)
    }

    @Test
    fun swipeToEndAndBack() {
        val pagerState = setPagerContent(pageCount = 4)

        // Now swipe towards start, from page 0 to page 1 and assert the layout
        composeTestRule.onNodeWithTag("0").swipeAcrossCenter(-MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(1)
        assertPagerLayout(1, pagerState.pageCount)

        // Repeat for 1 -> 2
        composeTestRule.onNodeWithTag("1").swipeAcrossCenter(-MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(2)
        assertPagerLayout(2, pagerState.pageCount)

        // Repeat for 2 -> 3
        composeTestRule.onNodeWithTag("2").swipeAcrossCenter(-MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(3)
        assertPagerLayout(3, pagerState.pageCount)

        // Swipe past the last item. We shouldn't move
        composeTestRule.onNodeWithTag("3").swipeAcrossCenter(-MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(3)
        assertPagerLayout(3, pagerState.pageCount)

        // Swipe back from 3 -> 2
        composeTestRule.onNodeWithTag("3").swipeAcrossCenter(MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(2)
        assertPagerLayout(2, pagerState.pageCount)

        // Swipe back from 2 -> 1
        composeTestRule.onNodeWithTag("2").swipeAcrossCenter(MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(1)
        assertPagerLayout(1, pagerState.pageCount)

        // Swipe back from 1 -> 0
        composeTestRule.onNodeWithTag("1").swipeAcrossCenter(MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(0)
        assertPagerLayout(0, pagerState.pageCount)

        // Swipe past the first item. We shouldn't move
        composeTestRule.onNodeWithTag("0").swipeAcrossCenter(MediumSwipeDistance)
        composeTestRule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(0)
        assertPagerLayout(0, pagerState.pageCount)
    }

    @Test
    fun mediumDistance_fastSwipe_toFling() {
        composeTestRule.mainClock.autoAdvance = false

        val pagerState = setPagerContent(pageCount = 10)

        assertThat(pagerState.isScrollInProgress).isFalse()
        assertThat(pagerState.targetPage).isEqualTo(0)

        // Now swipe towards start, from page 0 to page 1, over a medium distance of the item width.
        // This should trigger a fling()
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(
                distancePercentage = -MediumSwipeDistance,
                velocity = FastVelocity,
            )

        assertThat(pagerState.isScrollInProgress).isTrue()
        assertThat(pagerState.targetPage).isEqualTo(1)

        // Now re-enable the clock advancement and let the fling animation run
        composeTestRule.mainClock.autoAdvance = true
        // ...and assert that we now laid out from page 1
        assertPagerLayout(1, pagerState.pageCount)
    }

    @Test
    fun mediumDistance_slowSwipe_toSnapForward() {
        composeTestRule.mainClock.autoAdvance = false

        val pagerState = setPagerContent(pageCount = 10)

        assertThat(pagerState.isScrollInProgress).isFalse()
        assertThat(pagerState.targetPage).isEqualTo(0)

        // Now swipe towards start, from page 0 to page 1, over a medium distance of the item width.
        // This should trigger a spring to position 1
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(
                distancePercentage = -MediumSwipeDistance,
                velocity = SlowVelocity,
            )

        assertThat(pagerState.isScrollInProgress).isTrue()
        assertThat(pagerState.targetPage).isEqualTo(1)

        // Now re-enable the clock advancement and let the snap animation run
        composeTestRule.mainClock.autoAdvance = true
        // ...and assert that we now laid out from page 1
        assertPagerLayout(1, pagerState.pageCount)
    }

    @Test
    fun shortDistance_fastSwipe_toFling() {
        composeTestRule.mainClock.autoAdvance = false

        val pagerState = setPagerContent(pageCount = 10)

        assertThat(pagerState.isScrollInProgress).isFalse()
        assertThat(pagerState.targetPage).isEqualTo(0)

        // Now swipe towards start, from page 0 to page 1, over a short distance of the item width.
        // This should trigger a fling to page 1
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(
                distancePercentage = -ShortSwipeDistance,
                velocity = FastVelocity,
            )

        assertThat(pagerState.isScrollInProgress).isTrue()
        assertThat(pagerState.targetPage).isEqualTo(1)

        // Now re-enable the clock advancement and let the fling animation run
        composeTestRule.mainClock.autoAdvance = true
        // ...and assert that we now laid out from page 1
        assertPagerLayout(1, pagerState.pageCount)
    }

    @Test
    fun shortDistance_slowSwipe_toSnapBack() {
        composeTestRule.mainClock.autoAdvance = false

        val pagerState = setPagerContent(pageCount = 10)

        assertThat(pagerState.isScrollInProgress).isFalse()
        assertThat(pagerState.targetPage).isEqualTo(0)

        // Now swipe towards start, from page 0 to page 1, over a short distance of the item width.
        // This should trigger a spring back to the original position
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(
                distancePercentage = -ShortSwipeDistance,
                velocity = SlowVelocity,
            )

        assertThat(pagerState.isScrollInProgress).isTrue()
        assertThat(pagerState.targetPage).isEqualTo(0)

        // Now re-enable the clock advancement and let the snap animation run
        composeTestRule.mainClock.autoAdvance = true
        // ...and assert that we 'sprang back' to page 0
        assertPagerLayout(0, pagerState.pageCount)
    }

    @Test
    fun scrollToPage() {
        val pagerState = setPagerContent(pageCount = 10)

        fun testScroll(targetPage: Int, offset: Float = 0f) {
            composeTestRule.runOnIdle {
                runBlocking {
                    pagerState.scrollToPage(targetPage, offset)
                }
            }
            composeTestRule.runOnIdle {
                assertThat(pagerState.currentPage).isEqualTo(targetPage)
                assertThat(pagerState.currentPageOffset).isWithin(0.001f).of(offset)
            }
            assertPagerLayout(targetPage, pagerState.pageCount, offset)
        }

        // Scroll to page 3 and assert
        testScroll(3)
        // Now scroll to page 0 and assert
        testScroll(0)
        // Now scroll to page 1 with an offset of 0.5 and assert
        testScroll(1, 0.5f)
        // Now scroll to page 2 with an offset of 0.25 and assert
        testScroll(2, 0.25f)
    }

    @Test
    fun animateScrollToPage() {
        val pagerState = setPagerContent(pageCount = 10)

        fun testScroll(targetPage: Int, offset: Float = 0f) {
            composeTestRule.runOnIdle {
                runBlocking(AutoTestFrameClock()) {
                    pagerState.animateScrollToPage(targetPage)
                }
            }
            composeTestRule.runOnIdle {
                assertThat(pagerState.currentPage).isEqualTo(targetPage)
                assertThat(pagerState.currentPageOffset).isWithin(0.001f).of(offset)
            }
            assertPagerLayout(targetPage, pagerState.pageCount, offset)
        }

        // Scroll to page 3 and assert
        testScroll(3)
        // Now scroll to page 0 and assert
        testScroll(0)
    }

    @Test
    @Ignore("Currently broken") // TODO: Will fix this once we move to Modifier.scrollable()
    fun a11yScroll() {
        val pagerState = setPagerContent(pageCount = 10)

        // Perform a scroll to item 1
        composeTestRule.onNodeWithTag("1").performScrollTo()

        // ...and assert that we scrolled to page 1
        assertPagerLayout(1, pagerState.pageCount)
    }

    @Test
    fun scrollWhenStateObserved() {
        val pagerState = setPagerContent(pageCount = 4, observeStateInContent = true)

        // Now swipe towards start, from page 0 to page 1
        composeTestRule.onNodeWithTag("0")
            .swipeAcrossCenter(distancePercentage = -MediumSwipeDistance)
        // ...and assert that we now laid out from page 1
        assertPagerLayout(1, pagerState.pageCount)

        // Now swipe towards the end, from page 1 to page 0
        composeTestRule.onNodeWithTag("1")
            .swipeAcrossCenter(distancePercentage = MediumSwipeDistance)
        // ...and assert that we now laid out from page 0
        assertPagerLayout(0, pagerState.pageCount)
    }

    /**
     * Swipe across the center of the node. The major axis of the swipe is defined by the
     * overriding test.
     *
     * @param velocity Target end velocity for the swipe.
     * @param distancePercentage The swipe distance in percentage of the node's size.
     * Negative numbers mean swipe towards the start, positive towards the end.
     */
    abstract fun SemanticsNodeInteraction.swipeAcrossCenter(
        distancePercentage: Float,
        velocity: Float = MediumVelocity
    ): SemanticsNodeInteraction

    // TODO: add test for state restoration?

    private fun assertPagerLayout(currentPage: Int, pageCount: Int, offset: Float = 0f) {
        // Assert that the 'current page' exists and is laid out in the correct position
        composeTestRule.onNodeWithTag(currentPage.toString())
            .assertExists()
            .assertLaidOutItemPosition(currentPage, currentPage, offset)

        // Go through all of the pages, and assert the expected layout state (if it exists)
        (0 until pageCount).forEach { page ->
            // If this exists assert that it is laid out in the correct position
            composeTestRule.onNodeWithTag(page.toString()).apply {
                if (exists && isLaidOut) {
                    assertLaidOutItemPosition(page, currentPage, offset)
                }
            }
        }
    }

    protected abstract fun SemanticsNodeInteraction.assertLaidOutItemPosition(
        page: Int,
        currentPage: Int,
        offset: Float,
    ): SemanticsNodeInteraction

    protected abstract fun setPagerContent(
        pageCount: Int,
        observeStateInContent: Boolean = false,
    ): PagerState
}

private class AutoTestFrameClock : MonotonicFrameClock {
    private val time = AtomicLong(0)

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        return onFrame(time.getAndAdd(16_000_000))
    }
}
