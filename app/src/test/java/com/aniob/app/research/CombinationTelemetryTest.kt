package com.aniob.app.research

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aniob.app.db.FeatureCombinationDao
import com.aniob.app.db.FeatureCombinationEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CombinationTelemetryTest {
    private class Rows : FeatureCombinationDao {
        val rows = mutableMapOf<String, FeatureCombinationEntity>()
        override fun upsert(run: FeatureCombinationEntity) { rows[run.runId] = run }
        override fun find(runId: String) = rows[runId]
        override fun all() = rows.values.sortedByDescending { it.timestampMs }
    }

    @Test fun combinationSnapshotAndCorrectionsStayOutOfResearchFiles() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val rows = Rows()
        val manager = ResearchTelemetryManager(context, clock = { 100L }, logger = {}, combinationDao = { rows })
        manager.clearAll()
        val captured = FeatureSelection(3)
        manager.featureSelection = captured
        manager.startTask("old person", captured.mode, runId = "combo", featureMask = captured.mask)
        manager.featureSelection = FeatureSelection(16)
        val result = manager.endTask(ResearchOutcome.UNVERIFIED)!!
        assertEquals(3, result.featureMask)
        assertEquals("INTENT_SHORTCUTS+LEARNED_SKILLS", rows.find("combo")!!.configurationKey)
        assertTrue(manager.allRuns().isEmpty())
        val corrected = manager.markUserCorrected("combo")!!
        assertTrue(corrected.userCorrected)
        assertFalse(corrected.success)
        assertTrue(manager.allRuns().isEmpty())
        assertTrue(ResearchRun.fromCsvRow(rows.find("combo")!!.runCsv)!!.userCorrected)
        manager.overrideLastOutcome(false)
        assertEquals(1, rows.all().size)
        assertTrue(manager.allRuns().isEmpty())
    }
}
