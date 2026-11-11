package com.aniob.app.research

/**
 * One experimental condition of the ablation study.
 *
 * Each mode states which of the agent's speed mechanisms may run for a task. The flags are applied
 * once per task by `AniobViewModel.applyAblationMode`, which flips the real switches (the router's
 * intent-shortcut lookup, FastPath replay, the observe-burst policy and the event-driven capture
 * skip) — nothing here is a placebo label.
 *
 * | mode | intent shortcut | action bursts | fast delta | remember replay |
 * |---|---|---|---|---|
 * | [BASELINE] | off | off | off | off |
 * | [INTENT_SHORTCUT_ONLY] | on | off | off | off |
 * | [BURST_AND_FAST_OBSERVE] | on | on | on | off |
 * | [FULL_HUMAN_SPEED] | on | on | on | on |
 *
 * Node pruning (`AniobTokenOptimizer`) is NOT an ablation flag: it is the hard 60-node cap every
 * request depends on, so it stays on in all four modes. That is the only mechanism that does not
 * vary across this table.
 *
 * [FULL_HUMAN_SPEED] is the default because it preserves the behaviour the app had before the
 * study existed; the other modes are opt-in degradations for measurement.
 *
 * The compact observe pipeline ([fastObservation]) is part of the burst conditions: it prunes and
 * merges the live tree, gives every surviving element a sequential id and skips the screenshot
 * when the tree is dense enough to plan from.
 */
