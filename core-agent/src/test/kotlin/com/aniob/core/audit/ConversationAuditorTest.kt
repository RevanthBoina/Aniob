package com.aniob.core.audit

import com.aniob.core.external.AniobCloudLlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationAuditorTest {
    private fun provider(answer: suspend () -> String) = object : AniobCloudLlmProvider {
        override val name = "selected"
        override suspend fun generate(prompt: String, systemPrompt: String) = answer()
        override suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit) = answer()
    }
    @Test fun noProviderReportsEvidenceNotInventedCause() = runBlocking {
        val result = ConversationAuditor.audit("stopped", "User switched chats", emptyList(), "", null)
        assertEquals("EVIDENCE_ONLY", result.source)
        assertTrue(result.text.contains("cause of any interruption is unknown"))
        assertTrue(result.text.contains("not evidence of failure"))
    }
    @Test fun modelOutputIsHypothesisAndTransportFailureDoesNotChangeProvider() = runBlocking {
        val tasks = listOf(AuditTask("t", "do a task", "FAILED", "Target missing"))
        val result = ConversationAuditor.audit("errored", "Target missing", tasks, "transcript", provider { "Possibly stale UI" })
        assertTrue(result.text.contains("Model hypotheses"))
        assertTrue(result.text.contains("Recorded outcome: FAILED"))
        val failed = ConversationAuditor.audit("errored", "why", tasks, "", provider { throw java.io.IOException("secret") })
        assertEquals("EVIDENCE_ONLY", failed.source)
        assertFalse(failed.text.contains("secret"))
    }
    @Test fun cancellationIsNotAnAuditReport() = runBlocking {
        try {
            ConversationAuditor.audit("stopped", "", emptyList(), "", provider { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
