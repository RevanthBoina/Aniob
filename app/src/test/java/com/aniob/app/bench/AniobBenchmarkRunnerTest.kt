package com.aniob.app.bench

import com.aniob.app.research.CsvCodec
import com.aniob.core.bench.*
import com.aniob.core.bench.AniobBenchmarkManifest.Track
import com.aniob.core.domain.*
import com.aniob.core.execution.AniobActionExecutor
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.external.ChatTurn
import com.aniob.core.memory.RememberAction
import com.aniob.core.memory.RememberCache
import com.aniob.core.skills.AniobSkill
import com.aniob.core.skills.SkillStep
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Explicit fixture world state is the oracle, separate from ANIOb's accessibility observation. */
class AniobBenchmarkRunnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun resetIsVerifiedBeforeEveryTrialAndFailureIsNotAZeroScoredTrial() = runBlocking {
        val fixture = Fixture(skipRestoreAfterFirst = true)
        val case = fixture.case(BenchmarkCondition.COLD)
        val plan = fixture.plan(listOf(case), arms = listOf(BenchmarkConfiguration.NO_MEMORY), repetitions = 3)
        val results = fixture.runner().runBenchmark(plan)
        assertEquals(3, fixture.resetCalls)
        assertEquals(listOf(BenchmarkTaskStatus.VERIFIED, BenchmarkTaskStatus.INVALID_RESET, BenchmarkTaskStatus.INVALID_RESET),
            results.map { it.status })
        assertTrue(results.drop(1).all { it.actions.isEmpty() && !it.resetVerified })
        assertEquals(1, fixture.dispatches)
        val summary = BenchmarkStatistics.summarize(results, 1).single()
        assertEquals(1, summary.valid)
        assertEquals(3, summary.planned)
    }

    @Test fun resetWithMatchingStampButUnprovenInitialTaskStateIsRejected() = runBlocking {
        val fixture = Fixture(initialOracleUnknown = true)
        val results = fixture.runner().runBenchmark(fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)),
            arms = listOf(BenchmarkConfiguration.NO_MEMORY)))
        assertTrue(results.all { it.status == BenchmarkTaskStatus.INVALID_RESET && it.observedStart == it.trial.case.expectedStart })
        assertEquals(0, fixture.dispatches)
    }

    @Test fun matchingUiDoesNotHideWorldStateLeakage() = runBlocking {
        val fixture = Fixture()
        val original = fixture.case(BenchmarkCondition.COLD)
        val mismatched = original.copy(expectedStart = original.expectedStart.copy(worldFingerprint = "stale preferences"))
        val plan = fixture.plan(listOf(mismatched), arms = listOf(BenchmarkConfiguration.NO_MEMORY))
        val row = fixture.runner().runBenchmark(plan).single()
        assertEquals(BenchmarkTaskStatus.INVALID_RESET, row.status)
        assertEquals(original.expectedStart.screenFingerprint, row.observedStart?.screenFingerprint)
        assertEquals(0, fixture.dispatches)
    }

    @Test fun interruptedGenerationRemainsAnUnexecutedTimedRow() = runBlocking {
        val fixture = Fixture()
        val c = fixture.case(BenchmarkCondition.COLD)
        val plan = fixture.plan(listOf(c.copy(task = c.task.copy(timeoutSeconds = 1))),
            arms = listOf(BenchmarkConfiguration.NO_MEMORY))
        val runner = AniobBenchmarkRunner(fixture, BenchmarkGenerator { _, _ -> delay(2_000); BenchmarkGeneration(null) }, 0)
        val row = runner.runBenchmark(plan).single()
        assertEquals(BenchmarkTaskStatus.TIMED_OUT, row.status)
        assertEquals(BenchmarkActionStatus.FAILED, row.actions.single().status)
        assertEquals("GENERATION_TIMEOUT", row.actions.single().failureKind)
        assertNotNull(row.actions.single().generationMs)
        assertEquals(0, fixture.dispatches)
    }

    @Test fun providerAndOutputFailuresProduceRowsWithoutStoppingSchedule() = runBlocking {
        val fixture = Fixture(generationTimeoutMs = 30)
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), 4, listOf(BenchmarkConfiguration.NO_MEMORY))
        var calls = 0
        val saved = mutableListOf<BenchmarkTrialResult>()
        val runner = AniobBenchmarkRunner(fixture, BenchmarkGenerator { _, _ ->
            when (++calls) {
                1 -> throw IllegalStateException("upstream capacity exhausted")
                2 -> { delay(500); BenchmarkGeneration(null) }
                3 -> BenchmarkGeneration(null)
                else -> BenchmarkGeneration("not an action")
            }
        }, 0)
        val rows = runner.runBenchmark(plan, saved::add)
        assertEquals(plan.schedule(), rows.map { it.trial })
        assertEquals(saved, rows)
        val actions = rows.map { it.actions.single() }
        assertEquals(listOf("PROVIDER_ERROR", "GENERATION_TIMEOUT", "INVALID_OUTPUT", "INVALID_OUTPUT"), actions.map { it.failureKind })
        assertTrue(actions.all { it.status == BenchmarkActionStatus.FAILED && !it.dispatchAttempted })
        assertTrue(actions.first().failureReason!!.contains("upstream capacity exhausted"))
        val export = java.io.File(temporaryFolder.root, "provider-failures")
        BenchmarkCsv.write(export, plan, rows)
        assertTrue(java.io.File(export, "actions.csv").readText().contains("IllegalStateException: upstream capacity exhausted"))
    }

    @Test fun rejectionPreservesPlatformReasonAndDoesNotVerify() = runBlocking {
        val fixture = Fixture(rejectDispatch = true)
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), arms = listOf(BenchmarkConfiguration.NO_MEMORY))
        val action = fixture.runner().runBenchmark(plan).single().actions.first()
        assertEquals(BenchmarkActionStatus.FAILED, action.status)
        assertEquals("DISPATCH_REJECTED", action.failureKind)
        assertEquals("fixture dispatch rejected", action.failureReason)
    }

    @Test fun unresolvedDispatchStopsTrialAndBlockedRecoveryRemainsTraceable() = runBlocking {
        val fixture = Fixture()
        val fence = BenchmarkDispatchFence(10, 10)
        var issues = 0
        val environment = object : BenchmarkEnvironment by fixture {
            override suspend fun restore(case: BenchmarkCase) = fence.beforeReset({ false }) { fixture.restore(case) }
            override suspend fun dispatch(action: AniobAction): StepPipeline.DispatchOutcome {
                fence.dispatch { issues++ } // no terminal Android callback
                error("An unresolved dispatch cannot return success")
            }
        }
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), 2, listOf(BenchmarkConfiguration.NO_MEMORY))
        val runner = AniobBenchmarkRunner(environment, BenchmarkGenerator { _, _ ->
            BenchmarkGeneration(AniobActionSchema.toActionJson(AniobAction.Tap(SemanticTarget.SomIndex(1))))
        }, 0)
        val rows = runner.runBenchmark(plan)
        assertEquals(listOf(BenchmarkTaskStatus.DISPATCH_UNRESOLVED, BenchmarkTaskStatus.RECOVERY_BLOCKED), rows.map { it.status })
        val action = rows.first().actions.single()
        assertEquals(BenchmarkActionStatus.UNCONFIRMED, action.status)
        assertFalse(action.dispatchResolved)
        assertNull(action.audit)
        assertEquals(AuditVerdict.UNKNOWN, rows.first().finalAudit!!.verdict)
        assertEquals(1, fixture.resetCalls)
        assertEquals(1, issues)
    }

    @Test fun cancellationCheckpointsActiveTrialAndExportsUnrunTrials() = runBlocking {
        val fixture = Fixture()
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), 3, listOf(BenchmarkConfiguration.NO_MEMORY))
        val saved = mutableListOf<BenchmarkTrialResult>()
        val inModel = CompletableDeferred<Unit>()
        val runner = AniobBenchmarkRunner(fixture, BenchmarkGenerator { _, _ ->
            inModel.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }, 0)
        val job = launch { runner.runBenchmark(plan, saved::add) }
        inModel.await()
        job.cancelAndJoin()
        assertEquals(BenchmarkTaskStatus.INTERRUPTED, saved.single().status)
        assertEquals("INTERRUPTED", saved.single().actions.single().failureKind)
        val dir = java.io.File(temporaryFolder.root, "cancelled")
        BenchmarkCsv.write(dir, plan, saved)
        assertEquals(2, java.io.File(dir, "trials.csv").readLines().count { ",NOT_RUN," in it })
    }

    @Test fun seededAdapterReceivesAndExportsEffectiveSeedsWhileUnconfirmedAdaptersCannotClaimThem() = runBlocking {
        val settings = BenchmarkDecoding(DecodingMode.SEEDED_SAMPLING, mapOf("temperature" to "0.7", "top_k" to "5"))
        val fixture = Fixture(decoding = settings)
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), 2, listOf(BenchmarkConfiguration.NO_MEMORY))
        val seeds = mutableListOf<Int?>()
        val generator = BenchmarkGenerator.configured(settings) { _, seed ->
            seeds += seed
            BenchmarkGeneration(java.util.Random(requireNotNull(seed).toLong()).nextInt().toString())
        }
        val runner = AniobBenchmarkRunner(fixture, generator, 0)
        val first = runner.runBenchmark(plan)
        runner.runBenchmark(plan)
        val expected = plan.schedule().map { it.generationSeed }
        assertEquals(expected + expected, seeds)
        assertEquals(expected, first.map { it.actions.single().appliedGenerationSeed })
        val dir = java.io.File(temporaryFolder.root, "seeded")
        BenchmarkCsv.write(dir, plan, first)
        assertTrue(java.io.File(dir, "study.csv").readText().contains(",SEEDED_SAMPLING,true,false,"))
        assertTrue(java.io.File(dir, "decoding.csv").readText().contains("temperature,0.7"))
        try {
            fixture.runner().runBenchmark(plan)
            fail("An unspecified generator must not be accepted as seeded")
        } catch (_: IllegalArgumentException) { }
        val deterministic = BenchmarkGenerator.configured(BenchmarkDecoding(DecodingMode.DETERMINISTIC, mapOf("sampler" to "greedy"))) { _, seed ->
            assertNull(seed)
            BenchmarkGeneration("fixed")
        }
        assertEquals(deterministic.generate("prompt", 1), deterministic.generate("prompt", 99))
    }

    @Test fun nativeAdapterDoesNotAdvertiseSeedSupportAndPreservesNativeFailure() = runBlocking {
        val engine = object : com.aniob.core.providers.AniobNativeLlmEngine {
            override fun isReady() = true
            override fun load(path: String) = true
            override fun generate(prompt: String): String? = null
            override fun release() = Unit
        }
        val generator = AniobBenchmarkRunner.nativeGenerator(engine)
        assertEquals(DecodingMode.NON_DETERMINISTIC, generator.decoding.mode)
        val fixture = Fixture()
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), arms = listOf(BenchmarkConfiguration.NO_MEMORY))
        val action = AniobBenchmarkRunner(fixture, generator, 0).runBenchmark(plan).single().actions.single()
        assertNull(action.appliedGenerationSeed)
        assertEquals("PROVIDER_ERROR", action.failureKind)
        assertTrue(action.failureReason!!.contains("Native generation returned no result"))
    }

    @Test fun retrievalScoresCompetingCatalogsWithoutUsingFixtureLabelsForSelection() = runBlocking {
        val fixture = Fixture()
        val cold = fixture.case(BenchmarkCondition.COLD)
        val warm = fixture.case(BenchmarkCondition.WARM).experience!!
        val stale = fixture.case(BenchmarkCondition.STALE_EXPERIENCE).experience!!
        fun candidate(name: String, label: RetrievalLabel, trigger: String, artifact: BenchmarkExperience = warm) =
            BenchmarkRetrievalCandidate(artifact.copy(artifactId = name,
                skill = artifact.skill!!.copy(name = name, triggerKeywords = listOf(trigger))), label)
        fun query(id: String, vararg candidates: BenchmarkRetrievalCandidate) = cold.copy(scenarioId = id,
            retrieval = BenchmarkRetrievalCase(id, candidates.toList()))
        val decoy = candidate("decoy", RetrievalLabel.IRRELEVANT, "change airplane mode")
        val positive = query("positive", candidate("correct", RetrievalLabel.RELEVANT, "result panel"), decoy,
            candidate("old", RetrievalLabel.STALE, "retired panel", stale))
        val wrong = query("wrong", candidate("stale-wins", RetrievalLabel.STALE, "find the result panel", stale),
            candidate("relevant-loses", RetrievalLabel.RELEVANT, "result panel"))
        val ambiguous = query("ambiguous", candidate("tie-a", RetrievalLabel.AMBIGUOUS, "result panel"),
            candidate("tie-b", RetrievalLabel.AMBIGUOUS, "result panel"), decoy)
        val plan = fixture.plan(listOf(positive, wrong, ambiguous), arms = listOf(BenchmarkConfiguration.VALIDATED_SKILLS))
        val rows = fixture.runner().runBenchmark(plan)
        val actual = rows.associate { it.trial.case.scenarioId to it.retrieval!!.selectedArtifactId }
        assertEquals(mapOf("positive" to "correct", "wrong" to "stale-wins", "ambiguous" to null), actual)
        val metrics = BenchmarkStatistics.retrievalMetrics(rows, 2)
        for (name in listOf("retrieval_precision_at_1", "retrieval_recall_at_1", "stale_rejection_accuracy", "correct_skill_selection_rate"))
            assertEquals(0.5, metrics.getValue(name).mean!!, 0.0)
        for (name in listOf("irrelevant_rejection_accuracy", "ambiguous_retrieval_rejection_rate", "downstream_independently_verified_success_rate"))
            assertEquals(1.0, metrics.getValue(name).mean!!, 0.0)
        assertTrue(BenchmarkStatistics.summarize(rows, 2).isEmpty())
        val dir = java.io.File(temporaryFolder.root, "retrieval")
        BenchmarkCsv.write(dir, plan, rows)
        assertTrue(java.io.File(dir, "retrieval.csv").readText().contains("stale-wins"))
        assertTrue(java.io.File(dir, "study.csv").readText().contains("CONTROLLED_RETRIEVAL"))
    }

    @Test fun allArmsRunWithFrozenExperienceAndWithoutCarryover() = runBlocking {
        val fixture = Fixture()
        val plan = fixture.plan(BenchmarkCondition.entries.map(fixture::case), repetitions = 2)
        val results = fixture.runner().runBenchmark(plan)
        assertEquals(50, results.size)
        assertEquals(50, fixture.resetCalls)
        for (r in results) {
            assertTrue(r.resetVerified)
            assertEquals(r.trial.case.expectedStart, r.observedStart)
            if (r.trial.case.condition == BenchmarkCondition.COLD || r.trial.configuration == BenchmarkConfiguration.NO_MEMORY) {
                assertNull(r.loadedArtifactId)
                assertTrue(r.reuse.isEmpty())
            } else assertEquals(r.trial.case.experience!!.artifactId, r.loadedArtifactId)
        }
        fun arm(condition: BenchmarkCondition, configuration: BenchmarkConfiguration) = results.filter {
            it.trial.case.condition == condition && it.trial.configuration == configuration
        }
        assertTrue(arm(BenchmarkCondition.WARM, BenchmarkConfiguration.TRAJECTORY_REPLAY).all {
            it.actions.any { a -> a.source == "REPLAY" && a.dispatched }
        })
        assertTrue(arm(BenchmarkCondition.REPEATED_INPUT, BenchmarkConfiguration.LEARNED_SKILLS).all {
            it.actions.any { a -> a.source == "SKILL" && a.dispatched } && it.status == BenchmarkTaskStatus.VERIFIED
        })
        val adapted = arm(BenchmarkCondition.UI_DRIFT, BenchmarkConfiguration.VALIDATED_SKILLS)
        assertTrue(adapted.all { r -> r.reuse.any { it.decision == ReuseDecision.ADAPTED } && r.status == BenchmarkTaskStatus.VERIFIED })
        assertTrue(arm(BenchmarkCondition.UI_DRIFT, BenchmarkConfiguration.LEARNED_SKILLS).all { r ->
            r.reuse.none { it.decision == ReuseDecision.ADAPTED }
        })
        val rejected = arm(BenchmarkCondition.STALE_EXPERIENCE, BenchmarkConfiguration.VALIDATED_SKILLS)
        assertTrue(rejected.all { r -> r.reuse.any { it.decision == ReuseDecision.REJECTED } &&
            r.actions.none { it.source == "SKILL" && it.dispatchAttempted } })
        val stats = BenchmarkStatistics.summarize(results, plan.seed)
        val drift = stats.single { it.cell.condition == BenchmarkCondition.UI_DRIFT &&
            it.cell.configuration == BenchmarkConfiguration.VALIDATED_SKILLS }
        assertEquals(2, drift.metrics.getValue("skill_adaptation_success_rate").n)
        assertEquals(1.0, drift.metrics.getValue("skill_adaptation_success_rate").mean!!, 0.0)
        val stale = stats.single { it.cell.condition == BenchmarkCondition.STALE_EXPERIENCE &&
            it.cell.configuration == BenchmarkConfiguration.VALIDATED_SKILLS }
        assertEquals(1.0, stale.metrics.getValue("stale_skill_rejection_rate").mean!!, 0.0)
        assertTrue(fixture.prompts.any { "Training conversation marker" in it })
        assertTrue(fixture.prompts.filterNot { "Conversation history" in it }.none { "Training conversation marker" in it })
    }

    @Test fun independentlyAuditedEffectCanDisagreeWithAgentVerification() = runBlocking {
        val fixture = Fixture()
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.STALE_EXPERIENCE)),
            arms = listOf(BenchmarkConfiguration.LEARNED_SKILLS))
        val trial = fixture.runner().runBenchmark(plan).single()
        val first = trial.actions.first()
        assertEquals(BenchmarkActionStatus.VERIFIED, first.status) // It navigated, but to the wrong destination.
        assertEquals(AuditVerdict.NOT_MET, first.audit?.effect?.verdict)
        assertEquals(1.0, BenchmarkStatistics.summarize(listOf(trial), 1).single().metrics.getValue("false_verification_rate").mean!!, 0.0)
    }

    @Test fun dispatcherSuppliedSnapshotCannotManufactureSuccess() = runBlocking {
        val fixture = Fixture(fakeDispatchSnapshot = true)
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), arms = listOf(BenchmarkConfiguration.NO_MEMORY))
        val trial = fixture.runner().runBenchmark(plan).single()
        assertEquals(BenchmarkActionStatus.UNCONFIRMED, trial.actions.first().status)
        assertEquals(BenchmarkTaskStatus.NOT_MET, trial.status)
    }

    @Test fun acceptedDispatchWithWrongForegroundRecordsVerificationFailure() = runBlocking {
        val fixture = Fixture(fakeDispatchSnapshot = true)
        val cold = fixture.case(BenchmarkCondition.COLD)
        val shortcut = cold.copy(task = cold.task.copy(track = Track.DETERMINISTIC_SHORTCUT,
            allowedActionTypes = setOf("open_app", "finish"), observableCriteria = SuccessCriteria(mustShowPackage = listOf("fixture.done"))))
        val row = fixture.runner().runBenchmark(fixture.plan(listOf(shortcut))).single().actions.single()
        assertTrue(row.dispatched)
        assertEquals(BenchmarkActionStatus.FAILED, row.status)
        assertEquals("VERIFICATION_FAILED", row.failureKind)
        assertTrue(row.failureReason!!.contains("fixture.done"))
    }

    @Test fun shortcutTrackNeverInvokesGenerationOrRunsFiveCopies() = runBlocking {
        val fixture = Fixture()
        val cold = fixture.case(BenchmarkCondition.COLD)
        val shortcut = cold.copy(task = cold.task.copy(track = Track.DETERMINISTIC_SHORTCUT,
            allowedActionTypes = setOf("open_app", "finish"), observableCriteria = SuccessCriteria(mustShowPackage = listOf("fixture.done"))))
        val rows = fixture.runner().runBenchmark(fixture.plan(listOf(shortcut)))
        assertEquals(1, rows.size)
        assertTrue(fixture.prompts.isEmpty())
        assertEquals("SHORTCUT", rows.single().actions.single().source)
    }

    @Test fun statusClassificationDoesNotPromoteUnconfirmedOrSkipped() {
        val action = AniobAction.Tap(SemanticTarget.ResourceId("fixture:go"))
        fun status(verdict: DeterministicVerifier.Status, skip: Boolean = false): BenchmarkActionStatus {
            val result = StepResult(action, com.aniob.core.execution.DispatchCommand.NoOp("test"),
                verification = DeterministicVerifier.VerificationResult(verdict == DeterministicVerifier.Status.VERIFIED, 0, "test", status = verdict),
                nextCtx = TaskContext("test"), terminalState = StepResult.TerminalState.CONTINUE, verificationSkippedFast = skip)
            return AniobBenchmarkRunner.classifyAction(result, attempted = true, control = false, allowed = true)
        }
        assertEquals(BenchmarkActionStatus.VERIFIED, status(DeterministicVerifier.Status.VERIFIED))
        assertEquals(BenchmarkActionStatus.SKIPPED, status(DeterministicVerifier.Status.VERIFIED, skip = true))
        assertEquals(BenchmarkActionStatus.FAILED, status(DeterministicVerifier.Status.FAILED))
        assertEquals(BenchmarkActionStatus.UNCONFIRMED, status(DeterministicVerifier.Status.UNCONFIRMED))
    }

    @Test fun csvJoinsAuditsAndStatusesAndIncludesUnrunScheduleEntries() = runBlocking {
        val fixture = Fixture()
        val plan = fixture.plan(listOf(fixture.case(BenchmarkCondition.COLD)), repetitions = 2)
        val results = fixture.runner().runBenchmark(plan).take(1)
        val dir = temporaryFolder.root.resolve("evaluation")
        BenchmarkCsv.write(dir, plan, results)
        fun table(name: String): List<Map<String, String>> {
            val lines = dir.resolve("$name.csv").readLines().map(CsvCodec::split)
            return lines.drop(1).map { lines.first().zip(it).toMap() }
        }
        assertEquals("fixture, sim", table("study").single()["model"])
        val trials = table("trials")
        assertEquals(10, trials.size)
        assertEquals(9, trials.count { it["task_status"] == "NOT_RUN" })
        val actions = table("actions")
        assertEquals("VERIFIED", actions.first()["verification_status"])
        assertEquals("MET", actions.first()["independent_effect"])
        assertEquals("SCREEN_TRANSITION", actions.first()["evidence_category"])
        assertEquals(trials.first()["run_id"], actions.first()["run_id"])
        assertEquals("7", actions.first()["input_tokens"])
        assertEquals("SKIPPED", actions.last()["verification_status"])
    }

    private class Fixture(private val skipRestoreAfterFirst: Boolean = false, private val initialOracleUnknown: Boolean = false,
        private val fakeDispatchSnapshot: Boolean = false, private val rejectDispatch: Boolean = false,
        decoding: BenchmarkDecoding = BenchmarkDecoding(), generationTimeoutMs: Long = 30_000L) : BenchmarkEnvironment {
        override val runtime = BenchmarkRuntime("fixture", "1", "fixture, sim", "1", "SCRIPTED", "1", "benchmark-test",
            "fixture-settings@1", "test-source", simulated = true, decoding = decoding, generationTimeoutMs = generationTimeoutMs)
        var resetCalls = 0
        var dispatches = 0
        val prompts = mutableListOf<String>()
        private lateinit var current: BenchmarkCase
        private var phase = "start"
        fun runner() = AniobBenchmarkRunner(this, BenchmarkGenerator { prompt, _ ->
            prompts += prompt
            val output = if ("Loaded result" in prompt || "Wrong result" in prompt) {
                AniobActionSchema.toActionJson(AniobAction.Finish("done"))
            } else AniobActionSchema.toActionJson(AniobAction.Tap(SemanticTarget.SomIndex(1)))
            BenchmarkGeneration(output, 7, 4, "scripted fixture tokens")
        }, cooldownMs = 0)

        fun plan(cases: List<BenchmarkCase>, repetitions: Int = 1,
            arms: List<BenchmarkConfiguration> = BenchmarkConfiguration.entries) =
            BenchmarkPlan("fixture-study", "session-1", runtime, "fixture-oracle-v1", 41, repetitions, cases, arms)

        fun case(condition: BenchmarkCondition): BenchmarkCase {
            val input = if (condition == BenchmarkCondition.REPEATED_INPUT) "B" else "A"
            val layout = if (condition == BenchmarkCondition.UI_DRIFT) "new" else "old"
            val stale = condition == BenchmarkCondition.STALE_EXPERIENCE
            val target = if (stale) "other" else "go"
            val initial = observation(layout, input, "start")
            val task = AniobBenchmarkManifest.BenchmarkTask("fixture_open", Track.MODEL_GENERATION, "Find the result panel for $input",
                initial.screen, setOf("tap", "finish"), observableCriteria = SuccessCriteria(mustContainText = listOf("Loaded result $input")))
            val seed = if (condition == BenchmarkCondition.COLD) null else {
                val cache = RememberCache(clock = { 0 })
                val original = observation("old", "A", "start").screen
                val trainingGoal = "Find the result panel for A"
                check(cache.record(trainingGoal, original.packageName, RememberCache.navigationScreenKey(trainingGoal, original),
                    listOf(RememberAction("tap", viewId = "fixture:$target"))))
                BenchmarkExperience("artifact-$target", "training-$target", task.taskId,
                    BenchmarkAudit(AuditVerdict.MET, "training-fixture-proof:$target"), "A", "old",
                    history = listOf(ChatTurn(ChatTurn.USER, "Training conversation marker")), trajectoryJson = cache.toJson(),
                    skill = AniobSkill("result", "1", "Open fixture result", targetPackage = "fixture",
                        steps = listOf(SkillStep(1, "tap", target = SemanticTarget.ResourceId("fixture:$target")))),
                    references = mapOf(1 to SkillReference("fixture", "Button", if (stale) "Retired menu" else "Open result")),
                    intentionallyStale = stale)
            }
            return BenchmarkCase(task, condition.name, input, layout, "one navigation", condition, initial.stamp(), experience = seed)
        }

        override suspend fun restore(case: BenchmarkCase) {
            resetCalls++
            current = case
            if (resetCalls == 1 || !skipRestoreAfterFirst) phase = "start"
        }
        override suspend fun observe() = observation(current.layoutId, current.inputId, phase)
        override suspend fun dispatch(action: AniobAction): StepPipeline.DispatchOutcome {
            dispatches++
            if (rejectDispatch) return StepPipeline.DispatchOutcome(false, reason = "fixture dispatch rejected")
            if (fakeDispatchSnapshot) return StepPipeline.DispatchOutcome(true, screenAfter = observation(current.layoutId, current.inputId, "good").screen)
            val target = AniobActionExecutor.resolveTarget(action, observe().screen)?.node
            phase = when {
                action is AniobAction.OpenApp -> "shortcut"
                target?.text == "Open result" -> "good"
                else -> "wrong"
            }
            return StepPipeline.DispatchOutcome(true)
        }
        override suspend fun auditTask(case: BenchmarkCase, observation: BenchmarkObservation): BenchmarkAudit {
            val state = observation.worldFingerprint.substringAfterLast(':')
            if (initialOracleUnknown && state == "start") return BenchmarkAudit(AuditVerdict.UNKNOWN)
            return BenchmarkAudit(if (state == "good" || state == "shortcut") AuditVerdict.MET else AuditVerdict.NOT_MET, observation.evidenceRef)
        }
        override suspend fun auditAction(case: BenchmarkCase, action: AniobAction, before: BenchmarkObservation,
            after: BenchmarkObservation, dispatched: Boolean) = BenchmarkActionAudit(auditTask(case, after), unnecessaryRetry = false)

        private fun observation(layout: String, input: String, phase: String): BenchmarkObservation {
            val nodes = if (phase == "start") listOf(button(1, if (layout == "old") "go" else "next", "Open result"),
                button(2, "other", "Help")) else listOf(button(1, "result", if (phase == "good") "Loaded result $input" else "Wrong result"))
            val screen = AniobScreenState(if (phase == "shortcut") "fixture.done" else "fixture",
                nodes = nodes, treeHash = "$layout:$phase")
            return BenchmarkObservation(screen, "$layout:$input:$phase", "fixture-log:$layout:$input:$phase")
        }
        private fun button(id: Int, resource: String, label: String) = AniobNode(id, "Button", text = label,
            viewId = "fixture:$resource", bounds = AniobRect(0, id * 100, 200, id * 100 + 80), isClickable = true)
    }
}
