package com.aniob.core.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobRegistryBootTest {

    @Test
    fun `first launch has no cache and must probe`() {
        assertTrue(AniobRegistryBoot.mustProbe(cacheLoaded = false, cachedBuildCode = AniobRegistryBoot.NO_BUILD, currentBuildCode = 5, softStale = true))
        assertFalse(AniobRegistryBoot.isWarm(cacheLoaded = false, cachedBuildCode = AniobRegistryBoot.NO_BUILD, currentBuildCode = 5, registeredApps = 0))
    }

    @Test
    fun `an update re-registers even though a fresh cache was loaded`() {
        assertTrue(AniobRegistryBoot.mustProbe(cacheLoaded = true, cachedBuildCode = 4, currentBuildCode = 5, softStale = false))
        // The old build's entries are loaded but must not be served as ready.
        assertFalse(AniobRegistryBoot.isWarm(cacheLoaded = true, cachedBuildCode = 4, currentBuildCode = 5, registeredApps = 80))
    }

    @Test
    fun `a cache from before build keys existed counts as another build`() {
        assertFalse(AniobRegistryBoot.cacheMatchesBuild(AniobRegistryBoot.NO_BUILD, AniobRegistryBoot.NO_BUILD))
        assertTrue(AniobRegistryBoot.mustProbe(cacheLoaded = true, cachedBuildCode = AniobRegistryBoot.NO_BUILD, currentBuildCode = 5, softStale = false))
    }

    @Test
    fun `this build's fresh cache is warm and is not force-probed`() {
        assertFalse(AniobRegistryBoot.mustProbe(cacheLoaded = true, cachedBuildCode = 5, currentBuildCode = 5, softStale = false))
        assertTrue(AniobRegistryBoot.isWarm(cacheLoaded = true, cachedBuildCode = 5, currentBuildCode = 5, registeredApps = 80))
    }

    @Test
    fun `the daily soft TTL still forces a probe but the warm cache keeps answering`() {
        assertTrue(AniobRegistryBoot.mustProbe(cacheLoaded = true, cachedBuildCode = 5, currentBuildCode = 5, softStale = true))
        assertTrue(AniobRegistryBoot.isWarm(cacheLoaded = true, cachedBuildCode = 5, currentBuildCode = 5, registeredApps = 80))
    }

    @Test
    fun `an empty cache is never warm`() {
        assertFalse(AniobRegistryBoot.isWarm(cacheLoaded = true, cachedBuildCode = 5, currentBuildCode = 5, registeredApps = 0))
    }
}
