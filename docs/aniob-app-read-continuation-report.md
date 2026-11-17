# Aniob — in-app reading, same-task continuation, honest recovery

Engineering report for the change set on branch `arena/1a71ffaa-aniob` (base `1e0baea`).
Every claim below is backed by a file/line or by the test run recorded in §5.

---

## 1. Findings before implementation

| # | Observed behaviour (defect) | Where it lives (base commit) | Root cause | Fix |
|---|---|---|---|---|
| F1 | "What are my two newest emails?" was answered as a knowledge question; "Check my two newest emails" reached the device but nothing was read | `core/.../intent/AniobTaskIntentClassifier.kt` 88–123 (device verbs), 160–184 (knowledge keywords), 186–211 (order) | classification tests question-form **after** knowledge keywords, and had no notion of "read data inside an app" | new branch "2.5 App-data retrieval" that runs before `knowledgePrefixes`/`hasQuestionWord` (`AniobAppDataRead.detect`) |
| F2 | Opening Gmail was treated as doing the task | `core/.../ladder/AniobIntentResolver.kt` 155–181, `AniobWebLaunchPlanner.kt` 156–200 | every app/page shortcut is `completesOnDispatch` unless it says otherwise; no read-specific rung | `appDataRead` rung: always `requiresObservation = true`, never completes on dispatch; destination comes from the real registry/role/web page |
| F3 | "Which app / open the app first / which account" interruptions | `app/.../ui/AniobViewModelGrips`… `AniobViewModelGates.kt` 223–279, feasibility gates | a question tore the run down and restarted it; nothing decided *when* asking is justified | `AniobAppReadAccountPolicy` (ask only for ≥2 signed-in accounts, once per task) + `AniobAccountChooserScreen` (a mailbox is never a chooser) |
| F4 | No evidence that anything was read; a `Finish` closed the run anyway | `app/.../AniobViewModelPipelineClose.kt` (final gate), `core/.../execution/StepPipeline.kt` 341–404 | success criteria can only assert *text*; a read request names no text, so nothing could disprove a claim | `AppReadEvidence` fed by every observation + `AppReadCompletionGate` on the closing path |
| F5 | A prompt typed while a task ran was dropped | `app/.../ui/AniobViewModelSubmit.kt` 36–40 | `if (_uiState.value.isRunning) { log; return }` | `TaskInputChannel` + `deliverTaskInput` + `awaitModelRequest` (interrupt the model call, not the action) |
| F6 | A clarification answer restarted the whole pipeline from step 0 | `AniobViewModelGates.kt` 223–279 (`onGrillAnswersSubmitted`) | the run was discarded when it asked | the run **parks** (`parkTask`) and is **resumed** (`prepareResume`/`resumeParkedTask`) from the same step |
| F7 | A persisted "running" marker read like a checkpoint | `db/ActiveTaskEntity.kt` 6–14, `ConversationJournal.kt` 30–64, `AniobViewModelConversations.kt` 221–247, 269+ | the marker stored one free-text line and the card did not separate "verified" from "unknown" | marker records `verified:` / `may have happened:` / `waiting for you:`; `AniobInterruptedTask` explains it; continuation is an explicit user action |
| F8 | No audit of the actual call path | — | the work was planned from reading the code, not from prompts | this report's §3 flow, and the tests in §4 |

---

## 2. Changed files, exact ranges, and why

### core-agent (pure Kotlin/JVM — no `android.*`, preflight guard 3 stays green)

