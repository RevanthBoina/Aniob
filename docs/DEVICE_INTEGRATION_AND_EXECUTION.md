# Execution authority, document access and auto-mode routing

Status of this document: describes the code on this branch. **None of it has been built or run on a
device or emulator from this change set** (see *Verification*). Every limitation below is real, not
hedged boilerplate.

## Why this change exists (root cause analysis)

User-visible failures reduced to four mechanisms; they were worth the rewrite rather than a patch:

1. **No single execution authority.** "Open Play Store" could reach `startActivity` through at least
   four pathways: `AniobAccessibilityService.handleSystemIntent(OPEN_APP)`, the ViewModel's
   `dispatchSystemIntent`, `runDeviceCommand` (twice: ViewModel + service), and the session-recovery
   cold start. None of them shared an execution identifier, a lock, or a result record, so duplicate
   dispatches were invisible until two apps were open.
2. **Silent fallbacks sprayed intents at whatever would take them.** `media_play_search` with a pinned
   music app that could not take the search intent re-fired the same action *unpinned* at any app on
   the phone. (An equivalent unpinned Play Store fallback on the shortcut path had already been removed;
   installs still land on the `https` web storefront, and keyless HTTP URLs still get the system default
   browser — both are terminal success paths, not sprays.)
3. **Auto mode ping-ponged between local and cloud.** The per-step ladder heuristics (screen entropy,
   node count, dense animations, local fail count) re-decide the provider on every step, so a task the
   on-device model had already started could be routed to cloud mid-task, then back down, then up again —
   each escalation counted as a failure of the other side. There was no per-task memory of what was working.
