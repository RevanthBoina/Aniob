package com.aniob.core.ladder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PART 3 — when the live screen may be called an account chooser, and when it must not be.
 *
 * A mailbox is never a chooser: pausing a read on an inbox would be exactly the "please choose an
 * account" interruption this work removes.
 */
class AniobAccountChooserScreenTest {

    private val gmail = "com.google.android.gm"

    private fun screen(pkg: String, vararg texts: String) = com.aniob.core.domain.AniobScreenState(
        packageName = pkg,
        nodes = texts.mapIndexed { index, text ->
            com.aniob.core.domain.AniobNode(
                id = index + 1,
                className = "android.widget.TextView",
                text = text,
                isClickable = true
            )
        }
    )

    @Test
    fun `two account rows with no mailbox chrome is a chooser`() {
        val detected = AniobAccountChooserScreen.detect(
            screen(gmail, "work@example.com", "home@example.com"),
            setOf(gmail)
        )

        assertEquals(listOf("work@example.com", "home@example.com"), detected?.map { it.label })
    }

    @Test
    fun `an inbox is not a chooser even when sender addresses are on it`() {
        val detected = AniobAccountChooserScreen.detect(
            screen(gmail, "Inbox", "ana@example.com", "bo@example.com"),
            setOf(gmail)
        )

        assertNull("a mailbox must never pause the read", detected)
    }

    @Test
    fun `a message screen with two addresses and a body is not a chooser`() {
        val detected = AniobAccountChooserScreen.detect(
            screen(
                gmail,
                "ana@example.com",
                "me@example.com",
                "Quarterly numbers and the plan for next week — please read before the meeting and reply",
                "10:42 AM"
            ),
            setOf(gmail)
        )

        assertNull("a message must never be mistaken for an account chooser", detected)
    }

    @Test
    fun `one account is not ambiguous so nothing is detected`() {
        val detected = AniobAccountChooserScreen.detect(
            screen(gmail, "me@example.com", "Add another account"),
            setOf(gmail)
        )

        assertNull(detected)
    }

    @Test
    fun `a chooser in a different app is ignored`() {
        val detected = AniobAccountChooserScreen.detect(
            screen("com.example.other", "work@example.com", "home@example.com"),
            setOf(gmail)
        )

        assertNull(detected)
    }
}
