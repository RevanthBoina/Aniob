# Aniob repository audit

**Audit basis:** the checked-out repository at `a3c40a41416348a560c48e1eac6dc1e57ff26589` (2026-11-05). This is a source-tree review; no Android device/emulator build was run in this environment. This audit describes the implementation present in that revision, not future roadmap claims.

## Executive summary

Aniob is a native Android application for natural-language UI automation. Its Android app coordinates task understanding, screen observation, action planning, safety checks, and execution through Android services and user-approved capabilities. The project separates most agent/domain logic into a Kotlin/JVM module so it can be tested without Android. It supports on-device model integration, an optional OpenAI-compatible cloud gateway, model/skill YAML assets, task history and metrics, and research/benchmark tooling.

The audited baseline had substantial automated tests and two existing GitHub Actions workflows, but lacked contributor-facing documentation and governance: no root README, CONTRIBUTING guide, Code of Conduct, SECURITY policy, volunteer/IP policy, contributor acknowledgements file, issue/PR templates, or CODEOWNERS. This PR adds those onboarding materials. No root license or copyright notice is present. This is a public repository, but it does not currently grant an explicit open-source license; do not infer permission to reuse or redistribute code from its public visibility.

## Structure and architecture

- `app/`: Android application (`com.aniob.app`), manifest/resources, Jetpack Compose interface, Android-specific services, device integrations, model/provider adapters, local data, Room database and Android-facing workflows.
- `core-agent/`: Kotlin/JVM agent and domain logic. It contains action/task contracts, intent routing, planning, safety and verification, screen perception abstractions, providers, skills, memory, telemetry, and workflow reducers. CI enforces that this module does not import `android.*`.
- `configs/`, `skill_library/`: source-of-truth agent/model/provider configuration and skill definitions. Copies are shipped from `app/src/main/assets/`; scripts compare those copies for drift. `android-agent/configs/` is an additional mirrored config location.
- `app/src/test/`, `core-agent/src/test/`: JVM unit tests (including Robolectric-backed app tests). `app/src/androidTest/`: instrumentation/benchmark and UI tests.
- `scripts/`: CI/preflight guards, configuration sync checks, model URL health check, research data helpers, and optional fine-tuning/export utilities.
- `docs/`: product architecture, runtime, QA, study, device capability, and setup notes; `AGENTS.md` captures maintainer/build invariants.
- `android-agent/`, root `prompts/`: planning and implementation prompt notes; not separate build modules.

## Technology stack and current capabilities

- Android application: Kotlin, Android Gradle Plugin `9.1.1`, Java 17, `compileSdk 35`, `minSdk 30`, `targetSdk 35`.
- UI: Jetpack Compose / Material 3.
- Agent: Kotlin/JVM module, Kotlin coroutines, Moshi.
- Persistence: Room and DataStore.
- Networking/cloud integration: OkHttp and Retrofit; user-configured OpenAI-compatible OmniRoute gateway.
- On-device model runtime: Google AI Edge LiteRT-LM (`litertlm-android`, version `0.16.0`) with downloadable model artifacts. Configuration currently lists Qwen2.5, Gemma 4 and Phi-4 Mini artifacts; each has separate model terms (Gemma uses Gemma terms, not an SPDX license identifier).
- Other declared runtime/test libraries include AndroidX, Google Material, AndroidX Security Crypto, JUnit 4, AndroidX test/espresso/UIAutomator, Robolectric, and Roborazzi. Version catalog entries include some unused or commented-out optional libraries; the catalog is not identical to the resolved dependency graph.
- Implemented app surfaces include chat/task submission, setup and permissions, model selection/download/import, settings, skills, task history, stats/progress, trust/safety information, and a task tracker. Device integrations include accessibility, notifications, foreground execution, overlays, deep links/shares, quick-settings entry points, and selected device commands. Some functions require the user to enable Android permissions/services and are device/OS dependent.
- Tasks can route through local/on-device and user-configured cloud providers. Cloud use is not an implicit hosted account: gateway address/key are user-supplied. The API key store uses Android encrypted preferences. Do not promise that every task is fully offline: provider choice, model availability, permissions, and task capability affect execution.

## Setup, build, and test

There is **no committed Gradle wrapper**. CI pins Gradle `9.3.1`, so local command-line contributors currently need that version installed in addition to JDK 17 and an Android SDK. Android Studio can configure the SDK and generate `local.properties`; keep it untracked. `google-services.json` is intentionally absent and the Google Services plugin is configured to warn when missing.

Current commands (from repository root, with JDK 17, Android SDK, and Gradle 9.3.1 installed):

