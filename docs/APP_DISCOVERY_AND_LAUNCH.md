# App Discovery & Launch (UI Automation)

How the agent answers the two questions it needs on every task — *which apps exist?* and *which one
did the user mean by "open clock"?* — and how a launch is dispatched and verified inside the UI
automation loop.

No LLM, no embedding, no network: pure JVM `kotlin` + `java.text` matching for the lookup, and a
single Android-aware probe (`AniobAppBootstrap`) behind it.

---

## 1. The registry (`core-agent` → `knowledge/AniobAppRegistry.kt`)

One source of truth for "which package does the user mean, and how do I open it".

* **Launchable-only.** Entries come from `queryIntentActivities(MAIN/LAUNCHER)`, not
  `getInstalledApplications()`. "Installed" and "can be opened" are different sets, and only the
  second one is a valid target for the automation loop.
* **Resolution tiers**, weakest last: exact package → exact label → exact alias → word-boundary
  prefix → single-token fuzzy (normalised Levenshtein ≥ 0.62 with a first-character gate), all
  reported through `AppLookup.Match(app, how)`.
* **Ambiguity is an answer.** Two plausible apps return `AppLookup.Ambiguous(candidates, how)`; no
  caller may pick one. That is how "Settings Hub" stops opening Settings.
* **Roles come from the platform**, not from a vendor table: `resolveActivity` on
  `SHOW_ALARMS`, `APP_CALCULATOR`, `APP_BROWSER`, `APP_FILES`, `APP_MUSIC`, `APP_MESSAGING`,
  `IMAGE_CAPTURE`, `ACTION_SETTINGS`, `ACTION_DIAL`, `mailto:`, `geo:` → `role → package`.
* **Typo policy.** Fuzzy is single-token only (`instgram` → Instagram), because fuzzy-matching a
  phrase to a one-word alias is exactly how `"wi-fi settings"` used to open Settings. Trigrams were
  measured against the real alias vocabulary and rejected (`chorme`/`chrome` = 0.20); Levenshtein
  scores it 0.83 while every negative stays ≤ 0.22.
* **Cache.** TSV (`app_registry.tsv`), TTL 7 days, re-verified in the background once a day, and
  stale when the launchable count moves. A failed or empty scan never wipes a good cache. A corrupt
  cache loads as empty instead of throwing.

`AniobAppCatalog` still exists as a thin facade (`getAll`, `resolveApp`, `findCandidates`,
`findPackageForName`, `saveToFile`, `loadFromFile`) so no app-layer call site had to know the registry
exists — but it holds no state of its own.

## 2. Learning the device once per launch (`app` → `knowledge/AniobAppBootstrap.kt`)

`AniobApplication.onCreate` calls `AniobAppBootstrap.install(this)`:

1. **synchronously** load the TSV cache, so the first task after a cold start already resolves names
   (the old catalog was only populated after the accessibility service connected — that empty window
   is why "Open Clock" failed intermittently);
2. **synchronously** ask the platform which app fills each role;
3. **asynchronously** probe `MAIN/LAUNCHER` and rewrite the cache.

`<queries><intent> MAIN/LAUNCHER` is in the manifest: on Android 11+ a missing entry makes
`queryIntentActivities` return a partial list and the registry silently under-reports.

`AniobAccessibilityService.onServiceConnected` and `AniobPackageReceiver` refresh the same registry
(the receiver additionally upserts/removes the single package, ignoring `EXTRA_REPLACING` removals),
so an install or uninstall is visible to the very next task.

## 3. Planning a launch (`core-agent` → `ladder/AniobLaunchHub.kt`)

`AniobLaunchHub.plan(prompt, registry, isLaunchable)` — step 0:

* the task must **begin** with a launch verb (`open|launch|start|run|go to|switch to|fire up`) after
  optional politeness/articles, so a mention inside a sentence never launches;
* the app name must sit at the **start** of the remainder on a word boundary
  (`"Open Wi-Fi settings"` is a Settings sub-screen, not the Settings app);
* the longest name wins; an equal-length tie is dropped, not guessed;
* an entry the platform says is not launchable is never returned;
* the stock seed (`AniobAppRegistry.seedApps()`) is consulted only after the live registry, and is
  still gated by `isLaunchable`.

