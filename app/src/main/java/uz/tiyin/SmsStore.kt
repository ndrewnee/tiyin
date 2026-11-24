package uz.tiyin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import uz.tiyin.core.Ledger
import uz.tiyin.core.Money
import uz.tiyin.core.Parsers
import uz.tiyin.core.PaymeOp
import uz.tiyin.core.Sms
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class IncomingSms(val sender: String, val body: String, val epochMs: Long)

/**
 * The app's own copy of bank SMS and imported Payme export rows. It is the only state the app keeps,
 * so the user can wipe SMS on the phone without losing history.
 */
class SmsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "tiyin.db", null, 3) {

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
        createPaymeTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMerchantTable(db)
        if (oldVersion < 3) createPaymeTable(db)
    }

    /**
     * Exports have no operation id, so the row's own fields are its identity and re-importing an
     * overlapping period adds nothing. card is '' rather than NULL because SQLite never treats NULLs
     * as equal in a UNIQUE constraint.
     */
    private fun createPaymeTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE payme_op (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at TEXT NOT NULL,
                debit INTEGER NOT NULL,
                supplier TEXT NOT NULL,
                organization TEXT NOT NULL,
                amount_minor INTEGER NOT NULL,
                currency TEXT NOT NULL,
                category TEXT NOT NULL,
                card TEXT NOT NULL,
                card_name TEXT NOT NULL,
                via_payme INTEGER NOT NULL,
                imported_at INTEGER NOT NULL,
                UNIQUE (at, card, debit, amount_minor, supplier)
            )
            """.trimIndent(),
        )
    }

    /**
     * Bumped on every write that changes what the ledger shows. SMS caught by the receiver and files
     * imported in another activity instance never show up as "new" to an open screen otherwise.
     */
    @Volatile var revision = 0L
        private set

    /** Returns how many rows were new. */
    fun savePayme(ops: List<PaymeOp>, nowMs: Long): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (op in ops) {
                val v = ContentValues().apply {
                    put("at", op.at.toString())
                    put("debit", if (op.debit) 1 else 0)
                    put("supplier", op.supplier)
                    put("organization", op.organization)
                    put("amount_minor", op.amount.minor)
                    put("currency", op.amount.currency)
                    put("category", op.category)
                    put("card", op.card ?: "")
                    put("card_name", op.cardName)
                    put("via_payme", if (op.viaPayme) 1 else 0)
                    put("imported_at", nowMs)
                }
                if (db.insertWithOnConflict("payme_op", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) revision++
        return added
    }

    fun paymeOps(): List<PaymeOp> = readableDatabase.rawQuery(
        "SELECT at, debit, supplier, organization, amount_minor, currency, category, card, card_name, via_payme FROM payme_op",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                PaymeOp(
                    at = LocalDateTime.parse(c.getString(0)),
                    debit = c.getInt(1) == 1,
                    supplier = c.getString(2),
                    organization = c.getString(3),
                    amount = Money(c.getLong(4), c.getString(5)),
                    category = c.getString(6),
                    card = c.getString(7).ifEmpty { null },
                    cardName = c.getString(8),
                    viaPayme = c.getInt(9) == 1,
                ),
            )
        }
    }

    fun ledger(): Ledger = Ledger(all(), lookedUpCategories(), paymeOps())

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
        if (added > 0) revision++
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