```bash
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug --console=plain
gradle :app:assembleDebug --console=plain
```

A Gradle 9.3.1 wrapper candidate was generated from the official Gradle tag with a pinned distribution checksum cross-checked against independent GitHub-hosted references. A temporary PR revision invoked it in CI and reached the existing test failure, but the wrapper was removed from PR #26 to keep this onboarding change focused. It should be submitted and validated in a separate build-tooling PR; this sandbox has no Java runtime for local wrapper execution.

The debug APK build needs a local debug keystore. `AGENTS.md` documents a `keytool` command to create one; signing keys and passwords must never be committed. `bash scripts/ci-preflight.sh` runs project-specific guards, including model catalog URL checks that require working network access. Existing test inventory at audit time: 68 core-agent test classes/files, 19 app JVM test files, and 2 instrumentation test files (test counts are file counts, not test-case totals).

## Existing automation

- `.github/workflows/ci.yml`: runs for pull requests, pushes to `main`, and manual dispatch. Sets up Temurin 17 and Gradle 9.3.1, runs `scripts/ci-preflight.sh`, JVM tests, Android lint, and uploads test/lint reports.
- `.github/workflows/release.yml`: runs on `v*` tags or manual dispatch, runs preflight, builds a release APK, publishes artifacts/checksum, and creates a GitHub Release for a version tag. It uses `contents: write` for release publication and has a failure-diagnostics step that may post a commit comment.
- Existing scripts check billing references, config/skill asset drift, Android imports in `core-agent`, broad-catch count, selected behavior pins, user-facing copy, model catalog links, and tracked secret/build-output hygiene.
- No dependency vulnerability review is active in CI. A previous trial of GitHub's Dependency Review Action failed with a Dependency Graph prerequisite error. On 2026-11-05, however, the repository's Dependency Graph SBOM API returned SPDX 2.3 with six packages (the GitHub Actions and repository root), while the manifest list omitted the Gradle version catalog. Reconcile the differing observations in repository settings and confirm Gradle dependency detection before relying on either feature. This work does not alter repository security settings. Existing CI covers build/test/lint and project-specific PR checks, but CI does not establish copyright ownership or dependency licence compliance.

### Founder setup for GitHub security features

These settings are intentionally left for the sole repository owner to decide and enable:

1. **Dependency Graph:** in repository **Settings → Security → Code security and analysis**, confirm the current Dependency Graph setting and whether GitHub recognizes the Gradle manifests. The API currently returns an SBOM, but does not list the version catalog and the returned six-package inventory omits Android/Gradle libraries; do not treat that SBOM as a full app dependency inventory.
2. **Dependency review:** reconcile the earlier prerequisite error with current settings, then trial GitHub's Dependency Review Action in a separately reviewed workflow change with least-privilege permissions (`contents: read`, `pull-requests: read`). Test it on a dependency-changing PR before making the status check required. It reviews dependency changes; it is not a complete licence scan.
3. **Private vulnerability reporting:** enable it from the same settings page under **Private vulnerability reporting**. [SECURITY.md](../SECURITY.md) has the steps and reporting guidance.
4. **Dependency licence inventory:** this is not enabled by the Dependency Graph or Dependency Review Action. Before commercial release, generate a report/SBOM for the resolved runtime and test dependencies, inspect native components and notices, and separately verify downloaded model/data terms. Have the founder review it; no repository licence or dependency scanner is added here.

No repository settings were changed as part of this work.

## Security and ownership observations

Positive controls already present include ignored `.env` files and signing keys, `.env.example` with a placeholder (not a credential), a CI hygiene scan, encrypted storage for the cloud gateway credential, OTP masking/safety code, and review/approval-oriented task flows. Security-sensitive Android permissions are declared because app capabilities need them; reviewers should assess changes to permission scopes, data egress, model prompts, and user confirmation paths carefully. The public issue tracker must not be used to disclose vulnerabilities or real user data.

No repository-level license, copyright notice, contributor agreement, or signed contribution record was found. A pull request, public fork, or repository access does not itself assign copyright. Founder-created code, contributor-authored changes, a contributor's pre-existing material, downloaded model weights, and third-party dependencies remain distinct rights categories. Before integrating accepted volunteer work into a commercial product, the founder should arrange and apply a separate, appropriate contribution agreement; this repository documentation is not that agreement or legal advice.

## Dependency licence information and SBOM status

The Gradle version catalog identifies direct dependency coordinates and versions, but the repository has no resolved dependency lock, SBOM, or generated licence report. This audit therefore does **not** assign licence classifications to individual dependencies. A release inventory must check the exact resolved versions, transitive dependencies, Android/native components, and required notices against upstream metadata and licence texts.

