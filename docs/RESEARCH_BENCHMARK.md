# ANIOb controlled research benchmark

This benchmark evaluates a **single-step, shared `StepPipeline`** under controlled experience policies. It is not the interactive `research_runs.csv` telemetry, the Tracker, a test of the production routing ladder as a whole, or a published performance result. Interactive overrides do not enter this dataset.

## Protocol

1. **Freeze the study before measurement.** Record the source revision, task/fixture versions, device and OS build, model artifact and provider/runtime versions, execution mode, decoding parameters, device power/thermal/network controls, timeout, and step limits. The `settingsId` and `auditProtocolId` identify archived settings and scoring rules. Use a unique `sessionId` each time the experiment is executed. Keep model residency, accessibility capture mode, cooldown, and all non-experience settings fixed. Use task fixtures with predeclared difficulty and observable success criteria; use disposable state, not personal app data.
2. **Train separately and freeze the artifacts.** Archive an independently successful training run and its conversation, ANIOb `RememberCache` JSON transitions, learned `AniobSkill` with a version, and the training target labels/classes for validation. Use a stable content/version ID for the artifact. Do not seed from evaluation runs. No measured trial writes to these artifacts or to production stores. This compares the *use of experience*, not the quality or cost of skill distillation.
3. **Construct the cells.** `BenchmarkCase` records task, scenario, input, layout, difficulty, and a reference start fingerprint obtained from the prepared fixture **before** measuring. `COLD` has no seed; `WARM` matches the seed input and layout; `REPEATED_INPUT` changes the input only; `UI_DRIFT` changes the layout/navigation only; `STALE_EXPERIENCE` uses a deliberately outdated, predeclared seed. Do not infer conditions from round number. The no-memory arm gets no seed even in seeded cells, so it is a matched control on the same fixture. Cold refers to *experience*, not cold model load.
4. **Counterbalance and reset.** `BenchmarkPlan` uses a seeded shuffle of task cases and rotates configuration order within each task/scenario on successive repetitions. Use multiples of five repetitions for a complete rotation of the five arms; choose trial counts and task coverage before the study. The same task/scenario/repetition shares a block ID and model seed across arms. Before **each** trial, restore the app/device fixture, then separately read the actual accessibility screen and instrumented world-state fingerprint. Require an exact match and independent evidence that the task is not already complete. Failed resets are recorded as `INVALID_RESET`, not scored or used in pairs. Report attrition and rerun using a new session ID if necessary; do not silently replace invalid trials. Shortcut-only tasks run in their own track and are never pooled with model capability.
5. **Audit independently.** Use a test app’s instrumented state, test database, or blinded trace annotations for precondition, final task effect, and individual intended action effects. The audit interface is not given the arm or ANIOb verdict. Use `UNKNOWN` for missing/ambiguous evidence; archive the referenced audit artifacts. The decision to label a retry unnecessary also needs independent evidence. A matching UI hash, dispatch callback, model claim, or agent verification is **not** a ground-truth label. Final evidence is collected even if the agent did not report success. A deadline miss remains a timeout regardless of a later effect.

### What the arms actually receive

| Arm | Available during evaluation |
|---|---|
| `NO_MEMORY` | Current goal and fresh observation only in the model prompt. Safety/watchdog state still works. |
| `HISTORY_ONLY` | Same prompt plus bounded within-trial history and, if seeded, frozen conversation turns. No replay or skills. |
| `TRAJECTORY_REPLAY` | Frozen `RememberCache` lookups, one transition at a time. No conversation or skills. |
| `LEARNED_SKILLS` | Frozen skill and current-input slot binding with fresh grounding. No replay or conversation. |
| `VALIDATED_SKILLS` | Same skill plus recorded package/class/label checks. A mismatched selector can be retargeted only to one unambiguous matching control. Reject unresolved or ambiguous matches, and drop reuse when the step does not verify. |

Every arm shares the safety gate, dispatch, verifier, task budget, and model fallback. The benchmark supplies one case-specific artifact rather than evaluating production library retrieval/ranking. Model generation must be stateless across calls. The native helper records token counts as missing and does not enforce sampling seeds: use a seeded generator or deterministic decoding and describe this in the archived settings.

## Run and export

The scripted, independently instrumented fixture in `AniobBenchmarkRunnerTest` exercises all five arms and all five conditions. Run it and the core tests with:

