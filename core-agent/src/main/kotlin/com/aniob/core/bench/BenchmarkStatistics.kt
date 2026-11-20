package com.aniob.core.bench

import com.aniob.core.bench.AniobBenchmarkManifest.Track
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/** Descriptive statistics and task-cluster bootstrap CIs for the mean (rates are binary means). */
object BenchmarkStatistics {
    const val BOOTSTRAP_RESAMPLES = 2_000

    data class Interval(val lower: Double, val upper: Double)
    data class Sample(
        val n: Int, val taskClusters: Int, val mean: Double?, val median: Double?, val p95: Double?,
        val sampleSd: Double?, val meanCi95: Interval?
    )
    data class Cell(val track: Track, val condition: BenchmarkCondition, val configuration: BenchmarkConfiguration)
    data class Summary(val cell: Cell, val planned: Int, val valid: Int, val auditedTasks: Int,
        val actionStatuses: Map<BenchmarkActionStatus, Int>, val recoveryEpisodes: Int, val censoredRecoveries: Int,
        val inputTokensRecorded: Long, val outputTokensRecorded: Long, val metrics: Map<String, Sample>)
    data class Paired(val track: Track, val condition: BenchmarkCondition, val baseline: BenchmarkConfiguration,
        val comparison: BenchmarkConfiguration, val candidatePairs: Int, val wins: Int, val losses: Int, val ties: Int,
        val difference: Sample)

    fun summarize(results: List<BenchmarkTrialResult>, seed: Int): List<Summary> {
        require(results.distinctBy { it.trial.runId }.size == results.size) { "Duplicate benchmark run IDs" }
        return results.groupBy { Cell(it.trial.case.task.track, it.trial.case.condition, it.trial.configuration) }
            .toSortedMap(compareBy({ it.track.ordinal }, { it.condition.ordinal }, { it.configuration.ordinal }))
            .map { (cell, all) -> summarizeCell(cell, all, seed) }
    }

    private fun summarizeCell(cell: Cell, all: List<BenchmarkTrialResult>, seed: Int): Summary {
        val valid = all.filter { it.resetVerified }
        val groupedActions = valid.flatMap { trial -> trial.actions.map { trial to it } }
        val actions = groupedActions.filterNot { it.second.taskControl }
        val dispatches = actions.filter { it.second.dispatchAttempted }
        val audited = dispatches.filter { it.second.audit?.effect?.verdict in setOf(AuditVerdict.MET, AuditVerdict.NOT_MET) }
        val succeeded = valid.filter { it.status == BenchmarkTaskStatus.VERIFIED }
        val skillTrials = valid.filter { it.trial.configuration in setOf(BenchmarkConfiguration.LEARNED_SKILLS, BenchmarkConfiguration.VALIDATED_SKILLS) }
        val staleSkills = skillTrials.filter { it.trial.case.experience?.intentionallyStale == true }
        val phases = valid.flatMap(::recovery)
        fun trialSample(items: List<BenchmarkTrialResult>, value: (BenchmarkTrialResult) -> Double?) =
            sample(items.mapNotNull { trial -> value(trial)?.let { trial.taskId() to it } }, seed)
        fun actionSample(items: List<Pair<BenchmarkTrialResult, BenchmarkActionRow>>, value: (BenchmarkActionRow) -> Double?) =
            sample(items.mapNotNull { (trial, action) -> value(action)?.let { trial.taskId() to it } }, seed)
        fun Boolean.number() = if (this) 1.0 else 0.0
        val adaptation = skillTrials.flatMap { trial ->
            trial.reuse.mapNotNull { reuse ->
                val value = when (reuse.decision) {
                    ReuseDecision.ADAPTATION_FAILED -> 0.0
                    ReuseDecision.ADAPTED -> when (val action = trial.actions.find { it.step == reuse.step }) {
                        null -> null // e.g. timed out before the action was identified
                        else -> if (!action.dispatchAttempted) 0.0 else when (action.audit?.effect?.verdict) {
                            AuditVerdict.MET -> 1.0
                            AuditVerdict.NOT_MET -> 0.0
                            else -> null
                        }
                    }
                    else -> null
                }
                value?.let { trial.taskId() to it }
            }
        }
        return Summary(cell, all.size, valid.size, valid.count { it.finalAudit?.verdict in setOf(AuditVerdict.MET, AuditVerdict.NOT_MET) },
            BenchmarkActionStatus.entries.associateWith { status -> actions.count { it.second.status == status } },
            phases.size, phases.count { it.second == null },
            groupedActions.sumOf { it.second.inputTokens?.toLong() ?: 0L },
            groupedActions.sumOf { it.second.outputTokens?.toLong() ?: 0L },
            linkedMapOf(
                "independently_verified_task_success_rate" to trialSample(valid) { (it.status == BenchmarkTaskStatus.VERIFIED).number() },
                "action_verification_rate" to actionSample(actions) { (it.status == BenchmarkActionStatus.VERIFIED).number() },
                "unconfirmed_action_rate" to actionSample(actions) { (it.status == BenchmarkActionStatus.UNCONFIRMED).number() },
                "false_verification_rate" to actionSample(audited.filter { it.second.status == BenchmarkActionStatus.VERIFIED }) { (it.audit!!.effect.verdict == AuditVerdict.NOT_MET).number() },
                "false_failure_rate" to actionSample(audited.filter { it.second.status == BenchmarkActionStatus.FAILED }) { (it.audit!!.effect.verdict == AuditVerdict.MET).number() },
                "duplicate_action_proxy_rate" to actionSample(dispatches.filter { it.second.actionFingerprint != null }) { it.repeatedWithoutStateChange.number() },
                "unnecessary_retry_rate" to actionSample(dispatches) { it.audit?.unnecessaryRetry?.number() },
                "skill_reuse_rate" to trialSample(skillTrials) { t -> t.actions.any { it.source == "SKILL" && it.dispatchAttempted }.number() },
                "skill_reuse_precision" to actionSample(audited.filter { it.second.source == "SKILL" }) { (it.audit!!.effect.verdict == AuditVerdict.MET).number() },
                "stale_skill_rejection_rate" to trialSample(staleSkills) { t -> (t.reuse.any { it.decision == ReuseDecision.REJECTED } &&
                    t.actions.none { it.source == "SKILL" && it.dispatchAttempted }).number() },
                "skill_adaptation_success_rate" to sample(adaptation, seed),
                "task_completion_ms" to trialSample(succeeded) { it.durationMs },
                "action_latency_ms" to actionSample(dispatches) { it.actionLatencyMs },
                "actions_per_successful_task" to trialSample(succeeded) { t -> t.actions.count { it.dispatchAttempted }.toDouble() },
                "model_generation_ms" to actionSample(groupedActions) { it.generationMs },
                "input_tokens_per_call" to actionSample(groupedActions) { it.inputTokens?.toDouble() },
                "output_tokens_per_call" to actionSample(groupedActions) { it.outputTokens?.toDouble() },
                "total_tokens_per_complete_call" to actionSample(groupedActions) { r ->
                    if (r.inputTokens != null && r.outputTokens != null) r.inputTokens.toDouble() + r.outputTokens else null },
                "recovery_ms_completed_episodes" to sample(phases.mapNotNull { (task, duration) -> duration?.let { task to it } }, seed)
            ))
    }

