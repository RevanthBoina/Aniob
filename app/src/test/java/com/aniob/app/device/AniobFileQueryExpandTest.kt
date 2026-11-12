package com.aniob.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobFileQueryExpandTest {
    @Test fun resumeAlsoLooksForCvAndCurriculum() {
        val terms = AniobFileQuery.expand("my resume")
        assertEquals(listOf("resume", "cv", "curriculum"), terms)
        assertTrue(terms.contains("cv"))
        assertFalse(terms.contains("my"))
    }

    @Test fun billMatchesInvoiceAndReceipt() {
        assertEquals(listOf("electricity bill", "electricity", "bill", "invoice", "receipt"), AniobFileQuery.expand("Electricity  Bill"))
    }

    @Test fun idExpandsToNamedDocumentsButNeverToTheTwoLetterWord() {
        val terms = AniobFileQuery.expand("my id")
        assertEquals(listOf("id", "aadhaar", "pan", "passport"), terms)
    }

    @Test fun screenshotTodayKeepsThePhraseAndDropsTheDateWord() {
        assertEquals(listOf("screenshot today", "screenshot"), AniobFileQuery.expand("screenshot today"))
    }

    @Test fun plainSingleWordIsUnchangedAndBlankIsEmpty() {
        assertEquals(listOf("budget"), AniobFileQuery.expand("budget"))
        assertTrue(AniobFileQuery.expand("   ").isEmpty())
    }

    @Test fun neverMoreThanSixTerms() {
        val terms = AniobFileQuery.expand("resume invoice passport holiday pictures album")
        assertEquals(AniobFileQuery.MAX_TERMS, terms.size)
        assertEquals("resume invoice passport holiday pictures album", terms.first())
    }
}
