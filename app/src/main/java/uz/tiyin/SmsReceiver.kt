package uz.tiyin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives as several parts from the same sender in one broadcast.
        val messages = parts.groupBy { it.displayOriginatingAddress ?: "" }
            .filterKeys { it.isNotEmpty() }
            .map { (sender, ps) ->
                IncomingSms(sender, ps.joinToString("") { it.displayMessageBody ?: "" }, ps.first().timestampMillis)
            }
        val pending = goAsync()
        Thread {
            try {
                SmsStore.get(context).save(messages, "receiver")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
