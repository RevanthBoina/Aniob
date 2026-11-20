# ANIOb controlled research benchmark

Keep three evaluation tracks separate: **controlled mechanism**, **controlled retrieval**, and **production end-to-end**. The first two repeatedly invoke the shared single-step `StepPipeline` inside an isolated benchmark runner. Neither measures the full production routing or task-continuation system. Interactive `research_runs.csv`, the Tracker and manual overrides are not ground truth for these studies.

## Protocol

1. **Freeze the study before measurement.** Record the source revision, task/fixture versions, device and OS build, model artifact and provider/runtime versions, execution mode, decoding parameters, device power/thermal/network controls, timeout, and step limits. The `settingsId` and `auditProtocolId` identify archived settings and scoring rules. Use a unique `sessionId` each time the experiment is executed. Keep model residency, accessibility capture mode, cooldown, and all non-experience settings fixed. Use task fixtures with predeclared difficulty and observable success criteria; use disposable state, not personal app data.
2. **Train separately and freeze the artifacts.** Archive an independently successful training run and its conversation, ANIOb `RememberCache` JSON transitions, learned `AniobSkill` with a version, and the training target labels/classes for validation. Use a stable content/version ID for the artifact. Do not seed from evaluation runs. No measured trial writes to these artifacts or to production stores. This compares the *use of experience*, not the quality or cost of skill distillation.
3. **Construct the cells.** `BenchmarkCase` records task, scenario, input, layout, difficulty, and a reference start fingerprint obtained from the prepared fixture **before** measuring. `COLD` has no seed; `WARM` matches the seed input and layout; `REPEATED_INPUT` changes the input only; `UI_DRIFT` changes the layout/navigation only; `STALE_EXPERIENCE` uses a deliberately outdated, predeclared seed. Do not infer conditions from round number. The no-memory arm gets no seed even in seeded cells, so it is a matched control on the same fixture. Cold refers to *experience*, not cold model load.
4. **Counterbalance and reset.** `BenchmarkPlan` uses a seeded shuffle of task cases and rotates configuration order within each task/scenario on successive repetitions. Use multiples of five repetitions for a complete rotation of the five arms; choose trial counts and task coverage before the study. The same task/scenario/repetition shares a block ID and a scheduled model seed across arms. A scheduled seed is not necessarily applied. Before **each** trial, restore the app/device fixture, then separately read the actual accessibility screen and instrumented world-state fingerprint. Require an exact match and independent evidence that the task is not already complete. Failed resets are recorded as `INVALID_RESET`, not scored or used in pairs. Report attrition and rerun using a new session ID if necessary; do not silently replace invalid trials. Shortcut-only tasks run in their own track and are never pooled with model capability.
5. **Audit independently.** Use a test app’s instrumented state, test database, or blinded trace annotations for precondition, final task effect, and individual intended action effects. The audit interface is not given the arm or ANIOb verdict. Use `UNKNOWN` for missing/ambiguous evidence; archive the referenced audit artifacts. The decision to label a retry unnecessary also needs independent evidence. A matching UI hash, dispatch callback, model claim, or agent verification is **not** a ground-truth label. Final evidence is collected even if the agent did not report success. A deadline miss remains a timeout regardless of a later effect.

### What the arms actually receive

| Arm | Available during evaluation |
|---|---|
| `NO_MEMORY` | Current goal and fresh observation only in the model prompt. Safety/watchdog state still works. |
| `HISTORY_ONLY` | Same prompt plus bounded within-trial history and, if seeded, frozen conversation turns. No replay or skills. |
| `TRAJECTORY_REPLAY` | Frozen `RememberCache` lookups, one transition at a time. No conversation or skills. |
| `LEARNED_SKILLS` | Frozen skill and current-input slot binding with fresh grounding. No replay or conversation. |
| `VALIDATED_SKILLS` | Same skill plus recorded package/class/label checks. A mismatched selector can be retargeted only to one unambiguous matching control. Reject unresolved or ambiguous matches, and drop reuse when the step does not verify. |

