package com.aniob.app.bench

import com.aniob.app.research.CsvCodec
import com.aniob.core.bench.*
import java.io.File

/** Versioned research export, separate from interactive telemetry/manual outcome overrides. */
object BenchmarkCsv {
    const val SCHEMA_VERSION = 1

    fun write(directory: File, plan: BenchmarkPlan, results: List<BenchmarkTrialResult>) {
        val schedule = plan.schedule()
        val byId = results.associateBy { it.trial.runId }
        val expected = schedule.associateBy { it.runId }
        require(byId.size == results.size && results.all { expected[it.trial.runId] == it.trial }) {
            "Results do not match this planned session"
        }
        require(!directory.exists() && directory.mkdirs()) { "Use a new export directory per session" }
        val runtime = plan.runtime
        table(directory, "study.csv", "schema,experiment_id,session_id,seed,repetitions,planned_trials,completed_rows,audit_protocol_id,device_id,device_build,model,model_version,provider,provider_version,execution_mode,runtime_settings_id,source_revision,simulated,bootstrap_resamples") {
            row(SCHEMA_VERSION, plan.experimentId, plan.sessionId, plan.seed, plan.repetitions, schedule.size, results.size,
                plan.auditProtocolId, runtime.deviceId, runtime.deviceBuild, runtime.model, runtime.modelVersion, runtime.provider, runtime.providerVersion,
                runtime.executionMode, runtime.settingsId, runtime.sourceRevision, runtime.simulated, BenchmarkStatistics.BOOTSTRAP_RESAMPLES)
        }
        table(directory, "trials.csv", "experiment_id,session_id,run_id,block_id,task_id,scenario_id,track,configuration,condition,input_id,layout_id,difficulty,repetition,execution_order,model_seed,max_steps,timeout_seconds,expected_screen,expected_world,observed_screen,observed_world,seed_artifact_id,training_run_id,training_task_id,training_evidence_ref,training_input_id,training_layout_id,loaded_artifact_id,reset_verified,initial_audit,initial_evidence_ref,task_status,final_audit,final_evidence_ref,agent_reported_success,duration_ms,terminal_reason") {
            for (trial in schedule) {
                val r = byId[trial.runId]
                val c = trial.case
                row(plan.experimentId, plan.sessionId, trial.runId, trial.blockId, c.task.taskId, c.scenarioId, c.task.track,
                    trial.configuration, c.condition, c.inputId, c.layoutId, c.difficulty, trial.repetition, trial.order,
                    trial.generationSeed, c.task.maxSteps, c.task.timeoutSeconds, c.expectedStart.screenFingerprint, c.expectedStart.worldFingerprint,
                    r?.observedStart?.screenFingerprint, r?.observedStart?.worldFingerprint, c.experience?.artifactId,
                    c.experience?.sourceRunId, c.experience?.sourceTaskId, c.experience?.trainingAudit?.evidenceRef, c.experience?.sourceInputId, c.experience?.sourceLayoutId, r?.loadedArtifactId,
                    r?.resetVerified, r?.initialAudit?.verdict, r?.initialAudit?.evidenceRef, r?.status ?: "NOT_RUN", r?.finalAudit?.verdict,
                    r?.finalAudit?.evidenceRef, r?.agentReportedSuccess, r?.durationMs, r?.terminalReason)
            }
        }
        table(directory, "actions.csv", "run_id,step,tool,source,verification_status,evidence_category,failure_kind,dispatch_attempted,dispatch_accepted,task_control,action_latency_ms,step_latency_ms,ended_ms,generation_ms,input_tokens,output_tokens,usage_source,independent_effect,evidence_ref,unnecessary_retry,repeated_same_state,action_fingerprint,pre_screen,pre_world,post_screen,post_world") {
            for (trial in schedule) for (a in byId[trial.runId]?.actions.orEmpty()) {
                row(trial.runId, a.step, a.tool, a.source, a.status, a.evidence, a.failureKind, a.dispatchAttempted,
                    a.dispatched, a.taskControl, a.actionLatencyMs, a.latencyMs, a.endedMs, a.generationMs, a.inputTokens, a.outputTokens,
                    a.usageSource, a.audit?.effect?.verdict, a.audit?.effect?.evidenceRef, a.audit?.unnecessaryRetry,
                    a.repeatedWithoutStateChange, a.actionFingerprint, a.preState?.screenFingerprint, a.preState?.worldFingerprint,
                    a.postState?.screenFingerprint, a.postState?.worldFingerprint)
            }
        }
        table(directory, "reuse.csv", "run_id,step,artifact_id,skill_id,skill_version,decision") {
            for (trial in schedule) for (r in byId[trial.runId]?.reuse.orEmpty()) {
                row(trial.runId, r.step, r.artifactId, r.skillId, r.skillVersion, r.decision)
            }
        }
        val stats = BenchmarkStatistics.summarize(results, plan.seed)
        table(directory, "cells.csv", "track,condition,configuration,recorded_trials,valid_trials,audited_tasks,verified_actions,unconfirmed_actions,failed_actions,skipped_actions,recovery_episodes,censored_recoveries,input_tokens_recorded,output_tokens_recorded") {
            for (s in stats) {
                row(s.cell.track, s.cell.condition, s.cell.configuration, s.planned, s.valid, s.auditedTasks,
                    s.actionStatuses[BenchmarkActionStatus.VERIFIED], s.actionStatuses[BenchmarkActionStatus.UNCONFIRMED],
                    s.actionStatuses[BenchmarkActionStatus.FAILED], s.actionStatuses[BenchmarkActionStatus.SKIPPED],
                    s.recoveryEpisodes, s.censoredRecoveries, s.inputTokensRecorded.takeIf { s.metrics.getValue("input_tokens_per_call").n > 0 },
                    s.outputTokensRecorded.takeIf { s.metrics.getValue("output_tokens_per_call").n > 0 })
            }
        }
        table(directory, "metrics.csv", "track,condition,configuration,metric,n,task_clusters,mean,median,p95,sample_sd,mean_ci95_lower,mean_ci95_upper") {
            for (s in stats) for ((name, m) in s.metrics) {
                row(s.cell.track, s.cell.condition, s.cell.configuration, name, m.n, m.taskClusters, m.mean,
                    m.median, m.p95, m.sampleSd, m.meanCi95?.lower, m.meanCi95?.upper)
            }
        }
        table(directory, "paired.csv", "track,condition,baseline,comparison,candidate_pairs,valid_pairs,wins,losses,ties,task_clusters,mean_success_difference,median,p95,sample_sd,mean_ci95_lower,mean_ci95_upper") {
            for (p in BenchmarkStatistics.pairedSuccess(results, plan.seed)) {
                val m = p.difference
                row(p.track, p.condition, p.baseline, p.comparison, p.candidatePairs, m.n, p.wins, p.losses,
                    p.ties, m.taskClusters, m.mean, m.median, m.p95, m.sampleSd, m.meanCi95?.lower, m.meanCi95?.upper)
            }
        }
    }

    private fun table(directory: File, filename: String, header: String, write: Table.() -> Unit) {
        File(directory, filename).bufferedWriter().use { writer ->
            writer.appendLine(header)
            Table(writer, header.count { it == ',' } + 1).write()
        }
    }

    private class Table(private val writer: Appendable, private val width: Int) {
        fun row(vararg values: Any?) {
            check(values.size == width)
            writer.appendLine(values.joinToString(",") { CsvCodec.escape(CsvCodec.singleLine(it?.toString().orEmpty())) })
        }
    }
}
