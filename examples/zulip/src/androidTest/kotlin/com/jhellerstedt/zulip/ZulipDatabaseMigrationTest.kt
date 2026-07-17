package com.jhellerstedt.zulip

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jhellerstedt.zulip.data.MIGRATION_1_2
import com.jhellerstedt.zulip.data.ZulipDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZulipDatabaseMigrationTest {
    @Test
    fun migration1To2PreservesAccountsAndCreatesCacheAndOutboxTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
        context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    realm_url TEXT NOT NULL,
                    realm_name TEXT NOT NULL,
                    email TEXT NOT NULL,
                    encrypted_api_key BLOB NOT NULL,
                    user_id INTEGER NOT NULL,
                    zulip_feature_level INTEGER NOT NULL,
                    added_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_realm_url_email " +
                    "ON accounts (realm_url, email)",
            )
            db.execSQL(
                """
                INSERT INTO accounts
                    (id, realm_url, realm_name, email, encrypted_api_key, user_id, zulip_feature_level, added_at)
                VALUES
                    (7, 'https://zulip.example', 'Example', 'a@example.com', X'0102', 11, 481, 1000)
                """.trimIndent(),
            )
            db.version = 1
        }

        Room.databaseBuilder(context, ZulipDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .let { database ->
                try {
                    database.openHelper.writableDatabase.query(
                        "SELECT realm_name FROM accounts WHERE id = 7",
                    ).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Example", it.getString(0))
                    }
                    listOf(
                        "cached_streams",
                        "cached_users",
                        "cached_topics",
                        "cached_conversations",
                        "cached_messages",
                        "pending_messages",
                        "pending_attachments",
                    ).forEach { table ->
                        database.openHelper.writableDatabase.query(
                            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                            arrayOf(table),
                        ).use { assertTrue("missing $table", it.moveToFirst()) }
                    }
                } finally {
                    database.close()
                }
            }
    }

    private companion object {
        const val TEST_DB = "zulip-migration-test.db"
    }
}
