package com.aniob.core

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.memory.RememberAction
import com.aniob.core.memory.RememberCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zero-LLM replay cache: key normalisation, JSON round trip, identity matching and the
 * fall-through behaviour when the screen no longer matches.
 */
class RememberCacheTest {

    private fun node(
        id: Int,
        viewId: String = "",
        text: String = "",
        desc: String = "",
        top: Int = 100,
        clickable: Boolean = true,
        editable: Boolean = false,
        enabled: Boolean = true
    ) = AniobNode(
        id = id,
        className = "android.widget.Button",
        text = text,
        contentDescription = desc,
        viewId = viewId,
        bounds = AniobRect(0, top, 400, top + 80),
        isClickable = clickable,
        isEditable = editable,
        isEnabled = enabled
    )

    private fun screen(
        nodes: List<AniobNode>,
        packageName: String = "com.example.app",
        hash: String = "com.example.app:Main:123"
    ) = AniobScreenState(packageName = packageName, screenHash = hash, nodes = nodes, treeHash = hash)

    private val searchScreen = screen(
        listOf(
            node(1, viewId = "com.example.app:id/search", text = "Search", editable = true, clickable = false),
            node(2, viewId = "com.example.app:id/go", text = "Go")
        )
    )

    // ---------------------------------------------------------------------------------------------
    // Key normalisation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun goalNormalisationIgnoresOrderCaseAndStopWords() {
        assertEquals(
            RememberCache.normalizeGoal("Open Wi-Fi settings"),
            RememberCache.normalizeGoal("wifi   SETTINGS open")
        )
        assertEquals("settings-wifi", RememberCache.normalizeGoal("Open Wi-Fi settings"))
        assertEquals("", RememberCache.normalizeGoal("the a of"))
    }

    @Test
    fun theCacheFileLivesAtTheDocumentedName() {
        assertEquals("remember_cache.json", RememberCache.FILE_NAME)
        assertEquals(300, RememberCache.MAX_ENTRIES)
        assertEquals(0.06, RememberCache.COORDINATE_TOLERANCE, 0.0)
    }

    @Test
    fun keyIsGoalPipePackagePipeHash() {
        val key = RememberCache.keyOf("settings-wifi", "com.example.app", "com.example.app:Main:1")
        assertEquals("settings-wifi|com.example.app|com.example.app:Main:1", key)
    }

    // ---------------------------------------------------------------------------------------------
    // Record + lookup
    // ---------------------------------------------------------------------------------------------

    @Test
    fun recordsAndReplaysATransitionOnAReindexedScreen() {
        val cache = RememberCache(clock = { 1000L })
        val descriptor = RememberAction.fromAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(2)),
            node = searchScreen.nodes[1],
            screen = searchScreen
        )!!
        assertTrue(cache.record("search for pizza", "com.example.app", searchScreen.screenHash, listOf(descriptor)))

        // Same widgets, new ids: replay must re-target by identity, never by the stale index.
        val later = screen(searchScreen.nodes.map { it.copy(id = it.id + 20) })
        val replay = cache.lookup("search for pizza", later)
        assertNotNull(replay)
        assertEquals(1, replay!!.size)
        val tap = replay.single() as AniobAction.Tap
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/go"), tap.target)
    }

    @Test
    fun lookupFallsThroughWhenTheWidgetIsGone() {
        val cache = RememberCache()
        val descriptor = RememberAction.fromAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(2)),
            node = searchScreen.nodes[1],
            screen = searchScreen
        )!!
        cache.record("search", "com.example.app", searchScreen.screenHash, listOf(descriptor))

        val missing = screen(listOf(searchScreen.nodes.first()))
        assertNull("a vanished target must fall back to the model", cache.lookup("search", missing))
    }

    @Test
    fun lookupFallsThroughOnGoalPackageOrHashMismatch() {
        val cache = RememberCache()
        val descriptor = RememberAction.fromAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(2)),
            node = searchScreen.nodes[1],
            screen = searchScreen
        )!!
        cache.record("search", "com.example.app", searchScreen.screenHash, listOf(descriptor))

        assertNull(cache.lookup("delete everything", searchScreen))
        assertNull(cache.lookup("search", screen(searchScreen.nodes, packageName = "com.other.app")))
        assertNull(cache.lookup("search", screen(searchScreen.nodes, hash = "com.example.app:Main:999")))
    }

    @Test
    fun inputTextAndClearFlagSurviveTheRoundTrip() {
        val cache = RememberCache()
        val descriptor = RememberAction.fromAction(
            action = AniobAction.InputText(SemanticTarget.SomIndex(1), "pizza", clearFirst = true),
            node = searchScreen.nodes[0],
            screen = searchScreen
        )!!
        cache.record("type pizza", "com.example.app", searchScreen.screenHash, listOf(descriptor))

        val replay = cache.lookup("type pizza", screen(searchScreen.nodes.map { it.copy(id = it.id + 5) }))!!
        val input = replay.single() as AniobAction.InputText
        assertEquals("pizza", input.text)
        assertTrue(input.clearFirst)
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/search"), input.target)
    }

    @Test
    fun backAndScrollReplayWithoutATarget() {
        val cache = RememberCache()
        val back = RememberAction.fromAction(AniobAction.PressKey(KeyType.BACK), null, searchScreen)!!
        assertTrue(cache.record("leave screen", "com.example.app", searchScreen.screenHash, listOf(back)))
        assertEquals(1, cache.lookup("leave screen", searchScreen)!!.size)
    }

    @Test
    fun disabledTargetDoesNotReplay() {
        val cache = RememberCache()
        val descriptor = RememberAction.fromAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(2)),
            node = searchScreen.nodes[1],
            screen = searchScreen
        )!!
        cache.record("search", "com.example.app", searchScreen.screenHash, listOf(descriptor))

        val disabled = screen(searchScreen.nodes.map { if (it.id == 2) it.copy(isEnabled = false) else it })
        assertNull(cache.lookup("search", disabled))
    }

    @Test
    fun coordinateOnlyTargetDoesNotReplayAcrossCaptures() {
        val bare = screen(
            listOf(
                AniobNode(id = 1, className = "android.view.View", bounds = AniobRect(900, 1900, 1000, 2000)),
                AniobNode(id = 2, className = "android.view.View", bounds = AniobRect(100, 100, 200, 200))
            )
        )
        val descriptor = RememberAction(
            kind = RememberAction.TAP,
            normalizedX = 0.95,
            normalizedY = 0.95
        )
        val cache = RememberCache()
        cache.record("tap corner", bare.packageName, bare.screenHash, listOf(descriptor))
        val replay = cache.lookup("tap corner", bare)
        assertNull(replay)
    }

    @Test
    fun anAmbiguousLabelAbortsTheReplayInsteadOfGuessing() {
        val ambiguous = screen(
            listOf(
                node(1, text = "OK", top = 100),
                node(2, text = "OK", top = 300)
            )
        )
        val cache = RememberCache()
        cache.record("confirm", ambiguous.packageName, ambiguous.screenHash, listOf(RememberAction(RememberAction.TAP, text = "OK")))
        assertNull(cache.lookup("confirm", ambiguous))
    }

    @Test
    fun aKnownViewIdNeverFallsBackToCoordinates() {
        // The recorded widget had an id, so a different widget that inherited its spot is not it.
        val later = screen(listOf(node(7, viewId = "com.example.app:id/something_else")))
        val cache = RememberCache()
        cache.record(
            "search",
            "com.example.app",
            searchScreen.screenHash,
            listOf(RememberAction(RememberAction.TAP, viewId = "com.example.app:id/go", text = "Go", normalizedX = 0.5, normalizedY = 0.78))
        )
        assertNull(cache.lookup("search", later))
    }

    @Test
    fun nonReplayableActionsAreRejected() {
        val cache = RememberCache()
        assertNull(RememberAction.fromAction(AniobAction.Finish("done"), null, searchScreen))
        assertNull("HOME is not a reproduction of the transition", RememberAction.fromAction(AniobAction.SystemKey(KeyType.HOME), null, searchScreen))
        assertNull(RememberAction.fromAction(AniobAction.PressKey(KeyType.HOME), null, searchScreen))
        assertNotNull(RememberAction.fromAction(AniobAction.SystemKey(KeyType.BACK), null, searchScreen))
        assertFalse(cache.record("done", "com.example.app", "hash", emptyList()))
        assertFalse(cache.record("", "com.example.app", "hash", listOf(RememberAction(RememberAction.BACK))))
        assertFalse(cache.record("goal", "com.example.app", "", listOf(RememberAction(RememberAction.BACK))))
        assertFalse(cache.record("goal", "", "hash", listOf(RememberAction(RememberAction.BACK))))
    }

    // ---------------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------------

    @Test
    fun jsonRoundTripKeepsEntriesAndBehaviour() {
        val cache = RememberCache(clock = { 42L })
        cache.record(
            "search for pizza",
            "com.example.app",
            searchScreen.screenHash,
            listOf(
                RememberAction.fromAction(
                    action = AniobAction.InputText(SemanticTarget.SomIndex(1), "pizza"),
                    node = searchScreen.nodes[0],
                    screen = searchScreen
                )!!
            )
        )
        cache.record(
            "leave",
            "com.example.app",
            "com.example.app:Other:9",
            listOf(RememberAction.fromAction(AniobAction.PressKey(KeyType.BACK), null, searchScreen)!!)
        )

        val json = cache.toJson()
        assertTrue(json.startsWith("{\"version\":2"))
        assertTrue(json.contains("\"goal\":\"" + com.aniob.core.memory.RememberTemplate.keyOf("search for pizza") + "\""))

        val restored = RememberCache.fromJson(json)
        assertEquals(cache.size, restored.size)
        assertNotNull(restored.lookup("search for pizza", screen(searchScreen.nodes.map { it.copy(id = it.id + 3) })))
        assertNotNull(restored.lookup("leave", screen(searchScreen.nodes, hash = "com.example.app:Other:9")))
    }

    @Test
    fun damagedJsonYieldsAnEmptyCacheInsteadOfThrowing() {
        assertEquals(0, RememberCache.fromJson("").size)
        assertEquals(0, RememberCache.fromJson("{").size)
        assertEquals(0, RememberCache.fromJson("{\"entries\":").size)
        assertEquals(0, RememberCache.fromJson("{\"entries\":[{\"goal\":\"x\"}]}").size)
    }

    @Test
    fun entriesAreCappedByOldestUpdate() {
        val cache = RememberCache(maxEntries = 2, clock = { 0L })
        val descriptor = RememberAction(RememberAction.BACK)
        cache.record("one", "pkg", "h1", listOf(descriptor))
        cache.record("two", "pkg", "h2", listOf(descriptor))
        cache.record("three", "pkg", "h3", listOf(descriptor))
        assertEquals(2, cache.size)
        assertFalse(cache.contains("one", "pkg", "h1"))
        assertTrue(cache.contains("three", "pkg", "h3"))
    }

    @Test
    fun multiScreenTrajectoryLookupSucceedsOnHeadMatch() {
        val cache = RememberCache()
        // Step 1: tap "Go" on searchScreen
        val step1 = RememberAction(kind = RememberAction.TAP, viewId = "com.example.app:id/go", text = "Go")
        // Step 2: tap "Details" on a subsequent screen (not present on searchScreen)
        val step2 = RememberAction(kind = RememberAction.TAP, viewId = "com.example.app:id/details", text = "Details")

        cache.record(
            goal = "search for pizza",
            packageName = searchScreen.packageName,
            preActionScreenHash = searchScreen.screenHash,
            actions = listOf(step1, step2)
        )

        // Lookup on the initial searchScreen: step 1 resolves live, step 2 converts to AniobAction via toAction().
        val actions = cache.lookup("search for pizza", searchScreen)
        assertNotNull(actions)
        assertEquals(2, actions!!.size)
        assertTrue(actions[0] is AniobAction.Tap)
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/go"), (actions[0] as AniobAction.Tap).target)
        assertTrue(actions[1] is AniobAction.Tap)
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/details"), (actions[1] as AniobAction.Tap).target)
    }

    @Test
    fun parameterizedAnchorClearsViewIdPreventingWrongRowMatch() {
        val cache = RememberCache()
        // Template step with HomeLAN text and row_net_1 viewId
        val step = RememberAction(
            kind = RememberAction.TAP,
            viewId = "com.example.app:id/row_net_1",
            text = "HomeLAN"
        )
        cache.record(
            goal = "connect to \"HomeLAN\"",
            packageName = searchScreen.packageName,
            preActionScreenHash = searchScreen.screenHash,
            actions = listOf(step)
        )

        // Replay with OfficeLAN on a screen that has OfficeLAN on row 2
        val netScreen = screen(
            listOf(
                node(10, viewId = "com.example.app:id/row_net_1", text = "HomeLAN"),
                node(20, viewId = "com.example.app:id/row_net_2", text = "OfficeLAN")
            ),
            hash = searchScreen.screenHash
        )

        val actions = cache.lookup("connect to \"OfficeLAN\"", netScreen)
        assertNotNull(actions)
        assertEquals(1, actions!!.size)
        // Since viewId was dropped during parameterization, it matched by text "OfficeLAN" -> row_net_2!
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/row_net_2"), (actions[0] as AniobAction.Tap).target)
    }
}
