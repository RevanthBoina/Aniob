# Aniob

Aniob is an Android app that lets people ask for tasks in natural language and coordinates Android UI automation with planning, safety checks, and verification. The repository is a Kotlin Android project with a separate JVM-testable agent core. Aniob is under active development; capabilities depend on the device, Android permissions, and available models/providers.

## What is implemented

- Chat-based task requests, task tracking, history, and usage/progress views.
- Agent planning, screen observation, action routing, verification, and user-confirmation flows.
- Android integrations for accessibility-based interaction, notifications, foreground work, overlays, shares/deep links, and selected device commands. Android requires users to enable applicable permissions/services.
- On-device model integration and model download/import flows, plus a user-configured OpenAI-compatible cloud gateway option.
- Configurable skills, settings, and trust/safety information.

These are implemented surfaces, not a guarantee that every task or Android device is supported. See [the repository audit](docs/REPOSITORY_AUDIT.md) and the product/technical notes in [`docs/`](docs/).

## Technology

Kotlin, Android Gradle Plugin 9.1.1, Java 17, Android SDK (compile/target 35; minimum Android API 30), Jetpack Compose/Material 3, and a pure Kotlin/JVM `core-agent` module. Persistence uses Room and DataStore; networking uses Retrofit/OkHttp; on-device inference integrates LiteRT-LM. Dependencies and model artifacts have separate upstream terms. **No project-level open-source license is currently provided.**

## Get started

### Requirements

- Android Studio with Android SDK Platform 35 and build tools installed.
- JDK 17.
- Gradle 9.3.1 installed separately. A Gradle wrapper is not currently committed.
- Android phone or emulator for running the app; `minSdk` is 30.

Clone the repository and enter it:

```bash
git clone https://github.com/RevanthBoina/Aniob.git
cd Aniob
```

Open the project in Android Studio to configure the local SDK (Android Studio creates `local.properties`, which is ignored). Or use command-line Gradle after configuring `ANDROID_HOME`/`ANDROID_SDK_ROOT` and installing Gradle 9.3.1:

```bash
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug --console=plain
gradle :app:assembleDebug --console=plain
```

A debug APK build may need a local debug keystore. Generate it only for local development with:

```bash
keytool -genkeypair -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Never commit signing keys, passwords, `.env`, API keys, or `local.properties`. `.env.example` is a placeholder template, not a real credential. `google-services.json` is not required for the current build; the build is configured to warn if it is absent. The optional cloud gateway setup is documented in [`docs/OMNIROUTE_SETUP.md`](docs/OMNIROUTE_SETUP.md).

For project-specific checks, run `bash scripts/ci-preflight.sh`. It includes remote model URL checks and therefore needs network access. See [`AGENTS.md`](AGENTS.md) for architecture invariants and additional development notes.

## Project structure

```text
app/                 Android application, UI, Android integrations, persistence and app tests
core-agent/          Platform-independent Kotlin agent/domain logic and JVM tests
configs/             Source configuration mirrored into app assets
skill_library/       Source skill definitions mirrored into app assets
docs/                Architecture, QA, setup, study, audit and contributor documentation
scripts/             CI/preflight, sync and research utilities
.github/workflows/   Existing CI and tagged-release workflows
```

## Contributing

Student developers and volunteers are welcome. Contributions use **GitHub forks and pull requests**; ordinary contributors are not direct collaborators on the founder-controlled repository. Read [CONTRIBUTING.md](CONTRIBUTING.md), pick an available issue, comment that you would like to work on it, then fork, make a focused change, and open a PR. No application form is required. A PR does not transfer copyright; commercial incorporation of accepted contributions requires the separate agreement described in [IP_POLICY.md](IP_POLICY.md).

## Contact and project status

Repository owner and project founder: [Revanth Boina](https://github.com/RevanthBoina). Use GitHub issues for ordinary bugs/features. For a security concern, follow [SECURITY.md](SECURITY.md) and do not post exploit details or credentials publicly. The repository is public and founder-led; no organization or repository ownership transfer is planned.
