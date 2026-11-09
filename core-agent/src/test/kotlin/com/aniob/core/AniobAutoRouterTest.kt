package com.aniob.core

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteTarget
import com.aniob.core.tools.DevicePowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAutoRouterTest {

    @Test
    fun testOfflineForcedToLocalSLM() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 90, isNetworkAvailable = false)

        val decision = AniobAutoRouter.decideRoute("Search contacts", screen, power)
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
        assertTrue(decision.reason.contains("offline", ignoreCase = true))
    }

    @Test
    fun testLowBatteryOffloadedToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 14, isCharging = false, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Navigate map", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("battery", ignoreCase = true))
    }

    @Test
    fun testVisionNeedRoutesToOmnirouteCloud() {
        // Screen with >= 5 unlabelled clickable nodes AND vision task wording
        val unlabelledNodes = (1..6).map { id ->
            AniobNode(id = id, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(id * 50, 0, id * 50 + 40, 100))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = unlabelledNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Look at the photo on screen and tap icon", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.requiresVision)
    }

    @Test
    fun testTwoUnlabelledPlusVisionWordingRoutesToCloud() {
        val unlabelledNodes = (1..3).map { id ->
            AniobNode(id = id, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(id * 50, 0, id * 50 + 40, 100))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = unlabelledNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        // unlabelled=3 >= 2 && task mentions icon -> needs vision
        val decision = AniobAutoRouter.decideRoute("Identify the icon and tap it", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.requiresVision)
        assertTrue(decision.reason.contains("unlabelled 3"))
        assertTrue(decision.reason.contains("canvas false"))
    }

    @Test
    fun testUnlabelledBelowFiveWithoutVisionWordingStaysLocal() {
        // 3 unlabelled icons but no vision wording -> Skyvern: do NOT trigger cloud for every icon
        val unlabelledNodes = (1..3).map { id ->
            AniobNode(id = id, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(id * 50, 0, id * 50 + 40, 100))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = unlabelledNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Open the first item", screen, power)
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
    }

    @Test
    fun testCanvasNodePlusVisionWordingRoutesToCloud() {
        val screen = AniobScreenState(
            packageName = "com.test.app",
            nodes = listOf(
                AniobNode(id = 1, className = "android.view.SurfaceView", text = "", contentDescription = "", isClickable = false, bounds = AniobRect(0, 0, 200, 400)),
                AniobNode(id = 2, className = "android.widget.Button", text = "Cancel", isClickable = true, bounds = AniobRect(0, 420, 80, 440))
            )
        )
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("What picture is on the canvas", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.requiresVision)
        assertTrue(decision.reason.contains("canvas true"))
    }

    @Test
    fun testSimpleTextNavRoutesToLocalSLM() {
        val textNodes = (1..10).map { id ->
            AniobNode(id = id, className = "TextView", text = "Item $id", isClickable = true, bounds = AniobRect(0, id * 50, 200, id * 50 + 40))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = textNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Select item 3", screen, power)
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
    }

    @Test
    fun testMissingModelFileEscalatesToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Search notes",
            screenState = screen,
            powerState = power,
            isModelFileMissing = true
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("missing", ignoreCase = true))
    }

    @Test
    fun testLocalFailureEscalationToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Open settings",
            screenState = screen,
            powerState = power,
            lastLocalFailCount = 2
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("failed 2 times", ignoreCase = true))
    }

    @Test
    fun testUserModeLocalOnlyForbidsCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 10, isNetworkAvailable = true)

        // Normally low battery routes to cloud, but local-only must stay local
        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Open settings",
            screenState = screen,
            powerState = power,
            userRoutingMode = "local-only"
        )
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
    }

    @Test
    fun testUserModeLocalOnlyMissingModelReturnsCannotProceed() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Open settings",
            screenState = screen,
            powerState = power,
            isModelFileMissing = true,
            userRoutingMode = "local-only"
        )
        assertEquals(RouteTarget.CANNOT_PROCEED, decision.target)
    }

    @Test
    fun testUserModeCloudOnlyForbidsLocal() {
        val textNodes = (1..10).map { id ->
            AniobNode(id = id, className = "TextView", text = "Item $id", isClickable = true, bounds = AniobRect(0, id * 50, 200, id * 50 + 40))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = textNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        // Normally simple navigation routes to local, but cloud-only must route to cloud
        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Select item 3",
            screenState = screen,
            powerState = power,
            userRoutingMode = "cloud-only"
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
    }

    @Test
    fun testUserModeCloudOnlyOfflineReturnsCannotProceed() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = false)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Open settings",
            screenState = screen,
            powerState = power,
            userRoutingMode = "cloud-only"
        )
        assertEquals(RouteTarget.CANNOT_PROCEED, decision.target)
    }
}

