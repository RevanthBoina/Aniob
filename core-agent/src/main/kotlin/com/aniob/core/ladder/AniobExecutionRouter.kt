package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteDecision
import com.aniob.core.router.RouteTarget
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.skills.AniobSemanticSkillMatcher
import com.aniob.core.skills.AniobSkill
import com.aniob.core.skills.AniobSkillMatcher
import com.aniob.core.tools.AniobReplayEngine
import com.aniob.core.tools.DevicePowerState

/**
 * Execution Ladder Coordinator.
 * Sequentially queries:
 * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
 */
class AniobExecutionRouter(
    private val replayEngine: AniobReplayEngine = AniobReplayEngine(),
    private val skillMatcher: AniobSkillMatcher = AniobSkillMatcher(),
    private val semanticSkillMatcher: AniobSemanticSkillMatcher? = null,
    private val planningAgent: AniobPlanningAgent? = null,
    private val memoryStore: AniobEmbeddingStore? = null
) {
    /** Shared with the learning pipeline so hydration and replay hit the same store. */
    val fastPathEngine: AniobReplayEngine get() = replayEngine

    /**
     * UX-4: the Trust Center's FastPath switch is REAL — when off, the replay lookup is skipped
     * entirely, so a disabled toggle provably yields zero replay hits.
     */
    var fastPathEnabled: Boolean = true

    /** BL-1: ablation-controlled skill gating (disabled in arms 1-3 to prevent contamination). */
    var skillsEnabled: Boolean = true

    /**
     * Research ablation switch for the step-0 intent-shortcut lookup. Off means the ladder never
     * resolves `AniobIntentResolver` and every step goes through the model/skill rungs — the
     * BASELINE condition. Defaults to on, which is the shipping behaviour.
     */
    var intentShortcutsEnabled: Boolean = true

    /**
     * Remember-replay switch (the FULL_HUMAN_SPEED condition). On means the zero-LLM cache is
     * consulted right after the intent shortcut and before any model rung.
     */
    var rememberReplayEnabled: Boolean = false

    /**
     * Cache lookup supplied by the app layer: (goal, live screen) -> replayable actions, or null
     * when this exact transition was never solved. Kept as a callback so the ladder stays pure.
     */
    var rememberLookup: ((String, AniobScreenState) -> List<AniobAction>?)? = null

    /** Task the dispatch memory below belongs to; a new signature starts a new memory. */
    private var shortcutSignature: String? = null

    /** Shortcuts already fired for [shortcutSignature]; one intent per hop, never once per round. */
    private val dispatchedShortcuts = HashSet<String>()

    /**
     * True the first time [shortcut] is asked for in this task. A shortcut that carries parameters
     * may be dispatched past step 0, and without this memory every planning round would re-fire it
     * — reopening an alarm editor the run already filled in.
     */
    private fun shouldDispatchShortcut(taskSignature: String, shortcut: ResolvedIntentShortcut): Boolean {
        if (shortcutSignature != taskSignature) {
            shortcutSignature = taskSignature
            dispatchedShortcuts.clear()
        }
        val key = listOf(
            shortcut.action, shortcut.targetPackage, shortcut.uriString, shortcut.dataType,
            shortcut.extras.toSortedMap().toString()
        ).joinToString("|")
        return dispatchedShortcuts.add(key)
    }

    /**
     * App-name resolver supplied by the app layer: (goal) -> the app the task names, resolved
     * against the installed-app catalog. Turns "open Clock and set an alarm" into a direct launch
     * instead of a model round trip. Gated by [intentShortcutsEnabled]: it is the same class of
     * zero-model pre-resolution as the system-intent shortcut.
     */
    var appLaunchResolver: ((String) -> AppLaunchTarget?)? = null

    /** Platform launchability check passed into the pure intent resolver to reject stale/seeded packages. */
    var appIsLaunchable: ((String) -> Boolean)? = null

    /**
     * Mid-task app resolver: (goal) -> an app the goal names that this task has not opened yet.
     * Optional; when null, mid-task switches are simply not attempted and the ladder behaves as
     * before. Supplied by the app layer with the same installed-check as [appLaunchResolver].
     */
    var midTaskLaunchResolver: ((String) -> AppLaunchTarget?)? = null

    /** UX-5: skills the user disabled are skipped by both matchers. */
    private var disabledSkills: Set<String> = emptySet()

    fun setDisabledSkills(names: Set<String>) {
        disabledSkills = names.toSet()
    }

    fun isSkillDisabled(name: String): Boolean = disabledSkills.contains(name)

    /** Wires a FastPath lookup callback for use outside the ladder (tracker/learning tests). */
    fun lookup(taskSignature: String): AniobReplayEngine.ReplayTrajectory? =
        if (fastPathEnabled) replayEngine.findTrajectory(taskSignature) else null

    sealed class ExecutionPlanResult {
        data class DirectIntent(val shortcut: ResolvedIntentShortcut, val reason: String) : ExecutionPlanResult()
        /**
         * Step 0 opens a named app directly. The package came from the installed-app registry, so
         * the step costs no model call; the next step plans on the app's own screen.
         *
         * [launchActivity] is the registry's explicit launcher component (`<pkg>/<pkg>.Activity`)
         * when it is known, so the dispatch can bypass a resolver chooser and the UI-automation
         * verifier can confirm the foreground package it expects.
         */
        data class AppLaunch(
            val packageName: String,
            val appName: String,
            val reason: String,
            val launchActivity: String? = null
        ) : ExecutionPlanResult()
        /**
         * A verified transition replayed from the remember cache. The list is dispatched in order
         * without any model call, so it costs 0 LLM requests.
         */
        data class RememberReplay(val actions: List<AniobAction>, val reason: String) : ExecutionPlanResult()
        data class FastPathStep(val action: AniobAction, val reason: String) : ExecutionPlanResult()
        data class SkillStepExecution(val action: AniobAction, val skill: AniobSkill, val reason: String) : ExecutionPlanResult()
        data class ModelDispatch(
            val decision: RouteDecision,
            val progressSummary: String? = null,
            val progress: TaskProgress? = null
        ) : ExecutionPlanResult()
        data class CannotProceed(val reason: String) : ExecutionPlanResult()
    }

    fun planStep(
        taskPrompt: String,
        screenState: AniobScreenState,
        stepIndex: Int,
        taskSignature: String,
        powerState: DevicePowerState,
        installedModelId: String? = null,
        lastLocalFailCount: Int = 0,
        isModelFileMissing: Boolean = false,
        previousProgress: TaskProgress? = null,
        userRoutingMode: String = "auto",
        lastObservedResult: String? = null,
        /** Per-task provider lock; see [AniobAutoRouter.decideRoute]. */
        taskStickyTarget: RouteTarget? = null
    ): ExecutionPlanResult {
        // ----------------------------------------------------------------------------------- //
        // Step 0 / app switch: opening an app is a LOOKUP, never a model call.
        //
        //  · at step 0 the app named at the start of the task is opened;
        //  · mid-task, an app named later in the task is opened once, if it is not already in front.
        //
        // An app shortcut is NEVER returned as a DirectIntent. A DirectIntent finishes without an
        // observable post-condition, so the step pipeline rejects it and the task ends FAILED — that
        // is exactly how "Open Clock" used to fail while the clock app was open. Only reflexes whose
        // effect *is* the dispatch (torch, battery, dial, open URL) may finish immediately.
        // ----------------------------------------------------------------------------------- //
        if (intentShortcutsEnabled) {
            AniobLaunchHub.reset(taskSignature)
            val firstStep = stepIndex == 0
            val intentShortcut = AniobIntentResolver.resolve(taskPrompt, appIsLaunchable ?: { true })
            // A shortcut that already answers part of the prompt (an alarm with its time, a
            // settings panel, a search URL, the alarm list) is cheaper than opening the app and
            // planning there, so it outranks the app-launch hook. A shortcut that merely names an
            // app is an app launch and nothing else; that is the one case the branch below owns.
            val carriesParameters = intentShortcut != null && (
                !intentShortcut.requiresObservation || intentShortcut.action != AniobIntentResolver.ACTION_MAIN
                )
            val pending = runCatching {
                if (firstStep) appLaunchResolver?.invoke(taskPrompt) else midTaskLaunchResolver?.invoke(taskPrompt)
            }.getOrNull()
            if (!carriesParameters &&
                pending != null &&
                pending.packageName != screenState.packageName &&
                !AniobLaunchHub.wasLaunched(pending.packageName)
            ) {
                AniobLaunchHub.noteLaunched(pending.packageName)
                return ExecutionPlanResult.AppLaunch(
                    packageName = pending.packageName,
                    appName = pending.appName,
                    launchActivity = pending.launchActivity,
                    reason = pending.narration?.takeIf { it.isNotBlank() } ?: if (firstStep) {
                        "App shortcut: '${pending.appName}' is installed (0 LLM calls)"
                    } else {
                        "App switch: '${pending.appName}' is named later in the task and is not open (0 LLM calls)"
                    }
                )
            }

            // A shortcut that carries parameters, and that the caller then observes, may be
            // dispatched past step 0 — that is what turns the second hop of "open clock then set an
            // alarm for 7:30" into a dispatch instead of a model call. A silent shortcut still fires
            // at step 0 only: closing a run mid-trajectory needs the criteria re-check the caller
            // does, and a dial/torch/URL dispatch repeated every round is how a failed reflex
            // becomes an infinite loop. `shouldDispatchShortcut` bounds both cases to one dispatch
            // per task. Only an app *launch* is excluded here: it has a package and no parameters,
            // and the branch below handles it as an observed OpenApp.
            if (carriesParameters && intentShortcut != null &&
                (intentShortcut.requiresObservation || firstStep) &&
                shouldDispatchShortcut(taskSignature, intentShortcut)
            ) {
                return ExecutionPlanResult.DirectIntent(
                    shortcut = intentShortcut,
                    reason = "Direct intent shortcut resolved in <15ms"
                )
            }
            // A shortcut that opens an app must be observed, so it is dispatched as an OpenApp
            // action — which the UI-automation verifier confirms against the real foreground
            // package — instead of a DirectIntent that would finish with no post-condition.
            // Only at step 0: mid-task switches belong to the mid-task hook above, which knows what
            // this task already opened, and never to a re-read of the raw prompt.
            if (firstStep &&
                intentShortcut != null &&
                intentShortcut.requiresObservation &&
                intentShortcut.targetPackage != null &&
                intentShortcut.targetPackage != screenState.packageName &&
                !AniobLaunchHub.wasLaunched(intentShortcut.targetPackage)
            ) {
                AniobLaunchHub.noteLaunched(intentShortcut.targetPackage)
                val label = intentShortcut.label ?: intentShortcut.targetPackage
                return ExecutionPlanResult.AppLaunch(
                    packageName = intentShortcut.targetPackage,
                    appName = label,
                    launchActivity = intentShortcut.launchActivity,
                    reason = "App shortcut: '$label' resolved by the intent resolver and will be observed (0 LLM calls)"
                )
            }
            // A shortcut that names no package cannot be verified. Fall through to the rungs that
            // can observe, rather than dispatching something we cannot check.
        }

        // Step 0.5: Remember replay (0 LLM). Sits after the intent shortcut and before every
        // model rung, exactly where the ablation condition expects the cache to be consulted.
        if (rememberReplayEnabled) {
            val replay = rememberLookup?.invoke(taskPrompt, screenState)
            if (!replay.isNullOrEmpty()) {
                return ExecutionPlanResult.RememberReplay(
                    actions = replay,
                    reason = "Remember cache hit for ${screenState.packageName} (0 LLM calls)"
                )
            }
        }

        // Step 1: FastPath trajectory replay with fingerprint verification (0ms LLM, 0 tokens)
        // UX-4: the FastPath switch isn't decorative — OFF skips the lookup entirely.
        val trajectory = if (fastPathEnabled) replayEngine.findTrajectory(taskSignature) else null
        if (trajectory != null) {
            val cachedAction = replayEngine.nextAction(trajectory, stepIndex, screenState)
            if (cachedAction != null) {
                // If the action names an element not currently on screen, self-heal via semantic token overlap or scroll
                val target = cachedAction.semanticTarget()
                if (target is SemanticTarget.SomIndex && screenState.findNodeById(target.index) == null) {
                    val fallbackNode = findSemanticFallbackNode(taskPrompt, screenState)
                    if (fallbackNode != null) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Tap(SemanticTarget.SomIndex(fallbackNode.id)),
                            reason = "FastPath fallback matched node ${fallbackNode.id} by semantic token overlap"
                        )
                    }
                    if (screenState.nodes.any { it.isScrollable }) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Swipe(
                                direction = com.aniob.core.domain.SwipeDirection.DOWN,
                                distancePx = 600,
                                thought = "Scroll down to reveal off-screen FastPath target ${target.index}"
                            ),
                            reason = "FastPath target ${target.index} off-screen; scrolling to reveal"
                        )
                    }
                }
                return ExecutionPlanResult.FastPathStep(
                    action = cachedAction,
                    reason = "FastPath verified replay step $stepIndex"
                )
            }
        }

        // Step 2: Tier 1.5 YAML or Semantic Skill Match (BL-1)
        if (skillsEnabled) {
            val skillMatch = semanticSkillMatcher?.findBestSkill(taskPrompt, disabledSkills) ?: skillMatcher.match(taskPrompt, disabledSkills)
            // UX-5: a disabled or draft skill never participates, so user routing and quality switches are real.
            val skillUsable = skillMatch.skill != null &&
                skillMatch.skill.status == com.aniob.core.skills.SkillStatus.ACTIVE &&
                !skillMatch.skill.isDraft &&
                !disabledSkills.contains(skillMatch.skill.name)
            if (skillMatch.matched && skillUsable) {
                val skill = skillMatch.skill!!
                val step = skill.steps.getOrNull(stepIndex)
                if (step != null) {
                    val action = step.toAniobAction()
                    val target = action.semanticTarget()
                    if (target is SemanticTarget.SomIndex && screenState.findNodeById(target.index) == null) {
                        val fallbackNode = findSemanticFallbackNode(taskPrompt, screenState)
                        if (fallbackNode != null) {
                            return ExecutionPlanResult.SkillStepExecution(
                                action = AniobAction.Tap(SemanticTarget.SomIndex(fallbackNode.id)),
                                skill = skill,
                                reason = "Skill fallback matched node ${fallbackNode.id} by semantic token overlap"
                            )
                        }
                        if (screenState.nodes.any { it.isScrollable }) {
                            return ExecutionPlanResult.SkillStepExecution(
                                action = AniobAction.Swipe(
                                    direction = com.aniob.core.domain.SwipeDirection.DOWN,
                                    distancePx = 600,
                                    thought = "Scroll down to reveal off-screen skill target ${target.index}"
                                ),
                                skill = skill,
                                reason = "Skill target ${target.index} off-screen; scrolling to reveal"
                            )
                        }
                    }
                    return ExecutionPlanResult.SkillStepExecution(
                        action = action,
                        skill = skill,
                        reason = skillMatch.reason
                    )
                }
            }
        }

        // Context Retrieval via Memory Store RAG
        var retrievedContext: String? = null
        if (memoryStore != null) {
            val qVec = AniobEmbeddingStore.generatePseudoEmbedding(taskPrompt)
            val memories = memoryStore.search(qVec, topK = 1, threshold = 0.5f)
            if (memories.isNotEmpty()) {
                retrievedContext = memories.first().text
            }
        }

        // Planning Progress condensation: prefrontal-cortex summary replaces the full
        // interleaved observation history in the LLM prompt (~80% token reduction).
        val previousOp = lastObservedResult ?: if (stepIndex > 0) "Step ${stepIndex - 1} executed" else null
        val progress = planningAgent?.updateProgress(
            userInstruction = taskPrompt,
            previousOperation = previousOp,
            previousProgress = previousProgress,
            focusContent = retrievedContext
        )

        // Step 3 & 4: AutoRouter decides between Local SLM and Omniroute Cloud
        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = taskPrompt,
            screenState = screenState,
            powerState = powerState,
            hasFastPathHit = false,
            isIntentShortcut = false,
            installedModelId = installedModelId,
            lastLocalFailCount = lastLocalFailCount,
            isModelFileMissing = isModelFileMissing,
            userRoutingMode = userRoutingMode,
            taskStickyTarget = taskStickyTarget
        )

        if (decision.target == RouteTarget.CANNOT_PROCEED) {
            return ExecutionPlanResult.CannotProceed(decision.reason)
        }

        return ExecutionPlanResult.ModelDispatch(
            decision = decision,
            progressSummary = progress?.summary,
            progress = progress
        )
    }

    /**
     * Stopword-filtered token-salience fallback resolver.
     * Prevents false-positive substring matches on short stop-words ("on", "in", "to", "a")
     * and requires the candidate node to be visible, enabled, and interactive.
     */
    internal fun findSemanticFallbackNode(
        taskPrompt: String,
        screenState: AniobScreenState
    ): com.aniob.core.domain.AniobNode? {
        val promptTokens = extractSalientTokens(taskPrompt)
        if (promptTokens.isEmpty()) return null

        var bestNode: com.aniob.core.domain.AniobNode? = null
        var bestScore = 0.0

        for (node in screenState.nodes) {
            if (!node.isVisibleToUser || !node.isEnabled || (!node.isClickable && !node.isEditable)) continue
            val label = node.text.ifBlank { node.contentDescription }
            if (label.isBlank()) continue
            val nodeTokens = extractSalientTokens(label)
            if (nodeTokens.isEmpty()) continue

            val intersection = nodeTokens.count { it in promptTokens }
            if (intersection == 0) continue

            val recall = intersection.toDouble() / nodeTokens.size.toDouble()
            val precision = intersection.toDouble() / promptTokens.size.toDouble()
            val f1 = (2.0 * precision * recall) / (precision + recall)
            if (recall >= 0.5 && f1 > bestScore) {
                bestScore = f1
                bestNode = node
            }
        }
        return bestNode
    }

    private fun extractSalientTokens(text: String): Set<String> =
        text.lowercase()
            .split(TOKEN_SPLIT_REGEX)
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toSet()

    companion object {
        private val TOKEN_SPLIT_REGEX = Regex("\\W+")
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "onto", "tap", "click",
            "open", "press", "button", "app", "please", "can", "you"
        )
    }
}
