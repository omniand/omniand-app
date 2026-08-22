package dev.omniand.hub.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Displays Android's permanent-delete consent for MediaStore rows owned by another application. */
class MediaDeleteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris =
            intent.getStringArrayListExtra(URIS).orEmpty().map(Uri::parse).filter {
                it.authority == MediaStore.AUTHORITY
            }
        if (uris.isEmpty()) {
            finish()
            return
        }
        val request = MediaStore.createDeleteRequest(contentResolver, uris)
        startIntentSenderForResult(request.intentSender, DELETE_REQUEST, null, 0, 0, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DELETE_REQUEST) MediaEventBroadcaster.publish("delete-consent-complete")
        finish()
    }

    companion object {
        private const val URIS = "uris"
        private const val DELETE_REQUEST = 85
        private const val RESPONSE_HEAD_START_DELAY_MS = 250L

        fun request(context: Context, uris: Collection<Uri>) {
            val intent =
                Intent(context, MediaDeleteActivity::class.java)
                    .putStringArrayListExtra(URIS, ArrayList(uris.map(Uri::toString)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Let the HTTP response reach the WebView before Android covers it with consent UI.
            Handler(Looper.getMainLooper())
                .postDelayed(
                    { context.startActivity(intent) },
                    RESPONSE_HEAD_START_DELAY_MS,
                )
        }
    }
}
