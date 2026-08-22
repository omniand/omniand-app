package dev.omniand.hub.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

data class SmsSendProgress(
    val partCount: Int,
    val completedParts: Set<Int> = emptySet(),
    val failed: Boolean = false,
)

enum class SmsSendOutcome {
    PENDING,
    SENT,
    FAILED,
    DUPLICATE,
}

object SmsSendReducer {
    fun record(
        progress: SmsSendProgress,
        part: Int,
        succeeded: Boolean,
    ): Pair<SmsSendProgress, SmsSendOutcome> {
        require(progress.partCount > 0 && part in 0 until progress.partCount)
        if (part in progress.completedParts) return progress to SmsSendOutcome.DUPLICATE
        val updated =
            progress.copy(
                completedParts = progress.completedParts + part,
                failed = progress.failed || !succeeded,
            )
        val outcome =
            if (updated.completedParts.size < updated.partCount) SmsSendOutcome.PENDING
            else if (updated.failed) SmsSendOutcome.FAILED else SmsSendOutcome.SENT
        return updated to outcome
    }
}

object SmsSendTracker {
    private const val PREFS = "sms-send-results"

    @Synchronized
    fun start(context: Context, messageId: String, partCount: Int) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(messageId, encode(SmsSendProgress(partCount)))
            .commit()
    }

    @Synchronized
    fun record(
        context: Context,
        messageId: String,
        part: Int,
        partCount: Int,
        succeeded: Boolean,
    ): SmsSendOutcome {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current =
            preferences.getString(messageId, null)?.let(::decode) ?: SmsSendProgress(partCount)
        if (current.partCount != partCount) return SmsSendOutcome.DUPLICATE
        val (updated, outcome) = SmsSendReducer.record(current, part, succeeded)
        if (outcome == SmsSendOutcome.SENT || outcome == SmsSendOutcome.FAILED)
            preferences.edit().remove(messageId).commit()
        else if (outcome != SmsSendOutcome.DUPLICATE)
            preferences.edit().putString(messageId, encode(updated)).commit()
        return outcome
    }

    fun failImmediately(context: Context, messageId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(messageId).commit()
        updateProvider(context, messageId, SmsSendOutcome.FAILED, 0)
        SmsSendEventPublisher.publishFinal(SmsSendOutcome.FAILED, messageId)
    }

    fun updateProvider(
        context: Context,
        messageId: String,
        outcome: SmsSendOutcome,
        errorCode: Int,
    ) {
        if (outcome != SmsSendOutcome.SENT && outcome != SmsSendOutcome.FAILED) return
        val succeeded = outcome == SmsSendOutcome.SENT
        context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId.toLong()),
            ContentValues().apply {
                put(
                    Telephony.Sms.TYPE,
                    if (succeeded) Telephony.Sms.MESSAGE_TYPE_SENT
                    else Telephony.Sms.MESSAGE_TYPE_FAILED,
                )
                put(
                    Telephony.Sms.STATUS,
                    if (succeeded) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED,
                )
                put(Telephony.Sms.ERROR_CODE, if (succeeded) 0 else errorCode)
                if (succeeded) put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            },
            null,
            null,
        )
    }

    private fun encode(progress: SmsSendProgress) =
        "${progress.partCount}|${if (progress.failed) 1 else 0}|${progress.completedParts.sorted().joinToString(",")}"

    private fun decode(value: String): SmsSendProgress {
        val pieces = value.split('|', limit = 3)
        val count = pieces.getOrNull(0)?.toIntOrNull() ?: 1
        val failed = pieces.getOrNull(1) == "1"
        val completed =
            pieces.getOrNull(2).orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
        return SmsSendProgress(count, completed, failed)
    }
}

class SmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId =
            intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.toLongOrNull() != null } ?: return
        val part = intent.getIntExtra(EXTRA_PART, -1)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 0)
        if (partCount <= 0 || part !in 0 until partCount) return
        val outcome =
            SmsSendTracker.record(
                context,
                messageId,
                part,
                partCount,
                resultCode == Activity.RESULT_OK,
            )
        SmsSendTracker.updateProvider(context, messageId, outcome, resultCode)
        SmsSendEventPublisher.publishFinal(outcome, messageId)
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_PART = "part"
        const val EXTRA_PART_COUNT = "partCount"
    }
}
