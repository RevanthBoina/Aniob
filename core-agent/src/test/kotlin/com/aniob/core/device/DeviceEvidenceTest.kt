package com.aniob.core.device

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.DeviceStateSnapshot
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobStoreCatalog
import com.aniob.core.ladder.AniobIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truth kernel: a device claim is PASSED only when a probe saw the state; unprobed is
 * INCONCLUSIVE and can never satisfy a criterion. Covers the 2026-11-04 failure class where
 * "SUCCESS" was derived from an HTTP response instead of a torch/package state.
 */
class DeviceEvidenceTest {

    private val screen = AniobScreenState(packageName = "com.aniob.app")

    private fun torchCriteria(on: Boolean) = SuccessCriteria(
        deviceAsserts = listOf(if (on) DeviceAssertion.TORCH_ON else DeviceAssertion.TORCH_OFF)
    )

    @Test
    fun torchAssertionPassesOnlyWhenProbeMatches() {
        val on = torchCriteria(true)
        val pass = on.evaluateCriteria(screen, 1, DeviceStateSnapshot(torchOn = true))
        assertEquals(CriterionState.PASSED, pass.single().state)
        val fail = on.evaluateCriteria(screen, 1, DeviceStateSnapshot(torchOn = false))
        assertEquals(CriterionState.FAILED, fail.single().state)
    }

    @Test
    fun unprobedTorchIsInconclusiveNeverSuccess() {
        val unknown = torchCriteria(true).evaluateCriteria(screen, 1, DeviceStateSnapshot.UNKNOWN)
        assertEquals(CriterionState.INCONCLUSIVE, unknown.single().state)
        assertTrue(torchCriteria(true).unmet(screen, 1).isNotEmpty())
    }

    @Test
    fun deviceOnlyCriteriaCountAsCheckable() {
        val criteria = torchCriteria(true)
        assertFalse("No screen check", criteria.hasScreenCheck)
        assertTrue("Device checks make the verdict checkable", criteria.hasObservableCheck)
        assertTrue(criteria.isFullyCheckable)
    }

    @Test
    fun installRequiresPackageManagerConfirmation() {
        val criteria = SuccessCriteria(mustBeInstalled = listOf("com.linkedin.android"))
        val installed = DeviceStateSnapshot(
            installedPackages = mapOf("com.linkedin.android" to true)
        )
        val missing = DeviceStateSnapshot(
            installedPackages = mapOf("com.linkedin.android" to false)
        )
        assertEquals(
            CriterionState.PASSED,
            criteria.evaluateCriteria(screen, 1, installed).single().state
        )
        assertEquals(
            CriterionState.FAILED,
            criteria.evaluateCriteria(screen, 1, missing).single().state
        )
        // A store page being foreground proves nothing about the package.
        assertEquals(
            CriterionState.INCONCLUSIVE,
            criteria.evaluateCriteria(screen, 1, DeviceStateSnapshot.UNKNOWN).single().state
        )
    }

    @Test
    fun installPromptExtractsThePackageToVerify() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("install linkedin")
        assertTrue("com.android.vending" in criteria.mustShowPackage)
        assertEquals(listOf("com.linkedin.android"), criteria.mustBeInstalled)
        assertTrue(criteria.requireInstallConfirmation)
    }

    @Test
    fun unknownInstallCanNeverClaimSuccessOnTheStorePageAlone() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("install nebula reader")
        assertTrue(criteria.requireInstallConfirmation)
        assertTrue("No package id exists to probe", criteria.mustBeInstalled.isEmpty())
    }

    @Test
    fun storeCatalogAndDeepLinkAgree() {
        assertEquals("com.linkedin.android", AniobStoreCatalog.packageFor("linkedin"))
        assertNull(AniobStoreCatalog.packageFor("nebula reader"))
        assertEquals(
            "market://details?id=com.linkedin.android",
            AniobStoreCatalog.deepLinkFor("linkedin")
        )
        assertTrue(AniobStoreCatalog.deepLinkFor("nebula reader").startsWith("market://search?q="))
        assertTrue(
            AniobStoreCatalog.webLinkFor("linkedin")
                .startsWith("https://play.google.com/store/apps/details?id=com.linkedin.android")
        )
    }

    @Test
    fun installDeepLinkCarriesVerificationPayload() {
        val shortcut = AniobIntentResolver.resolve("install linkedin")
        assertNotNull(shortcut)
        assertEquals(
            "com.linkedin.android",
            shortcut?.extras?.get(AniobIntentResolver.EXTRA_INSTALL_PACKAGE)
        )
        assertTrue(shortcut?.requiresObservation == true)
        assertEquals("com.android.vending", shortcut?.preferredPackage)
    }
}
