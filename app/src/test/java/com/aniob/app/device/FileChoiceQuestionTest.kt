package com.aniob.app.device

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class FileChoiceQuestionTest {
    @Test fun pickerIsOfferedFirstOnlyWithoutAllFilesAccess() {
        val names = listOf("a.pdf", "b.pdf", "a.pdf", "c.pdf", "d.pdf")
        val without = AniobDeviceControl.fileChoiceQuestion("resume", names, allFilesAccess = false)
        assertEquals(listOf(AniobDeviceControl.FILE_CHOICE_PICKER, "a.pdf", "b.pdf", "c.pdf"), without.options)
        assertTrue(without.allowFreeText)
        assertTrue(without.question.contains("resume"))
        assertEquals(null, without.defaultOption)

        val with = AniobDeviceControl.fileChoiceQuestion("", names, allFilesAccess = true)
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf", AniobDeviceControl.FILE_CHOICE_PICKER), with.options)
        assertEquals("Which file should I open?", with.question)
    }
}
