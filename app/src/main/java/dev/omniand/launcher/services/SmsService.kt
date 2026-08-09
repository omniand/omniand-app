package dev.omniand.launcher.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

class SmsService(private val context: Context) {
    class PermissionMissing : Exception()

    fun recent(limit: Int = 100): JSONArray {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw PermissionMissing()
        }
        val result = JSONArray()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && result.length() < limit) {
                result.put(JSONObject()
                    .put("id", cursor.getString(id))
                    .put("address", cursor.getString(address) ?: "Unknown")
                    .put("body", cursor.getString(body) ?: "")
                    .put("date", cursor.getLong(date))
                    .put("type", if (cursor.getInt(type) == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent"))
            }
        }
        return result
    }
}
