package com.aniob.app.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aniob.core.outcome.RunOutcome

/**
 * Step 1, persistence layer. Room cannot declare CHECK constraints on an entity, and a CHECK added in a
 * migration would be missing on fresh installs, so the rule lives in triggers created on EVERY open
 * (`IF NOT EXISTS`, idempotent). Triggers are not part of Room's schema validation, so no schema hash changes.
 */
object OutcomeGuard {

    private val CLOSED_SET = RunOutcome.values().joinToString(",") { "'${it.name}'" }

    private const val CONDITION = "WHEN NEW.outcome NOT IN (%s) OR (NEW.outcome = 'VERIFIED' AND " +
        "(NEW.evidenceRef IS NULL OR length(trim(NEW.evidenceRef)) = 0))"
    private const val MESSAGE = "task_outcomes: outcome must be in the closed set and VERIFIED requires an evidence reference"

    val TRIGGERS: List<String> = listOf("INSERT", "UPDATE").map { op ->
        "CREATE TRIGGER IF NOT EXISTS task_outcomes_guard_${op.lowercase()} BEFORE $op ON task_outcomes " +
            CONDITION.format(CLOSED_SET) + " BEGIN SELECT RAISE(ABORT, '$MESSAGE'); END"
    }

    fun install(db: SupportSQLiteDatabase) {
        TRIGGERS.forEach(db::execSQL)
    }

    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            install(db)
        }
    }

    val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS task_outcomes (" +
                    "taskId TEXT NOT NULL, outcome TEXT NOT NULL, reason TEXT NOT NULL, " +
                    "evidenceRef TEXT, userDecision TEXT, recordedAtMs INTEGER NOT NULL, PRIMARY KEY(taskId))"
            )
            install(db)
        }
    }
}
