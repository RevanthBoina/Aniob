package com.aniob.core.knowledge

/**
 * Startup policy for the app registry: when the cached registry may be trusted, and when the
 * platform has to be asked again before the first task resolves an app name against it.
 *
 * The cache is keyed to the build that wrote it. A cache written by another version is still
 * loaded (stale names are better than none while the probe runs) but it is never *warm*: the
 * first task after an install or an update waits, briefly and bounded, for the re-registration.
 */
object AniobRegistryBoot {

    /** No build has registered apps yet. */
    const val NO_BUILD = -1

    /** How long the first task may wait for first-run registration. A no-op once warm. */
    const val READY_WAIT_MS = 1_500L

    /** True when the on-disk cache was written by the running build. */
    fun cacheMatchesBuild(cachedBuildCode: Int, currentBuildCode: Int): Boolean =
        cachedBuildCode != NO_BUILD && cachedBuildCode == currentBuildCode

    /**
     * Register every launchable app again: on first launch (nothing loaded), after every update
     * (the cache belongs to another build), and when the cached registry aged past its soft TTL.
     */
    fun mustProbe(cacheLoaded: Boolean, cachedBuildCode: Int, currentBuildCode: Int, softStale: Boolean): Boolean =
        !cacheLoaded || !cacheMatchesBuild(cachedBuildCode, currentBuildCode) || softStale

    /**
     * True when a task may resolve against the registry right now, without waiting for the probe:
     * this build's cache was loaded and it has real contents.
     */
    fun isWarm(cacheLoaded: Boolean, cachedBuildCode: Int, currentBuildCode: Int, registeredApps: Int): Boolean =
        cacheLoaded && registeredApps > 0 && cacheMatchesBuild(cachedBuildCode, currentBuildCode)
}
