package com.aniob.core.learning

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext

/**
 * The single seam through which the agent gets smarter.
 *
 * Before this interface existed, five learning systems (HippocampusTracker, ReplayEngine,
 * SkillDistiller, SemanticSkillMatcher, AppKnowledgeBase) each accumulated knowledge in its own
 * store and none of them talked. `LearningPipeline` gives one stage-owned path:
 *
 * ```
 * verified trajectory -> fastpath entry (now)
 *                     -> distill candidacy (signature match count)
 *                     -> skill YAML       (>= threshold)
 *                     -> indexed + persisted
 * ```
 *
 * Callers must only invoke these for **verified** outcomes; failures must never distill.
 */
interface LearningPipeline {

    /** A single verified step. Cheap, in-memory rehearsal only. */
    fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState)

    /** A task that ended in verified success. Persists fastpath + may promote a skill draft. */
    fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>)

    /** User attestation is a separate provenance channel, never a verified-step callback. */
    fun onUserAttestedTask(ctx: TaskContext, trajectory: List<AniobAction>) {}

    /** Clears task working memory / rehearsal on any terminal outcome. */
    fun onTaskEnd(taskId: String) {}

    companion object {
        /** Default no-op used until the learning track lands. */
        val NoOp: LearningPipeline = object : LearningPipeline {
            override fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState) = Unit
            override fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>) = Unit
            override fun onTaskEnd(taskId: String) = Unit
        }
    }
}