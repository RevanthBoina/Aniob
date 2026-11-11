package com.aniob.app.research

import org.junit.Assert.*
import org.junit.Test

class FeatureSelectionTest {
    @Test fun all32CombinationsKeepIndependentBits() {
        for (mask in 0..31) {
            val selection = FeatureSelection(mask)
            assertEquals(mask and 1 != 0, selection.intentShortcuts)
            assertEquals(mask and 2 != 0, selection.skillsEnabled)
            assertEquals(mask and 4 != 0, selection.rememberReplay)
            assertEquals(mask and 8 != 0, selection.actionBursts)
            assertEquals(mask and 16 != 0, selection.fastObservation)
            for (feature in ExecutionFeature.entries) {
                assertEquals(mask or feature.bit, selection.with(feature, true).mask)
                assertEquals(mask and feature.bit.inv(), selection.with(feature, false).mask)
            }
        }
        assertEquals(31, FeatureSelection.FULL_SPEED.mask)
        assertFalse(FeatureSelection(8).fastObservation)
        assertFalse(FeatureSelection(16).actionBursts)
        assertEquals(AblationMode.COMBINATION, FeatureSelection(3).mode)
    }

    @Test fun combinationsNeverEnterSingleFeatureSummary() {
        val single = ResearchRun(1, AblationMode.LEARNED_SKILLS, "task", 1.0, 0, 0, 1, true, "", false, false,
            featureMask = 2)
        val combined = single.copy(mode = AblationMode.COMBINATION, featureMask = 3)
        val full = single.copy(mode = AblationMode.FULL_SPEED, featureMask = 31)
        assertEquals(listOf(AblationMode.LEARNED_SKILLS), ResearchSummaryTable.build(listOf(single, combined, full)).map { it.mode })
        assertEquals(3, ResearchRun.fromCsvRow(combined.toCsvRow())!!.featureMask)
        // Historical rows retain their IDs and never acquire guessed experimental bits.
        val historical = single.copy(mode = AblationMode.INTENT_SHORTCUT_ONLY, featureMask = null)
        assertNull(ResearchRun.fromCsvRow(historical.toCsvRow())!!.featureMask)
    }

    @Test fun legacyPoliciesAreNotRelabeled() {
        assertEquals(0, FeatureSelection.fromLegacy(AblationMode.BASELINE).mask)
        assertEquals(3, FeatureSelection.fromLegacy(AblationMode.INTENT_SHORTCUT_ONLY).mask)
        assertEquals(27, FeatureSelection.fromLegacy(AblationMode.BURST_AND_FAST_OBSERVE).mask)
        assertEquals(31, FeatureSelection.fromLegacy(AblationMode.FULL_HUMAN_SPEED).mask)
    }
}
