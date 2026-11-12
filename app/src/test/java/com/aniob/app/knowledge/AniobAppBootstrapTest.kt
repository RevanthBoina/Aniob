package com.aniob.app.knowledge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.aniob.app.BuildConfig
import com.aniob.core.knowledge.AniobAppRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Registry readiness: the first task after an install or an update must be able to wait for the
 * registry to have real contents, and a warm start must never wait.
 *
 * Runs with a plain [Application] so nothing but this test calls `install`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AniobAppBootstrapTest {

    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences("aniob_registry", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        AniobAppRegistry.resetForTest()
        app = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            launcher,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.example.clock"
                    name = "com.example.clock.MainActivity"
                }
                nonLocalizedLabel = "Clock"
            }
        )
    }

    @After
    fun tearDown() {
        AniobAppRegistry.resetForTest()
    }

    private fun awaitReady(): Int = runBlocking {
        withTimeout(10_000L) { AniobAppBootstrap.registryReady.await() }
    }

    @Test
    fun firstLaunchRegistersEveryLaunchableAppBeforeReadinessCompletes() {
        assertEquals("no build has registered yet", -1, prefs.getInt("registry_build_code", -1))

        AniobAppBootstrap.install(app)
        val count = awaitReady()

        assertTrue("readiness must report real contents, got $count", count >= 1)
        assertNotNull(AniobAppRegistry.getInstance().byPackage("com.example.clock"))
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt("registry_build_code", -1))
    }

    @Test
    fun warmStartOfTheSameBuildIsReadyWithoutWaitingForTheProbe() {
        AniobAppBootstrap.install(app)
        awaitReady()

        // Next cold start of the same build: empty process memory, cache on disk.
        AniobAppRegistry.resetForTest()
        AniobAppBootstrap.install(app)

        assertTrue(
            "this build's cache must make the registry ready synchronously",
            AniobAppBootstrap.registryReady.isCompleted
        )
        assertTrue(awaitReady() >= 1)
        assertNotNull(AniobAppRegistry.getInstance().byPackage("com.example.clock"))
    }

    @Test
    fun updateReRegistersOnTheNextColdStart() {
        AniobAppBootstrap.install(app)
        awaitReady()

        // The cache on disk now belongs to an older build that knew an app that is gone.
        prefs.edit().putInt("registry_build_code", BuildConfig.VERSION_CODE - 1).commit()
        AniobAppRegistry.resetForTest()

        AniobAppBootstrap.install(app)
        val count = awaitReady()

        assertTrue(count >= 1)
        assertNotNull(AniobAppRegistry.getInstance().byPackage("com.example.clock"))
        assertEquals(
            "the registry must be re-keyed to the running build after the probe",
            BuildConfig.VERSION_CODE,
            prefs.getInt("registry_build_code", -1)
        )
        assertFalse(AniobAppRegistry.getInstance().isEmpty())
    }
}
