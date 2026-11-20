package com.aniob.core.bench

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.execution.StepPipeline
import com.aniob.core.external.ChatTurn
import com.aniob.core.skills.AniobSkill
import com.aniob.core.verifier.DeterministicVerifier
import java.security.MessageDigest
import kotlin.random.Random

/** Experimental cells, not aliases for the production speed-ablation modes. */
enum class BenchmarkConfiguration { NO_MEMORY, HISTORY_ONLY, TRAJECTORY_REPLAY, LEARNED_SKILLS, VALIDATED_SKILLS }
enum class BenchmarkCondition { COLD, WARM, REPEATED_INPUT, UI_DRIFT, STALE_EXPERIENCE }
enum class BenchmarkActionStatus { VERIFIED, UNCONFIRMED, FAILED, SKIPPED }
enum class BenchmarkTaskStatus { VERIFIED, NOT_MET, INCONCLUSIVE, INVALID_RESET, TIMED_OUT, DISPATCH_UNRESOLVED, RECOVERY_BLOCKED, EXECUTION_ERROR, INTERRUPTED }
enum class AuditVerdict { MET, NOT_MET, UNKNOWN }
enum class ReuseDecision { NO_MATCH, REPLAYED, REUSED, REJECTED, ADAPTED, ADAPTATION_FAILED, VALIDATION_FAILED }

data class BenchmarkAudit(val verdict: AuditVerdict, val evidenceRef: String? = null) {
    init { require(verdict == AuditVerdict.UNKNOWN || !evidenceRef.isNullOrBlank()) }
}

data class BenchmarkActionAudit(
    val effect: BenchmarkAudit,
    /** Independently labeled; null means not adjudicated. A repeated action need not be unnecessary. */
    val unnecessaryRetry: Boolean? = null
)

/** Provider counts only (or an explicitly identified tokenizer); no character-based estimates. */
data class BenchmarkGeneration(
    val output: String?, val inputTokens: Int? = null, val outputTokens: Int? = null, val usageSource: String? = null
) {
    init {
        require(listOfNotNull(inputTokens, outputTokens).all { it >= 0 })
        require((inputTokens == null && outputTokens == null) || !usageSource.isNullOrBlank())
    }
}

/** Stateless generation: no server-side conversation or hidden cross-trial state. */
fun interface BenchmarkGenerator {
    suspend fun generate(prompt: String, seed: Int): BenchmarkGeneration
    val decoding: BenchmarkDecoding get() = BenchmarkDecoding()

    companion object {
        /** The adapter must apply these effective settings on every call, not merely advertise them. */
        fun configured(settings: BenchmarkDecoding, call: suspend (String, Int?) -> BenchmarkGeneration): BenchmarkGenerator =
            object : BenchmarkGenerator {
                override val decoding = settings
                override suspend fun generate(prompt: String, seed: Int) =
                    call(prompt, seed.takeIf { settings.mode == DecodingMode.SEEDED_SAMPLING })
            }
    }
}

enum class DecodingMode { DETERMINISTIC, SEEDED_SAMPLING, NON_DETERMINISTIC }

data class BenchmarkDecoding(
    val mode: DecodingMode = DecodingMode.NON_DETERMINISTIC,
    val effectiveParameters: Map<String, String> = emptyMap()
) {
    init {
        require(effectiveParameters.none { (key, value) -> key.isBlank() || value.isBlank() })
        require(mode == DecodingMode.NON_DETERMINISTIC || effectiveParameters.isNotEmpty())
    }
}

class BenchmarkDispatchUnresolved(message: String) : IllegalStateException(message)
class BenchmarkRecoveryBlocked(message: String) : IllegalStateException(message)

data class BenchmarkRuntime(
    val deviceId: String,
    val deviceBuild: String,
    val model: String,
    val modelVersion: String,
    val provider: String,
    val providerVersion: String,
    val executionMode: String,
    /** Immutable record of decoding, thermals/power policy, network, task budgets and engine settings. */
    val settingsId: String,
    val sourceRevision: String,
    val simulated: Boolean,
    val decoding: BenchmarkDecoding = BenchmarkDecoding(),
    val generationTimeoutMs: Long = 30_000L
) {
    init {
        require(generationTimeoutMs > 0)
        require(listOf(deviceId, deviceBuild, model, modelVersion, provider, providerVersion, executionMode, settingsId, sourceRevision).none { it.isBlank() })
    }
}

