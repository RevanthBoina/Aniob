# Security policy

Aniob handles Android accessibility and device capabilities, user-configured provider credentials, model downloads, and potentially sensitive task content. Please help keep users and contributors safe.

## Report a vulnerability privately

**Do not open a public issue or pull request with exploit steps, credentials, personal data, or an unpatched vulnerability.** When private vulnerability reporting is enabled, use the repository's **Security** tab and choose **Report a vulnerability** / **New advisory**. If the option is not available, contact repository owner [Revanth Boina](https://github.com/RevanthBoina) through GitHub and ask for a private reporting channel; share technical details only after a private channel is established.

### Founder: enable private vulnerability reporting

Only the repository owner or an administrator can enable this repository setting. Revanth can do this manually:

1. Open [Aniob repository settings](https://github.com/RevanthBoina/Aniob/settings/security_analysis) while signed in with an account that administers the repository.
2. In **Security → Code security and analysis**, find **Private vulnerability reporting** and select **Enable**.
3. Confirm the change, then verify the **Security** tab offers a private vulnerability-reporting option. GitHub's UI labels may vary slightly; see [GitHub's setup instructions](https://docs.github.com/en/code-security/security-advisories/working-with-repository-security-advisories/configuring-private-vulnerability-reporting-for-a-repository).

This is an owner-only manual setup step; contributors should not be asked to enable it or granted repository access for it. Until enabled, use the private-contact fallback above and do not publish sensitive details.

Include only what is needed to assess the issue: affected version/commit, Android/API level where relevant, impact, concise reproduction steps, and a suggested mitigation if known. Do not include real user records, API keys, or unrelated personal information. The maintainer will acknowledge the report, investigate, coordinate a fix and disclosure, and avoid disclosing reporter identity without consent where reasonably possible. There is no guaranteed response-time or bounty commitment.

For ordinary non-sensitive bugs, use the public issue template.

## Safe secret handling

- Never commit `.env`, provider/API keys, passwords, private keys, keystores, `local.properties`, or signed APKs. `.env.example` is a placeholder only.
- Store local values in ignored files or a secure secret manager. For Actions, use GitHub Actions secrets only when a workflow needs them; use least-privilege permissions and do not echo secrets.
- Rotate a credential immediately if it was committed or exposed. Removing it in a later commit does not make it secret again; tell the maintainer privately and follow the provider's revocation process.
- Keep test data synthetic. Redact screenshots, logs, prompts, account details, phone numbers, and notification content before sharing.
- Do not weaken permission checks, user confirmations, data minimization, or secret-storage behavior to make a test pass.

## Supported versions

There is no formal release support matrix yet. Please report issues against the latest `main` or a published tagged release and include its version/commit. Security fixes are handled by the founder-led maintainer.
