package uz.tiyin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Resolves unknown merchants in the background, so a purchase made while the phone sleeps already has
 * a category when the app is opened. The system runs it once a network is available, also during
 * doze maintenance windows.
 */
class LookupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = SmsStore.get(applicationContext)
        val ledger = store.ledger()
        if (ledger.unknownMerchants.isEmpty()) return Result.success()
        val outcome = MerchantResolver.resolve(store, ledger.unknownMerchants)
        return if (outcome.failed && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "merchant-lookup"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LookupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // APPEND_OR_REPLACE: a purchase arriving while a run is in progress still gets its own run after it.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