| File | Lines | Change |
|---|---|---|
| `core/.../intent/AniobAppDataRead.kt` **(new, 239)** | 1–239 | `AppDataKind` (EMAIL/MESSAGES), `AppDataReadRequest`, `AniobAppDataRead.detect/isReadRequest/requestedCount`. A read request is recognised by (a) an app-data noun, (b) a possessive/slice/count/named-app clause, (c) a read form; explanation cues (`what is`, `how does`, `explain`, `template`, `etiquette`) and leading writing verbs disqualify. Counts: digit 1–20, two–ten, "a few/couple/several" → 3. |
| `core/.../intent/AniobTaskIntentClassifier.kt` | 205–215 (new "2.5" branch), 298–304 (`mustShowPackage` from `appHint`) | The read branch runs before `knowledgePrefixes`/`hasQuestionWord`, so a question-form read can no longer fall into `KNOWLEDGE_QA`. No existing verb was duplicated. |
| `core/.../ladder/AniobAppDataReadPlanner.kt` **(new, 201)** | 1–201 | Destination precedence: named installed app → its known web page → device-role app → kind default page → `Unresolved` (honest reason). Empty registry + named app ⇒ `Unresolved` (never guesses a URL). Duplicates ⇒ `Ambiguous` with candidates. |
| `core/.../ladder/AniobIntentResolver.kt` | 78–81 (KDoc), 182 (rung order), 342–383 (`appDataRead`) | New rung between `installViaStore` and `appViaRegistry`; app launch = `ACTION_MAIN`+`LAUNCHER`+activity+package, web = `ACTION_VIEW`+uri, both `requiresObservation = true`. |
| `core/.../ladder/AniobAppReadAccountPolicy.kt` **(new, 122)** | 1–122 | `AccountCandidate`, `AccountHandover{Proceed,AskOnce,NeedsSignIn}`, `candidatesFrom`, `decide`, `resolveAnswer`. One signed-in account ⇒ no question; ≥2 ⇒ ask exactly once; asked already ⇒ continue with the topmost; none ⇒ sign-in handover that keeps the task. |
| `core/.../ladder/AniobAccountChooserScreen.kt` **(new, 65)** | 1–65 | Recognises an account chooser from the live tree: ≥2 account rows, no mailbox chrome, small tree, no long body, no date/time. A mailbox/message is never a chooser (four negative tests). |
| `core/.../execution/AppReadEvidence.kt` **(new, 193)** | 1–193 | Observed-item evidence (dedupe by normalised text, only target-package captures, row-like text only, chrome excluded) and `AppReadCompletionGate` → `NotAReadRequest` / `Allowed(observed, requested)` / `Rejected(reason, observed, requested)`. |
| `core/.../run/TaskInputChannel.kt` **(new, 230)** | 1–230 | Ordered task-scoped inputs, `TaskInputKind`, `TaskWaitState`, `takeInstructions` (drain once), `isStaleResponse`, `mayClose`, and `TaskContinuation.applyToContext/applyAnswers/continuationNotice/isCancellation`. |
| `core/.../run/AniobInterruptedTask.kt` **(new, 116)** | 1–116 | `InterruptedTaskReport` + `AniobInterruptedTask.parse`: separates verified / unknown / was-waiting, and builds an explicit continuation prompt that forbids repeating the uncertain action. |
| `core/.../domain/TaskContext.kt` | 25–33, 50–61, 84–87, 129 | `additionalInstructions` (additive), `withInstructions` (dedupe), planner view block, pass-through to the executor request. |
| `core/.../domain/ExecutorRequest.kt` | 32–38, 60–66 | The instructions reach the model prompt, marked as additions to the same task. |

### app

