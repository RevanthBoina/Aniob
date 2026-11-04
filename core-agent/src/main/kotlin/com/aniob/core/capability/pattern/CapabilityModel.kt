package com.aniob.core.capability.pattern

import com.aniob.core.domain.DeviceCommand

/**
 * Capability-first execution model.
 *
 * The 2026-11-04 report was fixed twice at the level of individual prompts (a LinkedIn table, a
 * torch phrase list). This module exists to remove that failure mode from the architecture:
 *
 *  - a capability is declared ONCE with its slots, dispatch and **machine-checkable postcondition**;
 *  - entity slots accept ANY value and are resolved through the phone's own registries;
 *  - a plan is rejected at construction when an atom has no provable terminal state;
 *  - completion is a pure function of (plan, evidence), never of "the action was dispatched".
 *
 * Adding a new capability therefore means adding one [CapabilitySpec]; no planner, verifier or
 * report code needs to know the capability's name. That property is covered by
 * UniversalPatternTest (`aToZCapabilityCoverage`, `everyCapabilityNeedsEvidence`).
 */
enum class CapabilityId(val deviceDomain: Boolean, val sequencing: Boolean = false) {
    TORCH(true, sequencing = true),
    INSTALL_APP(true),
    OPEN_APP(true),
    SEND_MESSAGE(true),
    CALL(true),
    NAVIGATE(true),
    SET_VOLUME(true, sequencing = true),
    DND(true),
    SCREENSHOT(true),
    QUERY(true)
}

/** Slot values are runtime text — never enumerated target names. */
data class Slots(
    val target: String? = null,
    val secondary: String? = null,
    val payload: String? = null,
    val channel: String? = null,
    val percent: Int? = null,
    val on: Boolean? = null,
    val delayMs: Long? = null
)

/**
 * What must be TRUE in the world for the atom to count as done.
 * There is intentionally no "dispatched" member: dispatch is a cause, not a proof.
 */
sealed class Postcondition {
    abstract val human: String

    data class TorchIs(val on: Boolean) : Postcondition() {
        override val human: String get() = "torch is ${if (on) "ON" else "OFF"}"
    }

    /** [pkg] null = the target could not be named, therefore it can never be proven installed. */
    data class PackageInstalled(val pkg: String?) : Postcondition() {
        override val human: String get() = pkg?.let { "$it is installed" } ?: "an unnamed package is installed"
    }

    data class ForegroundPackage(val pkg: String?) : Postcondition() {
        override val human: String get() = pkg?.let { "$it is foreground" } ?: "an unnamed package is foreground"
    }

    /** The OS itself acknowledged a fire-and-forget command (screenshot, DND toggle…). */
    data class OsAcknowledged(val operation: String) : Postcondition() {
        override val human: String get() = "$operation was acknowledged by Android"
    }

    /** Deliberately unprovable. The planner refuses to emit it; it exists to make that testable. */
    object Unprovable : Postcondition() {
        override val human: String get() = "no provable terminal state"
    }
}

/** A plan atom: one capability invocation with its contract. */
data class PlanAtom(
    val capability: CapabilityId,
    val summary: String,
    val postcondition: Postcondition,
    val deviceCommand: DeviceCommand? = null,
    val args: Map<String, String> = emptyMap(),
    val intentUri: String? = null,
    val targetPackage: String? = null,
    val preferredPackage: String? = null,
    val requiresObservation: Boolean = false,
    val delayAfterMs: Long = 0L
) {
    init {
        require(postcondition != Postcondition.Unprovable) {
            "Capability ${capability.name} emitted an atom with no verification contract"
        }
    }
}

/** An ordered, verifiable plan. Ambiguity is represented, never guessed away. */
data class Plan(
    val prompt: String,
    val atoms: List<PlanAtom> = emptyList(),
    val clarifications: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {
    val isExecutable: Boolean get() = atoms.isNotEmpty() && clarifications.isEmpty()
    val needsClarification: Boolean get() = atoms.isEmpty() && clarifications.isNotEmpty()

    /** The final postcondition of the plan — the state a completion claim must prove. */
    val terminalPostcondition: Postcondition?
        get() = atoms.lastOrNull()?.postcondition

    companion object {
        fun clarify(prompt: String, question: String, note: String) =
            Plan(prompt = prompt, clarifications = listOf(question), notes = listOf(note))
    }
}

/** Declarative capability. `match` reads grammar only; it must not enumerate targets. */
data class CapabilitySpec(
    val id: CapabilityId,
    val description: String,
    /** Verb ontology this spec owns; used for diagnostics and collision tests. */
    val verbs: Set<String>,
    val match: (String) -> Slots?
)

/** Evidence collected from the device. Missing = unknown, never false-success. */
data class Evidence(
    val installed: Map<String, Boolean> = emptyMap(),
    val foregroundPackage: String? = null,
    val torchOn: Boolean? = null,
    val acknowledged: Set<String> = emptySet(),
    val visibleText: List<String> = emptyList()
) {
    companion object { val EMPTY = Evidence() }
}

enum class AtomVerdict { PROVEN, DISPROVEN, UNKNOWN }

data class AtomCheck(val atom: PlanAtom, val verdict: AtomVerdict, val detail: String)

sealed class OutcomeDecision {
    data class Completed(val checks: List<AtomCheck>) : OutcomeDecision()
    data class NotYet(val reason: String, val checks: List<AtomCheck>) : OutcomeDecision()
    data class Failed(val reason: String, val checks: List<AtomCheck>) : OutcomeDecision()
    data class NeedsClarification(val question: String) : OutcomeDecision()
}
