package com.aniob.app.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SetupConnectionCopyTest {
    @Test fun `disabled account feature never advertises sign in in setup copy`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val ids = setupConnectionCopyIds(false)
        val text = context.getString(ids.first) + context.getString(ids.second)
        assertFalse(text.contains("ChatGPT", ignoreCase = true))
        assertFalse(text.contains("sign in", ignoreCase = true))
        assertTrue(text.contains("on-device"))
        assertTrue(text.contains("gateway"))
    }

    @Test fun `eligible account feature explains sign in is independent of device pairing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val ids = setupConnectionCopyIds(true)
        assertTrue(context.getString(ids.first).contains("ChatGPT"))
        assertTrue(context.getString(ids.second).contains("does not require debugging or device pairing"))
    }
}
