# Audit-fix implementation and verification

Audit base: `cc0b33cdf51ed205e470a5ebc5a289bb7f0eb7bd`.

## Implementation

The eight implementation commits follow the requested order: composer/catalog, account response
compatibility, research schema/modes, local ambiguity, clarification/correction, idle/capture,
neutral skip/Finish, then completion/indexing. Follow-up regression coverage also checks cross-task
telemetry ownership and neutral tracker presentation.

Important contracts:

- Typed syntactically valid model IDs are not restricted to the offline catalog.
- Speed policies are snapshotted at task start; Baseline keeps its original speed switches off.
- An installed-app ambiguity does not become a web fallback. Ties are deterministic, with an
  explicit alternative that can be selected in a follow-up task.
- A fast verification skip is neither a verified step nor a failure, and never enters verified
  task learning. The tracker displays a neutral skipped state.
- User-corrected research rows retain the original outcome and are not machine successes.
  User-attested learning has its own provenance channel. It does not invent screen fingerprints,
  overwrite a device-verified macro, or increment its success count. Consolidated skill descriptions
  retain `USER_ATTESTATION` provenance.
- Optimized completion is published before consolidation/indexing. Background jobs use captured
  episodes and transitions, not the next task's mutable collections. Telemetry detaches by task ID
  before publication and its abnormal-exit safety net cannot close a newer task.
- Existing 12-, 15-, and 29-column CSV rows remain readable. New rows have 36 columns; appending
  migrates an old header and rows together. Preserved older summary rows are padded to the new header.

## Checks performed in the sandbox

Passed:

- `bash scripts/config-assets-sync-check.sh`
- `bash scripts/skill-assets-sync-check.sh`
- `bash scripts/check_no_billing.sh`
- `git diff --check`
- Preflight guards 1–6. Guards 8–9 were additionally run independently and passed, since guard 7
  prevents the complete script from reaching them.
- A supplementary Kotlin syntax survey found no newly introduced parser errors. This is not
  an Android compilation or lint result.

Supplementary standalone smoke checks passed for:

1. Catalog fallback and custom IDs.
2. Four-mode flags, categorized CSV round-trip/legacy read, independent summaries and delay median data.
3. Complete message/alarm/call prompts and one-question clarification cap.
4. User-attestation outcome provenance.
5. Local ambiguity, candidate-order independence, web exclusion and switching alternatives.
6. Neutral fast-step skip and rejection of failed observable Finish criteria.
7. Final cheap capture avoiding a bitmap.
8. Completion-before-bookkeeping ordering.
9. Single-skill loading/indexing.
10. Detached telemetry closure and immediate run-ID correction.
11. Busy callback-port fallback with real loopback sockets.
12. Optimized spinner-noise idle wait (approximately 255 ms in the last run).

**Limits of these smoke checks:** Java 17 was obtained using `jdk4py` and a standalone Kotlin 2.3.10
compiler using npm, outside the repository. The core compile used compile-only Moshi API stubs;
JSON/auth parsing paths were not exercised. The telemetry smoke used small Android Context,
preferences, clock and logging stand-ins, but the repository's real telemetry/store logic. These
checks do **not** substitute for the repository's pinned Kotlin/Gradle dependencies, JUnit/Robolectric,
Android compilation, or an emulator. Tools and scratch outputs are outside Git under
`/home/user/toolchains` and `/home/user/verification`.

## Blocked / still required — do not mark the audit complete

- Full `bash scripts/ci-preflight.sh`: guard 7 cannot reach Hugging Face model URLs in this sandbox.
  These are transport failures, not evidence that the model URLs are invalid.
- `./gradlew :core-agent:test :app:testDebugUnitTest --console=plain --stacktrace`: Gradle 9.3.1 cannot
  download from `services.gradle.org` (TLS/network restriction). No standard unit-suite pass exists.
- `./gradlew :app:lintDebug --console=plain`: blocked at the same Gradle download. No lint pass exists.
- Android app/test compilation and emulator/device execution: no Android SDK or emulator is present.
- Real JSON/SSE HTTP compatibility tests and the new/updated JUnit/Robolectric tests must run with
  the normal project dependencies.
- Run `AuditInteractionTest` with the software keyboard enabled. Verify gesture and three-button
  navigation separately, and check the top app bar in the real application.
- Device checks still required: optimized permanent spinner returns within the 400 ms policy cap;
  Baseline retains its 500 ms quiet/1000 ms cap; ambiguous apps open locally; correction is inline
  and nonblocking; completion appears before background bookkeeping finishes.

The implementation is saved, but required release verification remains incomplete.
