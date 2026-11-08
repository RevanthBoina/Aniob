# Bundled action plans (Reason → Act in one turn)

A normal agent turn is expensive because of *round trips*, not because of tokens. Every extra step
pays the whole prefill again: the system prompt, the capability catalogue, the observation and the
committed history are re-sent, the model re-reads them, and only then does it emit one action. On a
short phone task — set an alarm, open Wi-Fi, compute 12 × 8 — the model spends four or five of those
round trips doing work it had already reasoned about in the first one.

The bundled plan closes that gap: **one reply may plan several sequential actions**, the runner
executes the part of it that the observed screen can support, and the rest of the run continues from
the screen that work revealed.

This document describes the contract as implemented. The relevant code:

| Piece | Where |
| --- | --- |
| Plan model (`ActionBurst`) | `core-agent/domain/ActionBurst.kt` |
| Wire parser | `core-agent/domain/AniobActionSchema.kt` (`parseActionBurstOrNull`) |
| Screen scoping | `core-agent/perception/BurstPlanScope.kt` |
| Execution runner | `core-agent/execution/ActionBurstRunner.kt` |
| Structured-output boundary | `core-agent/providers/AniobStructuredOutput.kt` |
| Prompt contract | `core-agent/domain/ExecutorRequest.kt` (`burstMode`, `carriedPlanHint`) |
| Step loop | `app/ui/AniobViewModel.kt` (`Act:` plan block) |

## 1. The wire shape

```json
{"thought": "open the alarm editor, type the time, confirm",
 "is_complete": false,
 "actions": [{"type": "tap", "id": 1},
             {"type": "input", "id": 2, "text": "7:30", "submit": true},
             {"type": "tap", "id": 3}]}
```

* `type` is one of `tap`, `input`, `long_press`, `scroll`, `back`, `home`, `system_key`, `open_app`,
  `wait`, `device_control`, `finish`, `fail`.
* `id` is the sequential element id of the observation (the `id=` value in the element list). A
  quoted number, an `index` / `node_id` alias, and the canonical
  `target:{"kind":"som_index","value":2}` object are all accepted, because a model that is right
  about the plan should not lose the step over a quoting habit.
* `input` may omit `id` when the screen has exactly one text field, and the typed value must appear
  in the user's request (see §4).
* A non-empty multi-action array contains 2–8 actions. A one-action array is refused as a burst and
  falls back to the ordinary single-step parser. A longer reply is refused **whole**: truncating it
  would run a sequence whose later members the earlier ones were planned around.

## 2. The plan spans more than one screen — that is the point

The prompt asks for "the next stretch of the task", not "what is on this screen". A model that is
asked to stay on the current screen answers with one action, and the batching buys nothing; a model
that plans ahead writes `tap(Add alarm) → input(7:30) → tap(OK)` — and two thirds of that plan
describes a screen that does not exist yet.

`BurstPlanScope` splits the reply:

* **executable** — the longest prefix of actions anchored on elements the observation actually
  contains, resolved through the same `AniobGroundingResolver` the dispatcher uses.
* **deferred** — everything after the first action that does not resolve. It is *not* executed and
  not treated as evidence. It is rendered into the next planning request as a neutral hint
  (`PLAN ALREADY CHOSEN: …`), so the model continues its own plan instead of rediscovering it.
* **refused** — a plan whose *first* element action does not resolve. That is a model guessing ids,
  and the step falls back to the single-action parser and its repair retry.

Two rules keep this safe rather than merely permissive:

1. **Completion never survives truncation.** `is_complete` describes the full plan the model
   wrote; closing the run after the first of them would report a finished task the screen does not
   show. A plan with a deferred tail is never complete.
2. **A target must be able to receive the action.** Grounding answers "which element is this";
   it does not answer "can it be typed into". Ids are re-assigned on every capture, so a plan written
   for the *next* screen addresses ids that still exist on the current one as different widgets.
   Only an editable node can be the target of `input`; a lookalike closes the plan instead.

## 3. Execution: look again after every action

`ActionBurstRunner` dispatches members one at a time:

```
for each member:
    if the first member targets an element, or a later member is due:
        re-capture the screen
        BurstTargetGuard.check(member, origin, live)  # package + stable element identity
        target gone / navigated → close the plan, hand the live screen back
    re-ground the member on the live screen (view id → text → description → bounds)
    StepPipeline.executeStep(...)  # safety, approval, gate, dispatch, settle, verify, reflect, record
    FAIL                           → hard stop (safety block is final)
    SUCCESS                        → the plan finished the task
```

Every member goes through the **same** pipeline as a single-action step: no safety shortcut, no
skipped verification, no second dispatch path. `StepPipeline` compares the planning node with the
fresh node before dispatch; a recycled accessibility index is a grounding miss, not a new target.
Each member also uses the real approval gate; a sensitive member can pause for the user's decision
even when it is early in the plan. Silence leaves the action pending, and denial stops the rest of
the plan.

The separate `AniobObservationPolicy` default of three counts consecutive planning passes that may
reuse a recent verified observation in an ablation. It does not limit burst length or bypass these
live dispatch checks.

A closed plan is not a failure. The actions that ran really happened, they are already recorded and
verified, and the loop plans the next step from the screen they revealed. Two consecutive plans that
cannot execute even their first member switch batching off for the rest of the run: that is a
thrashing screen, and single actions are the honest response.

## 4. Trust boundaries

* **Typed text.** An `input` without an `id` is allowed only when the screen has exactly one editable
  field, the field is not a password, and the value appears in the user's request. An injected page
  cannot make the agent type something the user did not ask for.
* **No coordinates.** `id` is an element id from the observation. A plan carries no pixels; pixels
  are produced by the grounding resolver against the live tree, exactly as for a single action.
* **Safety is per member.** The safety interceptor, the injection canary and the blocked-fingerprint
  rule run on each member. A block ends the plan; it is never retried by the next member.
* **Deterministic commands stay single.** Media, volume, torch, calls and codes are `DEVICE_CONTROL`
  actions that need no screen; the prompt keeps them out of plans.

## 5. When batching is on

The plan shape is used only when the user's run is in a multi-action condition *and* the action is
being produced by a cloud model that can hold the contract. The on-device resolver is
single-action, `BASELINE` and `INTENT_SHORTCUT_ONLY` keep one action per call, and the ablation
switch is what the study measures. A run that hits a stale plan twice falls back to single actions
for its remaining steps.

## 6. Tests

* `core-agent/src/test/…/BurstPlanScopeTest.kt` — the spellings models emit, the screen-boundary
  split, the completion rule, the single-field typing rule, and the fallbacks.
* `core-agent/src/test/…/execution/ActionBurstRunnerTest.kt` — re-capture before the first targeted
  member and between members, unnamed-field grounding, target vanished, package navigation,
  recycled/lookalike ids, safety stop, user stop, failed capture, context hand-off.
* `core-agent/src/test/…/ActionBurstTest.kt` — the prompt contract, the 2–8 parser bounds, and the
  production fallback from a one-action array to the ordinary action contract.
