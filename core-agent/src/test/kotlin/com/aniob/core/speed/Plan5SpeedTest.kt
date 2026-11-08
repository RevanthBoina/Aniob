package com.aniob.core.speed

import com.aniob.core.config.AniobDecodeConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Plan5SpeedTest {

    // ------------------------------------------------------- 5.1 byte-stable prefix

    @Test
    fun `committed bytes are stable across turns so the cache can hit`() {
        val a = PromptAssembler("SYSTEM PREFIX")
        a.commit(PromptAssembler.CommittedTurn(1, "I ran tap on \"Inbox\"."))
        val first = a.assemble(PromptAssembler.Tail(observation = "screen A", ephemeral = listOf("nudge 1")))

        a.commit(PromptAssembler.CommittedTurn(2, "I ran tap on \"Compose\"."))
        val second = a.assemble(PromptAssembler.Tail(observation = "screen B", ephemeral = listOf("nudge 2")))

        assertNull(second.stabilityViolation)

        // Everything up to the end of turn 1 must be byte-identical across the two assemblies.
        val stableRegion = "SYSTEM PREFIX\n" + "--- step 1 ---\nI ran tap on \"Inbox\".\n"
        assertTrue(first.text.startsWith(stableRegion))
        assertTrue(second.text.startsWith(stableRegion))
        assertTrue(
            "only ${second.stablePrefixBytes} of ${stableRegion.length} bytes were stable",
            second.stablePrefixBytes >= stableRegion.length
        )
        assertTrue(second.cacheableRatio > 0.0)
    }

    @Test
    fun `history is append-only`() {
        val a = PromptAssembler("SYSTEM")
        a.commit(PromptAssembler.CommittedTurn(2, "b"))
        val threw = try { a.commit(PromptAssembler.CommittedTurn(1, "a")); false }
        catch (e: IllegalArgumentException) { true }
        assertTrue("committing out of order must be rejected", threw)
    }

    // ------------------------------------------------------- 5.2 don't narrate success

    @Test
    fun `only a failed turn carries an execution-result line`() {
        val a = PromptAssembler("SYSTEM")
        val ok = a.committedTurnFor(1, "I ran tap.", verified = true, failureReason = null)
        val bad = a.committedTurnFor(2, "I ran tap.", verified = false, failureReason = "dispatch failed")

        assertNull(ok.executionResult)
        assertNotNull(bad.executionResult)
        assertFalse("machine text must be neutralized", bad.executionResult!!.contains("failed"))
        assertFalse(ok.render().contains("execution result"))
    }

    // ------------------------------------------------------- 5.3 ephemerals are deleted

    @Test
    fun `ephemeral blocks never reach committed history`() {
        val a = PromptAssembler("SYSTEM")
        a.assemble(PromptAssembler.Tail(observation = "screen", ephemeral = listOf("REMINDER: be careful")))
        a.commit(PromptAssembler.CommittedTurn(1, "I ran tap."))
        val next = a.assemble(PromptAssembler.Tail(observation = "screen 2"))
        assertFalse(next.text.substringBefore("screen 2").contains("REMINDER"))
    }

    // ------------------------------------------------------- 5.5 speculative perception

    @Test
    fun `a valid probe is injected into the turn`() = runBlocking {
        val sp = SpeculativePerception<String>(
            probe = { "probe-data" },
            stillValid = { true },
            rollback = { true }
        )
        val r = sp.speculate { "generated" }
        assertTrue(r is SpeculativePerception.Result.Injected)
        assertEquals(1.0, sp.stats().injectionRate, 0.0)
    }

    @Test
    fun `a screen that moved during generation discards the speculation and rolls back`() = runBlocking {
        var rolledBack = false
        val sp = SpeculativePerception<String>(
            probe = { "probe-data" },
            stillValid = { false },                     // permission dialog appeared
            rollback = { rolledBack = true; true }
        )
        val r = sp.speculate { "generated" }
        assertTrue(r is SpeculativePerception.Result.Discarded)
        assertTrue(rolledBack)
        assertEquals(1.0, sp.stats().rollbackSuccessRate, 0.0)
        assertTrue((r as SpeculativePerception.Result.Discarded).reason.contains("restart"))
    }

    @Test
    fun `a slow probe never blocks generation`() = runBlocking {
        val sp = SpeculativePerception<String>(
            probe = { kotlinx.coroutines.delay(5_000); "late" },
            stillValid = { true },
            rollback = { true },
            probeTimeoutMs = 100
        )
        val started = System.currentTimeMillis()
        val r = sp.speculate { "generated" }
        val elapsed = System.currentTimeMillis() - started
        assertTrue(r is SpeculativePerception.Result.NoProbe)
        assertTrue("generation waited $elapsed ms on a dead probe", elapsed < 3_000)
        assertEquals(1, sp.stats().probeTimeouts)
    }

    // ------------------------------------------------------- 5.6 right-size every call

    @Test
    fun `every role has a tier and the cheap roles are on the smallest model`() {
        AniobDecodeConfig.Role.values().forEach {
            assertNotNull("role $it has no assignment", RoleModelPolicy.TABLE[it])
        }
        assertEquals(RoleModelPolicy.Tier.SMALLEST, RoleModelPolicy.assignmentFor(AniobDecodeConfig.Role.CONSISTENCY).tier)
        assertEquals(RoleModelPolicy.Tier.SMALLEST, RoleModelPolicy.assignmentFor(AniobDecodeConfig.Role.SUMMARIZER).tier)
        assertEquals(RoleModelPolicy.Tier.SMALLEST, RoleModelPolicy.assignmentFor(AniobDecodeConfig.Role.EXTRACTOR).tier)
        assertEquals(RoleModelPolicy.Tier.STRONGEST, RoleModelPolicy.assignmentFor(AniobDecodeConfig.Role.PLANNER).tier)
    }

    @Test
    fun `falling back to another tier is reported, never silent`() {
        val tiers = mapOf(
            RoleModelPolicy.Tier.STRONGEST to "big",
            RoleModelPolicy.Tier.MID to "mid",
            RoleModelPolicy.Tier.SMALLEST to "small"
        )
        val normal = RoleModelPolicy.resolve(AniobDecodeConfig.Role.PLANNER, tiers, setOf("big", "mid", "small"))!!
        assertEquals("big", normal.modelId)
        assertNull(normal.degradationNotice)

        val degraded = RoleModelPolicy.resolve(AniobDecodeConfig.Role.PLANNER, tiers, setOf("mid", "small"))!!
        assertEquals("mid", degraded.modelId)
        assertTrue(degraded.usedFallback)
        assertNotNull(degraded.degradationNotice)

        assertNull(RoleModelPolicy.resolve(AniobDecodeConfig.Role.PLANNER, tiers, emptySet()))
    }

    // ------------------------------------------------------- 5.7 wait drift

    @Test
    fun `the explicit wait shortens by the measured overhead`() {
        val c = WaitDriftCompensator()
        repeat(6) { c.observe(intendedWaitMs = 5_000, actualElapsedMs = 7_400) }
        assertTrue("overhead ~2400ms, got ${c.measuredOverheadMs}", c.measuredOverheadMs in 2_200..2_500)
        val next = c.compensate(5_000)
        assertTrue("expected ~2600ms, got $next", next in 2_400..2_800)
    }

    @Test
    fun `saturation is detected and announced`() {
        val c = WaitDriftCompensator()
        repeat(5) { c.observe(intendedWaitMs = 1_000, actualElapsedMs = 4_000) }
        assertTrue(c.isSaturated(1_000))
        assertNotNull(c.saturationNotice(1_000))
        assertNull(c.saturationNotice(30_000))
    }

    // ------------------------------------------------------- 5.8 tool budget

    @Test
    fun `evidence calls cannot consume the reserve`() {
        val b = TurnToolBudget(maxCallsPerTurn = 4, reserve = 1)
        b.startTurn()
        assertTrue(b.onCall(TurnToolBudget.Kind.PRE_DECISION).allowed)
        assertTrue(b.onCall(TurnToolBudget.Kind.PRE_DECISION).allowed)
        val last = b.onCall(TurnToolBudget.Kind.PRE_DECISION)
        assertEquals(TurnToolBudget.Decision.ALLOW_LAST_CALL, last.decision)

        val rejected = b.onCall(TurnToolBudget.Kind.PRE_DECISION)
        assertFalse(rejected.allowed)
        assertTrue(rejected.message!!.contains("reserved"))

        // the reserve is still there for the action
        assertTrue(b.onCall(TurnToolBudget.Kind.TURN_ENDING).allowed)
    }

    @Test
    fun `a pre-decision tool may never share a call list with a turn-ending action`() {
        val bad = TurnToolBudget.validateCallList(
            listOf(TurnToolBudget.Kind.PRE_DECISION, TurnToolBudget.Kind.TURN_ENDING)
        )
        assertNotNull(bad)
        assertTrue(bad!!.contains("before its own evidence"))

        assertNull(TurnToolBudget.validateCallList(
            listOf(TurnToolBudget.Kind.MEMORY_WRITE, TurnToolBudget.Kind.TURN_ENDING)
        ))
        assertNotNull(TurnToolBudget.validateCallList(
            listOf(TurnToolBudget.Kind.TURN_ENDING, TurnToolBudget.Kind.TURN_ENDING)
        ))
    }

    @Test
    fun `nothing runs after the turn ended`() {
        val b = TurnToolBudget()
        b.startTurn()
        b.onCall(TurnToolBudget.Kind.TURN_ENDING)
        assertFalse(b.onCall(TurnToolBudget.Kind.PRE_DECISION).allowed)
    }
}
