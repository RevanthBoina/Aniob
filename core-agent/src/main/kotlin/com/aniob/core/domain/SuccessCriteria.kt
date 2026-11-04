package com.aniob.core.domain

/**
 * Deterministically checkable success conditions for a task.
 *
 * Extracted from the raw prompt (and grill-me answers) before step 0 so that a `Finish`
 * proposal can be *disproved* without an LLM. Any criterion left empty is simply not checked;
 * [isFullyCheckable] reports whether a deterministic verdict is possible at all.
 */
enum class CriterionState {
    PASSED,
    FAILED,
    INCONCLUSIVE,
    UNCHECKED
}

/**
 * A world state the screen can never show: hardware modes and device-side facts. These are probed
 * on the device (app module), never read from pixels — a SUCCESS stamped without them is how the
 * 2026-11-04 report claimed "flashlight on" while the torch stayed off.
 */
enum class DeviceAssertion {
    TORCH_ON,
    TORCH_OFF
}

data class CriterionEvaluation(
    val description: String,
    val state: CriterionState,
    val detail: String = "",
    val textEvidence: String? = null
)

/**
 * Deterministically checkable success conditions for a task.
 *
 * Extracted from the raw prompt (and grill-me answers) before step 0 so that a `Finish`
 * proposal can be *disproved* without an LLM. Any criterion left empty is simply not checked;
 * [isFullyCheckable] reports whether a deterministic verdict is possible at all.
 */