Every arm shares the safety gate, dispatch, verifier, task budget, and model fallback. In the mechanism track the benchmark supplies one case-specific artifact. Reuse of a supplied artifact is not retrieval evidence.

### Effective decoding and failure handling

- Use an adapter that actually configures the backend on every call: `BenchmarkGenerator.configured` passes the allocated per-step seed to a `SEEDED_SAMPLING` adapter, or no seed to a `DETERMINISTIC` adapter (e.g. a verified greedy backend). Pass the effective parameters and require matching generator/runtime declarations before executing. Archive model artifact digest, engine/provider version, parameters such as temperature, top-k/top-p, token limits, prompt template revision, sampler, and any runtime nondeterminism. Before using a backend in a confirmatory study, verify its seed/decoding contract and repeat fixed-input checks on the same build/device. The adapter declaration cannot prove backend determinism or hardware equivalence.
- The native `generate(prompt)` API exposes neither seed control nor a verified deterministic mode; its adapter is always marked `NON_DETERMINISTIC`. It leaves `applied_generation_seed` and token counts empty. For all adapters the experiment seed still reproduces the schedule; model outputs are not claimed reproducible for a non-deterministic run. Keep calls stateless across prompts and trials.
- A per-call generation timeout is bounded by the remaining trial budget. Provider exceptions, generation timeouts, missing/malformed actions, dispatch rejection, and verification failures record distinct failure codes and bounded diagnostic reasons. A failed model call does not become `SKIPPED`. Native engines may mask underlying errors; the adapter reports the actual missing-result condition rather than guessing. Keep provider diagnostics free of private data; exports redact credential-shaped text but are intended for disposable fixtures and are not general PII scrubbers.

## Controlled retrieval protocol

Use a separate `BenchmarkPlan` and export directory. Give every case a `BenchmarkRetrievalCase` with a frozen, ordered catalog, independently train-derived candidate artifacts, and preregistered relevance labels: `RELEVANT`, `IRRELEVANT`, `STALE`, or `AMBIGUOUS`. Include both positive queries with relevant skills among competing decoys and no-correct-answer queries with only irrelevant/stale skills or multiple genuinely indistinguishable skills. Balance/rotate catalog order in the plan if studying tie sensitivity; the matcher receives exactly the archived order. Do not include an authoritative supplied seed. `COLD` is the required no-supplied-seed marker in this track, **not** an empty-catalog arm.

The runner calls `AniobSkillMatcher` on an isolated catalog containing only the designated candidates and uses the matcher's extracted slots. The matcher does not receive relevance/staleness labels, the oracle's target, or production user stores. A selected skill goes through the validated skill path; abstention or pre-dispatch validation failure can fall back to the model. Candidate labels are used only to score the original top-1 decision, not to filter a selected skill or forgive a wrong selection. This isolates the production matcher component; it is not evidence about the production routing ladder or catalog lifecycle.

| Retrieval metric | Definition (observed decisions only unless stated) |
|---|---|
| Precision@1 | Relevant top-1 selections / all top-1 selections. Abstentions are not positives. |
| Recall@1 | Selected relevant candidates / all independently relevant candidates across observed queries. A query with multiple relevant candidates can contribute multiple misses. |
| Correct-skill selection | Queries with a relevant top-1 selection / queries with at least one relevant candidate. |
| Stale or irrelevant rejection accuracy | Non-selected candidates / labeled candidates of that type, reported separately. This is candidate-level non-selection, not proof that the matcher understood why a candidate was wrong. |
| Ambiguous rejection | Abstentions / queries explicitly labeled as ambiguous. |
| Downstream verified success | Independently verified tasks / in-budget adjudicated tasks after retrieval and fallback. Audit and retrieval observation coverage are reported separately. Success after fallback does not make the original selection correct. |

