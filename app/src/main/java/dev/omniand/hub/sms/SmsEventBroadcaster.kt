package dev.omniand.hub.sms

import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Process-local invalidations for SMS views. Events are deliberately not replayed. */
object SmsEventBroadcaster {
    private val subscribers = CopyOnWriteArraySet<Subscription>()

    fun subscribe(heartbeatMillis: Long = 15_000, closeAfterEvent: Boolean = false): Subscription =
        Subscription(heartbeatMillis, closeAfterEvent) {
                subscribers.remove(it)
            }
            .also {
                subscribers.add(it)
            }

    fun publish(reason: String, messageId: String) {
        require(reason == "incoming" || reason == "delivery" || reason == "read")
        val frame =
            "event: sms-change\ndata: ${JSONObject().put("reason", reason).put("messageId", messageId)}\n\n"
                .toByteArray(Charsets.UTF_8)
        subscribers.forEach { it.offer(frame) }
    }

    fun publishThreadRead(threadId: String) {
        val frame =
            "event: sms-change\ndata: ${JSONObject().put("reason", "read").put("threadId", threadId)}\n\n"
                .toByteArray(Charsets.UTF_8)
        subscribers.forEach { it.offer(frame) }
    }

    internal fun subscriberCount(): Int = subscribers.size

    class Subscription
    internal constructor(
        private val heartbeatMillis: Long,
        private val closeAfterEvent: Boolean,
        private val onClose: (Subscription) -> Unit,
    ) : InputStream() {
        private val queue = ArrayBlockingQueue<ByteArray>(1)
        private val closed = AtomicBoolean(false)
        private var current = ": connected\nretry: 3000\n\n".toByteArray(Charsets.UTF_8)
        private var offset = 0
        private var eventDelivered = false

        internal fun offer(frame: ByteArray) {
            if (closed.get()) return
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        // WebResourceResponse calls this overload directly. Android's contract
        // requires custom streams to implement it rather than relying on the
        // InputStream default adapter.
        override fun read(target: ByteArray): Int = read(target, 0, target.size)

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            while (offset >= current.size) {
                // Locally intercepted WebResourceResponse streams are buffered by Android
                // WebView. Ending after one invalidation lets EventSource deliver it and reconnect;
                // desktop HTTP subscriptions remain persistent and receive heartbeats.
                if (closeAfterEvent && eventDelivered) {
                    close()
                    return -1
                }
                if (closed.get()) return -1
                val event = queue.poll(heartbeatMillis, TimeUnit.MILLISECONDS)
                current = event ?: ": heartbeat\n\n".toByteArray(Charsets.UTF_8)
                if (event != null) eventDelivered = true
                offset = 0
            }
            val count = minOf(length, current.size - offset)
            current.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) onClose(this)
        }
    }
}

object SmsSendEventPublisher {
    fun publishFinal(outcome: SmsSendOutcome, messageId: String) {
        if (outcome == SmsSendOutcome.SENT || outcome == SmsSendOutcome.FAILED) {
            SmsEventBroadcaster.publish("delivery", messageId)
        }
    }
}

object SmsIncomingEventPublisher {
    fun publishPersisted(messageId: String) = SmsEventBroadcaster.publish("incoming", messageId)
}

object SmsReadEventPublisher {
    fun publishMessage(messageId: String) = SmsEventBroadcaster.publish("read", messageId)

    fun publishThread(threadId: String) = SmsEventBroadcaster.publishThreadRead(threadId)
}