data class BenchmarkObservation(
    val screen: AniobScreenState,
    /** Fingerprint of instrumented world/fixture state, including state not present in the UI tree. */
    val worldFingerprint: String,
    /** Reference to independent fixture state, screenshot or device trace; not sent to the model. */
    val evidenceRef: String
) {
    init { require(worldFingerprint.isNotBlank() && evidenceRef.isNotBlank()) }

    fun stamp(): BenchmarkStart = BenchmarkStart(
        benchmarkDigest(listOf(screen.packageName, screen.activityName,
            com.aniob.core.diff.AniobScreenDiff.computeTreeHash(screen.nodes), screen.hasImeWindow,
            screen.nodes.filter { it.isFocused }.map { it.id }).toString()),
        worldFingerprint
    )
}

data class BenchmarkStart(val screenFingerprint: String, val worldFingerprint: String) {
    init { require(screenFingerprint.isNotBlank() && worldFingerprint.isNotBlank()) }
}

/** All inputs come from a separately verified training run; no measured trial writes back to it. */
data class BenchmarkExperience(
    val artifactId: String,
    val sourceRunId: String,
    val sourceTaskId: String,
    val trainingAudit: BenchmarkAudit,
    val sourceInputId: String,
    val sourceLayoutId: String,
    val history: List<ChatTurn> = emptyList(),
    val trajectoryJson: String? = null,
    val skill: AniobSkill? = null,
    val references: Map<Int, SkillReference> = emptyMap(),
    val intentionallyStale: Boolean = false
) {
    init {
        require(listOf(artifactId, sourceRunId, sourceTaskId, sourceInputId, sourceLayoutId).none { it.isBlank() })
        require(trainingAudit.verdict == AuditVerdict.MET) { "A seed needs independently verified training provenance" }
    }
}

/** Training-derived screen/target evidence, not evaluation oracle labels. */
data class SkillReference(val packageName: String, val className: String, val label: String) {
    init { require(packageName.isNotBlank() && className.isNotBlank() && label.isNotBlank()) }
}

data class BenchmarkCase(
    val task: AniobBenchmarkManifest.BenchmarkTask,
    val scenarioId: String,
    val inputId: String,
    val layoutId: String,
    val difficulty: String,
    val condition: BenchmarkCondition,
    val expectedStart: BenchmarkStart,
    val slots: Map<String, String> = emptyMap(),
    val experience: BenchmarkExperience? = null,
    val retrieval: BenchmarkRetrievalCase? = null
) {
    init {
        require(listOf(scenarioId, inputId, layoutId, difficulty).none { it.isBlank() })
        require(task.maxSteps > 0 && task.timeoutSeconds > 0 && task.observableCriteria.hasObservableCheck)
        require(!task.requiresConfirmation) { "Controlled fixtures must not require auto-approval" }
        require(retrieval == null || (experience == null && condition == BenchmarkCondition.COLD &&
            task.track == AniobBenchmarkManifest.Track.MODEL_GENERATION)) { "Retrieval has its own catalog, not a supplied seed" }
        val seed = experience
        require(seed == null || seed.sourceTaskId == task.taskId) { "Experience belongs to a different task" }
        require(when (condition) {
            BenchmarkCondition.COLD -> seed == null
            BenchmarkCondition.WARM -> seed != null && !seed.intentionallyStale && seed.sourceInputId == inputId && seed.sourceLayoutId == layoutId
            BenchmarkCondition.REPEATED_INPUT -> seed != null && !seed.intentionallyStale && seed.sourceInputId != inputId && seed.sourceLayoutId == layoutId
            BenchmarkCondition.UI_DRIFT -> seed != null && !seed.intentionallyStale && seed.sourceInputId == inputId && seed.sourceLayoutId != layoutId
            BenchmarkCondition.STALE_EXPERIENCE -> seed?.intentionallyStale == true
        }) { "Experience provenance does not support $condition" }
        if (task.track == AniobBenchmarkManifest.Track.DETERMINISTIC_SHORTCUT) {
            require(condition == BenchmarkCondition.COLD && task.observableCriteria.mustShowPackage.size == 1 &&
                "open_app" in task.allowedActionTypes)
        }
    }
}