data class SuccessCriteria(
    val mustContainText: List<String> = emptyList(),
    val mustShowPackage: List<String> = emptyList(),
    val mustShowActivity: List<String> = emptyList(),
    val minSteps: Int = 0,
    /** Hardware/world assertions probed on the device ([DeviceAssertion]); invisible to the screen. */
    val deviceAsserts: List<DeviceAssertion> = emptyList(),
    /** Package ids that must actually be reported installed by PackageManager. */
    val mustBeInstalled: List<String> = emptyList(),
    /**
     * True for an install prompt. When [mustBeInstalled] is empty (unknown target) the app layer
     * cannot prove the install and must not claim SUCCESS on the store page alone.
     */
    val requireInstallConfirmation: Boolean = false
) {
    val isEmpty: Boolean
        get() = mustContainText.isEmpty() && mustShowPackage.isEmpty() &&
            mustShowActivity.isEmpty() && minSteps == 0 &&
            deviceAsserts.isEmpty() && mustBeInstalled.isEmpty() && !requireInstallConfirmation

    /** True when a deterministic check needs a DEVICE probe (not only a screen read). */
    val hasDeviceChecks: Boolean
        get() = deviceAsserts.isNotEmpty() || mustBeInstalled.isNotEmpty()

    /** True when an observable SCREEN check is present. */
    val hasScreenCheck: Boolean
        get() = mustContainText.isNotEmpty() || mustShowPackage.isNotEmpty() || mustShowActivity.isNotEmpty()

    /**
     * A verdict is deterministic when ANY check is present — screen OR device. minSteps alone is
     * never sufficient evidence. Device assertions MUST be evaluated with a [DeviceStateSnapshot];
     * an unknown probe is INCONCLUSIVE, never a pass.
     */
    val hasObservableCheck: Boolean
        get() = hasScreenCheck || hasDeviceChecks

    /** A verdict is deterministic only when an observable check is present; minSteps alone is never sufficient evidence. */
    val isFullyCheckable: Boolean get() = hasObservableCheck

    /**
     * Evaluates all individual criteria against the observed screen and step count.
     */
    fun evaluateCriteria(
        screen: AniobScreenState,
        steps: Int,
        device: DeviceStateSnapshot = DeviceStateSnapshot.UNKNOWN
    ): List<CriterionEvaluation> {
        val evaluations = mutableListOf<CriterionEvaluation>()

        mustContainText.forEach { needle ->
            val matchingNode = screen.nodes.firstOrNull {
                it.text.contains(needle, ignoreCase = true) ||
                    it.contentDescription.contains(needle, ignoreCase = true)
            }
            if (matchingNode != null) {
                val matchedText = matchingNode.text.ifBlank { matchingNode.contentDescription }
                evaluations += CriterionEvaluation(
                    description = "Text '$needle'",
                    state = CriterionState.PASSED,
                    detail = "Found on screen: '$matchedText'",
                    textEvidence = matchedText
                )
            } else {
                evaluations += CriterionEvaluation(
                    description = "Text '$needle'",
                    state = CriterionState.FAILED,
                    detail = "Expected text '$needle' not present"
                )
            }
        }

        mustShowPackage.forEach { pkg ->
            if (screen.packageName.equals(pkg, ignoreCase = true)) {
                evaluations += CriterionEvaluation(
                    description = "Package '$pkg'",
                    state = CriterionState.PASSED,
                    detail = "Foreground package matches",
                    textEvidence = screen.packageName
                )
            } else {
                evaluations += CriterionEvaluation(
                    description = "Package '$pkg'",
                    state = CriterionState.FAILED,
                    detail = "Expected package '$pkg' not foreground (was '${screen.packageName}')"
                )
            }
        }

        mustShowActivity.forEach { activity ->
            if (screen.activityName.isBlank()) {
                evaluations += CriterionEvaluation("Activity '$activity'", CriterionState.INCONCLUSIVE, "Activity identity unknown")
            } else {
                val short = activity.substringAfterLast('.')
                if (screen.activityName.contains(short, ignoreCase = true)) {
                    evaluations += CriterionEvaluation(
                        description = "Activity '$activity'",
                        state = CriterionState.PASSED,
                        detail = "Activity matches",
                        textEvidence = screen.activityName
                    )
                } else {
                    evaluations += CriterionEvaluation(
                        description = "Activity '$activity'",
                        state = CriterionState.FAILED,
                        detail = "Expected activity '$activity' not shown (was '${screen.activityName}')"
                    )
                }
            }
        }

        evaluations += evaluateDeviceCriteria(device)

        if (minSteps > 0) {
            if (steps >= minSteps) {
                evaluations += CriterionEvaluation("Min steps ($minSteps)", CriterionState.PASSED, "Took $steps steps", textEvidence = "$steps steps")
            } else {
                evaluations += CriterionEvaluation("Min steps ($minSteps)", CriterionState.FAILED, "Expected at least $minSteps steps, took $steps")
            }
        }

        return evaluations
    }

    /**
     * Evaluates ONLY the device-side criteria. Public so the app layer can merge hardware/package
     * evidence into its final verdict without re-implementing the semantics (unknown = inconclusive).
     */
    fun evaluateDeviceCriteria(device: DeviceStateSnapshot): List<CriterionEvaluation> {
        val evaluations = mutableListOf<CriterionEvaluation>()

        deviceAsserts.forEach { assertion ->
            val expected = when (assertion) {
                DeviceAssertion.TORCH_ON -> true
                DeviceAssertion.TORCH_OFF -> false
            }
            val label = if (expected) "torch on" else "torch off"
            when (val actual = device.torchOn) {
                null -> evaluations += CriterionEvaluation(
                    "Device state: $label", CriterionState.INCONCLUSIVE,
                    "Torch state is not observable on this device"
                )
                expected -> evaluations += CriterionEvaluation(
                    "Device state: $label", CriterionState.PASSED,
                    "CameraManager reports torch ${if (actual) "ON" else "OFF"}",
                    textEvidence = "torch=${if (actual) "ON" else "OFF"}"
                )
                else -> evaluations += CriterionEvaluation(
                    "Device state: $label", CriterionState.FAILED,
                    "CameraManager reports torch ${if (actual) "ON" else "OFF"} — expected $label"
                )
            }
        }

        mustBeInstalled.forEach { pkg ->
            when (val installed = device.installedPackages[pkg]) {
                null -> evaluations += CriterionEvaluation(
                    "Device state: $pkg installed", CriterionState.INCONCLUSIVE,
                    "PackageManager was not queried for '$pkg'"
                )
                true -> evaluations += CriterionEvaluation(
                    "Device state: $pkg installed", CriterionState.PASSED,
                    "PackageManager reports '$pkg' installed", textEvidence = pkg
                )
                false -> evaluations += CriterionEvaluation(
                    "Device state: $pkg installed", CriterionState.FAILED,
                    "PackageManager does not report '$pkg' installed — opening the listing is not installing"
                )
            }
        }

        return evaluations
    }

    /**
     * Checks [screen] (the final observed state) against the criteria.
     * Returns the list of unmet criteria; empty means the criteria are satisfied.
     */
    fun unmet(
        screen: AniobScreenState,
        steps: Int,
        device: DeviceStateSnapshot = DeviceStateSnapshot.UNKNOWN
    ): List<String> {
        val evaluations = evaluateCriteria(screen, steps, device)
        return evaluations
            .filter { it.state == CriterionState.FAILED || it.state == CriterionState.INCONCLUSIVE }
            .map { it.detail.ifBlank { it.description } }
    }

    fun describeList(): List<String> {
        val list = mutableListOf<String>()
        mustContainText.forEach { list.add("Must contain text: $it") }
        mustShowPackage.forEach { list.add("Must show package: $it") }
        mustShowActivity.forEach { list.add("Must show activity: $it") }
        if (minSteps > 0) list.add("At least $minSteps steps")
        deviceAsserts.forEach { list.add("Device state must be: $it") }
        mustBeInstalled.forEach { list.add("Package must be installed: $it") }
        if (requireInstallConfirmation && mustBeInstalled.isEmpty()) {
            list.add("Install target must be resolved so PackageManager can confirm it")
        }
        return list
    }
}