| File | Lines | Change |
|---|---|---|
| `ui/AniobUiState.kt` | 26–32 | `waitingForUser`, `pendingInstructionCount` — a parked task is never shown as running, and the counter is the pending-instruction truth. |
| `ui/AniobViewModel.kt` | 126–132 | `activePipeline`, `taskInputs`, `inputSignal`, `taskWaitState`. |
| `ui/AniobViewModelTaskInput.kt` **(new, 334)** | 1–334 | `ModelRequestResult`, channel lifecycle, `awaitModelRequest` (select on the model call vs. a new input), `deliverTaskInput`, `drainInstructionsInto`, `mayCloseTask`, `parkTask`, `prepareResume`, `resumeParkedRun`, `resumeParkedTask`. |
| `ui/AniobViewModelPipeline.kt` | 15 (import), 152–155 (run-scoped state), 243–262 (read evidence + publish run), 276–328 (`recordTrajectory` hoisted out of the old local scope), 342–485 (`runPipelineSteps`), 487–513 (`runStepLoop`) | The run object is published before the first step; the loop is split from setup/cleanup so it can be resumed; a read request attaches `AppReadEvidence` with its target packages; the trajectory writer is shared by the first and resumed entries. |
| `ui/AniobViewModelPipelineState.kt` | 33–34 (`var task`), 100–121 (continuation state, `readEvidence`, `readTargetPackages`) | A run keeps answers, step, evidence, asked questions, parked/waiting flags. |
| `ui/AniobViewModelPipelineAct.kt` | 13 (import), 45–51 (marker with verified list), 523–525 (target-miss parks), 548–555 (ASK_USER parks, same question never repeated) | Questions no longer end the run; the marker records what was verified and what was about to happen. |
| `ui/AniobViewModelPipelineObserve.kt` | 5–6 (imports), 151–192 | Every observation feeds the read evidence; the account chooser is the only place a read interrupts, once. |
| `ui/AniobViewModelPipelinePlan.kt` | 554–559, 628–634 | The model call runs inside `awaitModelRequest`; a superseded reply returns `Continue` and nothing is dispatched from it. |
| `ui/AniobViewModelPipelineClose.kt` | 6, 12 (imports), 33–57 (pending-instruction guard), 123–138 (read gate) | A closing response may not swallow an unprocessed instruction; a read may not complete without observed content. |
| `ui/AniobViewModelSubmit.kt` | 36–53 (parked task takes text, cancel honoured) | While running, input joins the task (`deliverTaskInput`) instead of the old silent drop. |
| `ui/AniobViewModelGates.kt` | 252–258 | `onGrillAnswersSubmitted` resumes the parked run first; the restart path remains only for a run that no longer exists (e.g. after process death). |
| `ui/AniobViewModelLearning.kt` | 480–482 | `finishConversationTask` closes the input channel with the task. |
| `ui/AniobViewModelConversations.kt` | 221–246 (marker format), 293–328 (explicit continuation), 393–418 (honest card) | Marker carries verified/unknown/waiting; the recovery card lists both and offers a user-started continuation. |

---

## 3. Original vs corrected execution flow

**Original ("read my two newest emails")**

```
submitTask → isRunning? → classifier: KNOWLEDGE_QA (question form) → conversational answer
or  → DEVICE_AUTOMATION → resolver opens Gmail (completesOnDispatch) → Finish → SUCCESS
      (nothing was read; any clarification restarted the pipeline at step 0;
       a prompt typed mid-run was dropped; a killed process looked like a checkpoint)
```

**Corrected**

```
submitTask
 └─ parked run of this task?  → append text (verbatim) + resume the SAME run
 └─ isRunning?                → TaskInputChannel.post(INSTRUCTION) → signal → model call superseded
                                (a dispatched action still completes and is verified)
 └─ new task: classifier "2.5 App-data retrieval" (before KNOWLEDGE_QA)
    └─ resolver appDataRead rung → real package from AniobAppRegistry / role / known web page
       requiresObservation = true (opening is not reading)
       └─ executeTaskPipeline: AppReadEvidence(request, targetPackages) attached; run published
          └─ runStepLoop: drain instructions → observe (evidence.observe) →
             [account chooser? ask once, park] → plan (awaitModelRequest) → act → verify
             └─ parked?  → runPipelineSteps returns false; run keeps id/step/context/actions
                          answer → prepareResume → runPipelineSteps again (no replay)
          └─ pipelineComplete: pending instruction? keep working → read gate
             (Rejected ⇒ UNVERIFIED with observed-vs-required, never SUCCESS)
```

Process death stays separate: the marker is explained (`verified:` / `may have happened:` /
`waiting for you:`), never executed; continuing is an explicit user action that starts a new task
whose first move is to look at the live screen.

---

## 4. Tests added / modified

Added (core-agent, deterministic, no device):
- `intent/InAppTaskRoutingTest.kt` — extended to 7 tests (question-form reads, conversational negatives, read criteria).
- `ladder/AppDataReadPlannerTest.kt` — 8 (installed over web, exact activity, uninstalled → known page, role fallback, ambiguity, cold registry, resolver observation flags).
- `ladder/AniobAppReadAccountPolicyTest.kt` — 8 (one account, ask once, never twice, sign-in handover, chrome, duplicates, answer resolution).
- `ladder/AniobAccountChooserScreenTest.kt` — 5 (chooser detected; inbox, message body, single account and other app all rejected).
- `execution/AppReadEvidenceTest.kt` — 9 (two rows satisfy two; chrome; open-only; partial honest reason; other app; scroll dedupe; quoted text only; non-read unaffected; unspecified count).
- `run/TaskInputChannelTest.kt` — 11 (ordering, verbatim append, consume once, pending blocks close, answers, cancellation, stale reply, answer keeps goal, idempotence, notice).
- `run/AniobInterruptedTaskTest.kt` — 6 (verified vs unknown, blank marker, waiting, unlabelled = unknown, no-repeat continuation prompt, waiting prompt claims nothing in flight).

