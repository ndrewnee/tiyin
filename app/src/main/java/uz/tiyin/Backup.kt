package uz.tiyin

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uz.tiyin.core.Parsers
import uz.tiyin.core.Sms
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A copy of everything the app cannot get back by itself: stored SMS (the phone's inbox may be wiped),
 * imported Payme rows, merchant lookups and the user's marks. Restoring merges into what is there,
 * so restoring an old copy over newer data never loses anything.
 */
object Backup {
    class NotBackup(message: String) : Exception(message)

    data class Counts(val sms: Int, val payme: Int, val marks: Int)

    private const val FORMAT = "tiyin-backup"
    private const val VERSION = 1

    private val tables = mapOf(
        "sms" to listOf("sender", "body", "received_at", "source"),
        "payme_op" to listOf(
            "at", "debit", "supplier", "organization", "amount_minor", "currency", "category", "card", "card_name", "via_payme", "imported_at",
        ),
        "merchant_lookup" to listOf("merchant", "category", "org_name", "oked", "checked_at"),
        "mark" to listOf("target", "kind", "category", "updated_at"),
    )

    fun write(store: SmsStore, out: OutputStream): Counts {
        val db = store.readableDatabase
        val root = JSONObject().put("format", FORMAT).put("version", VERSION).put("saved_at", System.currentTimeMillis())
        val counts = mutableMapOf<String, Int>()
        // One transaction so the copy is a single consistent snapshot even if an SMS arrives meanwhile.
        db.beginTransactionNonExclusive()
        try {
            for ((table, columns) in tables) {
                val rows = JSONArray()
                db.rawQuery("SELECT ${columns.joinToString()} FROM $table", null).use { c ->
                    while (c.moveToNext()) rows.put(JSONObject().apply { columns.forEachIndexed { i, col -> put(col, value(c, i)) } })
                }
                root.put(table, rows)
                counts[table] = rows.length()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        out.bufferedWriter().use { it.write(root.toString()) }
        return Counts(counts.getValue("sms"), counts.getValue("payme_op"), counts.getValue("mark"))
    }

    private fun value(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        else -> c.getString(i)
    }

    /** Returns how many rows of each kind were new. */
    fun read(store: SmsStore, input: InputStream): Counts {
        val root = try {
            JSONObject(input.bufferedReader().use { it.readText() })
        } catch (e: JSONException) {
            throw NotBackup("not JSON")
        }
        if (root.optString("format") != FORMAT) throw NotBackup("not a Tiyin backup")
        if (root.optInt("version") > VERSION) throw NotBackup("made by a newer Tiyin")

        val db = store.writableDatabase
        val added = mutableMapOf<String, Int>()
        db.beginTransaction()
        try {
            for ((table, columns) in tables) {
                val rows = root.optJSONArray(table) ?: JSONArray()
                var n = 0
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (table == "sms" && !storable(row)) continue
                    val v = ContentValues()
                    for (col in columns) when (val x = row.opt(col)) {
                        null, JSONObject.NULL -> v.putNull(col)
                        is Number -> v.put(col, x.toLong())
                        else -> v.put(col, x.toString())
                    }
                    if (insert(db, table, v)) n++
                }
                added[table] = n
            }
            db.setTransactionSuccessful()
        } catch (e: JSONException) {
            throw NotBackup("broken backup: ${e.message}")
        } finally {
            db.endTransaction()
        }
        val counts = Counts(added.getValue("sms"), added.getValue("payme_op"), added.getValue("mark"))
        if (added.values.sum() > 0) store.changed()
        return counts
    }

    /** Re-checks the same filter as live capture, so a hand-edited copy cannot bring in one-time codes. */
    private fun storable(row: JSONObject): Boolean {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(row.optLong("received_at")), ZoneId.systemDefault())
        return Parsers.shouldStore(Sms(row.optString("sender"), row.optString("body"), at))
    }

    /** A mark the user changed after the copy was made is kept; everything else is only added. */
    private fun insert(db: SQLiteDatabase, table: String, v: ContentValues): Boolean {
        if (table != "mark") return db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        val target = v.getAsString("target")
        val newer = db.rawQuery("SELECT updated_at FROM mark WHERE target = ?", arrayOf(target)).use { c ->
            !c.moveToFirst() || c.getLong(0) < v.getAsLong("updated_at")
        }
        if (!newer) return false
        db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }
}
