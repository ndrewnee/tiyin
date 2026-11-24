package uz.tiyin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import uz.tiyin.core.Parsers
import uz.tiyin.core.Sms
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class IncomingSms(val sender: String, val body: String, val epochMs: Long)

/**
 * The app's own copy of bank SMS. It is the only state the app keeps, so the user can wipe
 * SMS on the phone without losing history.
 */
class SmsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "tiyin.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                UNIQUE (sender, body, received_at)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns how many rows were new. */
    fun save(messages: List<IncomingSms>, source: String): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in messages) {
                if (!Parsers.shouldStore(Sms(m.sender, m.body, toLocal(m.epochMs)))) continue
                val v = ContentValues().apply {
                    put("sender", m.sender.trim())
                    put("body", m.body)
                    put("received_at", m.epochMs)
                    put("source", source)
                }
                if (db.insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun all(): List<Sms> = readableDatabase.rawQuery("SELECT sender, body, received_at FROM sms", null).use { c ->
        buildList {
            while (c.moveToNext()) add(Sms(c.getString(0), c.getString(1), toLocal(c.getLong(2))))
        }
    }

    /** Backfill from the phone's inbox; safe to repeat because rows are unique. */
    fun importInbox(context: Context): Int {
        val rows = mutableListOf<IncomingSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                if (Parsers.isBankSender(sender)) rows += IncomingSms(sender, c.getString(1) ?: "", c.getLong(2))
            }
        }
        return save(rows, "inbox")
    }

    companion object {
        @Volatile private var instance: SmsStore? = null

        fun get(context: Context): SmsStore =
            instance ?: synchronized(this) { instance ?: SmsStore(context).also { instance = it } }

        fun toLocal(epochMs: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    }
}
