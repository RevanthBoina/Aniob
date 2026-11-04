package com.aniob.core.domain

/**
 * A point-in-time probe of the DEVICE, not of its screen.
 *
 * The 2026-11-04 report proved that screen evidence cannot see a torch, an installed package or a
 * scheduled transition. App-layer probes fill this snapshot; [SuccessCriteria.evaluateCriteria]
 * turns it into PASSED / FAILED / INCONCLUSIVE criteria. `null`/missing always means INCONCLUSIVE,
 * which is never treated as success.
 */
data class DeviceStateSnapshot(
    /** CameraManager.TorchCallback mirror: true = on, false = off, null = not observable. */
    val torchOn: Boolean? = null,
    /** package id -> installed? Missing key = not probed. */
    val installedPackages: Map<String, Boolean> = emptyMap()
) {
    companion object {
        /** Explicitly "nothing probed" — used by pure-JVM tests and non-device runs. */
        val UNKNOWN = DeviceStateSnapshot()
    }
}
