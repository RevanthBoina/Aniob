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

At the audited base commit, there was **no committed Gradle wrapper**; CI pinned Gradle `9.3.1`, and contributors had to install it separately. This PR adds the official Gradle Wrapper scripts/JAR from the `gradle/gradle` `v9.3.1` tag and pins the distribution URL and SHA-256 (`b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`) in `gradle/wrapper/gradle-wrapper.properties`. The distribution hash was cross-checked against JetBrains Android's Gradle SHA-256 catalog and Qt's build scripts; the wrapper JAR Git blob SHA from the official tag is `61285a659d17295f1de7c53e24fdf13ad755c379`. This wrapper addition is isolated in its own build-tooling commit in PR #26. Contributors still need JDK 17 and an Android SDK; Gradle itself is downloaded and checksum-verified on first use. Android Studio can configure the SDK and generate `local.properties`; keep it untracked. `google-services.json` is intentionally absent and the Google Services plugin is configured to warn when missing.

Current commands (from repository root, with JDK 17 and Android SDK):

```bash
./gradlew :core-agent:test :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
./gradlew :app:assembleDebug --console=plain
```

The debug APK build needs a local debug keystore. `AGENTS.md` documents a `keytool` command to create one; signing keys and passwords must never be committed. `bash scripts/ci-preflight.sh` runs project-specific guards, including model catalog URL checks that require working network access. Existing test inventory at audit time: 68 core-agent test classes/files, 19 app JVM test files, and 2 instrumentation test files (test counts are file counts, not test-case totals).

## Existing automation

- `.github/workflows/ci.yml`: runs for pull requests, pushes to `main`, and manual dispatch. Sets up Temurin 17, uses the checksum-pinned `./gradlew` wrapper, runs `scripts/ci-preflight.sh`, JVM tests, Android lint, and uploads test/lint reports.
- `.github/workflows/release.yml`: runs on `v*` tags or manual dispatch, runs preflight, builds a release APK, publishes artifacts/checksum, and creates a GitHub Release for a version tag. It uses `contents: write` for release publication and has a failure-diagnostics step that may post a commit comment.
- Existing scripts check billing references, config/skill asset drift, Android imports in `core-agent`, broad-catch count, selected behavior pins, user-facing copy, model catalog links, and tracked secret/build-output hygiene.
- No dependency vulnerability/license review is active in CI. A trial of GitHub's dependency-review action on the review PR failed because Dependency Graph is not enabled for this repository. This work does not alter repository security settings; the founder can enable the graph and add the review action later if desired. Existing CI covers build/test/lint and project-specific PR checks, but CI does not establish copyright ownership or dependency license compliance.

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

1. **Contributor entry point:** addressed in this PR with a short README, fork/PR guide, issue forms, review ownership, and recognition guidance.
2. **Ownership terms not configured:** founder should obtain a suitable, short separate contribution agreement before accepting contributions for commercial incorporation. Do not make a form, PR, or access imply assignment.
3. **No published project license or dependency inventory:** do not advertise the code as open source; decide licensing separately and add verified notices only with founder approval. Generate an SBOM/license report as a release check.
4. **Gradle Wrapper:** addressed in this PR's isolated build-tooling commit. Keep the wrapper scripts/JAR aligned with the selected Gradle release and update the verified distribution SHA-256 on future version bumps.
5. **Release signing needs founder attention:** the current build script falls back to a debug keystore/default values when a release keystore is absent, and the release workflow generates a debug keystore before building. Do not treat resulting APKs as production-signed; configure an explicit protected release signing setup and fail closed before any commercial/store release.
6. **CI has external network coupling:** the preflight model URL health test depends on remote model hosting. Decide whether transient upstream outages should block all PRs; if not, separate deterministic checks from optional network health checks in a reviewed future change.
7. **Dependency license inventory remains incomplete:** the repository's Dependency Graph is currently disabled, so the GitHub dependency-review action cannot run. The founder may enable the graph in repository settings and add vulnerability review after deciding; this task does not change security settings. A full SBOM/license report for the exact release graph is also still needed.
8. **Contributor protections are not enforceable by files alone:** founder should enable a main-branch ruleset, keep administrator/bypass rights founder-controlled, require PRs and CI, and avoid collaborator grants. Since the founder is the sole owner and cannot approve their own PR, do not set a global mandatory-approval count unless an owner-PR exception or independent reviewer is planned. CODEOWNERS requests review; it does not configure GitHub protections.
9. **Confirm communications/contact details:** use the founder's GitHub profile until a dedicated security/contact address is selected; configure GitHub private vulnerability reporting if desired.
10. **Pre-existing CI/KSP failure:** CI on `main` at the exact PR base commit `a3c40a41416348a560c48e1eac6dc1e57ff26589` already failed at the JVM unit-test job ([run #41877216464](https://github.com/RevanthBoina/Aniob/actions/runs/41877216464)); the PR's test job fails in the same place. The PR contains no app/KSP source, dependency-version, or test changes. The observed exception is `Cannot invoke ... Application.getService(...)` because KSP's `ApplicationManager.getApplication()` was null. This establishes that the failure predates the PR; the precise toolchain/plugin root cause is still unconfirmed. Investigate the Kotlin `2.2.10` / KSP `2.3.5` / AGP `9.1.1` / Gradle `9.3.1` interaction. Tests have not been skipped or disabled. GitHub also reports upcoming Node.js/action deprecation warnings for existing Actions v4 dependencies; update them in a separately validated maintenance change.

## Recommended next steps

- Publish short contributor-facing documents and lightweight issue/PR templates.
- Keep all contributions fork-based; have the founder review and merge accepted PRs.
- Configure a `main` ruleset and ensure Actions permissions are least-privilege.
- Decide separately on project licensing, contribution agreement, release signing, and contact channels.
- Keep tests, lint, and existing preflight checks; add new automation only after validating it against the real Gradle graph.
