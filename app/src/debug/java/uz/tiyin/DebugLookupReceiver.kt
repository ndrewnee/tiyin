package uz.tiyin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugLookupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LookupWorker.enqueue(context)
}
