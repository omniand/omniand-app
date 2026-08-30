package dev.omniand.hub.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.pairing.DeviceIdentity
import dev.omniand.hub.server.PlatformServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the authenticated tunnel and reconnects it until hosting is disabled or auth fails. */
class RelayTunnelClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relayUrl: String,
) {
    private val streams = ConcurrentHashMap<Long, LocalStream>()
    private val stopped = AtomicBoolean(false)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable.trySend(Unit)
            }
        }

    fun start(): Job {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        return scope.launch { connectionManager() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        streams.values.forEach(LocalStream::close)
        streams.clear()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        client.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun connectionManager() {
        val identity = DeviceIdentity(context)
        var attempt = 0
        while (!stopped.get()) {
            val credential = identity.credential()
            if (credential == null) {
                TunnelState.update("unenrolled", "Scan a computer QR code to enroll this phone")
                return
            }
            val started = System.currentTimeMillis()
            try {
                runTunnel(identity.deviceId, credential)
                if (System.currentTimeMillis() - started >= STABLE_MILLIS) attempt = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                streams.values.forEach(LocalStream::close)
                streams.clear()
                val detail = error.message.orEmpty()
                if (detail.contains("401") || detail.contains("Unauthorized", ignoreCase = true)) {
                    identity.resetEnrollment()
                    TunnelState.update(
                        "authentication-error",
                        "Relay authentication failed; scan a new QR code",
                    )
                    return
                }
                TunnelState.update("reconnecting", error.message ?: "Network unavailable")
            }
            val wait = RetryBackoff.fullJitterMillis(attempt++)
            select<Unit> {
                onTimeout(wait) {}
                networkAvailable.onReceive { attempt = 0 }
            }
        }
    }

    private suspend fun runTunnel(deviceId: String, credential: String) {
        try {
            val uri = URI(relayUrl)
            require(uri.scheme == "wss" || (BuildConfig.DEBUG && uri.scheme == "ws")) {
                "The relay must use wss://; ws:// is accepted only in debug builds"
            }
            client.webSocket(
                request = {
                    url(relayUrl)
                    header("Authorization", "Bearer $credential")
                    header("X-OmniAnd-Device-Id", deviceId)
                }
            ) {
                TunnelState.update("connected", null)
                val output = Channel<ByteArray>(capacity = 64)
                coroutineScope {
                    val writer = launch {
                        send(Frame.Binary(fin = true, data = TunnelProtocol.hello(deviceId)))
                        for (encoded in output) send(Frame.Binary(fin = true, data = encoded))
                    }
                    val keepAlive = launch {
                        while (isActive) {
                            delay(30_000)
                            output.send(TunnelProtocol.encode(TunnelFrame.Ping(byteArrayOf())))
                        }
                    }
                    try {
                        for (message in incoming) {
                            require(message is Frame.Binary) { "tunnel messages must be binary" }
                            dispatch(TunnelProtocol.decode(message.readBytes()), output)
                        }
                    } finally {
                        output.close()
                        keepAlive.cancel()
                        writer.cancel()
                    }
                }
            }
        } finally {
            streams.values.forEach(LocalStream::close)
            streams.clear()
        }
    }

    private suspend fun dispatch(frame: TunnelFrame, output: Channel<ByteArray>) {
        when (frame) {
            is TunnelFrame.Open -> open(frame.streamId, output)
            is TunnelFrame.Data -> {
                val local = stream(frame.streamId)
                local.receiveWindow.consume(frame.payload.size)
                local.incoming.send(StreamEvent.Data(frame.payload))
            }
            is TunnelFrame.Fin -> stream(frame.streamId).incoming.send(StreamEvent.Fin)
            is TunnelFrame.Reset -> streams.remove(frame.streamId)?.close()
            is TunnelFrame.WindowUpdate -> stream(frame.streamId).credit.release(frame.credit)
            is TunnelFrame.Ping ->
                output.send(TunnelProtocol.encode(TunnelFrame.Pong(frame.payload)))
            is TunnelFrame.Pong -> Unit
        }
    }

    /** Opens one bounded loopback stream, or explicitly resets it when local setup fails. */
    private suspend fun open(streamId: Long, output: Channel<ByteArray>) {
        if (streams.size >= TUNNEL_MAX_STREAMS || streams.containsKey(streamId)) {
            output.send(TunnelProtocol.encode(TunnelFrame.Reset(streamId)))
            return
        }
        val socket =
            runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        connect(java.net.InetSocketAddress("127.0.0.1", PlatformServer.PORT), 5_000)
                    }
                }
                .getOrElse {
                    output.send(TunnelProtocol.encode(TunnelFrame.Reset(streamId)))
                    return
                }
        val local = LocalStream(socket)
        if (streams.putIfAbsent(streamId, local) != null) {
            local.close()
            output.send(TunnelProtocol.encode(TunnelFrame.Reset(streamId)))
            return
        }
        scope.launch {
            try {
                coroutineScope {
                    listOf(
                            launch { copyFromSocket(streamId, local, output) },
                            launch { copyToSocket(streamId, local, output) },
                        )
                        .joinAll()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runCatching { output.send(TunnelProtocol.encode(TunnelFrame.Reset(streamId))) }
            } finally {
                streams.remove(streamId, local)
                local.close()
            }
        }
    }

    private suspend fun copyFromSocket(
        streamId: Long,
        local: LocalStream,
        output: Channel<ByteArray>,
    ) {
        val buffer = ByteArray(TUNNEL_MAX_DATA)
        while (true) {
            val count = local.socket.getInputStream().read(buffer)
            if (count < 0) {
                output.send(TunnelProtocol.encode(TunnelFrame.Fin(streamId)))
                return
            }
            if (count == 0) continue
            local.credit.acquire(count)
            output.send(
                TunnelProtocol.encode(TunnelFrame.Data(streamId, buffer.copyOfRange(0, count)))
            )
        }
    }

    private suspend fun copyToSocket(
        streamId: Long,
        local: LocalStream,
        output: Channel<ByteArray>,
    ) {
        for (event in local.incoming) {
            when (event) {
                is StreamEvent.Data -> {
                    local.socket.getOutputStream().write(event.payload)
                    local.receiveWindow.release(event.payload.size)
                    output.send(
                        TunnelProtocol.encode(
                            TunnelFrame.WindowUpdate(streamId, event.payload.size)
                        )
                    )
                }
                StreamEvent.Fin -> {
                    local.socket.shutdownOutput()
                    return
                }
            }
        }
    }

    private fun stream(streamId: Long): LocalStream =
        streams[streamId] ?: error("frame references an unknown stream")

    private class LocalStream(val socket: Socket) {
        val incoming = Channel<StreamEvent>(capacity = 32)
        val credit = FlowCredit()
        val receiveWindow = ReceiveWindow()

        fun close() {
            incoming.close()
            runCatching { socket.close() }
        }
    }

    private sealed interface StreamEvent {
        data class Data(val payload: ByteArray) : StreamEvent

        data object Fin : StreamEvent
    }

    /** Suspends socket reads before more than the negotiated per-stream window is in flight. */
    private class FlowCredit {
        private val mutex = Mutex()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private var available = TUNNEL_INITIAL_WINDOW

        suspend fun acquire(count: Int) {
            while (true) {
                val acquired = mutex.withLock {
                    if (available >= count) {
                        available -= count
                        true
                    } else {
                        false
                    }
                }
                if (acquired) return
                changed.receive()
            }
        }

        suspend fun release(count: Int) {
            mutex.withLock {
                require(
                    count in 1..TUNNEL_INITIAL_WINDOW && available + count <= TUNNEL_INITIAL_WINDOW
                ) {
                    "flow-control window overflow"
                }
                available += count
            }
            changed.trySend(Unit)
        }
    }

    /** Rejects a peer that sends more bytes than this side has acknowledged as consumed. */
    private class ReceiveWindow {
        private val mutex = Mutex()
        private var available = TUNNEL_INITIAL_WINDOW

        suspend fun consume(count: Int) {
            mutex.withLock {
                require(count in 1..available) { "peer exceeded the receive window" }
                available -= count
            }
        }

        suspend fun release(count: Int) {
            mutex.withLock {
                require(available + count <= TUNNEL_INITIAL_WINDOW) {
                    "receive window overflow"
                }
                available += count
            }
        }
    }

    private companion object {
        const val TAG = "OmniAndTunnel"
        const val STABLE_MILLIS = 30_000L
    }
}

object TunnelState {
    @Volatile
    var state = "disconnected"
        private set

    @Volatile
    var error: String? = null
        private set

    fun update(value: String, detail: String?) {
        state = value
        error = detail
    }
}

internal object RetryBackoff {
    fun maximumMillis(attempt: Int): Long =
        (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L)

    fun fullJitterMillis(attempt: Int): Long =
        kotlin.random.Random.nextLong(maximumMillis(attempt) + 1)
}
