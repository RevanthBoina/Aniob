package com.aniob.core.device

/**
 * U-02 — what a media command must change, as pure data so it is unit-testable without Android.
 * State ints mirror `android.media.session.PlaybackState` (this module has no Android dependency).
 */
object MediaExpectation {
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6
    const val STATE_CONNECTING = 8
    const val STATE_SKIPPING_TO_PREVIOUS = 9
    const val STATE_SKIPPING_TO_NEXT = 10
    const val STATE_SKIPPING_TO_QUEUE_ITEM = 11

    enum class Op { TOGGLE, PLAY, PAUSE, NEXT, PREVIOUS, STOP }

    /** [trackKey] = media id, else title|artist|duration. [positionMs] as reported at [state]. */
    data class Snap(val state: Int, val trackKey: String, val positionMs: Long)

    private fun playing(s: Int) = s == STATE_PLAYING || s == STATE_BUFFERING || s == STATE_CONNECTING
    private fun quiet(s: Int) = s == STATE_PAUSED || s == STATE_STOPPED || s == STATE_NONE

    fun satisfied(op: Op, before: Snap, now: Snap): Boolean = when (op) {
        Op.PLAY -> playing(now.state)
        Op.PAUSE -> quiet(now.state)
        Op.TOGGLE -> if (playing(before.state)) quiet(now.state) else playing(now.state)
        // Many players map "stop" to pause; both are an honest "playback ended".
        Op.STOP -> quiet(now.state)
        Op.NEXT -> now.trackKey != before.trackKey || now.state == STATE_SKIPPING_TO_NEXT || now.state == STATE_SKIPPING_TO_QUEUE_ITEM
        // "Previous" legitimately restarts the current track when it has been playing for a while.
        Op.PREVIOUS -> now.trackKey != before.trackKey || now.state == STATE_SKIPPING_TO_PREVIOUS ||
            (before.positionMs > 3_000 && now.positionMs + 1_500 < before.positionMs)
    }

    fun describe(s: Int): String = when {
        playing(s) -> "playing"
        s == STATE_PAUSED -> "paused"
        s == STATE_STOPPED -> "stopped"
        s == STATE_NONE -> "idle"
        else -> "state $s"
    }
}
