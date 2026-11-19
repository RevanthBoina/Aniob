package com.aniob.core.capability

import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.DeviceCommandGroup
import com.aniob.core.domain.DeviceRisk

/**
 * What the model is told about itself.
 *
 * A model that has never been told it is wired into a phone answers "I can't access your device".
 * This manifest is the cure: one block, generated from [DeviceCommand] (so it cannot drift from what
 * the schema accepts or the APK implements), appended to every executor system prompt. Two sizes:
 *
 *  - [render] full — for cloud models: capability statement, speed rules, command catalogue with a
 *    one-line purpose each, and live permission status.
 *  - [render] compact — for on-device SLMs: the same facts as terse `name(args)` lines, because a
 *    3B model pays for every prompt token in latency.
 *
 * The live [DeviceState] keeps the statement *true*: the model is told when notification access or
 * the accessibility service is off, so it routes around the gap (or FAILs with the exact setting to
 * enable) instead of emitting a command that cannot run.
 *
 * Pure Kotlin JVM: no `android.*` imports.
 */
object AniobCapabilityManifest {

    /** Runtime availability of the things some commands depend on. Defaults assume "available". */
    data class DeviceState(
        val accessibility: Boolean = true,
        val notificationAccess: Boolean = true,
        val callPhone: Boolean = true,
        val contacts: Boolean = true,
        val location: Boolean = true,
        val allFilesAccess: Boolean = true,
        val dndAccess: Boolean = true
    )

    /**
     * Why [command] cannot run right now, as an instruction the user can act on; null when it can.
     *
     * Enforces all runtime gates in [DeviceState]: accessibility, notification access, DND access,
     * location, phone call permission ([DeviceState.callPhone]), contacts lookup
     * ([DeviceState.contacts]), and external storage / all-files access ([DeviceState.allFilesAccess]).
     * When [args] is supplied (e.g. an explicit phone number instead of a contact name, or an
     * explicit SAF `uri` for `file_open`/`file_read`), commands that do not need the missing
     * permission remain permitted; when [args] is empty (capability catalogue inspection),
     * permission-gated commands are reported unavailable whenever their permission is off.
     */
    fun blockedReason(
        command: DeviceCommand,
        state: DeviceState,
        args: Map<String, String> = emptyMap()
    ): String? = when {
        command.needsAccessibility && !state.accessibility ->
            "Turn on Aniob in Settings > Accessibility"
        command.needsNotificationAccess && !state.notificationAccess ->
            "Turn on Aniob in Settings > Notifications > Device & app notifications (notification access)"
        (command == DeviceCommand.DND_ON || command == DeviceCommand.DND_OFF) && !state.dndAccess ->
            "Allow Aniob in Settings > Notifications > Do Not Disturb access"
        command == DeviceCommand.LOCATION_SHARE && !state.location ->
            "Allow location for Aniob in the app's permissions"
        command == DeviceCommand.CALL && !state.callPhone ->
            "Allow Phone permission (CALL_PHONE) for Aniob in Settings > Apps > Aniob > Permissions"
        needsContactsFor(command, args) && !state.contacts ->
            "Allow Contacts permission for Aniob in Settings > Apps > Aniob > Permissions"
        needsAllFilesFor(command, args) && !state.allFilesAccess ->
            "Allow \"All files access\" for Aniob in Settings > Apps > Special app access > All files access"
        else -> null
    }

    private fun needsContactsFor(command: DeviceCommand, args: Map<String, String>): Boolean {
        if (command != DeviceCommand.CALL &&
            command != DeviceCommand.MESSAGE_WHATSAPP &&
            command != DeviceCommand.MESSAGE_TELEGRAM &&
            command != DeviceCommand.MESSAGE_SMS
        ) return false
        if (args.isEmpty()) return true
        if (!args["contact"].isNullOrBlank()) return true
        val hasDirectNumber = !args["number"].isNullOrBlank() || !args["phone"].isNullOrBlank()
        if (hasDirectNumber) return false
        if (command == DeviceCommand.MESSAGE_TELEGRAM) {
            val to = args["to"].orEmpty().trim()
            if (to.startsWith("@") || (to.isNotEmpty() && to.none { it.isLetter() })) return false
        }
        return true
    }

