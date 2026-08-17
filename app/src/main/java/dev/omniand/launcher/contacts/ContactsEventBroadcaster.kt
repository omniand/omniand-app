package dev.omniand.launcher.contacts

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ContactsEventBroadcaster {
    private val started = AtomicBoolean(false)
    private val subscribers = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        try {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        publish()
                    }
                },
            )
        } catch (_: SecurityException) {
            started.set(false)
        }
    }

    fun publish() {
        subscribers.forEach { it.offer("event: contacts\ndata: {\"reason\":\"changed\"}\n\n") }
    }

    fun subscribe(closeAfterEvent: Boolean): InputStream {
        val queue = LinkedBlockingQueue<String>(1)
        subscribers += queue
        return object : InputStream() {
            private var bytes = "retry: 3000\n\n".toByteArray()
            private var offset = 0
            private var delivered = false

            override fun read(): Int {
                while (offset >= bytes.size) {
                    if (delivered && closeAfterEvent) return -1
                    val next = queue.poll(15, TimeUnit.SECONDS) ?: ": heartbeat\n\n"
                    delivered = next.startsWith("event:")
                    bytes = next.toByteArray()
                    offset = 0
                }
                return bytes[offset++].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val first = read()
                if (first < 0) return -1
                buffer[off] = first.toByte()
                var count = 1
                while (count < len && offset < bytes.size) buffer[off + count++] = bytes[offset++]
                return count
            }

            override fun close() {
                subscribers.remove(queue)
            }
        }
    }
}
