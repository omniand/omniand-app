package dev.omniand.launcher.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

/** Atomically journals WAP payloads before acknowledging their broadcast. */
class IncomingMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                val pdu = intent.getByteArrayExtra("data") ?: return@execute
                MmsDownloadCoordinator.receive(
                    context,
                    intent.getIntExtra("subscription", -1),
                    pdu,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