enum class AblationMode(
    /** Stable identifier used in `research_runs.csv`. Rename only with the whole study. */
    val id: String,
    /** Short UI label. */
    val label: String,
    /** One-line explanation shown under the radio button in Settings. */
    val description: String,
    /** Paper-ready label without commas or vendor names. */
    val paperLabel: String,
    val intentShortcuts: Boolean,
    val actionBursts: Boolean,
    val fastDeltaVerify: Boolean,
    val rememberReplay: Boolean,
    val skillsEnabled: Boolean,
    val fastIdleWait: Boolean,
    val skipFastStepVerification: Boolean,
    val optimizedFinish: Boolean,
    val immediateCompletion: Boolean
) {
    BASELINE(
        id = "BASELINE",
        label = "Baseline",
        description = "Full observation every step, one action per model call. No shortcuts, no replay.",
        paperLabel = "Baseline (full observation; one action per turn)",
        intentShortcuts = false,
        actionBursts = false,
        fastDeltaVerify = false,
        rememberReplay = false,
        skillsEnabled = false,
        fastIdleWait = false,
        skipFastStepVerification = false,
        optimizedFinish = false,
        immediateCompletion = false
    ),
    INTENT_SHORTCUT_ONLY(
        id = "INTENT_SHORTCUT_ONLY",
        label = "Shortcuts only",
        description = "Direct intent shortcuts are on; every remaining step uses the standard loop.",
        paperLabel = "Intent shortcuts only (+ OS intent teleportation layer)",
        intentShortcuts = true,
        actionBursts = false,
        fastDeltaVerify = false,
        rememberReplay = false,
        skillsEnabled = true,
        fastIdleWait = false,
        skipFastStepVerification = false,
        optimizedFinish = false,
        immediateCompletion = false
    ),
    BURST_AND_FAST_OBSERVE(
        id = "BURST_AND_FAST_OBSERVE",
        label = "Burst + fast capture",
        description = "Shortcuts, multi-action bursts and the event-driven capture skip.",
        paperLabel = "Burst and fast observe (+ compact tree folding + action bursts + event-driven verify)",
        intentShortcuts = true,
        actionBursts = true,
        fastDeltaVerify = true,
        rememberReplay = false,
        skillsEnabled = true,
        fastIdleWait = true,
        skipFastStepVerification = true,
        optimizedFinish = true,
        immediateCompletion = true
    ),
    FULL_HUMAN_SPEED(
        id = "FULL_HUMAN_SPEED",
        label = "Full human speed",
        description = "Every mechanism, including verified replay from remembered tasks.",
        paperLabel = "Full human speed (+ online verified replay cache)",
        intentShortcuts = true,
        actionBursts = true,
        fastDeltaVerify = true,
        rememberReplay = true,
        skillsEnabled = true,
        fastIdleWait = true,
        skipFastStepVerification = true,
        optimizedFinish = true,
        immediateCompletion = true
    ),
    INTENT_SHORTCUTS(
        id = "INTENT_SHORTCUTS", label = "Intent shortcuts", description = "Intent shortcuts", paperLabel = "Intent shortcuts",
        intentShortcuts = true, actionBursts = false, fastDeltaVerify = false, rememberReplay = false,
        skillsEnabled = false, fastIdleWait = false, skipFastStepVerification = false,
        optimizedFinish = false, immediateCompletion = false
    ),
    LEARNED_SKILLS(
        id = "LEARNED_SKILLS", label = "Learned skills", description = "Learned skills", paperLabel = "Learned skills",
        intentShortcuts = false, actionBursts = false, fastDeltaVerify = false, rememberReplay = false,
        skillsEnabled = true, fastIdleWait = false, skipFastStepVerification = false,
        optimizedFinish = false, immediateCompletion = false
    ),
    REMEMBER(
        id = "REMEMBER", label = "FastPath/remember replay", description = "FastPath/remember replay", paperLabel = "FastPath/remember replay",
        intentShortcuts = false, actionBursts = false, fastDeltaVerify = false, rememberReplay = true,
        skillsEnabled = false, fastIdleWait = false, skipFastStepVerification = false,
        optimizedFinish = false, immediateCompletion = false
    ),
    ACTION_BURST(
        id = "ACTION_BURST", label = "Action burst", description = "Action burst", paperLabel = "Action burst",
        intentShortcuts = false, actionBursts = true, fastDeltaVerify = false, rememberReplay = false,
        skillsEnabled = false, fastIdleWait = false, skipFastStepVerification = false,
        optimizedFinish = true, immediateCompletion = true
    ),
    FAST_OBSERVE(
        id = "FAST_OBSERVE", label = "Fast observation", description = "Fast observation", paperLabel = "Fast observation",
        intentShortcuts = false, actionBursts = false, fastDeltaVerify = true, rememberReplay = false,
        skillsEnabled = false, fastIdleWait = true, skipFastStepVerification = false,
        optimizedFinish = true, immediateCompletion = true
    ),
    FULL_SPEED(
        id = "FULL_SPEED", label = "Full speed", description = "Full speed", paperLabel = "Full speed",
        intentShortcuts = true, actionBursts = true, fastDeltaVerify = true, rememberReplay = true,
        skillsEnabled = true, fastIdleWait = true, skipFastStepVerification = false,
        optimizedFinish = true, immediateCompletion = true
    ),
    COMBINATION(
        id = "COMBINATION", label = "Custom combination", description = "Custom combination", paperLabel = "Custom combination",
        intentShortcuts = false, actionBursts = false, fastDeltaVerify = false, rememberReplay = false,
        skillsEnabled = false, fastIdleWait = false, skipFastStepVerification = false,
        optimizedFinish = false, immediateCompletion = false
    );

    /**
     * True when the Observe phase runs the compact pipeline: prune invisible/decorative nodes,
     * fold child labels into their interactive parent, assign sequential ids, hash the structure
     * and gate the bitmap capture. Both burst conditions enable it; the other two stay raw.
     */
    val fastObservation: Boolean get() = this == FAST_OBSERVE || this == FULL_SPEED || this == BURST_AND_FAST_OBSERVE || this == FULL_HUMAN_SPEED

    companion object {
        val DEFAULT: AblationMode = FULL_HUMAN_SPEED

        /** Accepts the enum name or the persisted id, case-insensitively; unknown → [DEFAULT]. */
        fun fromId(id: String?): AblationMode {
            if (id.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