## Separate production end-to-end evaluation (protocol only)

1. On dedicated test devices/profiles, install the release-equivalent build with normal routing and safety settings. Use synthetic accounts/fixtures and train or import frozen experience through the normal supported production flow. Keep the real production stores within the disposable test profile; never use an actual user's conversation, skills, or credentials. Preserve the intended on-device experience while restoring the app/world fixture, and verify both separately before each run.
2. Submit the preregistered prompts through normal chat (`AniobViewModel.submitTask`); do not call `AniobBenchmarkRunner`, inject a selected skill, or bypass approval. Observe the actual production route and stored-experience retrieval. Include matched uninterrupted and interrupted cases (e.g. background/foreground or task parking at a predeclared observable checkpoint). Resume through the supported chat/continuation flow, and record restored task identity, route changes, fresh grounding, verification, recovery, and whether unintended or duplicate effects occurred. If the supported flow cannot resume, retain that outcome; do not simulate success by replaying the script. Use the same fixture snapshot, builds/providers, execution budgets and reset checks across matched runs.
3. Collect production telemetry and sanitized route/resume traces as process evidence, not ground truth. Independently label final effects, action effects, and recovery using instrumented fixture evidence; archive resets and censor unresolved/time-limited cases. Report in a separate `PRODUCTION_END_TO_END` dataset with its own run IDs, paired analysis, coverage and failure rates. Do not merge records, effect estimates or claims with either controlled dataset. The benchmark changes do not implement or claim to have run this device harness.

## Run and export

The scripted fixtures exercise the controlled arms, competing catalogs, generation failure/seed handling, and unresolved Android callback behavior without running a real gesture. Run it and the core tests with:

```sh
./gradlew :core-agent:test :app:testDebugUnitTest :app:lintDebug --console=plain
```

For a physical-device study, use `AccessibilityBenchmarkEnvironment` from an instrumentation harness on an isolated lab device with ANIOb accessibility enabled. Supply the experiment-specific restore operation (for example, reseed your test app via its instrumentation and open its prescribed activity), world probe, and audit functions. Do not use a Home/Back key or the manifest's illustrative screen as proof of reset. There is no shipping UI to start an experiment. With the loaded model and a pre-registered `plan`:

```kotlin
val runner = AniobBenchmarkRunner(environment, generator)
// Before starting, archive the schedule (write a bundle with empty results).
val results = runner.runBenchmark(plan) { row ->
    // Persist synchronously by row.trial.runId in the lab's checkpoint store.
}
BenchmarkCsv.write(newSessionDirectory, plan, results)
```

The schema-v2 bundle starts with `study.csv`, `decoding.csv`, `trials.csv`, `actions.csv`, `reuse.csv`, `cells.csv`, `metrics.csv`, and `paired.csv`. The retrieval track additionally writes `retrieval.csv`, `retrieval_candidates.csv`, and `retrieval_metrics.csv` and does not populate the mechanism summaries. Join actions and reuse decisions by `run_id` and `step`; join arms with `block_id`. Empty metrics are unavailable, not zeros. Planned trials without a saved result appear as `NOT_RUN`; cooperative cancellation checkpoints the active trial as `INTERRUPTED` and propagates cancellation. If the process is killed, rebuild the last partial bundle from the saved schedule and checkpoint rows; the runner cannot checkpoint during process death. Do not treat unrun rows as failures in adjudicated comparisons. Synthetic trials remain marked `simulated` and are not pooled with device results.

**Android input recovery:** A callback timeout is `DISPATCH_UNRESOLVED`, not a platform rejection. The original callback ticket survives cancellation, the trial stops without a duplicate dispatch, and a subsequent reset is gated on a terminal callback or the harness proving the old input queue has been invalidated (e.g. a validated lab-device restart). A mere unchanged screen, Home/Back, or passage of time is not proof. Without recovery proof, the adapter refuses reset and the trial is recorded as `RECOVERY_BLOCKED`; it must not act on the contaminated fixture. Keep exclusive device ownership and the same adapter until the queue is safe. Android cancellations/rejections can still have partial effects; do not infer action effect from the callback. Native inference may be non-interruptible; report observed overruns and do not assume a coroutine deadline can preempt native code.

