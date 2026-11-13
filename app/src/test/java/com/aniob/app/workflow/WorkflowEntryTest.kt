package com.aniob.app.workflow

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.ui.handleIncomingShare
import com.aniob.core.workflow.WorkflowLimits
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class WorkflowEntryTest {

    private lateinit var app: AniobApplication
    private lateinit var reader: IncomingContentReader

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        reader = IncomingContentReader(app)
    }

    @Test
    fun testIncomingShareCreatesDraftNotAutoApproval() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared snippet to inspect")
        }

        val parsed = reader.parse(intent)
        assertNotNull(parsed)
        assertEquals("text/plain", parsed?.mimeType)
        assertEquals("Shared snippet to inspect", parsed?.text)
        assertFalse(parsed?.isMultiple ?: true)
    }

    @Test
    fun testDeduplicatesIncomingShareAcrossRecreation() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Identical share payload")
        }

        val firstParse = reader.parse(intent)
        assertNotNull("First ingestion must parse content", firstParse)

        val secondParse = reader.parse(intent)
        assertNull("Subsequent identical intent on recreation must be deduplicated", secondParse)
    }

    @Test
    fun testBoundsLargeIncomingText() {
        val oversize = "A".repeat(WorkflowLimits.TEXT_BRIEF_MAX_BYTES + 1000)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, oversize)
        }

        val parsed = reader.parse(intent)
        assertNotNull(parsed)
        assertEquals(WorkflowLimits.TEXT_BRIEF_MAX_BYTES, parsed?.text?.length)
    }

    @Test
    fun testHandleIncomingShareStagesDraftWithoutAutoExecution() {
        val viewModel = com.aniob.app.ui.AniobViewModel(app)
        val content = ParsedIncomingContent(
            mimeType = "text/plain",
            text = "Draft note content",
            uris = emptyList(),
            isMultiple = false
        )
        viewModel.handleIncomingShare(content)
        val state = viewModel.uiState.value
        assertEquals("Draft note content", state.pendingInputPrefill)
        assertFalse("Shared content must not auto-execute", state.isRunning)
    }
}
