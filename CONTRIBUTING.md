# Contributing to Aniob

Welcome! Aniob is a founder-led project and student developers are welcome to contribute through **forks and pull requests**. You do not need to fill out an application or share personal details to get started.

## Your first contribution

1. **Open Aniob.** Read the [README](README.md) to learn what the app does and how to set up the project.
2. **Choose an available issue** that interests you. Small first contributions are welcome.
3. **Comment on the issue** that you would like to work on it. If someone may already be working on it, wait for a quick reply to avoid duplicated effort.
4. **Fork the repository** on GitHub using the Fork button.
5. **Clone your fork and create a branch:**

   ```bash
   git clone https://github.com/YOUR-USERNAME/Aniob.git
   cd Aniob
   git remote add upstream https://github.com/RevanthBoina/Aniob.git
   git fetch upstream
   git switch -c feature/short-description upstream/main
   # For fixes, use bugfix/short-description; for docs, docs/short-description.
   ```

6. **Make your changes.** Keep them focused, preserve existing behavior, and follow [`AGENTS.md`](AGENTS.md). Run the relevant tests below. Do not commit secrets, signing keys, personal data, or downloaded model files.
7. **Push your branch to your fork and open a Pull Request** to `RevanthBoina/Aniob:main`. Briefly explain what changed, why, how you tested it, and link the issue if useful. The short PR template is there to help, not to create a questionnaire.
8. **Receive review and feedback.** The founder reviews contribution PRs, may suggest improvements, and decides whether to accept and merge them. You can ask questions or update your fork branch during review.

## Build and tests

Install JDK 17, Android SDK Platform 35, and Gradle 9.3.1. There is no Gradle wrapper yet. From the project root:

```bash
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug --console=plain
```

If your change affects app packaging, also run `gradle :app:assembleDebug --console=plain`. Debug packaging may need a local `debug.keystore` (see README); never add it to Git. Run `bash scripts/ci-preflight.sh` for repository checks; the model URL check requires network access. Android instrumentation tests in `app/src/androidTest` need an emulator/device and are not part of the current CI test command.

When changing configs or skills, update their source files and mirrored app assets together; CI checks for drift. Keep `core-agent` free of Android imports. Explain any proposed dependency, permission, data-egress, safety, model-term, or release-signing changes in the PR.

## Contribution agreement

Most onboarding is just choosing an issue and using a fork and PR—there is no application form. If the founder intends a task for commercial integration, they will share the short written contribution agreement and explain its terms before you start that work. You can ask questions and decide whether to proceed. See [IP_POLICY.md](IP_POLICY.md) for the plain-language summary; submitting a PR alone does not transfer copyright.

## Branches and releases

Use `feature/*` for features, `bugfix/*` for fixes, and `docs/*` for documentation. These are naming suggestions for branches in your own fork; all PRs target the founder-controlled `main`. The founder manages version tags (`v*`), release checks, signing, and GitHub releases. Contributors do not need direct repository write access.
