package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.grounding.AniobGroundingResolver

/**
 * Translates a semantic action into a concrete [DispatchCommand] against a live screen.
 *
 * Pure JVM. A target that cannot be grounded yields [DispatchCommand.NoOp] carrying a
 * `grounding_miss:` reason — never a fallback coordinate. Callers must treat a miss as a
 * recorded failure so the loop can scroll/reflect instead of tapping blind.
 */
object AniobActionExecutor {

    private const val SWIPE_FALLBACK_DISTANCE_PX = 600

    /**
     * @param liveScreen a fresh capture taken at dispatch time (never a cached planning screen).
     */
    fun planDispatch(
        action: AniobAction,
        liveScreen: AniobScreenState,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): DispatchCommand = when (action) {
        is AniobAction.Tap -> groundToTap(action, liveScreen)
        is AniobAction.LongPress -> groundToLongPress(action, liveScreen)
        is AniobAction.InputText -> groundToSetText(action, liveScreen)
        is AniobAction.Swipe -> planSwipe(action, liveScreen, screenWidth, screenHeight)
        is AniobAction.OpenApp -> DispatchCommand.SystemIntent("OPEN_APP", action.packageName)
        is AniobAction.SystemKey -> DispatchCommand.SystemIntent("SYSTEM_KEY", action.key.name)
        is AniobAction.PressKey -> DispatchCommand.SystemIntent("SYSTEM_KEY", action.key.name)
        is AniobAction.Clipboard -> DispatchCommand.SystemIntent("CLIPBOARD", action.operation.name)
        is AniobAction.GetScreenInfo -> DispatchCommand.SystemIntent("GET_SCREEN_INFO")
        is AniobAction.TakeScreenshot -> DispatchCommand.SystemIntent("TAKE_SCREENSHOT")
        is AniobAction.GetDeviceInfo -> DispatchCommand.SystemIntent("GET_DEVICE_INFO")
        is AniobAction.GetNotifications -> DispatchCommand.SystemIntent("GET_NOTIFICATIONS", action.filterPackage)
        is AniobAction.GetInstalledApps -> DispatchCommand.SystemIntent("GET_INSTALLED_APPS")
        is AniobAction.Wait -> DispatchCommand.SystemIntent("WAIT", action.clamped().durationMs.toString())
        is AniobAction.ConfirmWithUser -> DispatchCommand.SystemIntent("CONFIRM_WITH_USER", action.message)
        is AniobAction.Finish -> DispatchCommand.SystemIntent("FINISH", action.summary)
        is AniobAction.Fail -> DispatchCommand.SystemIntent("FAIL", action.reason)
    }

    private fun groundToTap(action: AniobAction.Tap, screen: AniobScreenState): DispatchCommand {
        val resolved = AniobGroundingResolver.resolve(action.target, screen)
            ?: return DispatchCommand.groundingMiss(action.target)
        return DispatchCommand.TapAt(
            x = resolved.x,
            y = resolved.y,
            targetDescription = action.target.describe(),
            nodeId = resolved.node.id
        )
    }

    private fun groundToLongPress(action: AniobAction.LongPress, screen: AniobScreenState): DispatchCommand {
        val resolved = AniobGroundingResolver.resolve(action.target, screen)
            ?: return DispatchCommand.groundingMiss(action.target)
        return DispatchCommand.LongPressAt(
            x = resolved.x,
            y = resolved.y,
            durationMs = action.durationMs,
            targetDescription = action.target.describe(),
            nodeId = resolved.node.id
        )
    }

    private fun groundToSetText(action: AniobAction.InputText, screen: AniobScreenState): DispatchCommand {
        val resolved = AniobGroundingResolver.resolve(action.target, screen)
            ?: return DispatchCommand.groundingMiss(action.target)
        return DispatchCommand.SetText(
            nodeId = resolved.node.id,
            text = action.text,
            clearFirst = action.clearFirst,
            submit = action.submit,
            targetDescription = action.target.describe()
        )
    }

    private fun planSwipe(
        action: AniobAction.Swipe,
        screen: AniobScreenState,
        screenWidth: Int,
        screenHeight: Int
    ): DispatchCommand {
        val container = AniobGroundingResolver.resolveContainer(action.container, screen)
        val distance = if (action.distancePx > 0) action.distancePx else SWIPE_FALLBACK_DISTANCE_PX

        // Swipe inside the container's own bounds when we have one, otherwise screen centre.
        val startX: Int
        val startY: Int
        val endX: Int
        val endY: Int
        if (container != null) {
            startX = (container.bounds.left + container.bounds.right) / 2
            startY = (container.bounds.top + container.bounds.bottom) / 2
            val maxDown = (container.bounds.bottom - 1).coerceAtLeast(container.bounds.top)
            val maxRight = (container.bounds.right - 1).coerceAtLeast(container.bounds.left)
            when (action.direction) {
                SwipeDirection.UP -> {
                    endX = startX; endY = (startY - distance).coerceAtLeast(container.bounds.top)
                }
                SwipeDirection.DOWN -> {
                    endX = startX; endY = (startY + distance).coerceAtMost(maxDown)
                }
                SwipeDirection.LEFT -> {
                    endX = (startX - distance).coerceAtLeast(container.bounds.left); endY = startY
                }
                SwipeDirection.RIGHT -> {
                    endX = (startX + distance).coerceAtMost(maxRight); endY = startY
                }
            }
        } else {
            startX = screenWidth / 2
            startY = screenHeight / 2
            when (action.direction) {
                SwipeDirection.UP -> {
                    endX = startX; endY = (startY - distance).coerceAtLeast(1)
                }
                SwipeDirection.DOWN -> {
                    endX = startX; endY = (startY + distance).coerceAtMost(screenHeight - 1)
                }
                SwipeDirection.LEFT -> {
                    endX = (startX - distance).coerceAtLeast(1); endY = startY
                }
                SwipeDirection.RIGHT -> {
                    endX = (startX + distance).coerceAtMost(screenWidth - 1); endY = startY
                }
            }
        }
        return DispatchCommand.SwipeGesture(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = 300L,
            targetDescription = "swipe_${action.direction.name.lowercase()}"
        )
    }

    /** Convenience for callers that need the grounded node alongside the command. */
    fun resolveTarget(action: AniobAction, liveScreen: AniobScreenState) =
        action.semanticTarget()?.let { AniobGroundingResolver.resolve(it, liveScreen) }
}