    /** Within the same task/scenario/repetition/session, with invalid resets left out of a pair. */
    fun pairedSuccess(results: List<BenchmarkTrialResult>, seed: Int,
        baseline: BenchmarkConfiguration = BenchmarkConfiguration.NO_MEMORY): List<Paired> {
        require(results.distinctBy { it.trial.runId }.size == results.size)
        return results.groupBy { it.trial.case.task.track to it.trial.case.condition }.flatMap { (cell, rows) ->
            val blocks = rows.groupBy { it.trial.blockId }
            val configs = rows.map { it.trial.configuration }.distinct().filter { it != baseline }
            configs.sortedBy { it.ordinal }.map { arm ->
                val matched = blocks.values.mapNotNull { block ->
                    val a = block.singleOrNull { it.trial.configuration == baseline } ?: return@mapNotNull null
                    val b = block.singleOrNull { it.trial.configuration == arm } ?: return@mapNotNull null
                    a to b
                }
                val differences = matched.filter { (a, b) -> a.resetVerified && b.resetVerified }.map { (a, b) ->
                    val from = if (a.status == BenchmarkTaskStatus.VERIFIED) 1.0 else 0.0
                    val to = if (b.status == BenchmarkTaskStatus.VERIFIED) 1.0 else 0.0
                    a.taskId() to (to - from)
                }
                Paired(cell.first, cell.second, baseline, arm, matched.size, differences.count { it.second > 0 },
                    differences.count { it.second < 0 }, differences.count { it.second == 0.0 }, sample(differences, seed))
            }
        }
    }

    private fun recovery(trial: BenchmarkTrialResult): List<Pair<String, Double?>> = buildList {
        var started: Double? = null
        for (row in trial.actions.filterNot { it.taskControl }.sortedBy { it.step }) {
            if (started != null && row.audit?.effect?.verdict == AuditVerdict.MET) {
                add(trial.taskId() to (row.endedMs - started).coerceAtLeast(0.0))
                started = null
            } else if (started == null && row.status in setOf(BenchmarkActionStatus.FAILED, BenchmarkActionStatus.UNCONFIRMED)) {
                started = row.endedMs
            }
        }
        if (started != null) add(trial.taskId() to null)
    }

    private fun BenchmarkTrialResult.taskId() = trial.case.task.taskId

    /** Cluster on task, not on steps or repetitions (which are correlated). No CI for one task. */
    fun sample(samples: List<Pair<String, Double>>, seed: Int): Sample {
        val values = samples.map { it.second }.sorted()
        if (values.isEmpty()) return Sample(0, 0, null, null, null, null, null)
        val groups = samples.groupBy({ it.first }, { it.second }).toSortedMap().values
            .map { group -> group.sorted().sum() to group.size }
        val mean = values.average()
        val sd = if (values.size < 2) null else sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        val ci = if (groups.size < 2) null else {
            val random = Random(seed)
            val bootstrap = List(BOOTSTRAP_RESAMPLES) {
                var sum = 0.0
                var n = 0
                repeat(groups.size) { val (s, k) = groups[random.nextInt(groups.size)]; sum += s; n += k }
                sum / n
            }.sorted()
            Interval(nearest(bootstrap, .025), nearest(bootstrap, .975))
        }
        val median = (values[(values.size - 1) / 2] + values[values.size / 2]) / 2
        return Sample(values.size, groups.size, mean, median, nearest(values, .95), sd, ci)
    }

    private fun nearest(sorted: List<Double>, p: Double): Double = sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
}
