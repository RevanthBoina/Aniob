package com.aniob.app.ui

import com.aniob.core.domain.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.device.AniobDeviceControl
import com.aniob.app.device.DeviceControlResult
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.Outcome
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.ResolvedIntentShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aniob.app.ui.AniobViewModel.Companion.SCROLL_STOPWORDS

internal fun AniobViewModel.promptKeywords(taskPrompt: String): List<String> =
    taskPrompt.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in SCROLL_STOPWORDS }
        .distinct()

/**
 * Waits for the window an intent opens, up to [timeoutMs], instead of a fixed delay: a cold
 * app start easily outlives 300 ms, and planning on the launcher costs a whole round trip.
 */
internal suspend fun AniobViewModel.awaitIntentScreen(beforePackage: String?, timeoutMs: Long, expectedPackage: String? = null): AniobScreenState? {
    val deadline = System.currentTimeMillis() + timeoutMs
    val service = AniobAccessibilityService.instance
    val chooserPackages = setOf("android", "com.android.intentresolver", "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.packageinstaller")
    while (System.currentTimeMillis() < deadline) {
        val screen = service?.captureCurrentScreenState()
        if (screen != null) {
            val isDifferent = beforePackage == null || !screen.packageName.equals(beforePackage, ignoreCase = true)
            val matchesExpected = expectedPackage != null && screen.packageName.equals(expectedPackage, ignoreCase = true)
            if (matchesExpected || (expectedPackage == null && isDifferent)) {
                val isChooser = screen.packageName in chooserPackages ||
                    screen.activityName.contains("Resolver", ignoreCase = true) ||
                    screen.activityName.contains("Chooser", ignoreCase = true)
                if (isChooser && expectedPackage != null && !screen.packageName.equals(expectedPackage, ignoreCase = true)) {
                    return null
                }
                return screen
            }
        }
        delay(120)
    }
    return null
}

/** Runs one direct device command on the thread it needs (accessibility calls: main; queries: IO). */
internal suspend fun AniobViewModel.runDeviceCommand(command: DeviceCommand, args: Map<String, String>): DeviceControlResult =
    withContext(if (command.needsAccessibility) Dispatchers.Main else Dispatchers.IO) {
        val fingerprint = args.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        var captured: DeviceControlResult? = null
        val outcome = app.deviceActionGateway.execute(
            taskId = _uiState.value.activeTask?.id,
            originAgent = "DEVICE_COMMAND:${_uiState.value.lastProviderUsed.ifBlank { "UNKNOWN" }}",
            signature = com.aniob.core.execution.DeviceActionGateway.deviceCommandSignature(command.wire, fingerprint),
            requestedAction = command.wire
        ) {
            val result = AniobDeviceControl.execute(app, command, args, AniobAccessibilityService.instance)
            captured = result
            com.aniob.core.execution.DeviceActionGateway.DispatchResult(result.ok, result.message.take(300))
        }
        captured ?: DeviceControlResult(
            ok = outcome.ok,
            message = if (outcome.deduplicated) "That action was already dispatched moments ago." else outcome.record.detail,
            informational = true
        )
    }

/**
 * Resolves a bare toggle's device noun from the last USER messages only. Assistant prose often
 * mentions hardware ("the flashlight is off") and must never be treated as user intent.
 */
internal fun AniobViewModel.resolveContextualGoal(prompt: String): String {
    if (!AniobTaskIntentClassifier.looksLikeBareToggle(prompt)) return prompt
    val implied = _uiState.value.chatMessages.asReversed()
        .filter { it.role == "user" }
        .take(6)
        .firstNotNullOfOrNull { AniobTaskIntentClassifier.impliedDeviceNoun(it.content) }
        ?: return prompt
    return "$prompt $implied"
}

/**
 * Live device snapshot for success criteria: torch mode from CameraManager's callback mirror,
 * installed state from PackageManager for exactly the packages the criteria mention.
 */