The model catalog contains `license` labels for downloadable models, but those are repository metadata, not an independent verification of model terms. Model licences/terms are separate from Android/Gradle library licences and must be checked against each model publisher before redistribution or commercial use.

GitHub Dependency Review is useful for reviewing dependency changes in PRs after Dependency Graph is enabled; it is not a complete SBOM or licence clearance. The founder can enable the graph and trial the review action using the manual steps above. For a release inventory, choose and validate an SBOM tool (for example, a CycloneDX Gradle plugin) in a separate maintenance change, generate a report from the actual release dependency graph, and have the founder review component licences/notices and model terms. No scanner, SBOM plugin, or project licence was added by this PR.

## Gaps, technical concerns, and recommendations

1. **Contributor entry point:** addressed in this PR with a short README, fork/PR guide, issue forms, review ownership, and recognition guidance.
2. **Ownership terms not configured:** founder should obtain a suitable, short separate contribution agreement before accepting contributions for commercial incorporation. Do not make a form, PR, or access imply assignment.
3. **No published project license or dependency inventory:** do not advertise the code as open source; decide licensing separately and add verified notices only with founder approval. Generate an SBOM/license report as a release check.
4. **Gradle Wrapper:** not included in this onboarding PR. The version/checksum were investigated, but a verified wrapper should be implemented and tested in a separate build-tooling PR; current contributor instructions still require Gradle 9.3.1.
5. **Release signing needs founder attention:** the current build script falls back to a debug keystore/default values when a release keystore is absent, and the release workflow generates a debug keystore before building. Do not treat resulting APKs as production-signed; configure an explicit protected release signing setup and fail closed before any commercial/store release.
6. **CI has external network coupling:** the preflight model URL health test depends on remote model hosting. `scripts/check-catalog-urls.sh` now reports transport/network failures (including HTTP 000), 404s, access denials, and server errors separately; each still fails preflight rather than silently passing. The local sandbox returned curl exit 35 / HTTP 000 (`SSL_ERROR_SYSCALL`) for Hugging Face, so URL validity could not be assessed here; PR CI preflight passed. Consider separating deterministic checks from this remote health check only in a separately reviewed change.
7. **Dependency inventory and review remain incomplete:** the Dependency Graph SBOM endpoint responds, but its six packages omit Gradle/Android libraries, and Dependency Review is not active in CI. The founder should reconcile the settings/action prerequisite observations and confirm Gradle manifest coverage before enabling review; no settings were changed. A complete SBOM/licence report for the exact release graph is still needed.
8. **Contributor protections are not enforceable by files alone:** founder should enable a main-branch ruleset, keep administrator/bypass rights founder-controlled, require PRs and CI, and avoid collaborator grants. Since the founder is the sole owner and cannot approve their own PR, do not set a global mandatory-approval count unless an owner-PR exception or independent reviewer is planned. CODEOWNERS requests review; it does not configure GitHub protections.
9. **Confirm communications/contact details:** use the founder's GitHub profile until a dedicated security/contact address is selected; configure GitHub private vulnerability reporting if desired.
10. **Pre-existing CI/KSP diagnostic:** CI on `main` at the exact PR base commit `a3c40a41416348a560c48e1eac6dc1e57ff26589` already failed at the JVM unit-test job ([run #41877216464](https://github.com/RevanthBoina/Aniob/actions/runs/41877216464)); PR #26 fails at the same job and does not change app/KSP source, dependency versions, or tests. The reported `ApplicationManager.getApplication() is null` exception exactly matches [google/ksp#2763](https://github.com/google/ksp/issues/2763), which the official KSP 2.3.6 release notes say was fixed. Upstream describes it as a CI exception that does not break build checks, so the exception alone does not explain Aniob's nonzero job exit. Full Actions logs/test artifacts could not be retrieved from this environment; the exact cause of the failed exit remains unconfirmed. Recommended follow-up is a separate, minimal KSP 2.3.5→2.3.6 compatibility test, with full test reports and no disabled tests; tracked in [issue #27](https://github.com/RevanthBoina/Aniob/issues/27). GitHub also reports upcoming Node.js/action deprecation warnings for existing Actions v4 dependencies; update them in a separately validated maintenance change.

## Recommended next steps

- Publish short contributor-facing documents and lightweight issue/PR templates.
- Keep all contributions fork-based; have the founder review and merge accepted PRs.
- Configure a `main` ruleset and ensure Actions permissions are least-privilege.
- Decide separately on project licensing, contribution agreement, release signing, and contact channels.
- Keep tests, lint, and existing preflight checks; add new automation only after validating it against the real Gradle graph.