    private fun needsAllFilesFor(command: DeviceCommand, args: Map<String, String>): Boolean {
        if (command != DeviceCommand.FILE_FIND &&
            command != DeviceCommand.FILE_OPEN &&
            command != DeviceCommand.FILE_READ
        ) return false
        if ((command == DeviceCommand.FILE_OPEN || command == DeviceCommand.FILE_READ) &&
            !args["uri"].isNullOrBlank()
        ) return false
        return true
    }

    /** True when [command] can actually execute under the current runtime permissions/services. */
    fun isCommandExecutable(command: DeviceCommand, state: DeviceState): Boolean =
        blockedReason(command, state) == null

    /** Only the [DeviceCommand]s executable in the current runtime [state]. */
    fun availableCommands(state: DeviceState = DeviceState()): List<DeviceCommand> =
        DeviceCommand.entries.filter { isCommandExecutable(it, state) }

    /** Generic action tools executable in the current runtime [state] (no app-specific logic). */
    fun availableActionTools(state: DeviceState = DeviceState()): List<String> = buildList {
        if (state.accessibility) {
            add("TAP")
            add("LONG_PRESS")
            add("INPUT_TEXT")
            add("SWIPE")
            add("SYSTEM_KEY")
        }
        add("OPEN_APP")
        if (availableCommands(state).isNotEmpty()) {
            add("DEVICE_CONTROL")
        }
        add("WAIT")
        add("FINISH")
        add("FAIL")
    }

    /** Single sentence used by answer-only prompts so even a Q&A reply never denies the capability. */
    const val IDENTITY_LINE: String =
        "Aniob is wired into this Android phone and operates it for the user: it can open apps, tap, type, " +
            "scroll, take screenshots, read notifications and the clipboard, control media, volume, the " +
            "flashlight, calls, messages, alarms, calendar, maps and files. If asked whether it can do " +
            "something on the phone, answer yes and offer to do it. Never claim to lack access to the " +
            "device, and never invent device data that was not provided."

    /** Fed back when a model replied with a refusal instead of an action. */
    const val REFUSAL_CORRECTION: String =
        "That reply refused the task, which is wrong here: you ARE connected to this phone and can operate " +
            "it (screen taps, typing, DEVICE_CONTROL commands, opening apps). Do not apologise or explain. " +
            "Pick the fastest route and reply with ONE JSON action. If only the user can unblock it, " +
            "reply FAIL with the exact setting to turn on."

    fun render(
        state: DeviceState = DeviceState(),
        compact: Boolean = false,
        executableOnly: Boolean = false
    ): String = if (compact) compactText(state, executableOnly) else fullText(state, executableOnly)