4. **Documents were second-class citizens.** Share sheets carrying a PDF/docx/pptx were parsed
   (MIME, URIs) and then dropped; the agent could only talk about *text/* shares. Cloud-stored documents
   (Google Drive) had no pathway at all, and `GET_INSTALLED_APPS` was an honest "unsupported" dead end.

## What is new

### 1. `DeviceActionGateway` — the single execution authority

`core-agent/.../execution/DeviceActionGateway.kt` (pure JVM, unit-testable, no Android types).

Every effect on the phone goes through one object. Contract:

- **Execution locking.** The *decision* (inspect the ledger, claim the execution id, mark PENDING →
  EXECUTING) happens under one monitor; the dispatch itself runs **outside** the monitor, so a slow
  dispatch (PackageManager scan, file read, cold-started target app) never blocks unrelated actions or
  the calling thread behind a global lock. Exactly-once is preserved by claiming the execution id
  atomically *before* dispatching: an identical concurrent request is refused as `DUPLICATE`
  (ok=false, "outcome unknown"), never queued behind the first. A re-entrant dispatch from inside an
  action lambda sees the EXECUTING record and is likewise refused — it cannot deadlock.
- **Intent deduplication.** An identical *signature* within `duplicateWindowMs` (default 2 s) is
  suppressed and reported as `DUPLICATE` **only when the earlier record ended COMPLETED** — then the
  outcome is reused with ok=true (`reusedPreviousResult=true`). If the earlier record is still in
  flight, the caller is told the outcome is unknown (ok=false). A FAILED or INTERRUPTED predecessor
  never suppresses a retry and is never reported as success.
- **Idempotency — across process death.** A caller-supplied `executionId` never dispatches twice:
  retries after a lost callback return the recorded outcome (`reusedPreviousResult=true`). When a
  `ledgerStore` is configured (the app wires `filesDir/device_action_ledger.txt`) the bounded ledger
  is persisted on every mutation and restored on first use, so the 5-minute ledger and its execution
  ids survive process death. Records left PENDING/EXECUTING by a dead process are reconciled to
  `INTERRUPTED` on restore and are never treated as proof the action happened.
- **Execution state tracking.** PENDING → EXECUTING → COMPLETED | FAILED, plus `DUPLICATE` for
  suppressed duplicates and `INTERRUPTED` for records reconciled after process death. Records carry
  executionId, taskId, origin agent, the requested action, the *resolved* application, detail, timing
  and an attempt counter.
- **Controlled retries.** A FAILED or INTERRUPTED signature may be retried; the attempt number is
  `priorFailures + 1`. The same `device_command:` signature in the same task within `repeatWindowMs`
  (default 5 min) is served from the ledger **only when its last record COMPLETED** — confirmation
  polling cannot cause a re-dispatch, and a failed action is never replayed as if it succeeded.
- **Centralized logging.** Every transition goes to a `logSink`. `AniobApplication` wires it to
  `EventLogger.info("DeviceActionGateway", …)` so one query answers "which agent fired which action, on
  which app, and what happened".
- **Bounded, self-purging ledger** (default 200 records, horizon = repeat window). With a
  `ledgerStore` configured, the bounded snapshot is written on every mutation and read once on first
  use; without one, the gateway is process-scoped. Either way it cannot recall an accepted Activity
  launch — exactly-once means "we do not fire the intent twice", not "we undo it".

Wired pathways: ViewModel `dispatchSystemIntent` (system intents, app launches via shortcuts,
install→web-storefront fallback), accessibility-service `OPEN_APP` (incl. the session-recovery
cold-start relaunch, which flows through it), `runDeviceCommand` in both the ViewModel and the
accessibility service (all `DEVICE_CONTROL` commands, signature = command + sorted-args fingerprint),
and the workflow launcher gained an `onAudit` one-line sink (claim/accept/fail) with **unchanged claim
semantics**.

### 2. Auto mode: per-task provider lock instead of ping-pong

`AniobAutoRouter.decideRoute` gained `taskStickyTarget: RouteTarget? = null`. Honoured only in
`auto` / `local-first` / `balanced`, only between `LOCAL_SLM` and `OMNIROUTE_CLOUD`, and only *after*
strict user policies (local-only/cloud-only), offline, missing-model, battery-low, thermal-throttle and
vision checks — any of those always wins. The ViewModel keeps `taskStickyTarget` for the task: set when
the effective provider is chosen (and re-set on cloud downgrade/escalation), cleared at task start, and
threaded through `planStep`. Net effect: once local (or cloud) has really done work for a task, the next
steps stay there instead of re-rolling the density heuristics every step. Strict modes and device
constraints are unaffected.

### 3. Finding, reading and summarizing documents (any format, no per-file code)

Pattern worth keeping (learned from mature agent workspaces, deliberately **not** ported wholesale and
**not** named in code): a document task is *find → read → feed text to the model*. Implemented once,
generically, in `app/.../device/AniobDocumentAccess.kt`:

- **Discovery**
  - `FILE_FIND` — MediaStore query, now with `extension=py|xlsx|…` (validated `[a-z0-9]{1,10}`) for
    "Python files on my phone". Still honest about the empty case (no All-files access → photos/video/
    audio only; the answer says so).
  - `FILE_SHARED` — lists files that arrived in Aniob via share sheets or the picker (persisted in
    `filesDir/shared_files.json`, cap 20).
  - `DOC_PICK` — opens the system Storage Access Framework picker (`AniobDocumentPickerActivity`,
    transparent, exported=false). This is the *only* Google Drive path a normal app gets: the user picks
    the file, we take a persistable read grant on exactly that file. Folder-scoped MediaStore cannot see
    `Android/media/com.whatsapp/...`; the picker can.
- **Reading** — `FILE_READ`. Every reader resolves to a local file first — a share copy in the
  private store is read in place; a `content://` URI is *streamed* into a bounded cache temp file
  (≤ 24 MB, then deleted) — and extraction never buffers an unbounded payload in the heap:
  - `text/*`, `.md/.csv/.json/.xml/.log/.kt/.py/…` — streamed with a character cap, not read whole.
  - Office Open XML (`.docx/.pptx/.xlsx`) and ODF — ZIP containers read through `java.util.zip.ZipFile`;
    text parts are kept only for the known text entries and each part is capped (2 MB) so a
    pathological member cannot exhaust the heap.
  - PDF — parsed from a bounded byte array (≤ 6 MB); each content stream is inflated chunk-wise with a
    1 MB per-stream cap and the literal strings in the text-showing operators are collected.
    **No PDF dependency exists or was added** (none is available offline), so PDFs whose text lives
    behind embedded font subsets, encryption or scanners will come back as an honest
    "could not extract on-device — ask me to open it instead". No content is ever invented.
  - Legacy binary `.doc/.ppt/.xls` — reported unsupported by name.
  - Sizes capped (≤ 24 MB streamed in, ≤ 6 MB for PDF into memory, ≤ 12 000 chars out, truncation is
    flagged in the answer).
- **Summarizing / acting on it**
  `FILE_READ` returns the text as the step's `result:`. The next step is an ordinary model call
  (local or cloud — the sticky router decides), so "summarize that pdf", "what does this slide deck
  recommend", "extract the table" all work without any per-format agent code. `FILE_READ` is a
  `completesOnDispatch=false` command so the content lands in history before the run may finish.
  For Google-Drive-only documents the flow is: `doc_pick(kind=presentation|pdf|document|spreadsheet|any)`
  → user picks once → `file_read` (or `file_shared`) reads the registered URI forever after.

**Inbound shares.** `ACTION_SEND` / `SEND_MULTIPLE` filters now cover pdf, msword, WordprocessingML,
PresentationML, SpreadsheetML, csv and image/* (previously text/plain only). On arrival the URIs are
parsed, the bytes are *copied* into `filesDir/shared/` (share-sheet grants are not persistable), the
copy is recorded in the shared-file registry, and `file_open` serves private copies through a
FileProvider authority instead of a raw `file://` (blocked since API 24). Share copies that the
registry no longer references are deleted on every registration (disk stays bounded by the registry
cap); `clearShared` removes both the registry file and the copies. All paths are canonical-path
checked against the shared directory with a separator boundary before any file is read or shared.

### 4. `GET_INSTALLED_APPS` is implemented

Launchable apps via `PackageManager.queryIntentActivities` over the `MAIN/LAUNCHER` intent,
which matches the `<queries>` declaration in the manifest (Android 11+ package visibility), so the
list is not silently truncated for lack of package-query permissions. Labels come from
`getApplicationLabel`; `getInstalledApplications(0)` is not used. Cap 40 default/`limit=`, optional
`query=` filter), `informational=true`. Wired to both the `GET_INSTALLED_APPS` system intent
and the new `installed_apps` device command (wire aliases `list_apps`, `apps_installed`, `apps`).

### 5. Duplicate-dispatch removals and one kept preference

- `media_play_search`: a pinned music app that cannot take the search intent now **fails**; it no
  longer re-fires unpinned at any app on the phone.
- `dispatchSystemIntent` and accessibility `OPEN_APP` are under the gateway, so a second pathway firing
  the same launch inside the window is suppressed and logged, not re-dispatched.
- Kept (documented preference semantics): preferred browser for `https` URLs, `https` web-storefront
  fallback for install failures, `geo:` unpinned navigation, `FILE_OPEN`'s unpinned `ACTION_VIEW`
  (the system viewer is the correct handler), pinned-then-default app launch for URLs with a
  `preferred_package`.

### 6. Manifest and pickers

`AndroidManifest.xml`: new `AniobDocumentPickerActivity` (exported=false, transparent), SEND/SEND_MULTIPLE
document MIMEs, FileProvider `files-path` for `shared/` (workflow_file_paths.xml). No new permissions,
no Drive OAuth, no Google Sign-In: the picker is the authorization.

## Verification — read this part

- **CI is the verifier.** This workspace has no JDK, no Android SDK, no Gradle cache, and no reachable
  Maven/Google repository (verified). Unit tests and Android lint run on GitHub Actions; trust
  `gh pr checks <PR>` and the run log over this file. The revision before this one was CI-green
  (run 42617528654, all steps incl. unit tests and lint). The defect fixes in this revision (durable
  cross-process ledger, dispatch-outside-lock, COMPLETED-only dedup, installed-apps visibility, bounded
  readers/eviction, path-boundary confinement) are verified the same way — see the PR checks for the
  run observed for this commit.
- **No device verification.** File indexing visibility, SAF provider behavior, PDF harvest accuracy,
  share-sheet copy semantics, accessibility-service launch pathing and the overlay between the two
  `runDeviceCommand` implementations are all unobserved on hardware.
- **New/updated tests for this revision** (`DeviceActionGatewayTest`): a FAILED original inside the
  duplicate window is retried, not deduped; a FAILED `device_command:` repeat is re-dispatched with a
  counted attempt; re-entrant dispatch during in-flight is refused, not deadlocked; cross-process
  replay of one `executionId` never dispatches twice (durable ledger); a mid-dispatch crash is
  reconciled to INTERRUPTED on restore, never reused as proof; plus the previous 10 cases.
- **Known thin spots:** PDF harvesting is a best-effort text operator scan (no layout, no tables;
  scanned PDFs yield nothing and say so); Office extraction flattens all text (no cell structure);
  `file_read` on images returns an honest "not text" message; the shared-file registry caps at 20
  entries and unreferenced share copies are evicted on registration; `DeviceActionGateway` exactly-once
  survives process death only for the ledger's lifetime (5-minute horizon) and still cannot recall an
  accepted Activity launch.

## Requirement traceability

| Requirement | Where it landed |
| --- | --- |
| "Open the resume received on WhatsApp" | `WHATSAPP_FILE_REGEX` reflex → `file_read(query=resume, kind=document)`; misses fall back to an honest message naming `doc_pick`; WhatsApp folder reachable via the picker |
| "Open my latest pdf from downloads" | `OPEN_FROM_REGEX` reflex → `file_open(kind=pdf)` |
| "Find python files on my phone" | `CODE_FILE_REGEX` reflex / `file_find extension=py` |
| "Open the presentation stored in Drive" | `DRIVE_OPEN_REGEX` reflex / `file_find` → `doc_pick(kind=presentation)`; user picks; later reads are direct |
| "Summarize that pdf … with cloud LLM help" | `file_read` output rides step history; local (sticky) or cloud model summarizes — no per-file code anywhere |
| No duplicate dispatches / one execution authority | `DeviceActionGateway` + all pathways wired (§1) |
| Execution locking, idempotency, state tracking, controlled retries, centralized logging | Gateway contract (§1) |
| Auto mode must stop local↔cloud ping-pong | `taskStickyTarget` in `decideRoute` (§2) |
| Installed apps list | `installed_apps` / `GET_INSTALLED_APPS` (§4) |
