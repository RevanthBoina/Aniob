# Aniob repository audit

**Audit basis:** the checked-out repository at `a3c40a41416348a560c48e1eac6dc1e57ff26589` (2026-11-05). This is a source-tree review; no Android device/emulator build was run in this environment. This audit describes the implementation present in that revision, not future roadmap claims.

## Executive summary

Aniob is a native Android application for natural-language UI automation. Its Android app coordinates task understanding, screen observation, action planning, safety checks, and execution through Android services and user-approved capabilities. The project separates most agent/domain logic into a Kotlin/JVM module so it can be tested without Android. It supports on-device model integration, an optional OpenAI-compatible cloud gateway, model/skill YAML assets, task history and metrics, and research/benchmark tooling.

The repository has substantial automated tests and two existing GitHub Actions workflows. Contributor-facing documentation and governance are missing: there is no root README, CONTRIBUTING guide, Code of Conduct, SECURITY policy, volunteer/IP policy, contributor acknowledgements file, issue/PR templates, or CODEOWNERS. No root license or copyright notice is present. This is a public repository, but it does not currently grant an explicit open-source license; do not infer permission to reuse or redistribute code from its public visibility.

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

There is **no committed Gradle wrapper**. Maintainer instructions and CI pin Gradle `9.3.1` and JDK 17. A local Android SDK is also required. Android Studio can configure the SDK and generate `local.properties`; keep it untracked. `google-services.json` is intentionally absent and the Google Services plugin is configured to warn when missing.

Typical commands (from repository root, with JDK 17, Android SDK, and Gradle 9.3.1 installed):

```bash
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug --console=plain
gradle :app:assembleDebug --console=plain
```

The debug APK build needs a local debug keystore. `AGENTS.md` documents a `keytool` command to create one; signing keys and passwords must never be committed. `bash scripts/ci-preflight.sh` runs project-specific guards, including model catalog URL checks that require working network access. Existing test inventory at audit time: 68 core-agent test classes/files, 19 app JVM test files, and 2 instrumentation test files (test counts are file counts, not test-case totals).

## Existing automation

- `.github/workflows/ci.yml`: runs for pull requests, pushes to `main`, and manual dispatch. Sets up Temurin 17 and Gradle 9.3.1, runs `scripts/ci-preflight.sh`, JVM tests, Android lint, and uploads test/lint reports.
- `.github/workflows/release.yml`: runs on `v*` tags or manual dispatch, runs preflight, builds a release APK, publishes artifacts/checksum, and creates a GitHub Release for a version tag. It uses `contents: write` for release publication and has a failure-diagnostics step that may post a commit comment.
- Existing scripts check billing references, config/skill asset drift, Android imports in `core-agent`, broad-catch count, selected behavior pins, user-facing copy, model catalog links, and tracked secret/build-output hygiene.
- This contributor-readiness change adds GitHub's dependency-review action for pull requests with a high-severity vulnerability threshold. It relies on GitHub's dependency graph and does not produce a complete license inventory/SBOM. Existing CI covers build/test/lint and project-specific PR checks, but CI does not establish copyright ownership or dependency license compliance.

## Security and ownership observations

Positive controls already present include ignored `.env` files and signing keys, `.env.example` with a placeholder (not a credential), a CI hygiene scan, encrypted storage for the cloud gateway credential, OTP masking/safety code, and review/approval-oriented task flows. Security-sensitive Android permissions are declared because app capabilities need them; reviewers should assess changes to permission scopes, data egress, model prompts, and user confirmation paths carefully. The public issue tracker must not be used to disclose vulnerabilities or real user data.

No repository-level license, copyright notice, contributor agreement, or signed contribution record was found. A pull request, public fork, or repository access does not itself assign copyright. Founder-created code, contributor-authored changes, a contributor's pre-existing material, downloaded model weights, and third-party dependencies remain distinct rights categories. Before integrating accepted volunteer work into a commercial product, the founder should arrange and apply a separate, appropriate contribution agreement; this repository documentation is not that agreement or legal advice.

## Dependency license information and limitations

The repository contains a Gradle version catalog, not a resolved dependency lock/SBOM or generated license report. The following is a **triage of declared components by their commonly published upstream terms**, not a legal clearance of the exact resolved graph:

| Declared component families | Common upstream license/terms (verify exact artifacts and transitive dependencies) |
| --- | --- |
| AndroidX, Jetpack Compose, Room, Kotlin, kotlinx.coroutines, Google Material Components, Retrofit, OkHttp, Moshi, Android Gradle/Kotlin plugins and related Google plugins | Generally Apache License 2.0 |
| JUnit 4 | Eclipse Public License 1.0 |
| Robolectric | GitHub license metadata reports `NOASSERTION` for the upstream repository; verify the versioned artifact and its notices before distribution. |
| Roborazzi | Apache License 2.0 |
| LiteRT-LM / Google AI Edge components | Check the exact artifact distribution and included native components' notices before release; do not rely solely on a group name |
| Downloadable model files | Per-model terms differ: current config labels Qwen2.5 Apache-2.0, Phi-4 Mini MIT, and Gemma 4 Gemma terms. Model terms are separate from application/library licenses. |

The catalog also declares optional/inactive libraries; the exact resolved app graph must be generated before a release. Recommended follow-up: generate a dependency/SBOM and third-party notices for the exact release variant, verify any native/model data licenses and attribution obligations, then have the founder review before distribution. No license file has been added as part of this onboarding work.

## Gaps, technical concerns, and recommendations

1. **No contributor entry point:** add a short README, fork/PR guide, issue forms, review ownership, and simple recognition guidelines.
2. **Ownership terms not configured:** founder should obtain a suitable, short separate contribution agreement before accepting contributions for commercial incorporation. Do not make a form, PR, or access imply assignment.
3. **No published project license or dependency inventory:** do not advertise the code as open source; decide licensing separately and add verified notices only with founder approval. Generate an SBOM/license report as a release check.
4. **No wrapper:** pinning the Gradle distribution in CI reduces variance there, but local setup is less reproducible. Consider committing a verified Gradle wrapper in a separate change after validating the project and update process.
5. **Release signing needs founder attention:** the current build script falls back to a debug keystore/default values when a release keystore is absent, and the release workflow generates a debug keystore before building. Do not treat resulting APKs as production-signed; configure an explicit protected release signing setup and fail closed before any commercial/store release.
6. **CI has external network coupling:** the preflight model URL health test depends on remote model hosting. Decide whether transient upstream outages should block all PRs; if not, separate deterministic checks from optional network health checks in a reviewed future change.
7. **Dependency license inventory remains incomplete:** CI now runs GitHub's dependency-review action for high-severity dependency changes on PRs, assuming the repository dependency graph is available. This does not generate a complete SBOM or license report; add those after confirming tool compatibility and review the exact release graph.
8. **Contributor protections are not enforceable by files alone:** founder should enable a main-branch ruleset, keep administrator/bypass rights founder-controlled, require CI and review, and avoid collaborator grants. CODEOWNERS requests review; it does not configure GitHub protections.
9. **Confirm communications/contact details:** use the founder's GitHub profile until a dedicated security/contact address is selected; configure GitHub private vulnerability reporting if desired.

## Recommended next steps

- Publish short contributor-facing documents and lightweight issue/PR templates.
- Keep all contributions fork-based; have the founder review and merge accepted PRs.
- Configure a `main` ruleset and ensure Actions permissions are least-privilege.
- Decide separately on project licensing, contribution agreement, release signing, and contact channels.
- Keep tests, lint, and existing preflight checks; add new automation only after validating it against the real Gradle graph.
