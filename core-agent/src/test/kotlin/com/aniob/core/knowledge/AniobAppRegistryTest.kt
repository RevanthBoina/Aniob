package com.aniob.core.knowledge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The registry is the reason app opening costs no tokens, so its contracts are pinned here:
 * ambiguity is an answer, roles come from the platform, the cache survives a restart, and a stale
 * cache is detectable.
 */
class AniobAppRegistryTest {

    private val registry get() = AniobAppRegistry.getInstance()

    private val installed = listOf(
        RegisteredApp("com.google.android.deskclock", "Clock", isSystemApp = true, launchActivity = "com.google.android.deskclock/com.android.deskclock.DeskClock"),
        RegisteredApp("com.instagram.android", "Instagram"),
        RegisteredApp("com.android.settings", "Settings", isSystemApp = true),
        RegisteredApp("com.example.settings", "Settings Hub"),
        RegisteredApp("com.whatsapp", "WhatsApp"),
        RegisteredApp("com.google.android.apps.photos", "Photos", isSystemApp = true)
    )

    @Before
    fun setUp() {
        AniobAppRegistry.resetForTest()
        registry.hydrate(
            installed,
            mapOf(AniobAppRegistry.ROLE_CLOCK to "com.google.android.deskclock"),
            builtAtMs = 1_000L
        )
    }

    @After
    fun tearDown() = AniobAppRegistry.resetForTest()

    @Test
    fun `resolves by package, label, alias and prefix`() {
        assertEquals("com.google.android.deskclock", packageOf("com.google.android.deskclock"))
        assertEquals("com.google.android.deskclock", packageOf("Clock"))
        assertEquals("com.google.android.deskclock", packageOf("alarm"))
        assertEquals("com.instagram.android", packageOf("insta"))
        assertEquals("com.whatsapp", packageOf("whatsapp"))
    }

    @Test
    fun `politeness and noise words do not change the answer`() {
        assertEquals(packageOf("clock"), packageOf("the clock app please"))
        assertEquals(packageOf("instagram"), packageOf("hey google open instagram"))
    }

    @Test
    fun `a typo still resolves, but only for a single-word query`() {
        assertEquals("com.instagram.android", packageOf("instgram"))
        // A phrase is not fuzzy-matched to a one-word alias: that is how the wrong app opens.
        assertTrue(registry.resolve("wi-fi settings") is AppLookup.Miss)
        assertTrue(registry.resolve("battery saver") is AppLookup.Miss)
    }

    @Test
    fun `two apps with the same label are ambiguous, not a coin flip`() {
        registry.hydrate(installed + RegisteredApp("com.other.clock", "Clock"), emptyMap(), 2_000L)
        val found = registry.resolve("clock")
        assertTrue(found is AppLookup.Ambiguous)
        assertEquals(2, (found as AppLookup.Ambiguous).candidates.size)
    }

    @Test
    fun `an exact label beats a longer label that starts with it`() {
        assertEquals("com.android.settings", packageOf("settings"))
        assertEquals("com.example.settings", packageOf("settings hub"))
    }

    @Test
    fun `roles are answered by the platform, not by a vendor table`() {
        val clock = registry.appForRole(AniobAppRegistry.ROLE_CLOCK)
        assertNotNull(clock)
        assertEquals("com.google.android.deskclock", clock!!.packageName)
        // A role is also a spoken name.
        assertEquals("com.google.android.deskclock", packageOf("clock"))
        assertNull(registry.appForRole("no-such-role"))
    }

    @Test
    fun `registered apps get curated and derived aliases`() {
        val instagram = registry.byPackage("com.instagram.android")!!
        assertTrue(instagram.aliases.contains("ig"))
        assertTrue(instagram.aliases.contains("insta"))
        assertTrue(AniobAppRegistry.deriveAliases("Instagram Lite").contains("instagram"))
        assertTrue(AniobAppRegistry.deriveAliases("Photos (Beta)").contains("photos"))
    }

    @Test
    fun `namedIn finds every app named anywhere in a sentence, longest first`() {
        val found = registry.namedIn("open clock and then check settings hub")
        assertEquals(listOf("com.example.settings", "com.google.android.deskclock"), found.map { it.packageName })
    }

    @Test
    fun `a cache round-trips through a file`() {
        val dir = Files.createTempDirectory("registry").toFile()
        val file = File(dir, AniobAppCatalog.CACHE_FILE_NAME)
        registry.saveTo(file)
        assertTrue(file.exists())

        AniobAppRegistry.resetForTest()
        assertTrue(AniobAppRegistry.getInstance().isEmpty())
        assertTrue(AniobAppRegistry.getInstance().loadFrom(file))
        assertEquals(installed.size, AniobAppRegistry.getInstance().size())
        assertEquals("com.instagram.android", packageOf("ig"))
        assertEquals("com.google.android.deskclock", AniobAppRegistry.getInstance().role(AniobAppRegistry.ROLE_CLOCK))
    }

    @Test
    fun `a corrupt or missing cache is a miss, never a crash`() {
        val dir = Files.createTempDirectory("registry-bad").toFile()
        assertFalse(registry.loadFrom(File(dir, "missing.tsv")))
        val junk = File(dir, "junk.tsv")
        junk.writeText("not\ta\tregistry\n")
        assertFalse(registry.loadFrom(junk))
    }

    @Test
    fun `staleness is detectable three ways`() {
        val builtAt = registry.builtAt()                       // 1_000L in setUp
        val fresh = builtAt + AniobAppRegistryAge.TTL - 1_000L
        assertFalse(registry.isStale(fresh, AniobAppRegistryAge.TTL))
        assertTrue(registry.isStale(builtAt + AniobAppRegistryAge.TTL + 1, AniobAppRegistryAge.TTL))
        assertTrue(registry.isStale(fresh, AniobAppRegistryAge.TTL, installedCount = 99))
        AniobAppRegistry.resetForTest()
        assertTrue("an empty registry is always stale", AniobAppRegistry.getInstance().isStale(fresh, AniobAppRegistryAge.TTL))
    }

    @Test
    fun `an empty registry falls back to stock package ids, never to nothing`() {
        AniobAppRegistry.resetForTest()
        val empty = AniobAppRegistry.getInstance()
        assertTrue(empty.isEmpty())
        val seed = empty.resolveWithSeed("clock")
        assertTrue(seed is AppLookup.Match)
        assertEquals("com.google.android.deskclock", (seed as AppLookup.Match).app.packageName)
        assertEquals("com.instagram.android", (empty.resolveWithSeed("insta") as AppLookup.Match).app.packageName)
        assertTrue(empty.resolveWithSeed("zephyr") is AppLookup.Miss)
    }

    @Test
    fun `package receiver updates are reflected immediately`() {
        registry.upsert(RegisteredApp("com.new.app", "New App"))
        assertEquals("com.new.app", packageOf("new app"))
        registry.remove("com.new.app")
        assertTrue(registry.resolve("new app") is AppLookup.Miss)
    }

    private fun packageOf(query: String): String? = when (val found = registry.resolve(query)) {
        is AppLookup.Match -> found.app.packageName
        else -> null
    }

    private object AniobAppRegistryAge {
        const val TTL = 7L * 24 * 3600 * 1000
    }
}
