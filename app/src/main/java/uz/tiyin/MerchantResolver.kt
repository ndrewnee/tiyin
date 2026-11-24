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

    /** Returns how many merchants got a category; blocking, call off the main thread. */
    fun resolve(store: SmsStore, unknown: Set<String>): Int {
        if (!running.compareAndSet(false, true)) return 0
        try {
            val now = System.currentTimeMillis()
            val todo = (unknown - store.recentlyChecked(notFoundRetryAfter, now)).sorted().take(MAX_PER_RUN)
            var found = 0
            for (merchant in todo) {
                val result = try {
                    orginfo.lookup(merchant)
                } catch (e: IOException) {
                    // Offline or the site is down: leave it unchecked so the next open retries.
                    Log.w(TAG, "lookup failed for $merchant: ${e.message}")
                    return found
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
            return found
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
