package uz.tiyin

import android.util.Log
import uz.tiyin.core.Orginfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Looks up merchants the dictionary does not know, once each, and stores the answer. */
object MerchantResolver {
    private const val TAG = "Tiyin"
    private val running = AtomicBoolean(false)
    private val notFoundRetryAfter = TimeUnit.DAYS.toMillis(30)
    // orginfo.uz is a small public site; one page at a time with a pause keeps the load polite.
    private const val PAUSE_MS = 1_000L
    private const val MAX_PER_RUN = 20

    private val orginfo = Orginfo(::get)

    /** [failed] means a network or site error stopped the run and the rest should be retried. */
    data class Outcome(val found: Int, val failed: Boolean)

    /** Blocking; call off the main thread. A run already in progress makes this one a no-op. */
    fun resolve(store: SmsStore, unknown: Set<String>): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome(0, failed = false)
        try {
            val now = System.currentTimeMillis()
            val todo = (unknown - store.recentlyChecked(notFoundRetryAfter, now)).sorted().take(MAX_PER_RUN)
            var found = 0
            for (merchant in todo) {
                val result = try {
                    orginfo.lookup(merchant)
                } catch (e: IOException) {
                    // Nothing is saved for this merchant, so a later run asks again.
                    Log.w(TAG, "lookup failed for $merchant: ${e.message}")
                    return Outcome(found, failed = true)
                }
                when (result) {
                    is Orginfo.Result.Found -> {
                        store.saveLookup(merchant, result.category, result.orgName, result.oked, System.currentTimeMillis())
                        found++
                    }
                    Orginfo.Result.NotFound -> store.saveLookup(merchant, null, null, null, System.currentTimeMillis())
                }
                Log.i(TAG, "lookup $merchant -> $result")
                Thread.sleep(PAUSE_MS)
            }
            return Outcome(found, failed = false)
        } finally {
            running.set(false)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Tiyin/0.1 (personal expense tracker)")
            conn.setRequestProperty("Accept-Language", "ru")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