class AniobStickyRoutingTest {

    private val power = DevicePowerState(batteryPercent = 90, isCharging = false, isNetworkAvailable = true)
    private fun screen(nodes: Int) = AniobScreenState(
        packageName = "com.test.app",
        nodes = (1..nodes).map { id ->
            AniobNode(id = id, className = "TextView", text = "row $id", contentDescription = "", isClickable = true, bounds = AniobRect(0, id * 40, 400, id * 40 + 38))
        }
    )

    @Test
    fun stickyLocalWinsOverDenseScreenHeuristic() {
        // 50 text nodes: normally balanced mode would escalate to cloud; the sticky lock keeps local.
        val decision = AniobAutoRouter.decideRoute(
            "do the task", screen(50), power,
            userRoutingMode = "balanced",
            taskStickyTarget = RouteTarget.LOCAL_SLM
        )
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
        assertTrue(decision.reason.contains("Sticky local", ignoreCase = true))
    }

    @Test
    fun stickyCloudWinsOverSimpleScreenHeuristic() {
        val decision = AniobAutoRouter.decideRoute(
            "do the task", screen(10), power,
            taskStickyTarget = RouteTarget.OMNIROUTE_CLOUD
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("Sticky cloud", ignoreCase = true))
    }

    @Test
    fun stickyLocalNeedsModel() {
        // Missing local model beats the sticky lock: we cannot run local without a model.
        val decision = AniobAutoRouter.decideRoute(
            "do the task", screen(10), power,
            isModelFileMissing = true,
            taskStickyTarget = RouteTarget.LOCAL_SLM
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
    }

    @Test
    fun stickyLocalYieldsToVisionScreen() {
        // >=5 unlabelled clickable nodes on a vision-worded prompt: cloud vision wins, sticky does not pin local.
        val nodes = (1..6).map { id ->
            AniobNode(id = id, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(id * 50, 0, id * 50 + 40, 100))
        }
        val visionScreen = AniobScreenState(packageName = "com.test.app", nodes = nodes)
        val decision = AniobAutoRouter.decideRoute(
            "look at the picture and tap", visionScreen, power,
            taskStickyTarget = RouteTarget.LOCAL_SLM
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.requiresVision)
    }

    @Test
    fun stickyUnlockedInStrictModes() {
        // local-only still forbids cloud; cloud-only still forbids local; both win over sticky.
        val localOnly = AniobAutoRouter.decideRoute(
            "do the task", screen(10), power, userRoutingMode = "local-only",
            installedModelId = "m", taskStickyTarget = RouteTarget.OMNIROUTE_CLOUD
        )
        assertEquals(RouteTarget.LOCAL_SLM, localOnly.target)
        val cloudOnly = AniobAutoRouter.decideRoute(
            "do the task", screen(10), power, userRoutingMode = "cloud-only",
            taskStickyTarget = RouteTarget.LOCAL_SLM
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, cloudOnly.target)
    }

    @Test
    fun stickyLocalYieldsToOfflineModelMissing() {
        val offline = DevicePowerState(batteryPercent = 90, isNetworkAvailable = false)
        val decision = AniobAutoRouter.decideRoute(
            "do the task", screen(10), offline,
            isModelFileMissing = true,
            taskStickyTarget = RouteTarget.OMNIROUTE_CLOUD
        )
        assertEquals(RouteTarget.CANNOT_PROCEED, decision.target)
    }
}