`AniobLaunchHub.planMidTask(...)` — a switch later in a task (`"… then open Instagram"`): scans the
whole prompt, skips the apps this task already opened (`reset` / `noteLaunched` / `wasLaunched`,
keyed by task signature) and the app already in front. This is what stops a multi-step goal from
re-launching its starting app in a loop.

`AniobAppLaunchResolver` remains the entries-based form of the same policy (`resolve`,
`resolveAnywhere`, `entriesFrom`) and is used by the hub for the mid-task scan.

## 4. The ladder (`ladder/AniobExecutionRouter.kt`)

Order inside the zero-token pre-resolution block:

1. `appLaunchResolver` at step 0, `midTaskLaunchResolver` afterwards → `AppLaunch` (skipped when the
   package is already in front or already launched by this task);
2. `AniobIntentResolver.resolve`:
   * a **reflex** (`!requiresObservation`) → `DirectIntent`, step 0 only, because its dispatch *is*
     the outcome and repeating it on every planning round is how a failed reflex becomes a loop;
   * an **app** shortcut (`requiresObservation`, names a package) → `AppLaunch` at step 0, so the
     launch is confirmed by observing the screen instead of finishing on a blind intent.

`ExecutionPlanResult.AppLaunch` carries `packageName`, `appName` and the explicit `launchActivity`
when the registry knows it.

## 5. Dispatch and verification inside the UI automation loop

```
AppLaunch → AniobAction.OpenApp(packageName, activityName)
          → AniobActionExecutor.planDispatch → DispatchCommand.SystemIntent("OPEN_APP", pkg, component)
          → AniobAccessibilityService: explicit Intent.setClassName(pkg, cls) when known,
            else getLaunchIntentForPackage(pkg); null → dispatch fails (never a guess)
          → StepPipeline: gate → dispatch → settle → verify
          → DeterministicVerifier: screenAfter.packageName == action.packageName
```

* The explicit component is preferred because an implicit launch can land on a chooser or be answered
  by another app registering the same action — the verifier would then read the wrong foreground
  package and the step would fail for the wrong reason.
* An app shortcut is **never** a `DirectIntent`: a `DirectIntent` finishes without a post-condition,
  is rejected by `StepPipeline`, and the task ends `FAILED` even though the app opened. That was the
  "Open Clock" bug.
* `AniobViewModel.isOfflineShortcut` therefore excludes app shortcuts
  (`resolvedShortcut != null && !resolvedShortcut.requiresObservation`), so an app launch is not
  exempt from the Doctor preflight: it needs the accessibility service in order to be observed.
* `AniobLaunchHub.verifyForeground(expected, observed)` is the reusable foreground check, and
  `AniobAccessibilityService` records a successful launch in the registry
  (`noteLaunch`, ranking only — it never overrides an exact match).

## 6. Deliberate limits

| candidate | verdict |
|---|---|
| Ask the model which package the user means | rejected — the answer is already on the device |
| `getInstalledApplications()` | rejected — includes packages that cannot be opened |
| Scan `PackageManager` per task | rejected — learn once per launch, cache to disk |
| Fuzzy over the whole query | rejected — matched "wi-fi settings" to Settings |
| Trigram similarity for typos | rejected by measurement (see §1) |
| Guess when two apps match | rejected — return `Ambiguous` and ask |
| Return an app launch as `DirectIntent` | rejected — ends `FAILED` with no post-condition |
| Only launch at step 0 | rejected — mid-task switches are a stated requirement |

## 7. Test coverage

`core-agent` (pure JVM, no Android):
`knowledge/AniobAppRegistryTest` (tiers, normalisation, typo vs phrase, ambiguity, roles, aliases,
TSV round-trip, corruption, staleness, seeds, upsert), `ladder/AniobLaunchHubTest` (planning,
word boundaries, ties, launchable gating, mid-task, per-task memory, foreground check),
`ladder/AniobMidTaskLaunchTest` (switch-once, no re-launch, foreground skip, step-0 routing,
observed launch vs `DirectIntent`, reflexes), plus the rewritten `AniobIntentResolverTest`.

`app` (Robolectric) covers ViewModel wiring; the bootstrap's real `PackageManager` probe and the
accessibility foreground check need a device or emulator.
