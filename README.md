# Aniob

Aniob is a **founder-led commercial Android app** for carrying out selected phone tasks from natural-language requests. It combines an Android interface and device integrations with a Kotlin/JVM agent core for routing, planning, safety checks, action execution, and verification. Automation depends on device support, Android permissions, and available models/providers; it cannot perform every task on every device.

**Want to help?** You can report a bug, test a flow, improve docs, refine prompts or skills, or contribute code. Start with [CONTRIBUTING.md](CONTRIBUTING.md); no application or registration form is needed.

## What Aniob does

- Accepts task requests in a chat interface and shows execution progress, results, and history.
- Routes supported tasks through direct intents, skills, local/on-device models, or a user-configured OpenAI-compatible cloud provider.
- Uses Android accessibility and other explicitly enabled device capabilities for supported actions.
- Applies user confirmations, safety checks, and verification where implemented; permissions and capability checks are part of the flow.
- Supports model download/import, configurable skills, settings, and task metrics.

These are implemented areas, not guarantees that a particular task will work. Some features require the user to enable Android services or permissions. **This repository is proprietary (All rights reserved)**; see [LICENSE](LICENSE) and [CONTRIBUTING.md](CONTRIBUTING.md). Its public visibility is for viewing and evaluation only, and does not grant reuse rights.

## Get started

### Prerequisites

- JDK 17.
- Android SDK Platform 35; Aniob has `minSdk` 30, so a device or emulator must run Android 11/API 30 or newer to run it.
- Checked-in Gradle 9.3.1 wrapper with pinned SHA-256 (or Gradle 9.3.1 installed separately). Android Studio is recommended for installing/configuring the SDK and opening the project.

### Set up and build

1. Clone the repository:

   ```bash
   git clone https://github.com/RevanthBoina/Aniob.git
   cd Aniob
   ```

2. Open the project in Android Studio and allow it to configure the local Android SDK, or install Java 17 and configure `JAVA_HOME` and `ANDROID_HOME`/`ANDROID_SDK_ROOT` for command-line use. The checked-in Gradle 9.3.1 wrapper downloads and verifies the build toolchain. Keep the generated `local.properties` untracked.
3. Run the existing JVM tests and lint tasks:

   ```bash
   ./gradlew :core-agent:test :app:testDebugUnitTest --console=plain
   ./gradlew :app:lintDebug --console=plain
   ```

4. To package a debug APK, create a local debug keystore if one is not already present (the CI job uses this command):

   ```bash
   keytool -genkeypair -keystore debug.keystore -storepass android \
     -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
     -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

   Then assemble the debug variant:

   ```bash
   ./gradlew :app:assembleDebug --console=plain
   ```

To install and launch the debug variant, select the `app` run configuration in Android Studio, connect an API 30+ device or emulator, and choose **Run**. The debug key is only for local development. Never commit it, other signing keys, credentials, `.env`, provider API keys, downloaded model files, or `local.properties`. `google-services.json` is optional for the current build; the plugin is configured to warn when it is absent. Cloud-provider configuration is user-supplied; see [OmniRoute setup](docs/OMNIROUTE_SETUP.md).

The test and lint task selectors above are the same ones configured in [CI](.github/workflows/ci.yml). The `assembleDebug` command is the Android packaging task; this sandbox could not run Gradle locally, so see [Roadmap and known issues](#roadmap-and-known-issues) for the current CI blocker. `bash scripts/ci-preflight.sh` runs additional project checks, including remote model URL checks that require network access.

## Architecture

The Gradle build contains two modules, declared in [`settings.gradle.kts`](settings.gradle.kts):

| Module | Responsibility | Main dependencies / boundary |
| --- | --- | --- |
| `:app` | Android application: Compose UI, `AniobViewModel`, Android services/accessibility integration, provider/model adapters, persistence and device-facing workflow execution. | Depends on `:core-agent`; AndroidX/Compose, Room, DataStore, Retrofit/OkHttp, LiteRT-LM and Android security APIs. This is the platform boundary. |
| `:core-agent` | Kotlin/JVM task, action and workflow models; routing/planning, skills, safety and verification logic. Its tests run without an Android device. | Kotlin/JVM with coroutines and Moshi; no `android.*` imports. The repository preflight checks this boundary. |

The root Gradle build supplies plugin configuration and [`gradle/libs.versions.toml`](gradle/libs.versions.toml) centralizes dependency versions. `configs/` and `skill_library/` are source files mirrored into app assets; CI checks those copies. `prompts/` and `android-agent/` contain prompt/specification material, not additional Gradle modules. JVM tests live under `core-agent/src/test` and `app/src/test`; device instrumentation tests live under `app/src/androidTest` and require an emulator/device. Current CI runs the JVM test tasks and lint, not instrumentation tests.

**Typical task flow:** `AniobChatScreen` submits text to `AniobViewModel.submitTask`. The core `WorkflowRouter` handles supported direct workflows or delegates to the existing automation path. `AniobExecutionRouter` selects among available direct intents, fast paths, skills, and local/cloud model providers; `StepPipeline` coordinates each proposed action with the app's safety, capability, and approval checks. The Android adapters dispatch supported actions, verification feeds the outcome back to the UI, and app state/history are updated. `docs/APP_DISCOVERY_AND_LAUNCH.md` describes the app-launch path in more detail.

## Roadmap and known issues

| Status | Work |
| --- | --- |
| **Released** | The latest published release is [v1.0.4](https://github.com/RevanthBoina/Aniob/releases/tag/v1.0.4), which added the app-discovery and launch layer to the UI-automation flow. See the release notes for its scope. |
| **Resolved / CI toolchain** | The KSP 2.3.5 JVM test / headless CI issue ([upstream KSP issue #2763](https://github.com/google/ksp/issues/2763)) and Android lint hardware declaration check are resolved with KSP 2.3.6 and the telephony `<uses-feature>` declaration. All preflight, JVM unit tests, and lint pass cleanly in CI. |
| **Planned features** | No additional feature work is currently filed or approved as a roadmap item. New proposals can be submitted with the [feature-request form](https://github.com/RevanthBoina/Aniob/issues/new?template=feature_request.yml); issues are proposals, not delivery promises. |

The open issue list is the source for new tracked work: [open Aniob issues](https://github.com/RevanthBoina/Aniob/issues). Android automation is permission- and device-dependent, and the remote URL preflight needs network access.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution paths, setup, review, and commercial-project terms. Regular changes come from forks and pull requests; Revanth Boina retains final review and merge control. For sensitive security reports, use [SECURITY.md](SECURITY.md), not a public issue. Aniob follows its [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

Copyright (c) 2026 Revanth Boina. All rights reserved.

This repository is made available for viewing and evaluation only.
No licence is granted to copy, modify, distribute or create derivative works.
Contributions are accepted under the terms in [CONTRIBUTING.md](CONTRIBUTING.md). See [LICENSE](LICENSE).
