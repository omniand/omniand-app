package dev.omniand.hub.background

import android.app.NotificationManager
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The visible phone confirmation revalidates the opaque request before single-use approval. */
class WakeConfirmationActivity : ComponentActivity() {
    private lateinit var message: TextView
    private lateinit var approve: Button
    private lateinit var deny: Button
    private var requestId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra("requestId").orEmpty()
        if (!requestId.matches(Regex("[0-9a-f]{32}"))) {
            finish()
            return
        }
        message =
            TextView(this).apply {
                text = "Checking the connection request…"
                textSize = 18f
            }
        approve =
            Button(this).apply {
                text = "Approve session"
                isEnabled = false
                setOnClickListener { decide(true) }
            }
        deny =
            Button(this).apply {
                text = "Deny"
                isEnabled = false
                setOnClickListener { decide(false) }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 64, 32, 32)
                addView(message)
                addView(approve)
                addView(deny)
            }
        )
        lifecycleScope.launch {
            try {
                check(BackgroundHostingManager.mode(this@WakeConfirmationActivity) == "on-demand") {
                    "On-demand hosting is disabled"
                }
                val request = WakeClient(this@WakeConfirmationActivity).request("/$requestId")
                check(request.getString("state") == "waiting") {
                    "This request is ${request.getString("state")}"
                }
                message.text =
                    "${request.getString("browser")} wants to connect.\n\nApproval enables a remote session for your paired browsers. It ends after five minutes without desktop clients."
                approve.isEnabled = true
                deny.isEnabled = true
            } catch (e: Exception) {
                message.text = e.message
            }
        }
    }

    private fun decide(allowed: Boolean) {
        approve.isEnabled = false
        deny.isEnabled = false
        lifecycleScope.launch {
            try {
                check(BackgroundHostingManager.mode(this@WakeConfirmationActivity) == "on-demand") {
                    "On-demand hosting is disabled"
                }
                WakeClient(this@WakeConfirmationActivity)
                    .request("/$requestId", "POST", JSONObject().put("approve", allowed))
                if (allowed) BackgroundHostingManager.approveSession(this@WakeConfirmationActivity)
                getSystemService(NotificationManager::class.java).cancel(requestId, 7402)
                finish()
            } catch (e: Exception) {
                message.text = e.message
            }
        }
    }
}