    private fun fullText(state: DeviceState, executableOnly: Boolean): String = buildString {
        val allowedTools = availableActionTools(state)
        appendLine("DEVICE CAPABILITIES (read first):")
        if (executableOnly && !state.accessibility) {
            appendLine(
                "You are connected to this Android phone. Accessibility screen control is currently OFF, " +
                    "so UI gestures (TAP, LONG_PRESS, INPUT_TEXT, SWIPE, SYSTEM_KEY) are NOT executable right now. " +
                    "Available tools: ${allowedTools.joinToString(", ")}. Use an available DEVICE_CONTROL command, " +
                    "OPEN_APP, or FAIL naming Settings > Accessibility if screen control is required."
            )
        } else {
            appendLine(
                "You are connected to this Android phone and operate it for the user: you see the screen, tap, " +
                    "type, scroll, open any installed app, press Back/Home/Recents, take screenshots, read the " +
                    "clipboard and notifications, and control media, volume, flashlight, calls, messages, " +
                    "alarms, timers, calendar, maps, files and storage."
            )
            if (executableOnly) {
                appendLine("Available tools right now: ${allowedTools.joinToString(", ")}.")
            }
        }
        appendLine(
            "NEVER say you cannot operate the phone, lack access to it, or are only a text model: that is " +
                "false here. If one route is blocked, take another (a different command, the screen, the web). " +
                "If only the user can unblock it (a permission or setting), reply FAIL naming that exact setting."
        )
        appendLine(
            "SPEED: (1) if a DEVICE_CONTROL command does the job, use it: no app opening, no taps. " +
                "(2) else OPEN_APP or a deep-link command. (3) else drive the UI. Never open an app just to " +
                "read a code, change volume, toggle the flashlight, start a timer or skip a track."
        )
        appendLine("DEVICE_CONTROL: {\"thought\":\"...\",\"tool\":\"DEVICE_CONTROL\",\"command\":\"<name>\",\"args\":{\"key\":\"value\"}}")
        appendLine(
            "One command per step; its answer appears in the next step's history as \"ExecutionResult ...\". " +
                "Reply FINISH only when observed evidence confirms the goal. Commands marked (asks) " +
                "are confirmed with the user before they run. Verification codes are never shown to you: " +
                "use otp_copy, then clipboard_paste or otp_paste."
        )
        appendLine("Commands:")
        for (group in DeviceCommandGroup.entries) {
            val commands = DeviceCommand.entries.filter { c ->
                c.group == group && (!executableOnly || isCommandExecutable(c, state))
            }
            if (commands.isEmpty()) continue
            appendLine("[${group.label}]")
            for (c in commands) {
                val asks = if (c.risk == DeviceRisk.CONFIRM) " (asks)" else ""
                val blocked = blockedReason(c, state)?.let { " [OFF: $it]" }.orEmpty()
                appendLine("- ${c.usage()}$asks: ${c.summary}$blocked")
            }
        }
        append(statusLines(state))
    }.trimEnd()

    private fun compactText(state: DeviceState, executableOnly: Boolean): String = buildString {
        val allowedTools = availableActionTools(state)
        if (executableOnly && !state.accessibility) {
            appendLine("DEVICE CAPABILITIES: Accessibility is OFF (no TAP/INPUT/SWIPE). Tools: ${allowedTools.joinToString(",")}.")
        } else {
            appendLine("DEVICE CAPABILITIES: you operate this Android phone (screen, apps, calls, media, files). Never say you can't or lack access; pick another route, or FAIL naming the setting to enable.")
        }
        appendLine("Prefer DEVICE_CONTROL (no UI, instant): {\"tool\":\"DEVICE_CONTROL\",\"command\":\"name\",\"args\":{\"k\":\"v\"}}. Result shows in next ExecutionResult. FINISH after verified evidence.")
        for (group in DeviceCommandGroup.entries) {
            val commands = DeviceCommand.entries.filter { c ->
                c.group == group && (!executableOnly || isCommandExecutable(c, state))
            }
            if (commands.isEmpty()) continue
            val line = commands.joinToString("; ") { c ->
                val off = if (blockedReason(c, state) != null) "[off]" else ""
                c.usage() + off
            }
            appendLine("${group.label}: $line")
        }
        append(statusLines(state, brief = true))
    }.trimEnd()

    private fun statusLines(state: DeviceState, brief: Boolean = false): String = buildString {
        val notes = mutableListOf<String>()
        if (!state.accessibility) {
            notes += "Accessibility is OFF: no screen control until the user enables Aniob in Settings > Accessibility."
        }
        if (!state.notificationAccess) {
            notes += "Notification access is OFF: notification_* and otp_* and media_now_playing cannot run."
        }
        if (!state.callPhone) {
            notes += "Phone permission (CALL_PHONE) is OFF: call cannot run until enabled in Settings > Apps > Aniob > Permissions."
        }
        if (!state.contacts) {
            notes += "Contacts permission is OFF: contact-name lookup for call/messages cannot run without Contacts permission."
        }
        if (!state.allFilesAccess) {
            notes += "All-files access is OFF: file_find/file_open/file_read storage queries cannot run; use doc_pick (system picker) or pass a SAF uri."
        }
        if (notes.isEmpty()) return@buildString
        if (!brief) appendLine("Live status:")
        notes.forEach { appendLine(if (brief) it else "- $it") }
    }.trimEnd().let { if (it.isEmpty()) "" else "\n$it" }
}
