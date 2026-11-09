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

- **Execution locking.** Every dispatch runs under one monitor; two conflicting actions cannot be in
  flight at the same time. The dispatch is synchronous at the call sites (the accessibility service is
  not a suspend context), so the lock is a plain `synchronized`, not a coroutine mutex.
- **Intent deduplication.** An identical *signature* within `duplicateWindowMs` (default 2 s) is
  suppressed and reported as `DUPLICATE`; the caller's intent is treated as satisfied (ok=true) because
  the action did happen — by the other pathway.
- **Idempotency.** Caller-supplied `executionId` never dispatches twice: retries after a crash or a lost
  callback return the recorded outcome (`reusedPreviousResult=true`).
- **Execution state tracking.** PENDING → EXECUTING → COMPLETED | FAILED (| DUPLICATE). Records carry
  executionId, taskId, origin agent, the requested action, the *resolved* application, detail, timing and
  an attempt counter.
- **Controlled retries.** A FAILED signature may be retried; the attempt number is `priorFailures + 1`.
  The same signature in the same task within `repeatWindowMs` (default 5 min) is served from the ledger
  instead of re-firing — confirmation polling cannot cause a re-dispatch.
- **Centralized logging.** Every transition goes to a `logSink`. `AniobApplication` wires it to
  `EventLogger.info("DeviceActionGateway", …)` so one query answers "which agent fired which action, on
  which app, and what happened".
- **Bounded, self-purging ledger** (default 200 records, horizon = repeat window). In-memory only:
  process death clears it, and idempotency therefore does not survive reboots. That is the honest limit.

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
- **Reading** — `FILE_READ`
  - `text/*`, `.md/.csv/.json/.xml/.log/.kt/.py/…` — direct read.
  - Office Open XML (`.docx/.pptx/.xlsx`) and ODF — these are ZIP containers; we unzip in memory and
    keep the text nodes (`word/document.xml`, `ppt/slides/slideN.xml`, `xl/sharedStrings.xml`, …).
  - PDF — best-effort harvest with the JDK's `Inflater`: every content stream is inflated and the
    literal strings in the text-showing operators are collected. **No PDF dependency exists or was added**
    (none is available offline), so PDFs whose text lives behind embedded font subsets, encryption or
    scanners will come back as an honest "could not extract on-device — ask me to open it instead".
    No content is ever invented.
  - Legacy binary `.doc/.ppt/.xls` — reported unsupported by name.
  - Sizes capped (≤ 8 MB in, ≤ 12 000 chars out, truncation is flagged in the answer).
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
FileProvider authority instead of a raw `file://` (blocked since API 24).

### 4. `GET_INSTALLED_APPS` is implemented

Launchable apps via `PackageManager` (count + "Name (package)" lines, cap 40 default/`limit=`,
optional `query=` filter), `informational=true`. Wired to both the `GET_INSTALLED_APPS` system intent
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

- **No build was run.** This workspace has no JDK, no Android SDK, no Gradle cache, and no reachable
  Maven/Google repository (verified). The only Kotlin check available was brace/paren counting and
  careful review. **Do not trust this branch yet; run `./gradlew :core-agent:testDebugUnitTest :app:assembleDebug`
  on a machine with the Android SDK.**
- **No test was executed.** New/updated tests are written for CI and have not been observed passing:
  `DeviceActionGatewayTest` (8 cases: dup window, idempotency replay incl. failure replay, same-task
  ledger reuse, cross-task re-fire, retry attempt counting, bounded ledger/purge, log sink, exception→FAILED),
  `AniobStickyRoutingTest` (6 cases: sticky local/cloud win, model-missing wins, vision wins, strict
  modes win, offline+missing-model → CANNOT_PROCEED), `AniobSmartFileReflexTest` (5 phrases),
  plus the existing reflex/schema suites.
- **No device verification.** File indexing visibility, SAF provider behavior, PDF harvest accuracy,
  share-sheet copy semantics, accessibility-service launch pathing and the overlay between the two
  `runDeviceCommand` implementations are all unobserved on hardware.
- **Known thin spots:** PDF harvesting is a best-effort text operator scan (no layout, no tables;
  scanned PDFs yield nothing and say so); Office extraction flattens all text (no cell structure);
  `file_read` on images returns an honest "not text" message; the shared-file registry caps at 20
  entries with no eviction policy beyond FIFO; `DeviceActionGateway` exactly-once is process-scoped and
  cannot recall an accepted Activity launch.

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
