# Contributing to Aniob

Thanks for considering a contribution. Aniob is founder-led and welcomes student and volunteer contributions through **forks and pull requests**. No application form or direct repository access is needed.

## Quick path

1. Browse [open issues](https://github.com/RevanthBoina/Aniob/issues). Comment on one to say you would like to work on it; wait for a reply if the issue may already be in progress.
2. Fork `RevanthBoina/Aniob` on GitHub.
3. Clone your fork and add the original repository as `upstream`:

   ```bash
   git clone https://github.com/YOUR-USERNAME/Aniob.git
   cd Aniob
   git remote add upstream https://github.com/RevanthBoina/Aniob.git
   ```

4. Create a focused branch from current `main`:

   ```bash
   git fetch upstream
   git switch -c feature/short-description upstream/main
   # use bugfix/short-description or docs/short-description as appropriate
   ```

5. Make a small, focused change. Preserve existing behavior and follow architecture notes in [`AGENTS.md`](AGENTS.md). Do not commit secrets, signing keys, user data, or generated model files.
6. Run relevant checks (see below), then commit and push your branch to **your fork**:

   ```bash
   git add path/to/changed/files
   git commit -m "Describe the change"
   git push -u origin feature/short-description
   ```

7. Open a Pull Request from your fork branch into `RevanthBoina/Aniob:main`. Explain what/why/how tested and link the issue if applicable. The short template is there to help, not to create a long application.

## Build and tests

Install JDK 17, Android SDK Platform 35, and Gradle 9.3.1. There is no Gradle wrapper. From the project root:

```bash
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug --console=plain
```

If your change affects app packaging, also run `gradle :app:assembleDebug --console=plain`. Debug packaging may require a locally generated `debug.keystore` (see README); never add it to Git. Run `bash scripts/ci-preflight.sh` for repository-specific checks; its model catalog URL check requires network access. Android instrumentation tests in `app/src/androidTest` require an emulator/device and are not part of the current CI test command.

When changing configs or skills, update the source files and mirrored app assets together; CI checks for drift. Keep `core-agent` free of Android imports. Do not add an unreviewed dependency or change permissions, data egress, safety gates, model terms, or release signing casually—explain the need in the PR.

## Review and merging

The founder/maintainer reviews pull requests. CI runs preflight checks, core/app JVM tests, and Android lint. Review may request clarification, tests, or changes; respond in the PR and push follow-up commits to the same branch. Keep the PR focused and resolve requested review conversations. Please do not merge your own PR or push to the official repository's `main` branch; only the founder merges accepted changes.

If `main` moves while you work, update your fork and rebase your feature branch (or merge upstream `main` if needed):

```bash
git fetch upstream
git rebase upstream/main
# resolve files marked by Git, then:
git add <resolved-files>
git rebase --continue
git push --force-with-lease
```

Use `--force-with-lease` only on your own feature branch after a rebase; never force-push `main`. If unsure, ask for help in the PR rather than overwriting work.

## Branches and releases

Use `feature/*` for features, `bugfix/*` for fixes, and `docs/*` for documentation. These are naming suggestions for branches in your own fork; all PRs target the founder-controlled `main`. The founder manages version tags (`v*`), release checks, signing, and GitHub releases. Contributors should not publish releases or change signing configuration.

For participation expectations and the separate contribution-rights arrangement, see [VOLUNTEER_POLICY.md](VOLUNTEER_POLICY.md) and [IP_POLICY.md](IP_POLICY.md). A PR or fork alone does not transfer copyright.
