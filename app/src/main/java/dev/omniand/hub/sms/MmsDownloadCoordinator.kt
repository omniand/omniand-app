package dev.omniand.hub.sms

import android.app.Activity
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu.NotificationInd
import com.google.android.mms.pdu.RetrieveConf
import java.io.File
import java.util.concurrent.Executors

/** Connects durable MMS journal entries to public subscription-specific carrier transport. */
object MmsDownloadCoordinator {
    fun receive(context: Context, subscriptionId: Int, bytes: ByteArray) {
        val journal = journal(context)
        val entry = journal.record(subscriptionId, bytes)
        if (entry.getString("state") != MmsJournalState.NOTIFIED.name) return
        val id = entry.getString("id")
        val (pdu, envelope) = MmsPduCodec.parse(bytes)
        when (pdu) {
            is NotificationInd -> {
                if (entry.isNull("providerId")) {
                    val providerId = MmsProviderAdapter(context).persistNotification(envelope)
                    journal.update(id, MmsJournalState.NOTIFIED, providerId = providerId)
                    SmsIncomingEventPublisher.publishPersisted("mms:$providerId")
                    SmsNotifications.publish(
                        context,
                        MmsProviderAdapter(context).threadId(providerId),
                        envelope.sender ?: "Messages",
                        envelope.subject?.takeIf { it.isNotBlank() } ?: "Downloading MMS…",
                        System.currentTimeMillis(),
                    )
                }
                schedule(context, id, 0)
            }
            else -> {
                MmsProviderAdapter(context).applyReport(pdu)
                journal.update(id, MmsJournalState.COMPLETE)
                SmsEventBroadcaster.publish("delivery", "mms:${envelope.messageId.orEmpty()}")
            }
        }
        journal.cleanup()
    }

    fun download(context: Context, id: String) {
        val journal = journal(context)
        val entry = journal.read(id) ?: return
        if (entry.optString("state") == MmsJournalState.COMPLETE.name) return
        if (entry.optLong("expiry") <= System.currentTimeMillis()) {
            journal.update(id, MmsJournalState.FAILED, "expired")
            return
        }
        val location = entry.optString("contentLocation").takeIf { it.isNotBlank() } ?: return
        val target =
            transportFile(context, id).apply {
                parentFile?.mkdirs()
                delete()
            }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        val callback =
            PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                Intent(context, MmsDownloadResultReceiver::class.java).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        journal.update(id, MmsJournalState.DOWNLOADING)
        try {
            SmsManager.getSmsManagerForSubscriptionId(entry.getInt("subscriptionId"))
                .downloadMultimediaMessage(context, location, uri, null, callback)
        } catch (error: Exception) {
            retry(context, id, error.javaClass.simpleName)
        }
    }

    fun complete(context: Context, id: String, resultCode: Int) {
        val journal = journal(context)
        if (journal.read(id)?.optString("state") == MmsJournalState.COMPLETE.name) return
        if (resultCode != Activity.RESULT_OK) {
            retry(context, id, "carrier-result-$resultCode", isPermanent(resultCode))
            return
        }
        val bytes =
            runCatching { transportFile(context, id).readBytes() }
                .getOrElse {
                    retry(context, id, it.javaClass.simpleName)
                    return
                }
        val pdu =
            runCatching { MmsPduCodec.parse(bytes).first as RetrieveConf }
                .getOrElse {
                    journal.update(id, MmsJournalState.QUARANTINED, "malformed-retrieve-conf")
                    transportFile(context, id).delete()
                    publishState(context, id)
                    return
                }
        try {
            val placeholder = journal.read(id)?.optString("providerId")?.takeIf { it.isNotBlank() }
            val providerId = MmsProviderAdapter(context).persistRetrieved(pdu, placeholder)
            journal.update(id, MmsJournalState.COMPLETE, providerId = providerId)
            transportFile(context, id).delete()
            SmsIncomingEventPublisher.publishPersisted("mms:$providerId")
            SmsNotifications.publish(
                context,
                MmsProviderAdapter(context).threadId(providerId),
                pdu.from?.string ?: "Messages",
                pdu.subject?.string?.takeIf { it.isNotBlank() } ?: "MMS downloaded",
                System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            retry(context, id, error.javaClass.simpleName)
        }
    }

    fun retry(context: Context, id: String, error: String, permanentFailure: Boolean = false) {
        val journal = journal(context)
        val entry = journal.read(id) ?: return
        val permanent = permanentFailure || entry.optLong("expiry") <= System.currentTimeMillis()
        if (permanent) {
            journal.update(id, MmsJournalState.FAILED, error)
            entry
                .optString("providerId")
                .takeIf { it.isNotBlank() }
                ?.let { providerId ->
                    SmsNotifications.publish(
                        context,
                        MmsProviderAdapter(context).threadId(providerId),
                        entry.optString("sender").ifBlank { "Messages" },
                        "MMS download failed — tap to retry",
                        System.currentTimeMillis(),
                    )
                }
        } else {
            val updated = journal.update(id, MmsJournalState.RETRY, error)
            schedule(
                context,
                id,
                (updated.getLong("nextAttempt") - System.currentTimeMillis()).coerceAtLeast(0),
            )
        }
        publishState(context, id)
    }

    fun schedule(context: Context, id: String, delay: Long) {
        val extras = android.os.PersistableBundle().apply { putString(EXTRA_ID, id) }
        val job =
            JobInfo.Builder(
                    id.hashCode() and 0x7fffffff,
                    ComponentName(context, MmsDownloadJob::class.java),
                )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setPersisted(true)
                .setExtras(extras)
                .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    fun journal(context: Context) = MmsJournal(File(context.noBackupFilesDir, "mms-journal"))

    private fun publishState(context: Context, id: String) {
        journal(context)
            .read(id)
            ?.optString("providerId")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                SmsEventBroadcaster.publish("delivery", "mms:$it")
            }
    }

    private fun isPermanent(resultCode: Int) =
        resultCode in
            setOf(
                SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
                SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
                SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
                SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER,
            )

    private fun transportFile(context: Context, id: String) =
        File(context.cacheDir, "mms-transport/$id.pdu")

    const val EXTRA_ID = "mmsJournalId"
}

class MmsDownloadResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(MmsDownloadCoordinator.EXTRA_ID) ?: return
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                MmsDownloadCoordinator.complete(context, id, resultCode)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

class MmsDownloadJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(MmsDownloadCoordinator.EXTRA_ID) ?: return false
        EXECUTOR.execute {
            try {
                MmsDownloadCoordinator.download(this, id)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
