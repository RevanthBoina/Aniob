package com.aniob.app.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class FeatureCombinationMigrationTest {
    @Test fun migrationPreservesHistoricalDataAndRoomValidatesNewSchema() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "feature-migration-test.db"
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
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO session_scores (taskId,userPrompt,status,totalSteps,durationMs,tokensUsed,providerUsed,decisionReason,timestamp) VALUES ('historic','old task','SUCCESS',1,100,0,'SKILL','test',1)")
            old.version = 6
        }
        val db = Room.databaseBuilder(context, AniobDatabase::class.java, name)
            .addMigrations(AniobDatabase.MIGRATION_6_7).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.query("SELECT taskId FROM session_scores").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("historic", cursor.getString(0))
            }
            db.featureCombinationDao().upsert(FeatureCombinationEntity("new", 9, "INTENT_SHORTCUTS+ACTION_BURST", 2, "row"))
            assertEquals(9, db.featureCombinationDao().find("new")!!.featureMask)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
