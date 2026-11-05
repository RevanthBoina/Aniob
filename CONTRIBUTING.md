# Contributing to Aniob

Thanks for your interest. Aniob is a public, founder-led **commercial project**; it has no project-level open-source licence. Contributions are voluntary and can offer practical learning, collaboration, and portfolio experience. No payment, employment, equity, certificate, or other benefit is promised. There is no application form, registration process, or need to share personal information.

## Ways to contribute

| Contribution | A simple first step |
| --- | --- |
| **Report a bug** | Open a [bug report](.github/ISSUE_TEMPLATE/bug_report.yml) with the reproduction steps and versions you know. Remove secrets and personal data. |
| **Test the app** | Run the relevant checks in the [README setup guide](README.md#get-started) or try a manual flow from [`docs/QA_CHECKLIST.md`](docs/QA_CHECKLIST.md). Report what happened and the device/Android version if relevant. |
| **Improve documentation** | Fix an unclear setup step, explain behavior, or add a focused example. |
| **Improve prompts or skills** | Runtime prompt/config files are in `configs/`; skill sources are in `skill_library/skills/`. Update their copies under `app/src/main/assets/` too; preflight checks config/skill drift. The root `prompts/` and `android-agent/prompts/` directories hold additional prompt/spec notes. |
| **Write or improve code** | Fix an issue, add a focused test, or improve Android or platform-independent agent behavior. Keep `core-agent` free of Android imports. |
| **Suggest a feature** | Use the short [feature request form](https://github.com/RevanthBoina/Aniob/issues/new?template=feature_request.yml); a proposal is not a commitment or delivery promise. |

Small, focused contributions and questions are welcome. You do not have to claim an issue before reporting a bug or opening a documentation fix.

## The contribution flow

1. Check [open issues](https://github.com/RevanthBoina/Aniob/issues), or file a bug/feature report. For larger work, comment on the issue first to agree on scope and avoid duplicate effort.
2. Fork Aniob, create a branch in your fork, and make a focused change. Do not request direct repository write access.
3. Run the relevant checks below, then open a pull request to `main`. Describe the change and what you tested; say plainly if a check could not be run.
4. Revanth Boina reviews contributions and retains final merge control. Address review feedback in your fork. See the [Code of Conduct](CODE_OF_CONDUCT.md).

## Setup and checks

Follow the prerequisites, build/test commands, and Android Studio run steps in the README's [Get started](README.md#get-started) section. Before opening a PR, run the relevant checks or state which ones you could not run. The CI workflow is the source of truth for pull-request checks; its preflight includes remote model URL checks and needs network access. JVM tests, Android unit tests, lint, packaging, and device instrumentation have different requirements. See the README roadmap for the known CI test issue.

## Commercial project and contribution rights

Aniob is not offered under an open-source licence. A public fork, issue discussion, or pull request does not transfer copyright or grant a licence to reuse Aniob. The founder's code, your pre-existing work, your contribution, and third-party materials remain distinct.

If work is intended for commercial incorporation, the founder will explain the proposed terms **before that work starts**. Accepted work intended for incorporation may require a separate written agreement, including an agreed intellectual-property assignment, **before incorporation**. The pull request itself is not that agreement. You can review the terms, ask questions, and decline; your pre-existing intellectual property remains yours unless you separately agree otherwise in writing.

Identify any third-party code, assets, models, or data you include, and provide their original licence or terms. A contribution agreement cannot change a third party's rights. Accepted work receives normal GitHub author attribution; release acknowledgements may also mention work at the maintainer's discretion. Aniob does not maintain a separate contributor roster. Any additional opportunities, if available, will be communicated separately.

## Security and privacy

Do not include API keys, passwords, keystores, real user records, private device data, or sensitive personal information in issues or pull requests. For a vulnerability, follow [SECURITY.md](SECURITY.md) and do not post details publicly. The founder controls repository settings and merges; reviews and CI do not replace careful handling of permissions, confirmations, secrets, and user data.
