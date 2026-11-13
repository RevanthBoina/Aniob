package com.aniob.app.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aniob.core.external.ChatTurn
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ConversationStoreTest {
    private lateinit var db: AniobDatabase
    private var clock = 1_000L
    private var ids = 0
    private lateinit var store: ConversationStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), AniobDatabase::class.java
        ).allowMainThreadQueries().build()
        store = ConversationStore(db.conversationDao(), now = { clock }, newId = { "m${ids++}" })
    }

    @After fun tearDown() = db.close()

    @Test fun historyReturnsStoredTurnsOldestFirst() = runBlocking {
        store.appendExchange("c1", "My name is Ravi", "Nice to meet you, Ravi.", "CLOUD")
        clock += 10
        store.appendExchange("c1", "What is my name?", "Ravi.", "CLOUD")

        assertEquals(
            listOf(
                ChatTurn("user", "My name is Ravi"),
                ChatTurn("assistant", "Nice to meet you, Ravi."),
                ChatTurn("user", "What is my name?"),
                ChatTurn("assistant", "Ravi.")
            ),
            store.historyForLlm("c1")
        )
        assertEquals(listOf("What is my name?", "Ravi."), store.historyForLlm("c1", maxTurns = 2).map { it.content })
        assertTrue(store.historyForLlm("other").isEmpty())
    }

    @Test fun secondExchangeKeepsTitleAndEarlierMessages() = runBlocking {
        store.appendExchange("c1", "first question", "a1", null)
        clock += 10
        store.appendExchange("c1", "second question", "a2", null)

        val conversation = store.observeAll().first().single()
        assertEquals("first question", conversation.title)
        assertEquals(1_000L, conversation.createdAt)
        assertEquals(1_010L, conversation.updatedAt)
        assertEquals(4, store.messages("c1").size)
    }

    @Test fun listIsNewestFirstAndDeleteRemovesMessages() = runBlocking {
        store.appendExchange("old", "old chat", "a", null)
        clock += 10
        store.appendExchange("new", "new chat", "a", null)
        assertEquals(listOf("new", "old"), store.observeAll().first().map { it.id })

        store.delete("old")
        assertEquals(listOf("new"), store.observeAll().first().map { it.id })
        assertTrue(store.messages("old").isEmpty())
        assertEquals(2, store.messages("new").size)
    }

    @Test fun titleIsFirstLineShortened() {
        assertEquals("New chat", ConversationStore.titleFor("   "))
        assertEquals("hello", ConversationStore.titleFor("hello\nsecond line"))
        val long = ConversationStore.titleFor("x".repeat(200))
        assertEquals(ConversationStore.TITLE_MAX_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    /** Room validates the migrated tables against the entities when the database opens. */
    @Test fun migrationFromSchemas6And8KeepsHistoricalRows() = runBlocking {
        for (startVersion in listOf(6, 8)) migrateHistoricalDatabase(startVersion)
    }

    private suspend fun migrateHistoricalDatabase(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "conversation-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.aniob.app.db.AniobDatabase/6.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(
                    indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO session_scores (taskId,userPrompt,status,totalSteps,durationMs,tokensUsed,providerUsed,decisionReason,timestamp) VALUES ('historic','old task','SUCCESS',1,100,0,'SKILL','test',1)")
            if (startVersion == 8) {
                old.execSQL("CREATE TABLE feature_combination_runs (runId TEXT NOT NULL PRIMARY KEY, featureMask INTEGER NOT NULL, configurationKey TEXT NOT NULL, timestampMs INTEGER NOT NULL, runCsv TEXT NOT NULL)")
                old.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, pinned INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, provider TEXT, timestamp INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_chat_messages_conversationId ON chat_messages(conversationId)")
                old.execSQL("INSERT INTO conversations VALUES ('legacy','Old conversation',1,1,0)")
                old.execSQL("INSERT INTO chat_messages VALUES ('old-message','legacy','user','Keep this history',NULL,1)")
            }
            old.version = startVersion
        }
        val migrated = Room.databaseBuilder(context, AniobDatabase::class.java, name)
            .addMigrations(AniobDatabase.MIGRATION_6_7, ConversationStore.MIGRATION_7_8, AniobDatabase.MIGRATION_8_9)
            .allowMainThreadQueries().build()
        try {
            migrated.openHelper.writableDatabase.query("SELECT taskId FROM session_scores").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("historic", cursor.getString(0))
            }
            val migratedStore = ConversationStore(migrated.conversationDao())
            if (startVersion == 8) {
                assertEquals("Keep this history", migratedStore.messages("legacy").single().content)
                assertEquals("unknown", migratedStore.find("legacy")!!.status)
            }
            migratedStore.appendExchange("c1", "q", "a", "CLOUD")
            assertEquals(2, migratedStore.historyForLlm("c1").size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
