package com.aniob.app.device

/**
 * Outcome of one direct device command.
 *
 * [message] is written for two readers at once: the user (shown in chat when [informational]) and the
 * model (fed back as the step's `result:`). It therefore never contains a verification code, and an
 * unavailable command carries the exact setting that unblocks it in [blockedBy].
 */
data class DeviceControlResult(
    val ok: Boolean,
    val message: String,
    /** True for answers worth showing the user ("Storage: 12 GB free"), false for silent success. */
    val informational: Boolean = false,
    val blockedBy: String? = null
)
