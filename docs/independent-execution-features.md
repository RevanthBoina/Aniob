# Independent execution features

Settings → Execution features has five independently persisted switches. Any of the 32
combinations is supported; FULL_SPEED selects all five. Changes made during execution apply
only to the next task. Research pass controls remain developer-only; execution features do not.
The Trust Center replay switch edits the same REMEMBER bit, not a second hidden gate.

| Bit | Feature | Enabled behavior |
| --- | --- | --- |
| 1 | Intent shortcuts | Direct intent/app launch resolution |
| 2 | Learned skills | Automatic active skill selection, verified skill-local progress |
| 4 | FastPath/remember replay | Hydrated verified trajectories and parameterized navigation cache |
| 8 | ACTION_BURST | Up to three actions per planned burst, with existing safety barriers |
| 16 | FAST_OBSERVE | Compact capture, event-driven verification and bounded fast idle wait |

ACTION_BURST does **not** enable FAST_OBSERVE; FAST_OBSERVE does **not** enable bursts.
Deterministic verification remains enabled in every selection, including FULL_SPEED, so new
skills and remembered transitions can actually be verified and learned. Existing risk checks,
app ambiguity handling and confirmation policies remain in force.

## Persistence and research

- Immutable `FeatureSelection` is captured once at task start and passed to task completion.
- New single-feature runs get distinct mode IDs, never historical preset IDs.
- CSV rows add nullable `feature_mask`; 12/15/29/36-column records remain readable.
- Zero/single-feature runs remain in `research_runs.csv` and the research summary.
- Runs with two or more features (including FULL_SPEED) are stored **only** in Room table
  `feature_combination_runs`, keyed by run ID, with exact bitmask, stable configuration key,
  timestamp and full versioned CSV payload. Corrections update the same row.
- Multi-feature rows are also defensively excluded from summary construction.
- Room migration 6→7 adds this table without dropping historical tables or research files.
- “Clear research” clears research CSVs, not the separate combination database.
- Existing presets and their stored labels/metrics are retained. On first upgrade the previous
  preset is translated to its actual bits, preserving a disabled legacy replay preference;
  no historical runs are relabeled.

## Automatic skills: investigation and changes

Previously, the semantic matcher loaded only learned files, not the packaged skill directory;
loading could race the first task. Routing indexed a skill with the **global task step**, so an
intent/app launch before selection could skip initial skill actions. Input slots were extracted
from lowercased prompts and were not bound into actions.

The runtime now loads packaged skills before learned overrides, awaits that job in the skills
arm, advances a task-local skill cursor only after verified recording, binds available slots
without lowercasing values, and declines unresolved slots/targets rather than typing braces or
guessing a target. A failed skill is excluded for the remainder of that task. Draft, disabled and
user-disabled skills still cannot execute. A new task resets progress even for repeated prompts.

Reference examined: [Anthropic's public skills repository](https://github.com/anthropics/skills),
README and its metadata/instructions model: discover reusable capabilities and select relevant
ones dynamically rather than requiring manual invocation. Aniob keeps its existing YAML/JVM
skill architecture and description/trigger-based matcher instead of adding a second skill
framework. This is not a claim that Claude-style instruction skills can be replayed as UI macros.

## Navigation replay and fresh inputs

The existing time/number/quoted-value template support is preserved. An additional conservative
purpose grammar recognizes LinkedIn messaging requests such as:

- `message Alice Brown on LinkedIn saying Hello Alice`
- `message Ravi Kumar on LinkedIn saying Can we discuss the role?`

These share a navigation purpose but bind different recipient and message slots. New inputs
must be supplied explicitly; unknown prose stays on the legacy exact-goal path. Purpose traces
use a package/activity/visible-structure key that abstracts only explicit task values. Input
actions that cannot be bound exactly are not cached. All targets must resolve on the live
screen; missing/ambiguous targets or changed structure fall back to planning. Existing
post-action verification and divergence suppression remain enabled. This is incremental
verified navigation reuse, not blind replay of an entire conversation or a promise to recognize
every natural-language paraphrase. Different profile layouts may still require model planning.

## Verification

Regression coverage includes all 32 combinations, burst/observation independence, historical
policy compatibility, CSV flag round trips, summary exclusion, separate combination corrections,
Room 6→7 migration/data preservation, skill slot binding/case preservation/local verified progress,
and LinkedIn fresh-recipient/message injection with safe misses on changed screens.

Android device tests are still required for Settings interaction, real LinkedIn layout changes,
cold-start skills, permission/confirmation dialogs and timing/capture behavior. Unit tests and
lint cannot establish those device behaviors.
