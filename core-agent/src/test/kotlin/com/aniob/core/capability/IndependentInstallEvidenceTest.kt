package com.aniob.core.capability

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.DeviceStateSnapshot
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.verifier.DeterministicVerifier
import org.junit.Assert.*
import org.junit.Test

class IndependentInstallEvidenceTest {
    @Test fun `store screen cannot prove an unresolved install`() {
        val criteria = SuccessCriteria(mustShowPackage = listOf("com.android.vending"), requireInstallConfirmation = true)
        val result = DeterministicVerifier.verifyFinish(criteria, AniobScreenState(packageName = "com.android.vending"), 1)
        assertFalse(result.isExpected)
        assertTrue(result.evaluations.any { it.state == CriterionState.INCONCLUSIVE })
    }

    @Test fun `final device probe replaces stale success evidence`() {
        val criteria = SuccessCriteria(deviceAsserts = listOf(com.aniob.core.domain.DeviceAssertion.TORCH_ON))
        val previous = criteria.evaluateDeviceCriteria(DeviceStateSnapshot(torchOn = true))
        assertEquals(CriterionState.PASSED, previous.single().state)
        assertEquals(CriterionState.INCONCLUSIVE,
            criteria.withLatestDeviceEvaluations(previous, DeviceStateSnapshot.UNKNOWN).single().state)
        assertEquals(CriterionState.FAILED,
            criteria.withLatestDeviceEvaluations(previous, DeviceStateSnapshot(torchOn = false)).single().state)
    }

    @Test fun `blank postcondition targets never verify`() {
        val screen = AniobScreenState(packageName = "", activityName = "Main")
        val criteria = listOf(
            SuccessCriteria(mustContainText = listOf("")),
            SuccessCriteria(mustShowPackage = listOf("")),
            SuccessCriteria(mustShowActivity = listOf("")),
            SuccessCriteria(mustBeInstalled = listOf(""))
        )
        criteria.forEach {
            val result = DeterministicVerifier.verifyFinish(it, screen, 1,
                DeviceStateSnapshot(installedPackages = mapOf("" to true)))
            assertFalse(result.isExpected)
            assertTrue(result.evaluations.any { evaluation -> evaluation.state == CriterionState.INCONCLUSIVE })
        }
    }

    @Test fun `install requires a known independently checked package`() {
        val criteria = SuccessCriteria(mustBeInstalled = listOf("example.app"), requireInstallConfirmation = true)
        val screen = AniobScreenState(packageName = "example.app")
        assertFalse(DeterministicVerifier.verifyFinish(criteria, screen, 1).isExpected)
        assertFalse(DeterministicVerifier.verifyFinish(criteria, screen, 1, DeviceStateSnapshot(installedPackages = mapOf("example.app" to false))).isExpected)
        assertTrue(DeterministicVerifier.verifyFinish(criteria, screen, 1, DeviceStateSnapshot(installedPackages = mapOf("example.app" to true))).isExpected)
    }
}