/**
 * Test-app/device harness. Restore a real fixture/checkpoint here, not just an agent snapshot.
 * Reset is verified by the runner with a separate observation and task audit. Audit methods
 * receive no arm or agent verdict. Use independently instrumented state or blinded annotations.
 */
interface BenchmarkEnvironment {
    val runtime: BenchmarkRuntime
    suspend fun restore(case: BenchmarkCase)
    suspend fun observe(): BenchmarkObservation
    suspend fun dispatch(action: AniobAction): StepPipeline.DispatchOutcome
    suspend fun settle(action: AniobAction, before: BenchmarkObservation): BenchmarkObservation = observe()
    suspend fun auditTask(case: BenchmarkCase, observation: BenchmarkObservation): BenchmarkAudit
    suspend fun auditAction(case: BenchmarkCase, action: AniobAction, before: BenchmarkObservation,
        after: BenchmarkObservation, dispatched: Boolean): BenchmarkActionAudit
}

data class BenchmarkTrial(
    val case: BenchmarkCase, val configuration: BenchmarkConfiguration, val repetition: Int,
    val order: Int, val blockId: String, val runId: String, val generationSeed: Int
)

data class BenchmarkPlan(
    val experimentId: String,
    val sessionId: String,
    val runtime: BenchmarkRuntime,
    val auditProtocolId: String,
    val seed: Int,
    val repetitions: Int,
    val cases: List<BenchmarkCase>,
    val configurations: List<BenchmarkConfiguration> = BenchmarkConfiguration.entries
) {
    init {
        require(experimentId.isNotBlank() && sessionId.isNotBlank() && auditProtocolId.isNotBlank())
        require(repetitions > 0 && cases.isNotEmpty() && configurations.isNotEmpty())
        require(configurations.distinct().size == configurations.size)
        require(cases.none { it.retrieval != null } || (cases.all { it.retrieval != null } &&
            configurations == listOf(BenchmarkConfiguration.VALIDATED_SKILLS))) {
            "Retrieval requires a separate plan using the validated skill execution policy"
        }
        require(cases.map { it.task.taskId to it.scenarioId }.distinct().size == cases.size)
        cases.mapNotNull { it.experience }.forEach { require(it.sourceRunId != sessionId) }
        for (seed in cases.mapNotNull { it.experience }) {
            if (BenchmarkConfiguration.HISTORY_ONLY in configurations) require(seed.history.isNotEmpty())
            if (BenchmarkConfiguration.TRAJECTORY_REPLAY in configurations) require(!seed.trajectoryJson.isNullOrBlank())
            if (configurations.any { it == BenchmarkConfiguration.LEARNED_SKILLS || it == BenchmarkConfiguration.VALIDATED_SKILLS }) {
                require(seed.skill != null && seed.skill.steps.isNotEmpty() && !seed.skill.isDraft &&
                    seed.skill.status == com.aniob.core.skills.SkillStatus.ACTIVE &&
                    seed.skill.name.isNotBlank() && seed.skill.version.isNotBlank())
            }
            if (BenchmarkConfiguration.VALIDATED_SKILLS in configurations) {
                require(seed.skill!!.steps.filter { it.target != null || it.targetNodeId != null }
                    .all { it.stepIndex in seed.references })
            }
        }
    }

    fun schedule(): List<BenchmarkTrial> {
        val random = Random(seed)
        val stableCases = cases.sortedWith(compareBy({ it.task.taskId }, { it.scenarioId }))
        val permutations = stableCases.associateWith { configurations.sortedBy { it.name }.shuffled(random) }
        return buildList {
            for (repetition in 1..repetitions) for (case in stableCases.shuffled(random)) {
                val order = if (case.task.track == AniobBenchmarkManifest.Track.DETERMINISTIC_SHORTCUT) {
                    listOf(BenchmarkConfiguration.NO_MEMORY)
                } else permutations.getValue(case)
                val block = benchmarkDigest(listOf(experimentId, sessionId, case.task.taskId, case.scenarioId, repetition).joinToString("\u0000"))
                val modelSeed = random.nextInt() // schedule allocation; applied only by seeded samplers
                order.indices.forEach { i ->
                    val arm = order[(i + repetition - 1) % order.size]
                    add(BenchmarkTrial(case, arm, repetition, size + 1, block, "$block-${arm.name}", modelSeed))
                }
            }
        }
    }
}

