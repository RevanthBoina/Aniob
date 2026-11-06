# Fast Verify and Remember replay

Two mechanisms that remove latency from the act → observe → decide loop without weakening the
evidence a step needs. Both are pure-JVM policy in `:core-agent`; `:app` only supplies the
accessibility service and the file.

## 1. Two-tier Verify (`com.aniob.core.verify.FastVerify`)

A dispatched action is no longer followed by a fixed sleep and a mandatory capture. The step
pipeline's `settle` seam is now a pass-through; all waiting happens in `FastVerify.await`, which is
called from the dispatch seam:

| Tier | Trigger | What it does | Cap |
|---|---|---|---|
| 1 | every dispatch | Samples the service's window-event counter every `POLL_INTERVAL_MS` (50 ms). Reads the node tree only when the counter moved, plus one baseline read and one safety read every `CAPTURE_EVERY_TICKS` (4 ticks = 200 ms). | `MAX_WAIT_MS` (800 ms) |
| 2 | tier 1 timed out | One `takeScreenshot` (Android 11+ `AccessibilityService.takeScreenshot`), kept for the next planning step. | one capture |

Tier 1 returns on the first of:

- the structural `screenHash` differs from the pre-action hash (the action had an effect);
- the foreground package changed;
- text the action writes or the task expects appeared **and was not already visible before the
  action** (so a task criterion cannot pass a no-op tap).

A screen whose tree did not move at all is exactly what a visual modal, a popup or a no-op tap
looks like, which is why those pixels are handed to the next Reason step instead of being thrown
away. A tier-2 bitmap is keyed by the screen identity it belongs to, so it can never leak onto a
different screen.

### Window events

`AniobAccessibilityService` maintains a monotonic counter incremented on
`TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED`, exposed as
`windowEventSequence()`. Apps that mutate the view hierarchy without announcing it are still
covered by the safety read.

## 2. Remember replay (`com.aniob.core.memory`)

A verified trajectory is replayed with **no model call**.

- **Store:** `filesDir/remember_cache.json`, written through a sibling temp file and renamed, so a
  process death mid-write keeps the previous cache. The JSON is hand-rolled (six scalars and one
  array) to keep the core dependency-free; damaged input reads as an empty cache, never an
  exception.
- **Key:** `normalisedGoalKeywords | packageName | preActionScreenHash`, where the keywords are
  lowercased, stop-word filtered, distinct and sorted. "Open Wi-Fi settings" and "wifi settings
  open" produce the same key.
- **Value:** the verified actions, stored by `viewIdResourceName`, then `text` /
  `contentDescription`, then the normalised centre of the element. Element ids are never stored:
  they are re-assigned on every observation.
- **Recorded:** only when the run ends with a verified PASS, and only under the ablation condition
  the run started in. A failed or unverified run teaches nothing.
- **Consulted:** every step, right after the intent shortcut and before every model rung, and only
  in `FULL_HUMAN_SPEED` (with the user's replay switch also on). A hit dispatches immediately and
  sets the research run's remember-hit flag; the run's LLM counter stays at 0.
- **Fallback:** a miss, a vanished/disabled/ambiguous target, or a replay that leaves the screen
  unchanged returns to the normal ladder. A stalled replay is not retried on that screen for the
  rest of the run, so the cache can never spin on the same action. Replayed actions are
  re-grounded on the live screen (`BurstTargetGuard`) before dispatch, and a replayed sequence runs
  through the same runner as a model plan (`ActionBurstRunner`, see
  [ACTION_PLANS.md](ACTION_PLANS.md)): every hop is verified before the next one is dispatched, and
  only verified hops are recorded.

## Tests

- `core-agent/src/test/.../FastVerifyTest.kt` — hash/text/package decisions, 50/800 ms budget,
  event-driven capture cadence, pre-existing text is not evidence, tier 2 runs once.
- `core-agent/src/test/.../RememberCacheTest.kt` — key normalisation, JSON round trip and damaged
  input, re-indexed replay, identity ladder, ambiguous/disabled/missing targets, cap, file
  contract.
- `core-agent/src/test/.../ladder/AniobExecutionRouterTest.kt` — replay rung ordering (after the
  intent shortcut), gating, empty results and cache misses fall through.
