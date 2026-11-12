package com.aniob.app.service

import android.app.Application
import com.aniob.core.domain.AniobRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ContainerSwipeTest {
    private val picker = AniobRect(100, 800, 400, 1200)

    @Test fun forwardSwipeMovesUpAndStaysInsideTheContainer() {
        val swipe = AniobAccessibilityService.containerSwipe(picker, forward = true, dyPx = 140)!!
        assertEquals(250, swipe.startX)
        assertEquals(swipe.startX, swipe.endX)
        assertEquals(140, swipe.startY - swipe.endY)
        assertTrue(swipe.startY in picker.top..picker.bottom && swipe.endY in picker.top..picker.bottom)
    }

    @Test fun backwardSwipeMovesDown() {
        val swipe = AniobAccessibilityService.containerSwipe(picker, forward = false, dyPx = 140)!!
        assertEquals(-140, swipe.startY - swipe.endY)
    }

    @Test fun travelIsClippedToTheContainer() {
        val swipe = AniobAccessibilityService.containerSwipe(picker, forward = true, dyPx = 5000)!!
        assertEquals(320, swipe.startY - swipe.endY)
        assertTrue(swipe.startY <= picker.bottom && swipe.endY >= picker.top)
    }

    @Test fun wideContainerIsSwipedSideways() {
        val carousel = AniobRect(0, 300, 1080, 500)
        val swipe = AniobAccessibilityService.containerSwipe(carousel, forward = true, dyPx = 400)!!
        assertEquals(swipe.startY, swipe.endY)
        assertEquals(400, swipe.startX - swipe.endX)
    }

    @Test fun emptyOrTinyContainerHasNoSwipe() {
        assertNull(AniobAccessibilityService.containerSwipe(AniobRect(0, 0, 0, 0), true, 140))
        assertNull(AniobAccessibilityService.containerSwipe(AniobRect(0, 0, 30, 30), true, 140))
    }
}