```sh
./gradlew :core-agent:test :app:testDebugUnitTest --console=plain
```

For a physical-device study, use `AccessibilityBenchmarkEnvironment` from an instrumentation harness on an isolated lab device with ANIOb accessibility enabled. Supply the experiment-specific restore operation (for example, reseed your test app via its instrumentation and open its prescribed activity), world probe, and audit functions. Do not use a Home/Back key or the manifest's illustrative screen as proof of reset. There is no shipping UI to start an experiment. With the loaded model and a pre-registered `plan`:

```kotlin
val runner = AniobBenchmarkRunner(environment, generator)
val results = runner.runBenchmark(plan) // optional per-trial callback for durable checkpoints
BenchmarkCsv.write(newSessionDirectory, plan, results)
```

The versioned CSV bundle includes `study.csv`, `trials.csv` (including unrun schedule entries), `actions.csv`, `reuse.csv`, `cells.csv`, `metrics.csv`, and `paired.csv`. Join actions and reuse decisions by `run_id` and `step`; join arms with `block_id`. Empty metrics are unavailable, not zeros. Raw payload text/screenshots are not exported; archive sanitized fixtures, artifacts, and audit traces separately. Synthetic runs must remain marked `simulated` and must not be pooled with hardware. The live binding uses Android accessibility; the harness must still supply and validate its own fixture operations. Cancellation of a blocking native inference or an already queued Android gesture may not be instantaneous; isolate and verify the next reset, and report deadline overshoots.

## Metric definitions

Results are stratified by **track × condition × configuration**, including separate cold/warm rates. Latencies are monotonic wall milliseconds. Task time includes observation, decisions, dispatch and verification through final capture, but excludes reset, cooldown and independent scoring.

| Metric | Numerator/denominator or sample |
|---|---|
| Independently verified task success | In-deadline, independently met tasks with a recorded dispatch attempt / reset-valid trials. Timeouts and inconclusive outcomes are not successes; also report audit coverage and invalid resets. |
| Action verification / unconfirmed rates | `VERIFIED` or `UNCONFIRMED` / all non-control action rows. `FAILED` and `SKIPPED` are counted separately; `Finish`/`Fail` are task-control rows. |
| False verification / false failure | Independently ineffective `VERIFIED` actions / independently labeled `VERIFIED` dispatch attempts; independently effective `FAILED` actions / independently labeled `FAILED` dispatch attempts. These are error proportions among the reported verdicts; unknown labels and unconfirmed actions are not false positives or false negatives. |
| Duplicate proxy / unnecessary retry | Repeated action fingerprint without observed screen/world-state change / fingerprintable dispatch attempts; independently unnecessary retries / attempts with a retry annotation. The proxy is not a claim of unnecessary behavior. |
| Skill reuse / precision | Trials attempting a skill action / reset-valid skill-arm trials; independently effective skill dispatch attempts / independently labeled skill dispatch attempts. |
| Stale rejection / adaptation | Explicitly rejected stale seed with no skill dispatch / valid deliberately stale skill trials; independently effective adapted actions / scored adaptations (including no viable adaptation and blocked adaptations as unsuccessful; unknown executed effects omitted). Adaptation is action-level, not task completion. |
| Completion and action timing | Completion time for independently successful trials. Action latency covers grounding/dispatch/settlement/verification without model generation, on dispatch attempts. Average actions per successful task counts dispatch attempts, including rejects. |
| Model latency and tokens | Wall latency per model call. Record provider/tokenizer input and output counts with source; report total tokens per call only where both are known. Cache/skill/shortcut actions are not zero-duration model calls. |
| Recovery | From the first `FAILED` or `UNCONFIRMED` step ending to the next independently effective action ending. Overlapping bad steps form one episode. Episodes without such an action are right-censored; latency summaries cover completed episodes only. |

Reports include `n`, task-cluster count, mean, midpoint median, nearest-rank P95, sample SD, and a seeded 2,000-resample task-cluster percentile 95% CI for the mean (rates are binary means). No CI is emitted for fewer than two task clusters. Paired success is treatment minus no-memory within complete reset-valid blocks, with wins/losses/ties and the same task-cluster bootstrap on the differences. Small task sets, sparse audit coverage, conditional latency samples, and multiple comparisons limit inference. Choose primary comparisons and control multiplicity separately before making confirmatory claims. No device evaluation or effect size is asserted by these tests.
