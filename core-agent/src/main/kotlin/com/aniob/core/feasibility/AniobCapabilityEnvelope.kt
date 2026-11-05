package com.aniob.core.feasibility

import com.aniob.core.grillme.GrillQuestion

/**
 * Step 3 — plan-time feasibility. A plan is checked against what this phone will actually let us do and
 * observe BEFORE the first dispatch, not discovered at the final gate.
 */
enum class Privilege { ACCESSIBILITY, NOTIFICATION_LISTENER, PACKAGE_QUERY, SHIZUKU_ADB, DEVICE_OWNER }

data class CapabilityEnvelope(val held: Set<Privilege>) {
    fun has(p: Privilege) = p in held
}

/** What an observer must be able to read to call a step done. [needsAnyOf] empty = readable with no special privilege. */
enum class Postcondition(val needsAnyOf: Set<Privilege>, val humanName: String, val enableHint: String?) {
    PACKAGE_INSTALLED(setOf(Privilege.PACKAGE_QUERY), "the app appears in the installed list", "allow package visibility"),
    PACKAGE_REMOVED(setOf(Privilege.PACKAGE_QUERY), "the app disappears from the installed list", "allow package visibility"),
    FOREGROUND_APP(setOf(Privilege.ACCESSIBILITY), "the expected app is in the foreground", "turn on Aniob in Settings > Accessibility"),
    SCREEN_TEXT(setOf(Privilege.ACCESSIBILITY), "the expected text is on screen", "turn on Aniob in Settings > Accessibility"),
    MEDIA_STATE(setOf(Privilege.NOTIFICATION_LISTENER), "the media session reports the new state", "turn on notification access for Aniob"),
    HARDWARE_CALLBACK(emptySet(), "the hardware reports the new state", null)
}

/** A step only the user (or a privileged channel) can complete. */
enum class ActionGate(val bypassedBy: Privilege?, val userAction: String) {
    NONE(null, ""),
    SYSTEM_PACKAGE_DIALOG(Privilege.SHIZUKU_ADB, "tap Install / Uninstall in the Android system dialog"),
    RUNTIME_PERMISSION_DIALOG(Privilege.SHIZUKU_ADB, "tap Allow in the Android permission dialog"),
    SECURE_SETTING_TOGGLE(Privilege.SHIZUKU_ADB, "switch the protected setting on yourself"),
    BIOMETRIC_OR_CREDENTIAL(null, "authenticate with your fingerprint, face or PIN"),
    PAYMENT_CONFIRM(null, "confirm the payment yourself")
}

data class PlanStep(
    val description: String,
    val gate: ActionGate = ActionGate.NONE,
    val postconditions: Set<Postcondition> = emptySet()
)

sealed interface Feasibility {
    data object Feasible : Feasibility

    /** Terminate cleanly as DELEGATED (or ask via Grill-Me and proceed). Never a claimed success, never a crash. */
    data class DelegateToUser(
        val step: PlanStep,
        val whatUserMustDo: String,
        val question: GrillQuestion
    ) : Feasibility

    /** Dispatching would produce a result nothing could observe. Say what to enable. */
    data class Infeasible(val step: PlanStep, val reason: String, val enable: String?) : Feasibility
}

object AniobFeasibility {

    const val OPT_WAIT = "I'll do it — wait for me"
    const val OPT_SKIP = "Skip this step"
    const val OPT_CANCEL = "Cancel the task"

    fun assess(steps: List<PlanStep>, envelope: CapabilityEnvelope): Feasibility {
        // 1) Observability first: a post-condition nobody can read is not worth dispatching.
        for (step in steps) for (pc in step.postconditions) {
            if (pc.needsAnyOf.isNotEmpty() && pc.needsAnyOf.none(envelope::has)) {
                return Feasibility.Infeasible(
                    step,
                    "I could not tell whether \"${step.description}\" worked: I would have to see that ${pc.humanName}, and this phone has not granted that.",
                    pc.enableHint
                )
            }
        }
        // 2) Gates: the first step that needs a human (or a privilege we do not hold).
        for (step in steps) {
            val gate = step.gate
            if (gate == ActionGate.NONE) continue
            val bypass = gate.bypassedBy
            if (bypass == null || !envelope.has(bypass)) {
                val todo = gate.userAction
                return Feasibility.DelegateToUser(
                    step = step,
                    whatUserMustDo = "I can set this up, but you need to $todo for \"${step.description}\".",
                    question = GrillQuestion(
                        id = "delegate.${gate.name.lowercase()}",
                        question = "I can't $todo for you on this phone. How should I continue?",
                        options = listOf(OPT_WAIT, OPT_SKIP, OPT_CANCEL),
                        allowFreeText = false,
                        defaultOption = OPT_WAIT
                    )
                )
            }
        }
        return Feasibility.Feasible
    }
}
