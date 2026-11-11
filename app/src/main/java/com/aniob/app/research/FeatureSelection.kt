package com.aniob.app.research

/** Stable bits are persisted per run. Do not reorder or reuse them. */
enum class ExecutionFeature(val bit: Int, val label: String, val description: String) {
    INTENT_SHORTCUTS(1, "Intent shortcuts", "Direct Android intent actions without a model call."),
    LEARNED_SKILLS(2, "Learned skills", "Automatically select relevant active skills for the task."),
    REMEMBER(4, "FastPath/remember replay", "Reuse verified application paths with fresh task inputs."),
    ACTION_BURST(8, "Burst limit (ACTION_BURST)", "Allow two or more actions per burst; observation is a separate switch."),
    FAST_OBSERVE(16, "Fast observation", "Compact observation and event-driven capture without enabling action bursts.")
}

/** Immutable task policy. All 32 feature combinations are independent and serializable. */
data class FeatureSelection(val mask: Int = ALL_MASK) {
    init { require(mask in 0..ALL_MASK) }
    fun enabled(feature: ExecutionFeature): Boolean = mask and feature.bit != 0
    fun with(feature: ExecutionFeature, enabled: Boolean): FeatureSelection =
        FeatureSelection(if (enabled) mask or feature.bit else mask and feature.bit.inv())
    val count: Int get() = Integer.bitCount(mask)
    val isCombination: Boolean get() = count > 1
    val key: String get() = if (mask == ALL_MASK) "FULL_SPEED" else
        ExecutionFeature.entries.filter(::enabled).joinToString("+") { it.name }.ifBlank { "NONE" }
    val intentShortcuts get() = enabled(ExecutionFeature.INTENT_SHORTCUTS)
    val skillsEnabled get() = enabled(ExecutionFeature.LEARNED_SKILLS)
    val rememberReplay get() = enabled(ExecutionFeature.REMEMBER)
    val actionBursts get() = enabled(ExecutionFeature.ACTION_BURST)
    val fastObservation get() = enabled(ExecutionFeature.FAST_OBSERVE)
    val fastDeltaVerify get() = fastObservation
    val fastIdleWait get() = fastObservation
    // Skipping verification would prevent learning new paths/skills. These independent modes
    // retain deterministic verification, rather than silently trading away another capability.
    val skipFastStepVerification get() = false
    val optimizedFinish get() = actionBursts || fastObservation
    val immediateCompletion get() = actionBursts || fastObservation
    val mode: AblationMode get() = when (mask) {
        0 -> AblationMode.BASELINE
        1 -> AblationMode.INTENT_SHORTCUTS
        2 -> AblationMode.LEARNED_SKILLS
        4 -> AblationMode.REMEMBER
        8 -> AblationMode.ACTION_BURST
        16 -> AblationMode.FAST_OBSERVE
        ALL_MASK -> AblationMode.FULL_SPEED
        else -> AblationMode.COMBINATION
    }
    companion object {
        const val ALL_MASK = 31
        val FULL_SPEED = FeatureSelection(ALL_MASK)
        fun fromLegacy(mode: AblationMode): FeatureSelection = FeatureSelection(
            (if (mode.intentShortcuts) 1 else 0) or (if (mode.skillsEnabled) 2 else 0) or
                (if (mode.rememberReplay) 4 else 0) or (if (mode.actionBursts) 8 else 0) or
                (if (mode.fastObservation) 16 else 0))
    }
}