Modified (app, Robolectric; written, not runnable in this sandbox):
- `AniobViewModelExecutionWiringTest.kt` +5 — instruction joins the same task; wake-only-the-model-request; late reply superseded (`runBlocking` + `awaitModelRequest`); clarification resumes the same id/step/context; closing refused while an instruction is pending.
- `ConversationJournalTest.kt` +1 — interrupted recovery is honest ("cause unknown"), terminal, not re-offered.

---

## 5. Actual test results

Command (local JDK 25 + Kotlin 2.2.10 compiler, JUnit/coroutines/Moshi shims because the sandbox has no Maven access; main sources and tests compiled together):

```
core-agent: 13 test files → Tests passed: 162, failed: 0
```

Toolchain: Temurin JDK 25 (`jdk4py` wheel) + `kotlin-compiler@2.2.10` from npm, with local
compile-only shims for `kotlinx.coroutines`, `Moshi` and a JUnit runner. The shims are verification
scaffolding only — they live in `/tmp`, are never added to the repository, and no test calls them.

Files in that run: `InAppTaskRoutingTest`, `AppDataReadPlannerTest`, `AppReadEvidenceTest`,
`TaskInputChannelTest`, `AniobInterruptedTaskTest`, `AniobAppReadAccountPolicyTest`,
`AniobAccountChooserScreenTest`, `AniobIntentResolverTest`, `SuccessCriteriaExtractionTest`,
`AniobLaunchHubTest`, `AniobMidTaskLaunchTest`, `AniobWebLaunchPlannerTest`, `StepPipelineTest`
(the existing pipeline/grounding/approval/ownership suite still passes untouched).

Preflight guards that do not need the network: no billing references, skill assets in sync,
**0 `android.*` imports in core-agent**, broad-catch count 119 ≤ baseline 129 (no new broad catch).
`scripts/ci-preflight.sh` stops at the catalog-URL guard because `huggingface.co` is unreachable
from this sandbox (`curl` exit 35 / HTTP 000) — that guard validates model URLs, not this change.

**Not run:** `./gradlew` (no Maven/dl.google.com/GitHub asset access), so the two Robolectric test
additions are written against the same APIs their neighbouring tests already use but are unverified
by execution here.

---

## 6. Remaining limitations (honest)

1. **Provider APIs are not implemented.** No Gmail/Graph API, no OAuth token storage, no
   `EMAIL_READ` command. Reading goes through the real accessibility hierarchy, as required; a
   provider integration needs its own OAuth/consent/rotation design first.
2. **Row heuristics are heuristics.** `AppReadEvidence` counts clickable/long-clickable rows (or text
   with an address/date) inside the target package. An app with an unusual row implementation may
   under-count — which fails *closed* (UNVERIFIED with the observed count), never open.
3. **The chooser detector is conservative on purpose.** Unrecognised chooser layouts fall through to
   the model instead of pausing; a false pause is worse than no pause, so the detector requires a
   small tree with account rows only.
4. **Resume is in-process.** A resumed run reuses the live `PipelineRun`; after process death there is
   no resumable run, only the honest card and a user-started continuation.
5. **Resuming after the closing phase re-creates providers.** `releaseTaskProviders` runs when the
   step loop ends; the continuation rebuilds them lazily (logged as a rebuild, bounded by user input).
6. **The app-module tests were not executed here** (see §5); they compile-check only by inspection.
7. **Interruption is cooperative.** `awaitModelRequest` cancels the model coroutine; a provider call
   that is blocked inside a synchronous socket keeps running until its own timeout, but its reply is
   discarded (it can never shape a later plan), so the task stays correct, only slightly later.
8. `AniobAppDataRead` targets EMAIL and MESSAGES. Other data kinds (calendar, files) would need their
   own noun/slice vocabulary before the same rules apply.
