package com.aniob.core.feasibility

import com.aniob.core.outcome.EvidenceRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After a run ends as DELEGATED the user acts by hand. This keeps asking an independent probe
 * ("is the package installed now?") so the result can become VERIFIED with real evidence, or stay DELEGATED.
 * It never decides anything itself: it only relays what [probe] observed.
 */
object AniobDelegationWatcher {
    suspend fun await(
        timeoutMs: Long = 120_000L,
        pollMs: Long = 1_500L,
        probe: suspend () -> EvidenceRef?
    ): EvidenceRef? = withTimeoutOrNull(timeoutMs) {
        var found: EvidenceRef? = null
        while (found == null) {
            found = probe()
            if (found == null) delay(pollMs)
        }
        found
    }
}
