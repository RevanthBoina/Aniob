# Development workflow and maintainer checklist

This document describes the recommended lightweight workflow for Aniob. The official repository remains a founder-controlled public personal repository; ordinary contributors submit changes from forks and do not receive direct write/admin access.

## Issue to pull request

1. The contributor selects an open issue and comments that they want to work on it. The maintainer can label it `in progress` to reduce duplicate work; no registration form is needed.
2. Contributor forks the repository, branches from `main`, implements the task, runs relevant checks, and opens a PR targeting the official `main`.
3. GitHub Actions runs the existing CI workflow on the PR: preflight guards, `:core-agent:test`, `:app:testDebugUnitTest`, and `:app:lintDebug`.
4. The founder/maintainer reviews behavior, tests, scope, security/privacy impact, dependencies and (where applicable) the separate contribution agreement. The contributor addresses feedback in their fork branch.
5. The founder decides whether to merge. Contributors do not merge their own PRs or push to the official `main`.

Use `feature/*` for features, `bugfix/*` for fixes, and `docs/*` for documentation. Branch naming applies within the contributor's fork. Keep PRs focused; split unrelated work.

## Review checklist

- Does the change solve the linked issue without unrelated churn?
- Are tests added/updated and relevant existing tests passing? Are config/skill mirrors in sync?
- Does it preserve the pure JVM boundary of `core-agent`?
- Are Android permissions, accessibility behavior, safety prompts/approval, credential handling, data egress, and user privacy unchanged or explicitly justified?
- Are dependencies, model files, copied code, and assets identified with their terms and attribution?
- Do README/docs accurately match behavior? No secrets, real user data, or signing files included?
- Has the founder confirmed any separate rights agreement needed before commercial product incorporation?

## Automated checks

Existing `.github/workflows/ci.yml` is the pull-request validation path. It uses JDK 17 and the repository's checksum-pinned Gradle 9.3.1 Wrapper, then runs project preflight, JVM tests, and Android lint. Review the Action run and resolve failures before merging. A PR can still be reviewed if an external model URL check has a transient failure, but the founder should confirm the failure is unrelated and decide whether to rerun; do not silently bypass a security or test failure.

The release workflow is separate, tag-driven, and writes release artifacts. Do not use it as a PR check or grant contributors release permissions. Dependency review is not currently active: GitHub's dependency-review action reported that the repository Dependency Graph is disabled. The founder may enable it and add the action in a later change; this task does not modify repository security settings. A full dependency inventory/license scan is also not configured; arrange release SBOM/license review separately.

## Conflicts and updates

Contributors should fetch the latest upstream `main` and rebase their own feature branch when needed. Resolve conflicts carefully, rerun affected tests, and push with `--force-with-lease` only to the contributor's own rebased branch. Never rewrite or force-push official `main`. If a conflict is not clear, ask the reviewer for guidance.

## Main branch protection recommendation

The official repository is a personal repository with one owner, Revanth Boina. `.github/CODEOWNERS` requests his review for changes; it does not create an independent reviewer. GitHub does not allow the PR author to approve their own PR, so requiring one approval for every PR would also block Revanth's own PRs unless he uses a bypass or another reviewer is available.

In GitHub repository **Settings → Rules → Rulesets**, configure a ruleset targeting `main`:

- Require a pull request before merging; do not require an approval globally while Revanth is the sole owner/reviewer.
- Require the existing CI check to pass; choose its actual check name in GitHub and keep the required status check enabled.
- Require conversations to be resolved and block force pushes and branch deletion.
- Keep bypass rights limited to the founder/administrator. Do not add ordinary contributors as repository collaborators or bypass actors.

As the governance rule, Revanth reviews and decides on contributor PRs before merging them. The ruleset can enforce PRs and checks, but cannot technically require the sole owner to review their own PR. For a founder-authored PR, CI and self-review are the available safeguards unless an independent reviewer is involved. If the founder later chooses to require independent approvals, design an explicit owner-PR exception without granting ordinary contributors repository write access.

GitHub UI capabilities vary by plan; the founder should verify the configured rules with a test fork PR. `CODEOWNERS` requests review but does not enforce a ruleset. Only the founder should change repository settings; this PR does not modify them.

## Releases

The founder owns release decisions, version tags (`v*`), protected credentials, signing, release notes, and GitHub publication. Before a commercial or store release, use an explicitly configured private release key and make release builds fail closed if that key is absent. The current workflow/build configuration includes a debug-signing fallback; see the [repository audit](REPOSITORY_AUDIT.md). Do not distribute these artifacts as production signed builds until the founder reviews signing and dependency/model notices.
