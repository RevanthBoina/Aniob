package com.aniob.core.domain

/**
 * Canonical action set for the Aniob autonomous agent (v2, semantic-only).
 *
 * Invariant: **no LLM-reachable field can express a coordinate.** [Tap], [LongPress] and
 * [InputText] name their element with a [SemanticTarget] only. Pixels are derived from the
 * live tree by `AniobGroundingResolver` at dispatch time. Any x/y a model might emit is
 * rejected by `AniobActionSchema.parseActionJson` with a field-level error.
 *
 * Strictly pure Kotlin JVM domain model.
 */
sealed class AniobAction {
    abstract val thought: String
    abstract val toolName: String

    // 1. tap
    data class Tap(
        val target: SemanticTarget,
        override val thought: String = "Tapping target element"
    ) : AniobAction() {
        override val toolName: String = "tap"
        fun describeTarget(): String = target.describe()
    }

    // 2. long_press
    data class LongPress(
        val target: SemanticTarget,
        val durationMs: Long = 1000L,
        override val thought: String = "Long pressing target element"
    ) : AniobAction() {
        override val toolName: String = "long_press"
        fun describeTarget(): String = target.describe()
    }

    // 3. swipe
    data class Swipe(
        val direction: SwipeDirection,
        val distancePx: Int = 600,
        val container: SemanticTarget? = null,
        override val thought: String = "Swiping $direction"
    ) : AniobAction() {
        override val toolName: String = "swipe"
    }

    // 4. input_text
    /**
     * [submit] presses the IME action (search/go/enter) after the text is committed. A search box
     * that only holds the query is not a search, and every extra step spent hunting a hidden
     * keyboard button is a step the model has to pay for.
     */
    data class InputText(
        val target: SemanticTarget,
        val text: String,
        val clearFirst: Boolean = false,
        val submit: Boolean = false,
        override val thought: String = "Typing text into target element"
    ) : AniobAction() {
        override val toolName: String = "input_text"
        fun describeTarget(): String = target.describe()
    }

    // 5. open_app
    data class OpenApp(
        val packageName: String,
        val activityName: String? = null,
        override val thought: String = "Opening application: $packageName"
    ) : AniobAction() {
        override val toolName: String = "open_app"
    }

    // 6. get_screen_info
    data class GetScreenInfo(
        override val thought: String = "Capturing screen node hierarchy and metadata"
    ) : AniobAction() {
        override val toolName: String = "get_screen_info"
    }

    // 7. take_screenshot
    data class TakeScreenshot(
        override val thought: String = "Capturing visual screenshot for multi-modal analysis"
    ) : AniobAction() {
        override val toolName: String = "take_screenshot"
    }

    // 8. get_device_info
    data class GetDeviceInfo(
        override val thought: String = "Querying device state, battery, and network status"
    ) : AniobAction() {
        override val toolName: String = "get_device_info"
    }

    // 9. get_notifications
    data class GetNotifications(
        val filterPackage: String? = null,
        override val thought: String = "Reading active notification stream"
    ) : AniobAction() {
        override val toolName: String = "get_notifications"
    }

    // 10. get_installed_apps
    data class GetInstalledApps(
        override val thought: String = "Listing launchable user applications"
    ) : AniobAction() {
        override val toolName: String = "get_installed_apps"
    }

    // 11. clipboard
    data class Clipboard(
        val operation: ClipboardOp = ClipboardOp.GET,
        val text: String? = null,
        override val thought: String = "Operating clipboard: $operation"
    ) : AniobAction() {
        override val toolName: String = "clipboard"
    }

    // 12. system_key (alias PressKey)
    data class SystemKey(
        val key: KeyType,
        override val thought: String = "Triggering system key $key"
    ) : AniobAction() {
        override val toolName: String = "system_key"
    }

    data class PressKey(
        val key: KeyType,
        override val thought: String = "Pressing $key key"
    ) : AniobAction() {
        override val toolName: String = "system_key"
    }

    // 13. wait — duration clamped so a model cannot stall the loop indefinitely
    data class Wait(
        val durationMs: Long = DEFAULT_WAIT_MS,
        override val thought: String = "Waiting ${durationMs}ms for UI to settle"
    ) : AniobAction() {
        override val toolName: String = "wait"

        /** Returns a copy whose duration lies within [MIN_WAIT_MS]..[MAX_WAIT_MS]. */
        fun clamped(): Wait = copy(durationMs = durationMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))

        companion object {
            const val MIN_WAIT_MS = 200L
            const val MAX_WAIT_MS = 5000L
            const val DEFAULT_WAIT_MS = 1000L
        }
    }

    // 14. confirm_with_user
    data class ConfirmWithUser(
        val message: String,
        val riskLevel: String = "HIGH",
        override val thought: String = "Requesting explicit user confirmation: $message"
    ) : AniobAction() {
        override val toolName: String = "confirm_with_user"
    }

    // 15. finish
    data class Finish(
        val summary: String = "Task completed successfully",
        override val thought: String = "Goal accomplished"
    ) : AniobAction() {
        override val toolName: String = "finish"
    }

    // 16. device_control — direct, UI-free device commands (media, volume, torch, calls, OTP, files...).
    /**
     * A command from the closed [DeviceCommand] catalogue plus its string arguments. Like every other
     * action it can express no coordinate; unlike a tap it needs no screen, so it executes in tens of
     * milliseconds and its result text is fed back to the model on the next step.
     */
    data class DeviceControl(
        val command: DeviceCommand,
        val args: Map<String, String> = emptyMap(),
        override val thought: String = "Running device command ${command.wire}"
    ) : AniobAction() {
        override val toolName: String = "device_control"
    }

    // Execution failure state
    data class Fail(
        val reason: String,
        override val thought: String = "Execution failed: $reason"
    ) : AniobAction() {
        override val toolName: String = "fail"
    }

    /** Semantic identity of this action for logs, watchdogs and learning signatures. */
    fun describeAction(): String = when (this) {
        is Tap -> "TAP_${target.describe()}"
        is LongPress -> "LONG_PRESS_${target.describe()}"
        is InputText -> "INPUT_${target.describe()}_${text.hashCode()}"
        is Swipe -> "SWIPE_$direction"
        is OpenApp -> "OPEN_APP_$packageName"
        is SystemKey -> "SYSTEM_KEY_$key"
        is PressKey -> "PRESS_KEY_$key"
        is Wait -> "WAIT_${durationMs}"
        is Finish -> "FINISH"
        is Fail -> "FAIL"
        is ConfirmWithUser -> "CONFIRM"
        is DeviceControl -> "DEVICE_${command.wire}"
        else -> toolName
    }

    /** The element this action targets, or null for whole-screen/global actions. */
    fun semanticTarget(): SemanticTarget? = when (this) {
        is Tap -> target
        is LongPress -> target
        is InputText -> target
        else -> null
    }
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class KeyType {
    BACK, HOME, ENTER, RECENTS, VOLUME_UP, VOLUME_DOWN
}

enum class ClipboardOp {
    GET, SET
}