@Suppress("DEPRECATION")
internal fun AniobViewModel.probeDeviceState(criteria: SuccessCriteria?): DeviceStateSnapshot {
    val installed = criteria?.mustBeInstalled.orEmpty().associateWith { pkg ->
        runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
    return DeviceStateSnapshot(
        torchOn = AniobDeviceControl.currentTorchState(app),
        installedPackages = installed
    )
}

/** Device-side criteria evaluated against hardware/package state. Unknown is never success. */
internal fun AniobViewModel.evalDeviceAssertions(
    criteria: SuccessCriteria?,
    device: DeviceStateSnapshot = probeDeviceState(criteria)
): List<CriterionEvaluation> = criteria?.evaluateDeviceCriteria(device).orEmpty()

/** Reflex path: 0 tokens. A command that cannot run says which setting to enable, then planning continues. */
internal suspend fun AniobViewModel.runDeviceShortcut(command: DeviceCommand, args: Map<String, String>): Boolean {
    val result = runDeviceCommand(command, args)
    app.eventLogger.info("AniobViewModel", "Device reflex ${command.wire}: ok=${result.ok}")
    postDeviceResult(result)
    return result.ok
}

/**
 * Shows answers ("12 GB free") and actionable blocks ("turn on notification access") in the chat.
 * Silent successes stay silent. Results never contain a verification code, so this is safe to show.
 */
internal fun AniobViewModel.postDeviceResult(result: DeviceControlResult) {
    if (!result.informational && result.blockedBy == null) return
    val message = ChatMessage(
        id = "msg_device_${System.currentTimeMillis()}",
        role = "assistant",
        content = (if (result.ok) "\uD83D\uDCF1 " else "\u26A0\uFE0F ") + result.message,
        stepIndex = 0
    )
    _uiState.update { it.copy(chatMessages = it.chatMessages + message) }
}

internal suspend fun AniobViewModel.dispatchSystemIntent(shortcut: ResolvedIntentShortcut): Boolean {
    val systemAction = shortcut.extras[AniobIntentResolver.EXTRA_SYSTEM_ACTION]
    return try {
        when (systemAction) {
            AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT -> {
                // Route through the VERIFIED device-control path: the torch state is read back
                // after dispatch, so an unconfirmed toggle can no longer masquerade as success.
                val state = shortcut.extras["state"] ?: shortcut.extras["enabled"]
                val turnOff = state.equals("off", ignoreCase = true) || state.equals("false", ignoreCase = true) ||
                    shortcut.action.contains("off", ignoreCase = true)
                val cmd = if (turnOff) DeviceCommand.TORCH_OFF else DeviceCommand.TORCH_ON
                runDeviceCommand(cmd, emptyMap()).ok
            }
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO -> {
                val state = AniobDeviceTelemetry.getRealState(app)
                app.eventLogger.info(
                    "AniobViewModel",
                    "Device info: battery ${state.batteryPercent}% charging=${state.isCharging} " +
                        "thermal=${state.isThermalThrottled} network=${state.isNetworkAvailable} ram=${state.totalRamGb}GB"
                )
                // Surface the answer right back in the running transcript for M0-ExternalAI style tasks.
                val summary = "Battery ${state.batteryPercent}% (charging: ${if (state.isCharging) "yes" else "no"}). " +
                    "RAM ${state.totalRamGb}GB. Thermal throttled: ${state.isThermalThrottled}. " +
                    "Network available: ${state.isNetworkAvailable}."
                val deviceMsg = ChatMessage(
                    id = "msg_device_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = "📱 $summary",
                    stepIndex = 0
                )
                _uiState.update { it.copy(chatMessages = it.chatMessages + deviceMsg) }
                true
            }
            else -> {
                val launchCtx = AniobAccessibilityService.instance ?: AniobBackgroundController.currentActivity ?: app
                val intent = Intent(shortcut.action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    // A URI-addressed intent (dial, view, contact insert) must not be pinned to a
                    // package: the system picks the handler that can open it.
                    if (shortcut.uriString == null) {
                        // launchActivity is stored flattened ("<pkg>/<activity>");
                        // setClassName wants the class name only, and a flattened one builds a
                        // component that cannot be resolved.
                        val activityClass = AniobLaunchHub.activityClassName(shortcut.launchActivity)
                        val targetPkg = shortcut.targetPackage
                        if (activityClass != null && targetPkg != null) {
                            setClassName(targetPkg, activityClass)
                        } else {
                            targetPkg?.let { setPackage(it) }
                        }
                    }
                    shortcut.uriString?.let { data = Uri.parse(it) }
                    shortcut.category?.let { addCategory(it) }
                    shortcut.dataType?.let { type = it }
                    shortcut.extras.forEach { (key, value) -> putShortcutExtra(this, key, value) }
                }
                // One execution authority for the whole dispatch: near-simultaneous identical
                // requests are suppressed, and which agent fired which action is on record.
                val outcome = app.deviceActionGateway.execute(
                    taskId = _uiState.value.activeTask?.id,
                    originAgent = "VIEWMODEL:${_uiState.value.lastProviderUsed.ifBlank { "UNKNOWN" }}",
                    signature = com.aniob.core.execution.DeviceActionGateway.systemIntentSignature(
                        shortcut.action, shortcut.uriString, shortcut.targetPackage
                    ),
                    requestedAction = shortcut.action,
                    resolvedApplication = shortcut.targetPackage
                ) {
                    var launched = false
                    var how = ""
                    // "open linkedin in chrome": the browser is a preference. Try it pinned first; if it
                    // cannot take the URL (not installed, disabled) the system default still can.
                    val preferred = shortcut.preferredPackage
                    if (preferred != null && shortcut.uriString != null) {
                        if (startActivitySafely(launchCtx, Intent(intent).setPackage(preferred))) {
                            launched = true; how = "preferred $preferred"
                        } else {
                            app.eventLogger.info(
                                "AniobViewModel",
                                "$preferred could not open the URL; using the system default browser"
                            )
                        }
                    }
                    if (!launched && startActivitySafely(launchCtx, intent)) {
                        launched = true; how = "default handler"
                    }
                    // Play Store missing/disabled: open the web storefront so the install task
                    // still lands on a terminal page instead of failing silently (BUG-05).
                    if (!launched) {
                        shortcut.extras[AniobIntentResolver.EXTRA_INSTALL_PACKAGE]?.let { installPkg ->
                            val web = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=$installPkg")
                            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            if (startActivitySafely(launchCtx, web)) { launched = true; how = "web storefront" }
                        }
                    }
                    // Some launchers only resolve with the package's own launch intent.
                    if (!launched) {
                        val pkg = shortcut.targetPackage
                        val relaunch = pkg?.let { app.packageManager.getLaunchIntentForPackage(it) }
                        if (relaunch != null) {
                            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (startActivitySafely(launchCtx, relaunch)) { launched = true; how = "package launch intent" }
                        }
                    }
                    com.aniob.core.execution.DeviceActionGateway.DispatchResult(
                        launched, how.ifBlank { "no handler could take the action" }
                    )
                }
                outcome.ok
            }
        }
    } catch (e: Exception) {
        app.eventLogger.error("AniobViewModel", "Failed to launch system intent: ${e.message}")
        false
    }
}

internal fun AniobViewModel.startActivitySafely(context: android.content.Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        app.eventLogger.warn("AniobViewModel", "startActivity failed (${e.message})")
        false
    }

/**
 * Extra values arrive as strings from the pure-JVM resolver; the clock and contact intents are
 * typed, and passing "7" where an Int is expected makes the target app ignore the extra.
 */
internal fun AniobViewModel.putShortcutExtra(intent: Intent, key: String, value: String) {
    when (key) {
        AniobIntentResolver.EXTRA_ALARM_HOUR,
        AniobIntentResolver.EXTRA_ALARM_MINUTES,
        AniobIntentResolver.EXTRA_ALARM_LENGTH -> value.toIntOrNull()?.let { intent.putExtra(key, it) }
        AniobIntentResolver.EXTRA_ALARM_SKIP_UI -> intent.putExtra(key, value.toBoolean())
        else -> intent.putExtra(key, value)
    }
}

fun AniobViewModel.stopCurrentTask() {
    val stoppingGen = ++activeGeneration
    val currentTask = _uiState.value.activeTask
    app.taskOwner.stop(currentTask?.id)
    activeTaskJob?.cancel()
    activeTaskJob = null

    app.approvalController.clearForTask(currentTask?.id ?: "")
    targetMissLedger.clear()
    pendingTargetClarificationKey = null
    notificationGrillAnswers.remove(currentTask?.id)

    AniobAccessibilityService.isTaskActive = false
    AniobForegroundService.clearConfirmation(app)
    hippocampusTracker.onFailure()

    val stoppedSummary = buildSummary(
        prompt = currentTask?.rawPrompt ?: "Task",
        outcome = Outcome.STOPPED,
        steps = _uiState.value.currentStep,
        durationMs = 0L,
        provider = _uiState.value.lastProviderUsed.ifBlank { "NONE" },
        evidence = emptyList(),
        reason = "Stopped. Earlier changes were not undone."
    )
    postSummaryCard(stoppedSummary)
    AniobBackgroundController.onTaskFinished(
        app,
        "Stopped. Earlier changes were not undone.",
        isSuccess = false,
        openChat = false,
        wasStopped = true,
        resultTitle = com.aniob.app.outcome.AniobOutcomeCopy
            .forTask(Outcome.STOPPED, "Stopped. Earlier changes were not undone.").title
    )
    updateToIdle { it.copy(statusMessage = "Stopped. Earlier changes were not undone.", isStreaming = false, streamingBubbleText = "") }
}
