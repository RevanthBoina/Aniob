package com.aniob.app.auth

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobSignInServiceTest {

    @Test
    fun startAndCancelSignInService() {
        var cancelled = false
        AniobSignInService.onCancelRequested = { cancelled = true }

        val startIntent = Intent(ApplicationProvider.getApplicationContext(), AniobSignInService::class.java).apply {
            action = AniobSignInService.ACTION_START
        }
        val controller = Robolectric.buildService(AniobSignInService::class.java, startIntent)
        controller.create()
        controller.startCommand(0, 1)

        val service = controller.get()
        val shadowService = shadowOf(service)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)

        val cancelIntent = Intent(ApplicationProvider.getApplicationContext(), AniobSignInService::class.java).apply {
            action = AniobSignInService.ACTION_CANCEL
        }
        controller.withIntent(cancelIntent).startCommand(0, 2)
        assertTrue(cancelled)
    }
}
