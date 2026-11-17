package com.aniob.app.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ConversationJournalTest {
    @Test fun outcomesAreIsolatedAndLateClosureCannotCloseNextTask() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AniobDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val journal = ConversationJournalStore(db)
            val conversations = ConversationStore(db.conversationDao())
            journal.begin(ConversationTaskEntity("t1", "a", "old task", 1))
            journal.begin(ConversationTaskEntity("t2", "a", "next task", 2))
            journal.finish("t1", "FAILED", "old failure")
            assertEquals("running", conversations.find("a")!!.status)
            journal.finish("t2", "ANSWERED", "answer delivered")
            assertEquals("completed", conversations.find("a")!!.status)
            journal.begin(ConversationTaskEntity("t3", "b", "other chat", 3))
            assertEquals(listOf("t1", "t2"), db.conversationJournalDao().tasks("a").map { it.taskId })
            assertTrue(conversations.historyForLlm("b").isEmpty())
            journal.finish("t1", "VERIFIED", "user says success")
            assertEquals("FAILED", db.conversationJournalDao().task("t1")!!.outcome)
            journal.recover(3) // Active tasks started at/after the process boundary are untouched.
            assertEquals("RUNNING", db.conversationJournalDao().task("t3")!!.outcome)
            journal.recover(4)
            assertEquals("INTERRUPTED", db.conversationJournalDao().task("t3")!!.outcome)
            assertEquals("stopped", conversations.find("b")!!.status)
            db.conversationJournalDao().saveAudit(ConversationAuditEntity("b", 5, "evidence", "EVIDENCE_ONLY"))
            conversations.delete("b")
            assertNull(db.conversationJournalDao().audit("b"))
            assertTrue(db.conversationJournalDao().tasks("b").isEmpty())
        } finally { db.close() }
    }

    /**
     * PART 6 — recovery is honest and one-shot: a task whose process died is recorded INTERRUPTED
     * with a reason that promises nothing, and once recorded it is not offered again (so a restart
     * loop cannot re-report, and nothing is re-executed for it).
     */
    @Test fun interruptedRecoveryIsHonestAndNeverReopensTheTask() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AniobDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val journal = ConversationJournalStore(db)
            journal.begin(ConversationTaskEntity("t1", "a", "read my two newest emails", 1))
            journal.recover(2)

            val recorded = db.conversationJournalDao().task("t1")!!
            assertEquals("INTERRUPTED", recorded.outcome)
            assertTrue(
                "the reason says the cause was unknown instead of claiming success",
                recorded.reason.contains("cause unknown")
            )

            // Recovery is not a checkpoint: the row is terminal, and a later success claim from the
            // dead run cannot rewrite it.
            journal.finish("t1", "VERIFIED", "user says success")
            assertEquals("INTERRUPTED", db.conversationJournalDao().task("t1")!!.outcome)
            assertTrue(
                "a recorded interruption is not offered again on the next open",
                db.conversationJournalDao().interrupted(3).isEmpty()
            )
        } finally { db.close() }
    }
}
