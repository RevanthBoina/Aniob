package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.ladder.ExecutionLlmGateway.LocalAttempt
import com.aniob.core.ladder.ExecutionLlmGateway.Request
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionLlmGatewayTest {
    private val request = Request("SYSTEM", "USER", "c2NyZWVu")
    private val localAction = AniobAction.Finish("local")
    private val cloudAction = AniobAction.Finish("cloud")
    private val unusable = LocalAttempt(AniobAction.Fail("unparseable"), usedFallback = true, reason = "unparseable")

    @Test fun localSuccessMakesZeroCloudCalls() = runBlocking {
        val gateway = ExecutionLlmGateway { true }
        var cloud = 0
        val decision = gateway.plan(
            request,
            local = { LocalAttempt(localAction, usedFallback = false) },
            cloud = { cloud++; cloudAction }
        )
        assertSame(localAction, decision.action)
        assertEquals(ExecutionLlmGateway.Lane.LOCAL, decision.lane)
        assertEquals(0, cloud)
        assertEquals(0, gateway.cloudCalls)
        assertFalse(gateway.stickyCloud)
    }

    @Test fun fallbackSendsTheIdenticalRequestToCloudAndSticks() = runBlocking {
        val gateway = ExecutionLlmGateway { true }
        val localSaw = mutableListOf<Request>()
        val cloudSaw = mutableListOf<Request>()
        val first = gateway.plan(
            request,
            local = { localSaw += it; unusable },
            cloud = { cloudSaw += it; cloudAction }
        )
        assertSame(cloudAction, first.action)
        assertTrue(first.escalated)
        assertTrue(gateway.stickyCloud)
        assertSame(localSaw.single(), cloudSaw.single())
        assertEquals(request, cloudSaw.single())

        // Next step of the same task: no second on-device attempt.
        val second = gateway.plan(
            Request("SYSTEM", "USER 2"),
            local = { error("the on-device model must not be asked again once the task is on cloud") },
            cloud = { cloudSaw += it; cloudAction }
        )
        assertEquals(ExecutionLlmGateway.Lane.CLOUD, second.lane)
        assertFalse(second.escalated)
        assertEquals(1, gateway.localCalls)
        assertEquals(2, gateway.cloudCalls)
    }

    @Test fun localFailureWithoutCloudIsAnHonestFail() = runBlocking {
        val gateway = ExecutionLlmGateway { false }
        val decision = gateway.plan(request, local = { unusable }, cloud = { error("cloud is not available") })
        val fail = decision.action as AniobAction.Fail
        assertEquals(ExecutionLlmGateway.Lane.NONE, decision.lane)
        assertTrue(fail.reason.contains("unparseable"))
        assertTrue(fail.reason.contains("Settings"))
        assertFalse(gateway.stickyCloud)
        assertEquals(0, gateway.cloudCalls)
    }

    @Test fun noModelAtAllIsAnHonestFail() = runBlocking {
        val decision = ExecutionLlmGateway { false }.plan(request, local = null, cloud = { error("unavailable") })
        assertTrue((decision.action as AniobAction.Fail).reason.contains("No model is available"))
    }

    @Test fun deterministicLaneSkipsBothModels() = runBlocking {
        val gateway = ExecutionLlmGateway { true }
        val shortcut = AniobAction.Finish("shortcut")
        val decision = gateway.plan(
            request,
            deterministic = { shortcut },
            local = { error("no model call for a deterministic step") },
            cloud = { error("no model call for a deterministic step") }
        )
        assertSame(shortcut, decision.action)
        assertEquals(ExecutionLlmGateway.Lane.DETERMINISTIC, decision.lane)
        assertEquals(0, gateway.localCalls + gateway.cloudCalls)
    }

    @Test fun withoutAnOnDeviceModelCloudIsUsedWithoutSticking() = runBlocking {
        val gateway = ExecutionLlmGateway { true }
        val decision = gateway.plan(request, local = null, cloud = { cloudAction })
        assertEquals(ExecutionLlmGateway.Lane.CLOUD, decision.lane)
        assertFalse(decision.escalated)
        assertFalse(gateway.stickyCloud)
    }

    @Test fun stickyTaskReturnsToTheOnDeviceModelWhenCloudDropsOut() = runBlocking {
        var online = true
        val gateway = ExecutionLlmGateway { online }
        gateway.plan(request, local = { unusable }, cloud = { cloudAction })
        assertTrue(gateway.stickyCloud)

        online = false
        val decision = gateway.plan(
            request,
            local = { LocalAttempt(localAction, usedFallback = false) },
            cloud = { error("offline") }
        )
        assertSame(localAction, decision.action)
        assertEquals(ExecutionLlmGateway.Lane.LOCAL, decision.lane)
    }
}
