package com.jarvis.ai.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.model.SessionInfo

class ChatDb(context: Context) {

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_SESSIONS)
            db.execSQL(SQL_CREATE_MESSAGES)
            db.execSQL(SQL_CREATE_MESSAGE_INDEX)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    fun sessions(): List<SessionInfo> {
        val result = mutableListOf<SessionInfo>()
        helper.readableDatabase.rawQuery(
            "SELECT $COL_ID, $COL_TITLE, $COL_UPDATED_AT FROM $TABLE_SESSIONS ORDER BY $COL_UPDATED_AT DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SessionInfo(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    updatedAt = cursor.getLong(2)
                )
            }
        }
        return result
    }

    fun createSession(session: SessionInfo) {
        val values = contentValues().apply {
            put(COL_ID, session.id)
            put(COL_TITLE, session.title)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_UPDATED_AT, session.updatedAt)
        }
        helper.writableDatabase.insert(TABLE_SESSIONS, null, values)
    }

    fun renameSession(id: String, title: String) {
        val values = contentValues().apply {
            put(COL_TITLE, title)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        helper.writableDatabase.update(TABLE_SESSIONS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun touchSession(id: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = contentValues().apply { put(COL_UPDATED_AT, updatedAt) }
        helper.writableDatabase.update(TABLE_SESSIONS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun deleteSession(id: String) {
        helper.writableDatabase.delete(TABLE_SESSIONS, "$COL_ID = ?", arrayOf(id))
    }

    fun messages(sessionId: String): List<Message> {
        val result = mutableListOf<Message>()
        helper.readableDatabase.rawQuery(
            "SELECT $COL_MSG_ID, $COL_SENDER, $COL_TEXT, $COL_TIMESTAMP, $COL_IS_ERROR " +
                "FROM $TABLE_MESSAGES WHERE $COL_SESSION_ID = ? ORDER BY $COL_TIMESTAMP ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Message(
                    id = cursor.getString(0),
                    sender = Sender.valueOf(cursor.getString(1)),
                    text = cursor.getString(2),
                    timestamp = cursor.getLong(3),
                    isError = cursor.getInt(4) != 0
                )
            }
        }
        return result
    }

    fun appendMessage(sessionId: String, message: Message) {
        val values = contentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_SESSION_ID, sessionId)
            put(COL_SENDER, message.sender.name)
            put(COL_TEXT, message.text)
            put(COL_TIMESTAMP, message.timestamp)
            put(COL_IS_ERROR, if (message.isError) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteMessages(sessionId: String) {
        helper.writableDatabase.delete(TABLE_MESSAGES, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    private fun contentValues() = ContentValues()

    companion object {
        private const val DB_NAME = "jarvis.db"
        private const val DB_VERSION = 1

        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_MESSAGES = "messages"
        private const val COL_ID = "_id"
        private const val COL_TITLE = "title"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_SESSION_ID = "session_id"
        private const val COL_MSG_ID = "id"
        private const val COL_SENDER = "sender"
        private const val COL_TEXT = "text"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_IS_ERROR = "is_error"

        private const val SQL_CREATE_SESSIONS =
            "CREATE TABLE $TABLE_SESSIONS (" +
                "$COL_ID TEXT PRIMARY KEY, " +
                "$COL_TITLE TEXT NOT NULL, " +
                "$COL_CREATED_AT INTEGER NOT NULL, " +
                "$COL_UPDATED_AT INTEGER NOT NULL)"

        private const val SQL_CREATE_MESSAGES =
            "CREATE TABLE $TABLE_MESSAGES (" +
                "$COL_MSG_ID TEXT PRIMARY KEY, " +
                "$COL_SESSION_ID TEXT NOT NULL REFERENCES $TABLE_SESSIONS($COL_ID) ON DELETE CASCADE, " +
                "$COL_SENDER TEXT NOT NULL CHECK($COL_SENDER IN ('USER','JARVIS')), " +
                "$COL_TEXT TEXT NOT NULL, " +
                "$COL_TIMESTAMP INTEGER NOT NULL, " +
                "$COL_IS_ERROR INTEGER NOT NULL DEFAULT 0)"

        private const val SQL_CREATE_MESSAGE_INDEX =
            "CREATE INDEX idx_messages_session ON $TABLE_MESSAGES($COL_SESSION_ID)"
    }
}
