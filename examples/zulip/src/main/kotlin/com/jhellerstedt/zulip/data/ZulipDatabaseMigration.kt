package com.jhellerstedt.zulip.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_streams (
                account_id INTEGER NOT NULL,
                stream_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                invite_only INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account_id, stream_id),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_users (
                account_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                full_name TEXT NOT NULL,
                email TEXT NOT NULL,
                is_active INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account_id, user_id),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_topics (
                account_id INTEGER NOT NULL,
                stream_id INTEGER NOT NULL,
                topic_name TEXT NOT NULL,
                max_message_id INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account_id, stream_id, topic_name),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_conversations (
                account_id INTEGER NOT NULL,
                narrow_key TEXT NOT NULL,
                title TEXT NOT NULL,
                unread_count INTEGER NOT NULL,
                last_message_id INTEGER NOT NULL,
                last_message_timestamp INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account_id, narrow_key),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_messages (
                account_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                narrow_key TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                cached_at INTEGER NOT NULL,
                PRIMARY KEY(account_id, narrow_key, message_id),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_messages (
                local_id TEXT NOT NULL,
                account_id INTEGER NOT NULL,
                narrow_key TEXT NOT NULL,
                content TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempt_at INTEGER,
                failure_reason TEXT,
                PRIMARY KEY(local_id),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_attachments (
                local_id TEXT NOT NULL,
                account_id INTEGER NOT NULL,
                message_local_id TEXT NOT NULL,
                source_index INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                file_path TEXT NOT NULL,
                uploaded_url TEXT,
                PRIMARY KEY(local_id),
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE,
                FOREIGN KEY(message_local_id) REFERENCES pending_messages(local_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cached_messages_account_id_narrow_key_cached_at " +
                "ON cached_messages (account_id, narrow_key, cached_at)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_messages_account_id_state_created_at " +
                "ON pending_messages (account_id, state, created_at)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_attachments_account_id " +
                "ON pending_attachments (account_id)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_attachments_message_local_id " +
                "ON pending_attachments (message_local_id)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_attachments_message_local_id_source_index " +
                "ON pending_attachments (message_local_id, source_index)",
        )
    }
}
