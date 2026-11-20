package com.aniob.core.bench

import com.aniob.core.bench.AniobBenchmarkManifest.Track
import com.aniob.core.verifier.DeterministicVerifier.EvidenceCategory
import org.junit.Assert.*
import org.junit.Test

class BenchmarkStatisticsTest {
    private val task = AniobBenchmarkManifest.getModelGenerationTasks().first()
    private val cell = BenchmarkCase(task, "cold", "input-a", "layout-a", "easy", BenchmarkCondition.COLD,
        BenchmarkStart("screen", "world"))
    private val runtime = BenchmarkRuntime("device", "build", "model", "version", "provider", "provider-version", "mode", "settings", "rev", true)
    private val plan = BenchmarkPlan("study", "session", runtime, "audit-v1", 7, 5, listOf(cell))

    @Test fun counterbalanceIsReproducibleAndComplete() {
        val ordered = plan.schedule()
        assertEquals(ordered, plan.schedule())
        assertEquals(ordered.size, ordered.map { it.runId }.distinct().size)
        assertEquals(5, ordered.groupBy { it.blockId }.size)
        for ((_, block) in ordered.groupBy { it.blockId }) {
            assertEquals(5, block.size)
            assertEquals(1, block.map { it.generationSeed }.distinct().size)
        }
        assertEquals(5, ordered.chunked(5).map { it.first().configuration }.distinct().size)
    }

    @Test fun coldAndWarmCannotBeClaimedWithoutMatchingProvenance() {
        val seed = BenchmarkExperience("artifact", "training", task.taskId, BenchmarkAudit(AuditVerdict.MET, "training-proof"), "input-a", "layout-a")
        assertThrows(IllegalArgumentException::class.java) { cell.copy(experience = seed) }
        assertThrows(IllegalArgumentException::class.java) { cell.copy(condition = BenchmarkCondition.WARM) }
        assertThrows(IllegalArgumentException::class.java) { cell.copy(condition = BenchmarkCondition.UI_DRIFT, experience = seed) }
        assertThrows(IllegalArgumentException::class.java) { cell.copy(condition = BenchmarkCondition.REPEATED_INPUT, experience = seed) }
        assertThrows(IllegalArgumentException::class.java) {
            cell.copy(condition = BenchmarkCondition.WARM, experience = seed.copy(sourceTaskId = "different"))
        }
        assertEquals(BenchmarkCondition.WARM, cell.copy(condition = BenchmarkCondition.WARM, experience = seed).condition)
    }

    @Test fun denominatorsExcludeUndeterminedGroundTruthAndKeepStatusesSeparate() {
        val rows = listOf(
            action(0, BenchmarkActionStatus.VERIFIED, AuditVerdict.NOT_MET),
            action(1, BenchmarkActionStatus.UNCONFIRMED),
            action(2, BenchmarkActionStatus.FAILED, AuditVerdict.MET, retry = true),
            action(3, BenchmarkActionStatus.SKIPPED),
            action(4, BenchmarkActionStatus.FAILED),
            action(5, BenchmarkActionStatus.VERIFIED, AuditVerdict.MET, retry = false)
        )
        val s = BenchmarkStatistics.summarize(listOf(result(plan.schedule().first(), true, rows)), 3).single()
        assertEquals(mapOf(BenchmarkActionStatus.VERIFIED to 2, BenchmarkActionStatus.UNCONFIRMED to 1,
            BenchmarkActionStatus.FAILED to 2, BenchmarkActionStatus.SKIPPED to 1), s.actionStatuses)
        assertEquals(2.0 / 6, s.metrics.getValue("action_verification_rate").mean!!, 1e-10)
        assertEquals(1.0 / 6, s.metrics.getValue("unconfirmed_action_rate").mean!!, 1e-10)
        assertEquals(0.5, s.metrics.getValue("false_verification_rate").mean!!, 0.0)
        assertEquals(1, s.metrics.getValue("false_failure_rate").n)
        assertEquals(1.0, s.metrics.getValue("false_failure_rate").mean!!, 0.0)
        assertEquals(0.5, s.metrics.getValue("unnecessary_retry_rate").mean!!, 0.0)
        assertEquals(2, s.recoveryEpisodes)
        assertEquals(10.0, s.metrics.getValue("recovery_ms_completed_episodes").mean!!, 0.0)
        assertEquals(0, s.metrics.getValue("total_tokens_per_complete_call").n)
        assertNull(s.metrics.getValue("total_tokens_per_complete_call").mean)
    }

    @Test fun pairsAreBlockedAndInvalidResetsDontBecomeLosses() {
        val blocks = plan.copy(configurations = listOf(BenchmarkConfiguration.NO_MEMORY, BenchmarkConfiguration.LEARNED_SKILLS))
            .schedule().groupBy { it.repetition }.values.toList()
        val rows = blocks.take(2).flatten().map { trial ->
            result(trial, trial.configuration == BenchmarkConfiguration.LEARNED_SKILLS).let {
                if (trial.repetition == 2 && trial.configuration == BenchmarkConfiguration.LEARNED_SKILLS)
                    it.copy(resetVerified = false, status = BenchmarkTaskStatus.INVALID_RESET) else it
            }
        }
        val paired = BenchmarkStatistics.pairedSuccess(rows, 8).single()
        assertEquals(2, paired.candidatePairs)
        assertEquals(1, paired.difference.n)
        assertEquals(1, paired.wins)
        assertEquals(1.0, paired.difference.mean!!, 0.0)
        assertNull(paired.difference.meanCi95)
    }

    @Test fun statisticsUseSampleDeviationNearestRankAndTaskClusterIntervals() {
        val samples = listOf("A" to 1.0, "A" to 3.0, "B" to 5.0, "B" to 7.0)
        val s = BenchmarkStatistics.sample(samples, 10)
        assertEquals(4, s.n)
        assertEquals(2, s.taskClusters)
        assertEquals(4.0, s.median!!, 0.0)
        assertEquals(7.0, s.p95!!, 0.0)
        assertEquals(kotlin.math.sqrt(20.0 / 3), s.sampleSd!!, 1e-10)
        assertEquals(BenchmarkStatistics.Interval(2.0, 6.0), s.meanCi95)
        assertEquals(s, BenchmarkStatistics.sample(samples.reversed(), 10))
    }

    private fun action(step: Int, status: BenchmarkActionStatus, audit: AuditVerdict? = null, retry: Boolean? = null) =
        BenchmarkActionRow(step, "tap", status, EvidenceCategory.NONE, status != BenchmarkActionStatus.SKIPPED,
            status == BenchmarkActionStatus.VERIFIED || status == BenchmarkActionStatus.UNCONFIRMED,
            false, 2.0, step * 10.0, audit = audit?.let { BenchmarkActionAudit(BenchmarkAudit(it, "oracle:$step"), retry) }, source = "MODEL")

    private fun result(trial: BenchmarkTrial, success: Boolean, actions: List<BenchmarkActionRow> = emptyList()) =
        BenchmarkTrialResult(trial, if (success) BenchmarkTaskStatus.VERIFIED else BenchmarkTaskStatus.NOT_MET,
            trial.case.expectedStart, true, "fixture", 40.0, actions)
}
