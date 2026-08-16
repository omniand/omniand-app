package dev.omniand.launcher.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** Atomically journals WAP payloads before acknowledging their broadcast. */
class IncomingMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                val pdu = intent.getByteArrayExtra("data") ?: return@execute
                val root = File(context.noBackupFilesDir, "mms-journal")
                check(root.isDirectory || root.mkdirs())
                val target = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}.pdu")
                val temporary = File(root, ".${target.name}.tmp")
                FileOutputStream(temporary).use { output ->
                    output.write(intent.getIntExtra("subscription", -1).toString().toByteArray())
                    output.write('\n'.code)
                    output.write(pdu)
                    output.fd.sync()
                }
                check(temporary.renameTo(target))
                SmsNotifications.publishMmsUnsupported(context, System.currentTimeMillis())
            } finally { pending.finish() }
        }
    }

    companion object { private val EXECUTOR = Executors.newSingleThreadExecutor() }
}
