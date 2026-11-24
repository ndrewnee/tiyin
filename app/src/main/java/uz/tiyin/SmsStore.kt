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
    SQLiteOpenHelper(context.applicationContext, "tiyin.db", null, 2) {

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
        createMerchantTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMerchantTable(db)
    }

    /** One row per merchant looked up in the company register; category is NULL when nothing matched. */
    private fun createMerchantTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE merchant_lookup (
                merchant TEXT PRIMARY KEY,
                category TEXT,
                org_name TEXT,
                oked TEXT,
                checked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    fun lookedUpCategories(): Map<String, String> =
        readableDatabase.rawQuery("SELECT merchant, category FROM merchant_lookup WHERE category IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    /** Merchants checked recently enough that asking again is pointless, found or not. */
    fun recentlyChecked(notFoundRetryAfterMs: Long, nowMs: Long): Set<String> =
        readableDatabase.rawQuery(
            "SELECT merchant FROM merchant_lookup WHERE category IS NOT NULL OR checked_at > ?",
            arrayOf((nowMs - notFoundRetryAfterMs).toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun saveLookup(merchant: String, category: String?, orgName: String?, oked: String?, nowMs: Long) {
        val v = ContentValues().apply {
            put("merchant", merchant)
            put("category", category)
            put("org_name", orgName)
            put("oked", oked)
            put("checked_at", nowMs)
        }
        writableDatabase.insertWithOnConflict("merchant_lookup", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

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
