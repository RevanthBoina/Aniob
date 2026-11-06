package com.aniob.app.ui

import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.research.AblationMode
import com.aniob.app.research.ResearchRun
import com.aniob.app.research.ResearchSummaryRow
import com.aniob.app.research.StudyPass
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.BlockedRecord
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.app.ui.model.MomentChip
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.app.ui.model.StepEvent
import com.aniob.app.ui.model.TaskSummary
import com.aniob.core.domain.*
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.narration.PhaseSnapshot
import com.aniob.core.outcome.SealedOutcome

data class AniobUiState(
    val activeTask: AniobTask? = null,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentStep: Int = 0,
    val grillMeResult: AniobGrillMeResult? = null,
    val showGrillMeSheet: Boolean = false,
    /** Audit: what the agent is doing right now, and whether a model is really involved. */
    val agentPhase: PhaseSnapshot = PhaseSnapshot(),
    /** Audit: the closed-set result of the last run, with its evidence and the user's thumbs. */
    val lastSealedOutcome: SealedOutcome? = null,
    val lastSealedTaskId: String? = null,
    val showTrackerSheet: Boolean = false,
    val trackerFilter: String = "ALL", // ALL, CORRECT, FAILED
    val steps: List<AniobStepRecord> = emptyList(),
    val stepEvents: List<StepEvent> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val omnirouteApiKey: String = "",
    val omnirouteModel: String = "auto",
    /** OmniRoute gateway base URL; blank means AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL. */
    val omnirouteBaseUrl: String = "",
    val omnirouteTesting: Boolean = false,
    val omnirouteConnectionStatus: String = "",
    val omnirouteDiscoveredModels: List<String> = emptyList(),
    val autoRouterMode: String = "auto", // auto, local-first, local-only, cloud-only, balanced
    val statusMessage: String = "Ready",
    val lastRoutingReason: String = "Ready",
    val lastProviderUsed: String = "NONE",
    val sessionScores: List<SessionScoreEntity> = emptyList(),
    val streamingBubbleText: String = "",
    val isStreaming: Boolean = false,
    /** UX-6: honest recovery state after a process death mid-task. */
    val interruptedSummary: TaskSummary? = null,
    /** UX-6: last completed task, rendered as a card on next open (Quiet Return). */
    val lastSummary: TaskSummary? = null,
    /** UX-6: accessibility access was revoked mid-run; recovery banner until the user re-enables. */
    val accessibilityRecoveryNeeded: Boolean = false,
    /** UX-4: pending approval the user must actually see. */
    val confirmRequest: ConfirmRequest? = null,
    /** UX-3: the last narration line, for the ticker and lock-adjacent surfaces. */
    val lastNarration: String = "",
    /** UX-2/UX-5: Remembered Moments chips are ephemeral and shown above the input. */
    val momentChips: List<MomentChip> = emptyList(),
    /** UX-2: grill questions already answered from the vault, rendered with a "saved ✓" badge. */
    val grillRememberedKeys: Set<String> = emptySet(),
    /** UX-4: Trust Center controls — real state, never placebo. */
    val safetyLevel: SafetyLevel = SafetyLevel.STANDARD,
    val fastPathEnabled: Boolean = true,
    val safetyGateEnabled: Boolean = true,
    /** UX-4: last 20 approval decisions with context. */
    val confirmationHistory: List<ConfirmationRecord> = emptyList(),
    /** UX-4: fingerprints the interceptor has hard-blocked, with rule + time. */
    val blockedActions: List<BlockedRecord> = emptyList(),
    /** UX-2: follow-up queued while a task runs (depth 1, honest label). */
    val queuedPrompt: String? = null,
    /** UX-5: names of skills the user has disabled; matcher skips these. */
    val disabledSkills: Set<String> = emptySet(),
    /** Staged draft from share or incoming intent that requires user confirmation/submission */
    val pendingInputPrefill: String? = null,
    /** Result of the last Cloud AI save / connection test, rendered as a banner in Settings. */
    val cloudTest: CloudTestUiState = CloudTestUiState(),
    /** Research: ablation condition that the next task will run under. */
    val ablationMode: AblationMode = AblationMode.DEFAULT,
    /** Research: study pass (warm-up vs measured). */
    val studyPass: StudyPass = StudyPass.MEASURED,
    /** Research: one-line HUD for the last finished task (`[MODE] Latency… | Calls… | Success…`). */
    val researchStatusLine: String? = null,
    /** Research: increments per finished run so the host can fire a one-shot toast. */
    val researchRunSequence: Long = 0L,
    /** Research: last finished run, shown with the manual override in the Stats screen. */
    val researchLastRun: ResearchRun? = null,
    /** Research: running averages for [ablationMode]. */
    val researchSummary: ResearchSummaryRow? = null
)

/** Outcomes of saving or testing the Cloud AI (OmniRoute) settings. */
enum class CloudTestResult { NONE, SAVED, SUCCESS, BAD_KEY, UNREACHABLE, INVALID_SETUP }

/**
 * Settings feedback for the Cloud AI card. [message] is short, user-facing copy that explains the
 * concrete next step; [testing] disables the buttons while the probe is in flight.
 */
data class CloudTestUiState(
    val testing: Boolean = false,
    val result: CloudTestResult = CloudTestResult.NONE,
    val message: String? = null
)
