package dev.omniand.launcher.sms

import android.app.Activity
import android.app.IntentService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.omniand.launcher.WebAppActivity
import dev.omniand.launcher.services.SmsService
import java.net.URLEncoder

class SmsSendToActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme in setOf("mms", "mmsto")) {
            Toast.makeText(this, "MMS sending is not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val address = uri?.schemeSpecificPart?.substringBefore('?').orEmpty()
        val body = uri?.getQueryParameter("body").orEmpty()
        val route = "#/compose?to=${encode(address)}" + if (body.isNotEmpty()) "&body=${encode(body)}" else ""
        startActivity(Intent(this, WebAppActivity::class.java)
            .putExtra(WebAppActivity.EXTRA_APP_ID, "messages")
            .putExtra(WebAppActivity.EXTRA_ROUTE, route))
        finish()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

@Suppress("DEPRECATION")
class RespondViaMessageService : IntentService("respond-via-message") {
    override fun onHandleIntent(intent: Intent?) {
        val target = intent?.data?.schemeSpecificPart?.substringBefore('?').orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (target.isNotBlank() && body.isNotBlank()) SmsService(this).send(target, body)
    }
}