data class BenchmarkReuse(
    val step: Int, val artifactId: String, val skillId: String?, val skillVersion: String?, val decision: ReuseDecision
)

data class BenchmarkActionRow(
    val step: Int,
    val tool: String,
    val status: BenchmarkActionStatus,
    val evidence: DeterministicVerifier.EvidenceCategory,
    val dispatchAttempted: Boolean,
    val dispatched: Boolean,
    val taskControl: Boolean,
    val latencyMs: Double,
    val endedMs: Double,
    val generationMs: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val usageSource: String? = null,
    val audit: BenchmarkActionAudit? = null,
    val repeatedWithoutStateChange: Boolean = false,
    val actionFingerprint: String? = null,
    val source: String,
    val failureKind: String? = null,
    /** Grounding, dispatch and verification wall time, excluding generation. */
    val actionLatencyMs: Double? = null,
    val preState: BenchmarkStart? = null,
    val postState: BenchmarkStart? = null,
    val failureReason: String? = null,
    val appliedGenerationSeed: Int? = null,
    /** False on an attempted dispatch whose physical completion/rejection is still unknown. */
    val dispatchResolved: Boolean = dispatchAttempted
)

data class BenchmarkTrialResult(
    val trial: BenchmarkTrial,
    val status: BenchmarkTaskStatus,
    val observedStart: BenchmarkStart?,
    val resetVerified: Boolean,
    val terminalReason: String,
    val durationMs: Double?,
    val actions: List<BenchmarkActionRow> = emptyList(),
    val reuse: List<BenchmarkReuse> = emptyList(),
    val initialAudit: BenchmarkAudit? = null,
    val finalAudit: BenchmarkAudit? = null,
    val agentReportedSuccess: Boolean = false,
    /** Only artifacts exposed to the policy, independent of whether it invoked a skill. */
    val loadedArtifactId: String? = null,
    val retrieval: BenchmarkRetrievalResult? = null
)

fun benchmarkDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Labels are used for scoring only, never passed to the matcher or execution policy. */
enum class RetrievalLabel { RELEVANT, IRRELEVANT, STALE, AMBIGUOUS }

data class BenchmarkRetrievalCandidate(val artifact: BenchmarkExperience, val label: RetrievalLabel) {
    val skill: AniobSkill = requireNotNull(artifact.skill)
    init { require(skill.steps.isNotEmpty() && skill.name.isNotBlank() && skill.version.isNotBlank()) }
}

data class BenchmarkRetrievalCase(val catalogId: String, val candidates: List<BenchmarkRetrievalCandidate>) {
    init {
        require(catalogId.isNotBlank() && candidates.size >= 2)
        require(candidates.distinctBy { it.artifact.artifactId }.size == candidates.size)
        require(candidates.distinctBy { it.skill.name.lowercase(java.util.Locale.ROOT) }.size == candidates.size)
        require(candidates.none { it.label == RetrievalLabel.AMBIGUOUS } ||
            (candidates.count { it.label == RetrievalLabel.AMBIGUOUS } >= 2 && candidates.none { it.label == RetrievalLabel.RELEVANT }))
    }
}

data class BenchmarkRetrievalResult(val selectedArtifactId: String?, val reason: String)
