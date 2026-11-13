# Android conversations, retrospective audit and capability-grounded learning

This extends the Android implementation; there is no parallel Python runtime or replacement
model router. Existing saved chats, provider preferences, model defaults, safety gates, and
verified-learning paths remain in place. No model availability is asserted by a model ID alone.

## Commands

- `/new [full title]`: preserve the previous chat and start an independent thread.
- `/list`: show saved conversation IDs, titles and recorded statuses.
- `/switch <unique ID prefix>`: reopen that chat. Missing/ambiguous prefixes do not select one.
- `/close [reason]`: close this thread; the next ordinary request starts a new one.
- `/audit [unique ID prefix|all]`: default/all analyzes stopped/errored conversations; a unique
  ID explicitly selects one conversation, including historical chats with unknown status.
- `/learn [goal]`: use the selected cloud provider to propose one capability-grounded draft.
  With no goal, request a useful proposal from the actual source outline/runtime capabilities.
- `/skills`: list loaded skills and their statuses; `/help` lists the commands.

New/switch/delete operations do not move an in-flight task into another conversation. Stop the
current task first. A command also reserves the busy state while reading/writing or using a model.
The existing previous-chats sheet remains the ordinary history UI and shows recorded status.

## Lifecycle, persistence and isolation

Room migration 8→9 adds status/stop reason/latest-task ownership to conversations, plus
`conversation_tasks` and `conversation_audits` with cascading conversation foreign keys. Upgrade
keeps existing messages; historical status is `unknown`, not an invented success/failure.

A request is durably registered with its conversation before routing. Successful Q&A pairs
retain the existing chat-message storage and have task-linked IDs. Failed/unanswered and device
requests also have journal records, so history is not restricted to successful answers. Restore
combines those records without duplicating answered exchanges. Completed results are committed
before releasing Q&A's busy reservation. Storage failures are surfaced, not reported as saved.

Task outcomes/stop reasons are immutable in this journal: a later user attestation does not
rewrite original machine evidence. The normal sealed outcome store remains its existing owner.
Late journal closure cannot overwrite a newer task's conversation status. Startup marks earlier
unfinished journal entries interrupted with an **unknown cause**, not an invented exception.
Destination choices/clarification/handoffs are waiting states, not automatic task failures.
A workflow launch acknowledgment is labeled `LAUNCH_ACCEPTED`, never proof the whole task ran.

Conversation-specific remembered answers use a namespaced adapter over the existing persistent
knowledge store. They cannot read another chat or the legacy shared vault. Deleting a conversation
clears its namespace and cascades journal/audit deletion without clearing unrelated preferences.
Switch/new/close also clear correction ownership, queued prompts and contextual task state.
Provider history remains the existing bounded recent-turn window; full stored history is retained.
This change does not add an LLM summarization service or fabricate old conversational facts.

## Audit: facts versus hypotheses

Audits are **on demand**, not automatic background uploads. They use the chosen cloud source,
endpoint and model through `AniobCloudLlmProvider`; there is no new hardcoded model, endpoint,
provider substitution or fallback stub. If that provider is unavailable or a handled transport
failure occurs, return an explicitly evidence-only report, not a fake model diagnosis.

Input includes recorded session/task reasons, bounded conversation context and recorded action
log entries. Existing credential redaction is applied before model analysis and report output.
The model is instructed to treat transcripts as untrusted data. Model suggestions are explicitly
hypotheses, not machine evidence; audit never changes outcomes, executes a suggestion, or learns
a verified macro. Switching chats alone is not evidence of dissatisfaction. Cancellation
propagates rather than being saved as a successful audit. Reports are persisted per conversation.

## `/learn`: actual structure, not a hardcoded capability list

The Android variant generated-assets API packages `capability-index.tsv`. Its Gradle task reads
an allowlisted set of core source files (device commands, skills, capability manifest and provider
contracts), records source paths/declaration names, and rebuilds when inputs change. It is a
lexical source outline, not a full compiler AST, and does not include secrets or a Git checkout.
The installed APK does **not** pretend to walk repository source at runtime.

The prompt combines that build index with the existing `DeviceCommand`-derived manifest and
live permission/service availability. The returned proposal is parsed using the existing skill
codec, constrained to a safe filename, forced to DRAFT, and stored under a unique name without
overwriting working skills. Its description records the source-index digest and conversation
provenance. Missing grounding may produce an empty **draft**, never an empty active skill or a
claim of an executed action. Malformed proposals fail instead of becoming executable text.

The Skills screen has a draft-only review section with no replay/enable controls. A draft is
**not production-ready or verified**. This patch does not introduce self-modifying code or
automatic draft activation. Existing verified learning and its provenance remain separate.

Matching retains phrase/regex support and adds token/stem/limited typo scoring. Word boundaries
prevent substring matches; low or ambiguous scores fall through. Negated requests and ambiguous
lexical results cannot be rescued by semantic fallback. Drafts, disabled skills and user-disabled
skills remain excluded. Model-rung tests explicitly disable skills when testing model dispatch,
since a paraphrase may now legitimately select a skill instead.

## Verification and limits

Relevant checks:

```sh
./gradlew :core-agent:test :app:testDebugUnitTest :app:lintDebug --console=plain
bash scripts/ci-preflight.sh
git diff --check
```

Regression coverage includes schema 6/8→9 historical-data preservation and Room validation,
late task closure, interrupted recovery boundaries, failed-task records, audit deletion,
conversation fact isolation, late correction ownership, provider failure/cancellation,
source-grounded draft parsing/exclusion, disabled skills and paraphrase/ambiguity handling.
Generated assets are exercised by the Android build/test pipeline.

A green JVM/CI result is not a device or live-provider test. Device acceptance still needs
restart/new/switch/close while interacting with the real UI, cancellation during model requests,
real provider responses, model eligibility, permission-dependent capabilities and review of
proposed skills. No live credentials or model calls were used by regression tests. No generated
skill is claimed verified. Local preflight can be blocked by catalog-host network restrictions;
that is distinct from CI's independently executed preflight result.
