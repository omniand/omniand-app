package dev.omniand.hub

import android.app.Activity
import android.os.Bundle
import dev.omniand.hub.server.DesktopPairing

/** Displays the physical allow/deny decision reached from a desktop pairing notification. */
class DesktopPairingApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        val request = DesktopPairing.pending().firstOrNull { it.id == id }
        if (request == null) {
            finish()
            return
        }
        DesktopPairingNotifications.approvalDialog(this, request) { finish() }.show()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "pairingRequestId"
    }
}