## Metric definitions

Results are stratified by **track × condition × configuration**, including separate cold/warm rates. Latencies are monotonic wall milliseconds. Task time includes observation, decisions, dispatch and verification through final capture, but excludes reset, cooldown and independent scoring.

| Metric | Numerator/denominator or sample |
|---|---|
| Independently verified task success | `VERIFIED` with independent `MET` / reset-valid, in-budget trials adjudicated as `VERIFIED` + `MET` or `NOT_MET` + `NOT_MET`. The numerator also requires a recorded dispatch attempt. Unknown, interrupted, timed-out, unresolved, and execution-error outcomes are not losses. |
| Coverage and attrition | Final-audit coverage is known final labels / reset-valid trials (including known audits collected after deadlines); adjudication coverage uses eligible in-budget labels instead. Inconclusive, task timeout, generation timeout and unresolved-dispatch rates use reset-valid trials. Invalid-reset rate uses verified resets plus invalid resets; recovery-blocked and interruption rates use recorded rows. Conservative recorded-trial success uses all recorded rows and is not the adjudicated estimate. |
| Action verification / unconfirmed rates | `VERIFIED` or `UNCONFIRMED` / all non-control action rows. `FAILED` and `SKIPPED` are counted separately; `Finish`/`Fail` are task-control rows. |
| False verification / false failure | Independently ineffective `VERIFIED` actions / independently labeled `VERIFIED` dispatch attempts; independently effective `FAILED` actions / independently labeled `FAILED` dispatch attempts. These are error proportions among the reported verdicts; unknown labels and unconfirmed actions are not false positives or false negatives. |
| Duplicate proxy / unnecessary retry | Repeated action fingerprint without observed screen/world-state change / fingerprintable dispatch attempts; independently unnecessary retries / attempts with a retry annotation. The proxy is not a claim of unnecessary behavior. |
| Skill reuse / precision | Trials attempting a skill action / reset-valid skill-arm trials; independently effective skill dispatch attempts / independently labeled skill dispatch attempts. |
| Stale rejection / adaptation | Explicitly rejected stale seed with no skill dispatch / valid deliberately stale skill trials; independently effective adapted actions / scored adaptations (including no viable adaptation and blocked adaptations as unsuccessful; unknown executed effects omitted). Adaptation is action-level, not task completion. |
| Completion and action timing | Completion time for independently successful trials. Action latency covers grounding/dispatch/settlement/verification without model generation, on dispatch attempts. Average actions per successful task counts dispatch attempts, including rejects. |
| Model latency and tokens | Wall latency per model call. Record provider/tokenizer input and output counts with source; report total tokens per call only where both are known. Cache/skill/shortcut actions are not zero-duration model calls. |
| Recovery | From the first `FAILED` or `UNCONFIRMED` step ending to the next independently effective action ending. Overlapping bad steps form one episode. Episodes without such an action are right-censored; latency summaries cover completed episodes only. |

Reports include `n`, task-cluster count, mean, midpoint median, nearest-rank P95, sample SD, and a seeded 2,000-resample task-cluster percentile 95% CI for the mean (rates are binary means). No CI is emitted for fewer than two task clusters. Paired success is treatment minus no-memory within blocks where both arms have independently adjudicated in-budget outcomes, with wins/losses/ties and the same task-cluster bootstrap on the differences. Small task sets, sparse audit coverage, conditional latency samples, and multiple comparisons limit inference. Choose primary comparisons and control multiplicity separately before making confirmatory claims. No device evaluation or effect size is asserted by these tests.
